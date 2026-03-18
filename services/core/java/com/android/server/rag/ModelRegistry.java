package com.android.server.rag;

import android.util.Log;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModelRegistry — manages all active Cactus (modelHandle, indexHandle) pairs.
 *
 * Phase 3 prerequisite: replaces the static sModelHandle / sIndexHandle fields
 * that were hardcoded in RagIndexWorker. Adding a new model is now a single
 * register() call — no code changes elsewhere.
 *
 * Each model entry tracks:
 *   - modelHandle  : opaque Cactus model pointer
 *   - indexHandle  : opaque Cactus index pointer
 *   - embeddingDim : must match the model's output dimension
 *   - indexDir     : where index.bin / data.bin live on disk
 *
 * Rules:
 *   1. Indexes are NEVER shared across models — embedding geometry differs.
 *   2. The model that indexed a chunk must be the model that queries it.
 *   3. 0L handle means uninitialized / failed — callers must null-check via isReady().
 *
 * Usage:
 *   ModelRegistry.getInstance().register("rag", modelPath, INDEX_DIR_RAG, 1024);
 *   ModelRegistry.getInstance().register("tools", modelPath, INDEX_DIR_TOOLS, 1024);
 *
 *   ModelEntry e = ModelRegistry.getInstance().get("rag");
 *   if (e != null && e.isReady()) { ... }
 */
public class ModelRegistry {

    private static final String TAG = "ModelRegistry";

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static volatile ModelRegistry sInstance;

    public static ModelRegistry getInstance() {
        if (sInstance == null) {
            synchronized (ModelRegistry.class) {
                if (sInstance == null) sInstance = new ModelRegistry();
            }
        }
        return sInstance;
    }

    private ModelRegistry() {}

    // -------------------------------------------------------------------------
    // ModelEntry — one (modelHandle, indexHandle) pair
    // -------------------------------------------------------------------------

    public static class ModelEntry {
        public final String name;
        public final String modelPath;
        public final String indexDir;
        public final int    embeddingDim;

        public volatile long modelHandle = 0L;
        public volatile long indexHandle = 0L;

        ModelEntry(String name, String modelPath, String indexDir, int embeddingDim) {
            this.name         = name;
            this.modelPath    = modelPath;
            this.indexDir     = indexDir;
            this.embeddingDim = embeddingDim;
        }

        /** True only when both handles are live. */
        public boolean isReady() {
            return modelHandle != 0L && indexHandle != 0L;
        }

        @Override
        public String toString() {
            return "ModelEntry{name=" + name
                    + ", dim=" + embeddingDim
                    + ", modelReady=" + (modelHandle != 0L)
                    + ", indexReady=" + (indexHandle != 0L) + "}";
        }
    }

    // -------------------------------------------------------------------------
    // Internal map  name → entry
    // -------------------------------------------------------------------------

    private final ConcurrentHashMap<String, ModelEntry> mEntries = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Register and initialize a model + index pair.
     *
     * Safe to call multiple times — re-entrant calls for an already-registered
     * name are ignored unless the handles have become invalid (0L).
     *
     * @param name         logical name, e.g. "rag" or "tools"
     * @param modelPath    absolute path to the .gguf model file
     * @param indexDir     directory for this model's Cactus index files
     * @param embeddingDim embedding vector dimension (must match model output)
     * @return the initialized ModelEntry, or an entry with 0L handles on failure
     */
    public ModelEntry register(String name, String modelPath, String indexDir, int embeddingDim) {
        ModelEntry existing = mEntries.get(name);
        if (existing != null && existing.isReady()) {
            Log.d(TAG, "Already registered and ready: " + name);
            return existing;
        }

        ModelEntry entry = new ModelEntry(name, modelPath, indexDir, embeddingDim);
        mEntries.put(name, entry);

        // Initialize model handle
        entry.modelHandle = CactusWrapper.init(modelPath, null, true);
        if (entry.modelHandle == 0L) {
            Log.e(TAG, "Failed to init model for: " + name + " path=" + modelPath);
            return entry;
        }

        // Initialize index handle
        new File(indexDir).mkdirs();
        entry.indexHandle = CactusWrapper.indexInit(indexDir, embeddingDim);
        if (entry.indexHandle == 0L) {
            Log.e(TAG, "Failed to init index for: " + name + " dir=" + indexDir);
            // Model is up but index failed — destroy model to avoid half-ready state
            CactusWrapper.destroy(entry.modelHandle);
            entry.modelHandle = 0L;
            return entry;
        }

        Log.i(TAG, "Registered: " + entry);
        return entry;
    }

    /**
     * Get a registered ModelEntry by name.
     *
     * @return ModelEntry or null if not registered
     */
    public ModelEntry get(String name) {
        return mEntries.get(name);
    }

    /**
     * Convenience — get and assert ready.
     *
     * @return ModelEntry if registered and both handles are live, null otherwise
     */
    public ModelEntry getReady(String name) {
        ModelEntry e = mEntries.get(name);
        return (e != null && e.isReady()) ? e : null;
    }

    /**
     * Destroy all registered models and indexes.
     * Called on RagService shutdown.
     */
    public void destroyAll() {
        for (ModelEntry entry : mEntries.values()) {
            destroy(entry.name);
        }
        mEntries.clear();
        Log.i(TAG, "All models destroyed");
    }

    /**
     * Destroy a single registered model by name.
     */
    public void destroy(String name) {
        ModelEntry entry = mEntries.remove(name);
        if (entry == null) return;
        if (entry.indexHandle != 0L) {
            CactusWrapper.indexDestroy(entry.indexHandle);
            entry.indexHandle = 0L;
        }
        if (entry.modelHandle != 0L) {
            CactusWrapper.destroy(entry.modelHandle);
            entry.modelHandle = 0L;
        }
        Log.i(TAG, "Destroyed: " + name);
    }

    /** Log all registered entries — useful for debugging. */
    public void dump() {
        for (ModelEntry e : mEntries.values()) {
            Log.d(TAG, e.toString());
        }
    }
}
