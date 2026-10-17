package com.android.server.jarvis.inference;

import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The llama.cpp engine (vendor/llama.cpp, MIT). Loads .gguf models and, unlike
 * Cactus, also runs on x86_64, so a model can be tested on the emulator.
 *
 * llama.cpp has no vector index, so this engine keeps its own
 * ({@link FlatVectorIndex}).
 */
final class LlamaEngine implements InferenceEngine {

    private static final String TAG = "LlamaEngine";
    private static final String MODEL_DIR = "/data/system/jarvis/models/";
    private static final int CONTEXT_TOKENS = 4096;

    private static final boolean sLoaded = load();

    private final ConcurrentHashMap<Long, FlatVectorIndex> mIndexes = new ConcurrentHashMap<>();
    private final AtomicLong mNextIndexHandle = new AtomicLong(1);

    private static boolean load() {
        try {
            // The engine is built with CPU features the x86_64 baseline does
            // not guarantee; loading it without them would crash this
            // process. The check lives in its own, baseline-built library.
            System.loadLibrary("llama_jarvis_cpucheck");
            if (!nativeCpuSupported()) {
                Log.w(TAG, "This CPU lacks AVX2/FMA/F16C; llama.cpp disabled "
                        + "(on the emulator, start it with -qemu -cpu host)");
                return false;
            }
            System.loadLibrary("llama_jarvis");
            Log.i(TAG, "llama.cpp native library loaded");
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load llama.cpp native library", e);
            return false;
        }
    }

    private static native boolean nativeCpuSupported();
    private static native long nativeInit(String modelPath, boolean embedding, int nCtx,
            int nThreads);
    private static native void nativeDestroy(long handle);
    private static native float[] nativeEmbed(long handle, String text, boolean normalize);
    private static native String nativeComplete(long handle, String messagesJson,
            String optionsJson, String toolsJson);
    private static native String nativeGetLastError();

    @Override public String name() { return "llama"; }

    @Override public boolean isAvailable() { return sLoaded; }

    @Override public String modelPath(String role) { return MODEL_DIR + role + ".gguf"; }

    @Override
    public long init(String modelPath, boolean forEmbedding) {
        if (!sLoaded) return 0L;
        // Leave cores for the rest of system_server; more threads than big
        // cores makes a phone slower, not faster.
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        return nativeInit(modelPath, forEmbedding, CONTEXT_TOKENS, threads);
    }

    @Override
    public void destroy(long modelHandle) {
        if (sLoaded && modelHandle != 0L) nativeDestroy(modelHandle);
    }

    @Override
    public float[] embed(long modelHandle, String text) {
        if (!sLoaded || modelHandle == 0L) return null;
        return nativeEmbed(modelHandle, text, true);
    }

    @Override
    public String complete(long modelHandle, String messagesJson, String toolsJson) {
        if (!sLoaded || modelHandle == 0L) return null;
        return nativeComplete(modelHandle, messagesJson, null, toolsJson);
    }

    @Override
    public String complete(long modelHandle, String messagesJson, String toolsJson,
            String optionsJson) {
        if (!sLoaded || modelHandle == 0L) return null;
        return nativeComplete(modelHandle, messagesJson, optionsJson, toolsJson);
    }

    @Override
    public long indexInit(String indexDir, int embeddingDim) {
        FlatVectorIndex index = FlatVectorIndex.open(indexDir, embeddingDim);
        if (index == null) return 0L;
        long handle = mNextIndexHandle.getAndIncrement();
        mIndexes.put(handle, index);
        return handle;
    }

    @Override
    public int indexAdd(long indexHandle, int id, String document, String metadata,
            float[] embedding) {
        FlatVectorIndex index = mIndexes.get(indexHandle);
        return index != null && index.add(id, embedding) ? 0 : -1;
    }

    @Override
    public int[] indexQuery(long indexHandle, float[] queryEmbedding, int topK) {
        FlatVectorIndex index = mIndexes.get(indexHandle);
        return index != null ? index.query(queryEmbedding, topK) : null;
    }

    @Override
    public int indexDelete(long indexHandle, int[] ids) {
        FlatVectorIndex index = mIndexes.get(indexHandle);
        return index != null && ids != null && index.delete(ids) ? 0 : -1;
    }

    @Override public void indexDestroy(long indexHandle) { mIndexes.remove(indexHandle); }

    @Override
    public String getLastError() {
        return sLoaded ? nativeGetLastError() : "llama.cpp native library not loaded";
    }
}
