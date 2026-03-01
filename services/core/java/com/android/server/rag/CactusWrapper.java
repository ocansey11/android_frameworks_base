package com.android.server.rag;

import android.util.Log;

/**
 * JNI bridge between JarvisOS Java system services and the Cactus C++ engine.
 *
 * Wraps the subset of cactus_ffi.h needed for JarvisOS:
 *   - init / destroy       model lifecycle
 *   - embed                text → float[] embedding
 *   - indexInit            create/load a Cactus binary index
 *   - indexAdd             add documents + embeddings to the index
 *   - indexQuery           nearest-neighbour search
 *   - indexDelete          remove entries by ID
 *   - indexDestroy         free index memory
 *   - complete             LLM completion with optional RAG context
 *   - getLastError         retrieve last C++ error string
 *
 * Threading:
 *   All native calls are blocking. Callers must run on a background thread.
 *   RagService.initializeAsync() and RagIndexWorker.doWork() already do this.
 *
 * Model handle:
 *   cactus_model_t is a void* in C++. We store it as a long in Java.
 *   0L means uninitialized / destroyed.
 *
 * Index handle:
 *   cactus_index_t is also a void*. Same convention — 0L = invalid.
 *
 * Embedding dimensions:
 *   Determined at runtime from the model. Query EMBEDDING_DIM after init.
 *   Typical values: Qwen embed = 1024, nomic-embed = 768.
 */
public class CactusWrapper {

    private static final String TAG = "CactusWrapper";

    // Max embedding dimensions we support — buffer size for native calls
    private static final int MAX_EMBEDDING_DIM = 2048;

    // Max response buffer size for completions
    private static final int RESPONSE_BUFFER_SIZE = 8192;

    static {
        try {
            System.loadLibrary("cactus");
            Log.i(TAG, "Cactus native library loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load cactus native library", e);
        }
    }

    // -------------------------------------------------------------------------
    // Native method declarations — implemented in cactus_ffi.h / cactus_jni.cpp
    // -------------------------------------------------------------------------

    /** Initialize a model. Returns opaque handle (long), 0 on failure. */
    private static native long nativeInit(String modelPath, String corpusDir, boolean cacheIndex);

    /** Destroy a model and free its memory. */
    private static native void nativeDestroy(long modelHandle);

    /**
     * Generate text embedding.
     * Returns float[] of length embedding_dim, or null on failure.
     */
    private static native float[] nativeEmbed(long modelHandle, String text, boolean normalize);

    /** Initialize a Cactus index at indexDir. Returns index handle, 0 on failure. */
    private static native long nativeIndexInit(String indexDir, int embeddingDim);

    /**
     * Add entries to the index.
     * ids, documents, embeddings must be the same length.
     * Returns 0 on success, negative on failure.
     */
    private static native int nativeIndexAdd(
            long indexHandle,
            int[] ids,
            String[] documents,
            String[] metadatas,
            float[][] embeddings,
            int embeddingDim);

    /**
     * Query nearest neighbours.
     * Returns int[] of matching IDs ordered by score, or null on failure.
     */
    private static native int[] nativeIndexQuery(
            long indexHandle,
            float[] queryEmbedding,
            int embeddingDim,
            int topK);

    /**
     * Delete entries from the index by ID.
     * Returns 0 on success.
     */
    private static native int nativeIndexDelete(long indexHandle, int[] ids);

    /** Destroy the index and free memory. */
    private static native void nativeIndexDestroy(long indexHandle);

    /**
     * LLM completion.
     * messagesJson — JSON array of {role, content} objects
     * optionsJson  — optional generation params (temperature, max_tokens etc)
     * toolsJson    — optional tool definitions for function calling
     * Returns response string, or null on failure.
     */
    private static native String nativeComplete(
            long modelHandle,
            String messagesJson,
            String optionsJson,
            String toolsJson);

    /** Get last error string from C++ layer. */
    private static native String nativeGetLastError();

    // -------------------------------------------------------------------------
    // Public Java API
    // -------------------------------------------------------------------------

    /**
     * Initialize a Cactus model.
     *
     * @param modelPath  absolute path to the .gguf model file
     * @param corpusDir  optional corpus directory for built-in RAG (pass null to skip)
     * @param cacheIndex true = reuse cached index if available
     * @return model handle (pass to all subsequent calls), 0 on failure
     */
    public static long init(String modelPath, String corpusDir, boolean cacheIndex) {
        if (modelPath == null || modelPath.isEmpty()) {
            Log.e(TAG, "init: modelPath is null or empty");
            return 0L;
        }
        long handle = nativeInit(modelPath, corpusDir, cacheIndex);
        if (handle == 0L) {
            Log.e(TAG, "init failed: " + getLastError());
        } else {
            Log.i(TAG, "Model initialized: " + modelPath);
        }
        return handle;
    }

