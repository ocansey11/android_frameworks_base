package com.android.server.jarvis.agent;

import android.util.Log;

import com.android.server.jarvis.model.AgentSession;

/**
 * RouterNode — deterministic routing between loop nodes.
 *
 * This is the LangGraph "conditional edge" equivalent. It reads AgentSession state
 * and the model's last output, then decides what node runs next.
 *
 * Critically: RouterNode makes NO model calls. It is purely deterministic logic.
 * Keeping routing cheap is non-negotiable on mobile — every model call is ~1s+.
 *
 * Gemma 4 emits <|tool_call|> as a hard token boundary when it wants to call a tool.
 * isToolCall() checks for this token directly — no heuristics, no extra inference.
 *
 * Source: https://ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4
 */
public class RouterNode {

    private static final String TAG = "RouterNode";

    /** Gemma 4 token emitted when the model wants to call a tool. */
    private static final String TOOL_CALL_TOKEN = "<|tool_call|>";

    /** Signals that the model wants more context before answering. */
    private static final String[] RETRIEVAL_SIGNALS = {
        "I don't have information",
        "let me look that up",
        "I need to retrieve",
        "I don't have access to",
    };

    public enum Next {
        /** PlanNode should run (first turn only). */
        PLAN,
        /** RetrieveNode should run — model needs more context. */
        RETRIEVE,
        /** ToolNode should run — model emitted a tool call token. */
        TOOL_CALL,
        /** RespondNode should run — planning done, context ready, no tool call. */
        RESPOND,
        /** Loop complete — RespondNode has produced finalAnswer. */
        DONE,
        /** maxTurns hit or unrecoverable error — stop the loop. */
        FAILED
    }

    /**
     * Route based on session state + model output.
     *
     * @param session     current session state (status + turnCount)
     * @param modelOutput last string returned by any model call (may be null)
     * @return which node runs next
     */
    public Next route(AgentSession session, String modelOutput) {
        if (session.turnCount >= session.maxTurns) {
            Log.w(TAG, "maxTurns reached (" + session.maxTurns + ") — failing session");
            return Next.FAILED;
        }

        // Status-driven routing first — overrides output inspection
        if ("DONE".equals(session.status))   return Next.DONE;
        if ("FAILED".equals(session.status)) return Next.FAILED;

        // First turn: always plan
        if (session.turnCount == 0 && !"PLANNING".equals(session.status)) {
            return Next.PLAN;
        }

        if (modelOutput == null) return Next.RESPOND;

        // Tool call check — Gemma 4 token boundary
        if (isToolCall(modelOutput)) {
            Log.d(TAG, "Tool call token detected — routing to ToolNode");
            return Next.TOOL_CALL;
        }

        // Retrieval check — model signals it lacks context
        if (needsRetrieval(modelOutput, session)) {
            Log.d(TAG, "Retrieval signal detected — routing to RetrieveNode");
            return Next.RETRIEVE;
        }

        return Next.RESPOND;
    }

    /** True if the model output contains Gemma 4's tool call token. */
    private boolean isToolCall(String output) {
        return output.contains(TOOL_CALL_TOKEN);
    }

    /** True if the model output signals it wants more context and we haven't retrieved yet. */
    private boolean needsRetrieval(String output, AgentSession session) {
        if (session.accumulatedContext != null && !session.accumulatedContext.isEmpty()) {
            return false; // already retrieved — don't loop on retrieval
        }
        for (String signal : RETRIEVAL_SIGNALS) {
            if (output.contains(signal)) return true;
        }
        return false;
    }
}
