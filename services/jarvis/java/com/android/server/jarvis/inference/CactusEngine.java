package com.android.server.jarvis.inference;

/**
 * The Cactus engine, by way of {@link CactusWrapper} (which stays the only
 * entry point to Cactus native code). arm64 only.
 *
 * Model paths are directories, not files: Cactus v2 loads a "bundle" folder
 * (config.txt plus weights), not a .gguf. No bundle has been tried on a
 * device yet.
 */
final class CactusEngine implements InferenceEngine {

    private static final String MODEL_DIR = "/data/system/jarvis/models/cactus/";

    @Override public String name() { return "cactus"; }

    @Override public boolean isAvailable() { return CactusWrapper.isLoaded(); }

    @Override public String modelPath(String role) { return MODEL_DIR + role; }

    @Override
    public long init(String modelPath, boolean forEmbedding) {
        return CactusWrapper.init(modelPath, null, forEmbedding);
    }

    @Override public void destroy(long modelHandle) { CactusWrapper.destroy(modelHandle); }

    @Override
    public float[] embed(long modelHandle, String text) {
        return CactusWrapper.embed(modelHandle, text);
    }

    @Override
    public String complete(long modelHandle, String messagesJson, String toolsJson) {
        return CactusWrapper.complete(modelHandle, messagesJson, null, toolsJson);
    }

    @Override
    public long indexInit(String indexDir, int embeddingDim) {
        return CactusWrapper.indexInit(indexDir, embeddingDim);
    }

    @Override
    public int indexAdd(long indexHandle, int id, String document, String metadata,
            float[] embedding) {
        return CactusWrapper.indexAdd(indexHandle, id, document, metadata, embedding);
    }

    @Override
    public int[] indexQuery(long indexHandle, float[] queryEmbedding, int topK) {
        return CactusWrapper.indexQuery(indexHandle, queryEmbedding, topK);
    }

    @Override
    public int indexDelete(long indexHandle, int[] ids) {
        return CactusWrapper.indexDelete(indexHandle, ids);
    }

    @Override public void indexDestroy(long indexHandle) { CactusWrapper.indexDestroy(indexHandle); }

    @Override public String getLastError() { return CactusWrapper.getLastError(); }
}
