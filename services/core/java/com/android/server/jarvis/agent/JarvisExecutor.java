package com.android.server.jarvis.agent;

import android.util.Log;

import com.android.server.jarvis.core.JarvisStore;
import com.android.server.jarvis.model.AgentSession;
import com.android.server.jarvis.tools.ToolDispatcher;

import java.util.UUID;

/**
 * JarvisExecutor — the agentic loop.
 *
 * This is the LangGraph graph-runner equivalent. It calls nodes in sequence,
 * reads RouterNode output to decide what runs next, and persists AgentSession
 * to ObjectBox after every node.
 *
 * Called from JarvisService.processQuery() instead of the old direct tool/RAG dispatch.
 * JarvisService stays single-purpose — JarvisExecutor owns the loop.
 *
 * Loop: Plan → [Retrieve?] → [ToolCall? → back to Plan] → Respond
 * Hard ceiling: maxTurns = 5 (thermal + KV cache constraint on mobile).
 *
 * If the loop reaches DONE, session.finalAnswer is returned to the caller.
 * If the loop reaches FAILED (maxTurns or error), a graceful error string is returned.
 *
 * Session is persisted at DONE and FAILED — DreamWorker (Phase 6) reads it later.
 */
public class JarvisExecutor {

    private static final String TAG = "JarvisExecutor";

    /** Hard ceiling on turns per session. Do NOT raise without profiling on target hardware. */
    private static final int MAX_TURNS = 5;

    private final PlanNode     mPlanNode;
    private final RetrieveNode mRetrieveNode;
    private final ToolNode     mToolNode;
    private final RespondNode  mRespondNode;
    private final RouterNode   mRouter;

    public JarvisExecutor(ToolDispatcher toolDispatcher) {
        mPlanNode     = new PlanNode();
        mRetrieveNode = new RetrieveNode();
        mToolNode     = new ToolNode(toolDispatcher);
        mRespondNode  = new RespondNode();
        mRouter       = new RouterNode();
    }

    /**
     * Run the agentic loop for a user query.
     *
     * Blocking — must be called on a background thread (JarvisService.mBinder
     * already dispatches processQuery to a thread via Binder).
     *
     * @param query the user's query string
     * @return the final answer string, or an error string (never null)
     */
    public String execute(String query) {
        AgentSession session = createSession(query);
        persistSession(session);

        String lastOutput = null;

        while (true) {
            RouterNode.Next next = mRouter.route(session, lastOutput);
            Log.d(TAG, "Router → " + next + " (turn " + session.turnCount + ")");

            switch (next) {
                case PLAN:
                    lastOutput = mPlanNode.execute(session);
                    if (lastOutput == null) {
                        session.status = "FAILED";
                        persistSession(session);
                        return "Error: planning failed";
                    }
                    session.turnCount++;
                    persistSession(session);
                    break;

                case RETRIEVE:
                    lastOutput = mRetrieveNode.execute(session);
                    // Retrieval failure is non-fatal — continue to RESPOND with whatever we have
                    if (lastOutput == null) lastOutput = "";
                    session.turnCount++;
                    persistSession(session);
                    break;

                case TOOL_CALL:
                    lastOutput = mToolNode.execute(session, lastOutput);
                    session.turnCount++;
                    persistSession(session);
                    // After a tool call, re-enter PLAN so the model can continue with the result
                    lastOutput = mPlanNode.execute(session);
                    if (lastOutput == null) lastOutput = "";
                    session.turnCount++;
                    persistSession(session);
                    break;

                case RESPOND:
                    String answer = mRespondNode.execute(session);
                    persistSession(session);
                    if (answer != null) return answer;
                    return buildFallback(session);

                case DONE:
                    persistSession(session);
                    return session.finalAnswer != null
                            ? session.finalAnswer
                            : buildFallback(session);

                case FAILED:
                    session.status = "FAILED";
                    persistSession(session);
                    return buildFallback(session);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentSession createSession(String query) {
        AgentSession s = new AgentSession();
        s.sessionId  = UUID.randomUUID().toString();
        s.originalQuery = query;
        s.turnCount  = 0;
        s.maxTurns   = MAX_TURNS;
        s.status     = "PLANNING";
        s.createdAt  = System.currentTimeMillis();
        s.lastUpdatedAt = s.createdAt;
        return s;
    }

    private void persistSession(AgentSession session) {
        if (!JarvisStore.isReady()) return;
        try {
            session.lastUpdatedAt = System.currentTimeMillis();
            JarvisStore.box(AgentSession.class).put(session);
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist session: " + session.sessionId, e);
        }
    }

    private String buildFallback(AgentSession session) {
        if ("FAILED".equals(session.status) && session.turnCount >= session.maxTurns) {
            return "I wasn't able to complete your request within the allowed steps. "
                    + "Please try rephrasing your query.";
        }
        return "I wasn't able to generate a response. Please try again.";
    }
}
