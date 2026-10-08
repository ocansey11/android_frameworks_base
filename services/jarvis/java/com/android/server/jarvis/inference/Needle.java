package com.android.server.jarvis.inference;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The small, fast models: Needle 3 turns a request into tool calls with a
 * confidence score, and Whistle turns speech into text. They sit beside the
 * selected {@link InferenceEngine}, not behind it: they cannot chat or embed
 * documents, so the chat model stays the fallback for everything they decline.
 *
 * Every engine call runs on one thread. The engine is not thread-safe, and it
 * pins its own worker threads to the fast cores, which must not happen to a
 * Binder thread.
 */
public final class Needle {

    private static final String TAG = "Needle";

    private static final String MODEL_DIR = "/data/system/jarvis/models/";
    /** Tool embeddings the engine keeps for retrieval. Its own directory: see ARCHITECTURE.md. */
    private static final String TOOL_INDEX = "/data/system/jarvis/index_tools_needle/tools.idx";

    /** Room for a few calls and the model's one-line reasoning. */
    private static final int MAX_NEW_TOKENS = 128;
    /** Whistle's languages; any other locale lets it detect the language. */
    private static final List<String> SPEECH_LANGUAGES =
            List.of("en", "de", "fr", "es", "it", "nl", "pl");
    private static final String SPEECH_KEYWORDS = "Jarvis";

    private static final ExecutorService sThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Jarvis-needle");
        t.setDaemon(true);
        return t;
    });

    // Touched only on sThread.
    private static String sDeclaredTools;
    private static boolean sToolsReady;

    private static volatile int sModels;

    private Needle() {}

    /** What the model decided for one request. */
    public static final class Decision {
        /** What was heard; null for a typed request. */
        public String transcript;
        public final List<Call> calls = new ArrayList<>();
        /** 0 to 1; how sure the model is of {@link #calls}. */
        public double confidence;
        /** Time spent inside the engine, including transcription. */
        public long engineMs;
        /** Time to the first transcribed token, when speech was given. */
        public double speechFirstTokenMs;
    }

    public static final class Call {
        public final String name;
        public final JSONObject arguments;

        Call(String name, JSONObject arguments) {
            this.name = name;
            this.arguments = arguments;
        }
    }

    /** Where the model for a role ("needle" or "whistle") is expected. */
    public static String modelPath(String role) {
        return MODEL_DIR + ("whistle".equals(role) ? "whistle.cact" : "needle3.cact");
    }

    /**
     * Loads whichever of the two model files exist. Safe to call again after a
     * model is installed.
     */
    public static void loadModels() {
        if (!NeedleWrapper.isLoaded()) return;
        sModels = run(() -> {
            for (String role : new String[] {"needle", "whistle"}) {
                String path = modelPath(role);
                if (!new File(path).isFile()) continue;
                if (NeedleWrapper.nativeLoad(path) < 0) {
                    Log.w(TAG, "Could not load " + path + ": "
                            + NeedleWrapper.nativeGetLastError());
                }
            }
            sDeclaredTools = null;
            sToolsReady = false;
            String language = Locale.getDefault().getLanguage();
            NeedleWrapper.nativeSetAudio(
                    SPEECH_LANGUAGES.contains(language) ? language : null,
                    SPEECH_KEYWORDS, false);
            return NeedleWrapper.nativeModels();
        }, 0);
        Log.i(TAG, "Models: " + describe());
    }

    /** True when requests can be turned into tool calls. */
    public static boolean canRoute() { return (sModels & NeedleWrapper.TEXT) != 0; }

    /** True when speech can be turned into text. */
    public static boolean canHear() { return (sModels & NeedleWrapper.SPEECH) != 0; }

    public static String describe() {
        if (!NeedleWrapper.isLoaded()) return "engine not in this build";
        return "tool calling " + (canRoute() ? "ready" : "not installed")
                + ", speech " + (canHear() ? "ready" : "not installed");
    }

    /**
     * Asks Needle which tools a typed request needs.
     *
     * @param toolsJson every tool it may choose from, in OpenAI function format
     * @return the decision, or null if Needle is not loaded or failed
     */
    public static Decision decide(String text, String toolsJson) {
        if (!canRoute() || text == null || toolsJson == null) return null;
        return run(() -> complete(text, null, toolsJson), null);
    }

    /**
     * Same as {@link #decide}, from speech: transcribes and decides in one
     * engine call.
     *
     * @param pcm 16 kHz mono samples in [-1, 1], at most 30 seconds
     */
    public static Decision decideSpeech(float[] pcm, String toolsJson) {
        if (!canRoute() || !canHear() || pcm == null || toolsJson == null) return null;
        return run(() -> complete(null, pcm, toolsJson), null);
    }

    /** Speech to text only. Null on failure, "" for silence. */
    public static String transcribe(float[] pcm) {
        if (!canHear() || pcm == null) return null;
        return run(() -> {
            String language = Locale.getDefault().getLanguage();
            String json = NeedleWrapper.nativeTranscribe(pcm,
                    SPEECH_LANGUAGES.contains(language) ? language : null, SPEECH_KEYWORDS);
            if (json == null) {
                Log.w(TAG, "transcribe failed: " + NeedleWrapper.nativeGetLastError());
                return null;
            }
            return new JSONObject(json).optString("text", "");
        }, null);
    }

    // Runs on sThread.
    private static Decision complete(String text, float[] pcm, String toolsJson)
            throws JSONException {
        if (!toolsJson.equals(sDeclaredTools)) {
            new File(TOOL_INDEX).getParentFile().mkdirs();
            int prefix = NeedleWrapper.nativeInit(null, toolsJson, TOOL_INDEX);
            sDeclaredTools = toolsJson;
            sToolsReady = prefix >= 0;
            if (sToolsReady) {
                Log.i(TAG, "Tools declared: " + prefix + " prefix tokens");
            } else {
                Log.w(TAG, "Could not declare tools: " + NeedleWrapper.nativeGetLastError());
            }
        }
        if (!sToolsReady) return null;

        // Each request stands alone; without this, turns pile up as one conversation.
        NeedleWrapper.nativeReset();
        final long start = System.nanoTime();
        String json = NeedleWrapper.nativeComplete(text, pcm, MAX_NEW_TOKENS);
        final long engineMs = (System.nanoTime() - start) / 1_000_000;
        if (json == null) {
            Log.w(TAG, "complete failed: " + NeedleWrapper.nativeGetLastError());
            return null;
        }
        Decision decision = parse(json);
        decision.engineMs = engineMs;
        return decision;
    }

    /** Reads the engine's reply. Visible for tests. */
    static Decision parse(String json) throws JSONException {
        JSONObject reply = new JSONObject(json);
        Decision decision = new Decision();
        decision.confidence = reply.optDouble("confidence", 0);
        decision.transcript = reply.has("audio_text") ? reply.optString("audio_text") : null;
        decision.speechFirstTokenMs = reply.optDouble("audio_ttft_ms", 0);
        JSONArray calls = reply.optJSONArray("function_calls");
        for (int i = 0; calls != null && i < calls.length(); i++) {
            JSONObject call = calls.getJSONObject(i);
            JSONObject arguments = call.optJSONObject("arguments");
            decision.calls.add(new Call(call.getString("name"),
                    arguments != null ? arguments : new JSONObject()));
        }
        return decision;
    }

    private static <T> T run(Callable<T> work, T onFailure) {
        try {
            return sThread.submit(work).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return onFailure;
        } catch (Exception e) {
            Log.e(TAG, "Needle call failed", e);
            return onFailure;
        }
    }
}
