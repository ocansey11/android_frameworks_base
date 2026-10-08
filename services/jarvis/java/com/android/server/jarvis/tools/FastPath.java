package com.android.server.jarvis.tools;

import android.os.SystemProperties;
import android.util.Log;

import com.android.server.jarvis.inference.Needle;

/**
 * The short way from a request to a tool: Needle picks the tools, and if it is
 * confident they run at once and their results are the reply. No chat model is
 * involved, which is what makes it quick. Anything Needle declines or is unsure
 * of is left to the agent loop (AGENTIC_LOOP.md).
 */
public final class FastPath {

    private static final String TAG = "FastPath";

    /** "0" turns the fast path off, so every request goes through the agent loop. */
    public static final String PROP_ENABLED = "persist.sys.jarvis.fast";
    /** Least confidence, in percent, at which Needle's choice runs without the chat model. */
    public static final String PROP_CONFIDENCE = "persist.sys.jarvis.fast_confidence";
    private static final int DEFAULT_CONFIDENCE_PERCENT = 75;

    private final ToolDispatcher mDispatcher;

    public FastPath(ToolDispatcher dispatcher) {
        mDispatcher = dispatcher;
    }

    /** What happened to one request. */
    public static final class Outcome {
        /** True if tools ran and {@link #reply} is the answer. */
        public boolean handled;
        public String reply;
        /** What was heard, for a spoken request; null if nothing could be transcribed. */
        public String transcript;
        /** The calls Needle chose, whether or not they ran. */
        public String calls = "";
        public double confidence;
        public long decideMs;
        public long toolMs;
    }

    public static boolean isEnabled() {
        return SystemProperties.getBoolean(PROP_ENABLED, true);
    }

    /** Handles a typed request. Never null; {@code handled} is false if it was left alone. */
    public Outcome tryText(String query) {
        if (!isEnabled() || !Needle.canRoute()) return new Outcome();
        return act(Needle.decide(query, mDispatcher.allToolsJson()), true);
    }

    /**
     * Handles a spoken request. When it is not handled, {@code transcript} is
     * still set if the speech model is loaded, for the agent loop to answer.
     *
     * @param pcm 16 kHz mono samples in [-1, 1]
     */
    public Outcome tryAudio(float[] pcm) {
        if (isEnabled() && Needle.canRoute() && Needle.canHear()) {
            return act(Needle.decideSpeech(pcm, mDispatcher.allToolsJson()), true);
        }
        Outcome outcome = new Outcome();
        outcome.transcript = Needle.transcribe(pcm);
        return outcome;
    }

    /** What Needle would do with a typed request, without doing it. */
    public Outcome preview(String query) {
        return act(Needle.decide(query, mDispatcher.allToolsJson()), false);
    }

    private Outcome act(Needle.Decision decision, boolean dispatch) {
        Outcome outcome = new Outcome();
        if (decision == null) return outcome;
        outcome.transcript = decision.transcript;
        outcome.confidence = decision.confidence;
        outcome.decideMs = decision.engineMs;

        StringBuilder calls = new StringBuilder();
        for (Needle.Call call : decision.calls) {
            if (calls.length() > 0) calls.append("; ");
            calls.append(call.name).append(' ').append(call.arguments);
        }
        outcome.calls = calls.toString();

        final int percent = SystemProperties.getInt(PROP_CONFIDENCE, DEFAULT_CONFIDENCE_PERCENT);
        final boolean confident = decision.confidence * 100 >= percent;
        Log.i(TAG, "decided in " + decision.engineMs + " ms, confidence "
                + decision.confidence + ": " + (calls.length() > 0 ? calls : "no tool"));
        if (!dispatch || decision.calls.isEmpty() || !confident) return outcome;

        final long start = System.nanoTime();
        StringBuilder reply = new StringBuilder();
        for (Needle.Call call : decision.calls) {
            if (reply.length() > 0) reply.append('\n');
            reply.append(mDispatcher.dispatchByName(call.name, call.arguments.toString()));
        }
        outcome.toolMs = (System.nanoTime() - start) / 1_000_000;
        outcome.reply = reply.toString();
        outcome.handled = true;
        return outcome;
    }
}
