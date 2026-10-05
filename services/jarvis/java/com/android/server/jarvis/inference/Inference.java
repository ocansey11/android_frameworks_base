package com.android.server.jarvis.inference;

import android.os.SystemProperties;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The one door to on-device inference. Forwards to the selected
 * {@link InferenceEngine}; callers never see which engine it is.
 *
 * The choice is the system property {@code persist.sys.jarvis.engine}:
 * "auto" (default), "cactus" or "llama". "auto" takes Cactus when its library
 * is present and otherwise llama.cpp. Handles belong to the engine that made
 * them, so after {@link #select} every model and index must be reloaded
 * (JarvisService does this).
 */
public final class Inference {

    private static final String TAG = "Inference";
    public static final String ENGINE_PROPERTY = "persist.sys.jarvis.engine";

    private static final InferenceEngine[] ENGINES = { new CactusEngine(), new LlamaEngine() };
    private static volatile InferenceEngine sEngine;

    private Inference() {}

    /** The engine in use, chosen from the property on first use. */
    public static InferenceEngine engine() {
        InferenceEngine engine = sEngine;
        if (engine == null) {
            synchronized (Inference.class) {
                if (sEngine == null) sEngine = choose(SystemProperties.get(ENGINE_PROPERTY, "auto"));
                engine = sEngine;
            }
        }
        return engine;
    }

    private static InferenceEngine choose(String wanted) {
        for (InferenceEngine engine : ENGINES) {
            if (engine.name().equals(wanted)) {
                if (!engine.isAvailable()) {
                    Log.w(TAG, "Engine '" + wanted + "' was asked for but cannot run here");
                }
                return engine;
            }
        }
        for (InferenceEngine engine : ENGINES) {
            if (engine.isAvailable()) {
                Log.i(TAG, "Engine (auto): " + engine.name());
                return engine;
            }
        }
        Log.w(TAG, "No inference engine can run on this device");
        return ENGINES[0];
    }

    /**
     * Switches engine and remembers the choice across reboots. The caller must
     * have destroyed every handle from the old engine first.
     *
     * @param name "auto", "cactus" or "llama"
     * @return false if the name is not one of those
     */
    public static synchronized boolean select(String name) {
        boolean known = "auto".equals(name);
        for (InferenceEngine engine : ENGINES) known |= engine.name().equals(name);
        if (!known) return false;
        SystemProperties.set(ENGINE_PROPERTY, name);
        sEngine = choose(name);
        return true;
    }

    /** "cactus (available), llama (not available)" for status output. */
    public static String describeEngines() {
        StringBuilder sb = new StringBuilder();
        for (InferenceEngine engine : ENGINES) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(engine.name()).append(engine.isAvailable() ? "" : " (cannot run here)");
        }
        return sb.toString();
    }

    public static String modelPath(String role) {
        return engine().modelPath(role);
    }

    public static long init(String modelPath, boolean forEmbedding) {
        if (modelPath == null || modelPath.isEmpty()) return 0L;
        InferenceEngine engine = engine();
        if (!engine.isAvailable()) return 0L;
        long handle = engine.init(modelPath, forEmbedding);
        if (handle == 0L) {
            Log.e(TAG, engine.name() + ": could not load " + modelPath + ": "
                    + engine.getLastError());
        }
        return handle;
    }

    public static void destroy(long modelHandle) {
        if (modelHandle != 0L) engine().destroy(modelHandle);
    }

    public static float[] embed(long modelHandle, String text) {
        if (modelHandle == 0L || text == null || text.trim().isEmpty()) return null;
        return engine().embed(modelHandle, text);
    }

    /**
     * Asks the model for a reply, with an optional set of tools it may call.
     *
     * @param messagesJson JSON array: [{"role":"user","content":"..."}]
     * @param context      retrieved context to put in front as a system message, or null
     * @param toolsJson    JSON array of {name, description, parameters}, or null
     * @return the reply, or null if the engine failed
     */
    public static Completion completeWithTools(long modelHandle, String messagesJson,
            String context, String toolsJson) {
        if (modelHandle == 0L || messagesJson == null) return null;

        String messages = messagesJson;
        if (context != null && !context.trim().isEmpty()) {
            try {
                JSONObject system = new JSONObject();
                system.put("role", "system");
                system.put("content", "Context:\n" + context
                        + "\n\nAnswer based on the above context.");
                JSONArray withContext = new JSONArray();
                withContext.put(system);
                JSONArray given = new JSONArray(messagesJson);
                for (int i = 0; i < given.length(); i++) withContext.put(given.get(i));
                messages = withContext.toString();
            } catch (JSONException e) {
                Log.e(TAG, "complete: malformed messagesJson", e);
                return null;
            }
        }

        InferenceEngine engine = engine();
        Completion completion = Completion.parse(engine.complete(modelHandle, messages, toolsJson));
        if (completion == null) {
            Log.e(TAG, engine.name() + ": complete failed: " + engine.getLastError());
        } else if (completion.error != null) {
            Log.e(TAG, engine.name() + ": complete failed: " + completion.error);
            return null;
        }
        return completion;
    }

    /** As {@link #completeWithTools} with no tools; returns just the text, or null. */
    public static String complete(long modelHandle, String messagesJson, String context) {
        Completion completion = completeWithTools(modelHandle, messagesJson, context, null);
        return completion != null ? completion.text : null;
    }

    public static long indexInit(String indexDir, int embeddingDim) {
        return engine().indexInit(indexDir, embeddingDim);
    }

    public static int indexAdd(long indexHandle, int id, String document, String metadata,
            float[] embedding) {
        if (indexHandle == 0L || embedding == null) return -1;
        return engine().indexAdd(indexHandle, id, document, metadata, embedding);
    }

    public static int[] indexQuery(long indexHandle, float[] queryEmbedding, int topK) {
        if (indexHandle == 0L || queryEmbedding == null) return null;
        return engine().indexQuery(indexHandle, queryEmbedding, topK);
    }

    public static int indexDelete(long indexHandle, int[] ids) {
        if (indexHandle == 0L || ids == null || ids.length == 0) return -1;
        return engine().indexDelete(indexHandle, ids);
    }

    public static void indexDestroy(long indexHandle) {
        if (indexHandle != 0L) engine().indexDestroy(indexHandle);
    }
}
