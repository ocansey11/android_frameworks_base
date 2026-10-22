package com.android.server.jarvis.inference;

import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Cactus engine, by way of {@link CactusWrapper} (which stays the only
 * entry point to Cactus native code). Native on arm64. On the x86_64
 * emulator libcactus is built with its NEON kernels translated by SIMDe
 * (vendor/cactus/jarvisos/x86): enough to prove the path works, far too slow
 * to compare, so "auto" leaves it alone there.
 *
 * Model paths are directories, not files: Cactus v2 loads a "bundle" folder
 * (config.txt plus weights), not a .gguf. No bundle has been tried on a
 * device yet.
 */
final class CactusEngine implements InferenceEngine {

    private static final String MODEL_DIR = "/data/system/jarvis/models/cactus/";

    @Override public String name() { return "cactus"; }

    @Override public boolean isAvailable() { return CactusWrapper.isLoaded(); }

    @Override
    public boolean suitsAuto() {
        return isAvailable() && !Build.SUPPORTED_ABIS[0].startsWith("x86");
    }

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
        return CactusWrapper.complete(modelHandle, messagesJson, null, openAiTools(toolsJson));
    }

    /**
     * Cactus's tool parser (parse_tools_json in utils.h) only finds tools by
     * their "function" key, so the bare definitions JarvisOS passes were all
     * skipped and Cactus never saw a tool. Wraps each one as
     * {"type":"function","function":{...}}; already wrapped ones pass through.
     */
    static String openAiTools(String toolsJson) {
        if (toolsJson == null || toolsJson.isEmpty()) return toolsJson;
        try {
            JSONArray in = new JSONArray(toolsJson);
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject tool = in.getJSONObject(i);
                if (tool.has("function")) {
                    out.put(tool);
                } else {
                    out.put(new JSONObject().put("type", "function").put("function", tool));
                }
            }
            return out.toString();
        } catch (JSONException e) {
            Log.e("CactusEngine", "tools are not a JSON array of objects", e);
            return toolsJson;
        }
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