    public static void destroy(long modelHandle) {
        if (modelHandle == 0L) return;
        nativeDestroy(modelHandle);
        Log.i(TAG, "Model destroyed");
    }

    /**
     * Generate a text embedding.
     *
     * @param modelHandle handle from init()
     * @param text        text to embed
     * @return float[] embedding, or null on failure
     */
    public static float[] embed(long modelHandle, String text) {
        if (modelHandle == 0L) {
            Log.e(TAG, "embed: invalid model handle");
            return null;
        }
        if (text == null || text.trim().isEmpty()) {
            Log.w(TAG, "embed: empty text");
            return null;
        }
        float[] result = nativeEmbed(modelHandle, text, true);
        if (result == null) {
            Log.e(TAG, "embed failed: " + getLastError());
        }
        return result;
    }

    /**
     * Initialize a Cactus index.
     *
     * @param indexDir     directory where index.bin and data.bin will be stored
     * @param embeddingDim must match the model's embedding dimension
     * @return index handle, 0 on failure
     */
    public static long indexInit(String indexDir, int embeddingDim) {
        long handle = nativeIndexInit(indexDir, embeddingDim);
        if (handle == 0L) {
            Log.e(TAG, "indexInit failed: " + getLastError());
        } else {
            Log.i(TAG, "Index initialized at: " + indexDir);
        }
        return handle;
    }

    /**
     * Add a single document + embedding to the index.
     *
     * @param indexHandle  handle from indexInit()
     * @param id           unique integer ID (store in DocumentChunk.cactusIndexId)
     * @param document     the text content (chunk text or summary)
     * @param metadata     optional JSON metadata string (pass null to skip)
     * @param embedding    float[] from embed()
     * @return 0 on success, negative on failure
     */
    public static int indexAdd(long indexHandle, int id, String document, String metadata, float[] embedding) {
        if (indexHandle == 0L || embedding == null) {
            Log.e(TAG, "indexAdd: invalid params");
            return -1;
        }
        int result = nativeIndexAdd(
                indexHandle,
                new int[]{id},
                new String[]{document},
                metadata != null ? new String[]{metadata} : null,
                new float[][]{embedding},
                embedding.length);
        if (result != 0) {
            Log.e(TAG, "indexAdd failed: " + getLastError());
        }
        return result;
    }

    /**
     * Query the index for nearest neighbours.
     *
     * @param indexHandle    handle from indexInit()
     * @param queryEmbedding float[] from embed()
     * @param topK           number of results to return
     * @return int[] of matching IDs ordered by relevance, or null on failure
     */
    public static int[] indexQuery(long indexHandle, float[] queryEmbedding, int topK) {
        if (indexHandle == 0L || queryEmbedding == null) {
            Log.e(TAG, "indexQuery: invalid params");
            return null;
        }
        int[] results = nativeIndexQuery(indexHandle, queryEmbedding, queryEmbedding.length, topK);
        if (results == null) {
            Log.e(TAG, "indexQuery failed: " + getLastError());
        }
        return results;
    }

    /**
     * Delete entries from the index by their IDs.
     */
    public static int indexDelete(long indexHandle, int[] ids) {
        if (indexHandle == 0L || ids == null || ids.length == 0) return -1;
        int result = nativeIndexDelete(indexHandle, ids);
        if (result != 0) {
            Log.e(TAG, "indexDelete failed: " + getLastError());
        }
        return result;
    }

    public static void indexDestroy(long indexHandle) {
        if (indexHandle == 0L) return;
        nativeIndexDestroy(indexHandle);
    }

    /**
     * Run LLM completion.
     *
     * @param modelHandle  handle from init()
     * @param messagesJson JSON array: [{"role":"user","content":"..."}]
     * @param context      retrieved context to prepend (pass null if none)
     * @param toolsJson    optional tool definitions JSON (pass null if none)
     * @return response string, or null on failure
     */
    public static String complete(long modelHandle, String messagesJson, String context, String toolsJson) {
        if (modelHandle == 0L || messagesJson == null) {
            Log.e(TAG, "complete: invalid params");
            return null;
        }

        // If we have retrieved context, inject it as a system message
        String finalMessages = messagesJson;
        if (context != null && !context.trim().isEmpty()) {
            // Prepend system message with context
            // Format: [{"role":"system","content":"Context:\n...\n\nAnswer based on the above."},...]
            String systemMsg = "{\"role\":\"system\",\"content\":\"Context:\\n"
                    + context.replace("\"", "\\\"").replace("\n", "\\n")
                    + "\\n\\nAnswer based on the above context.\"}";
            // Insert before the first message
            finalMessages = "[" + systemMsg + "," + messagesJson.substring(1);
        }

        String result = nativeComplete(modelHandle, finalMessages, null, toolsJson);
        if (result == null) {
            Log.e(TAG, "complete failed: " + getLastError());
        }
        return result;
    }

    public static String getLastError() {
        String err = nativeGetLastError();
        return err != null ? err : "unknown error";
    }
}
