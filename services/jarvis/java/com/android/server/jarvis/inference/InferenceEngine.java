package com.android.server.jarvis.inference;

/**
 * What JarvisOS needs from an on-device inference engine. Everything outside
 * this package goes through {@link Inference}, which forwards to whichever
 * engine is selected; nothing else may name an engine or its types.
 *
 * Handles are opaque and belong to the engine that made them: a model or
 * index handle from one engine must never be passed to another. 0 means
 * "none" everywhere.
 */
public interface InferenceEngine {

    /** Short name used in settings, logs and `cmd jarvis engine`. */
    String name();

    /** False when the engine's native library is missing or this CPU cannot run it. */
    boolean isAvailable();

    /** Whether "auto" may pick this engine; false for one that only runs here for testing. */
    default boolean suitsAuto() { return isAvailable(); }

    /**
     * Where this engine expects the model for a role ("embed" or "chat").
     * Engines use different on-disk formats, so each has its own files.
     */
    String modelPath(String role);

    /**
     * Loads a model.
     *
     * @param forEmbedding true to use it with {@link #embed}, false for {@link #complete}
     * @return model handle, 0 on failure
     */
    long init(String modelPath, boolean forEmbedding);

    void destroy(long modelHandle);

    /** Returns a normalised embedding of {@code text}, or null on failure. */
    float[] embed(long modelHandle, String text);

    /**
     * Runs a chat completion.
     *
     * @param messagesJson JSON array of {role, content}
     * @param toolsJson    JSON array of {name, description, parameters}, or null
     * @return the engine's reply as a JSON envelope, read with
     *         {@link Completion#parse}; null on failure
     */
    String complete(long modelHandle, String messagesJson, String toolsJson);

    /**
     * As {@link #complete(long, String, String)}, with engine options as a
     * JSON object (max_tokens, temperature, tool_choice, parallel_tool_calls,
     * constrain_tools, grammar). An engine that has no options ignores them.
     */
    default String complete(long modelHandle, String messagesJson, String toolsJson,
            String optionsJson) {
        return complete(modelHandle, messagesJson, toolsJson);
    }

    /** Opens (or creates) a vector index stored under {@code indexDir}. 0 on failure. */
    long indexInit(String indexDir, int embeddingDim);

    /** Adds or replaces one vector. Returns 0 on success. */
    int indexAdd(long indexHandle, int id, String document, String metadata, float[] embedding);

    /** IDs of the nearest vectors, best first; null on failure. */
    int[] indexQuery(long indexHandle, float[] queryEmbedding, int topK);

    int indexDelete(long indexHandle, int[] ids);

    void indexDestroy(long indexHandle);

    String getLastError();
}
