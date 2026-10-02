package com.android.server.jarvis.agent;

import android.util.Log;

import com.android.server.jarvis.core.JarvisStore;
import com.android.server.jarvis.model.AgentSession;
import com.android.server.jarvis.tools.ToolDispatcher;

import java.util.UUID;

/**
 * SubAgentExecutor — a child agentic loop scoped to a specific sub-task.
 *
 * Pattern: Claude Code coordinator model (https://github.com/nirholas/claude-code)
 * A parent JarvisExecutor can spawn a SubAgentExecutor when it needs a specialised
 * sub-task handled by a different "agent" with a different context or tool scope.
 *
 * Key differences from JarvisExecutor:
 *   - Has a parent session ID — the parent's accumulatedContext is injected as initial context
 *   - maxTurns is halved (2 by default) — sub-tasks must be tight
 *   - Sub-session is linked to the parent via parentSessionId field
 *   - Result is a single string returned to the parent, not a full response to the user
 *
 * Phase 6 use cases:
 *   - Parent asks: "what files did the user work on last week?" → SubAgent handles file lookup
 *   - Parent asks: "summarise this document" → SubAgent handles retrieval + summarisation
 *   - Parent has tool X and tool Y → spawns sub-agent with only tool X context
 *
 * Phase 6+ (not built yet):
 *   - Sub-agents with separate model entries (planning model vs. tool model)
 *   - Sub-agent pool (cap concurrent sub-agents to 2 — thermal constraint)
 */
public class SubAgentExecutor {

    private static final String TAG = "SubAgentExecutor";

    /** Sub-agents have a tighter turn limit — they solve one thing and return. */
    private static final int SUB_AGENT_MAX_TURNS = 2;

    private final PlanNode     mPlanNode;
    private final RetrieveNode mRetrieveNode;
    private final ToolNode     mToolNode;
    private final RespondNode  mRespondNode;
    private final RouterNode   mRouter;

    private final String mParentSessionId;
    private final String mParentContext;

    /**
     * @param toolDispatcher  the parent's ToolDispatcher — sub-agent shares the tool registry
     * @param parentSessionId UUID of the parent AgentSession (for traceability)
     * @param parentContext   accumulatedContext from the parent — injected into sub-session
     */
    public SubAgentExecutor(ToolDispatcher toolDispatcher,
                            String parentSessionId,
                            String parentContext) {
        mPlanNode       = new PlanNode();
        mRetrieveNode   = new RetrieveNode();
        mToolNode       = new ToolNode(toolDispatcher);
        mRespondNode    = new RespondNode();
        mRouter         = new RouterNode();
        mParentSessionId = parentSessionId;
        mParentContext   = parentContext;
    }

    /**
     * Execute a sub-task.
     *
     * @param subQuery the specific sub-task query (derived from parent's plan)
     * @return the sub-agent's result string (returned to parent, not to user)
     */
    public String execute(String subQuery) {
        AgentSession session = createSubSession(subQuery);
        persistSession(session);

        String lastOutput = null;

        while (true) {
            RouterNode.Next next = mRouter.route(session, lastOutput);
            Log.d(TAG, "[sub:" + mParentSessionId.substring(0, 8) + "] Router → "
                    + next + " (turn " + session.turnCount + ")");

            switch (next) {
                case PLAN:
                    lastOutput = mPlanNode.execute(session);
                    if (lastOutput == null) {
                        session.status = "FAILED";
                        persistSession(session);
                        return "Sub-agent planning failed for: " + subQuery;
                    }
                    session.turnCount++;
                    persistSession(session);
                    break;

                case RETRIEVE:
                    lastOutput = mRetrieveNode.execute(session);
                    if (lastOutput == null) lastOutput = "";
                    session.turnCount++;
                    persistSession(session);
                    break;

                case TOOL_CALL:
                    lastOutput = mToolNode.execute(session, lastOutput);
                    session.turnCount++;
                    persistSession(session);
                    // After a tool call in a sub-agent, go straight to RESPOND
                    // (sub-agents don't re-plan — they return their result)
                    lastOutput = mRespondNode.execute(session);
                    persistSession(session);
                    return session.finalAnswer != null
                            ? session.finalAnswer
                            : lastOutput != null ? lastOutput : "Sub-agent returned no result";

                case RESPOND:
                    String answer = mRespondNode.execute(session);
                    persistSession(session);
                    return answer != null ? answer : "Sub-agent returned no result";

                case DONE:
                    persistSession(session);
                    return session.finalAnswer != null
                            ? session.finalAnswer
                            : "Sub-agent completed with no answer";

                case FAILED:
                    session.status = "FAILED";
                    persistSession(session);
                    return "Sub-agent failed after " + session.turnCount + " turns";
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AgentSession createSubSession(String subQuery) {
        AgentSession s = new AgentSession();
        s.sessionId         = UUID.randomUUID().toString();
        s.originalQuery     = subQuery;
        s.accumulatedContext = mParentContext; // inherit parent context
        s.turnCount         = 0;
        s.maxTurns          = SUB_AGENT_MAX_TURNS;
        s.status            = "PLANNING";
        s.createdAt         = System.currentTimeMillis();
        s.lastUpdatedAt     = s.createdAt;
        // Tag with parent ID for traceability — stored in currentPlan field
        // (avoids adding a new DB column — Phase 6+ can add parentSessionId properly)
        s.currentPlan       = "[sub-agent of " + mParentSessionId + "]";
        return s;
    }

    private void persistSession(AgentSession session) {
        if (!JarvisStore.isReady()) return;
        try {
            session.lastUpdatedAt = System.currentTimeMillis();
            JarvisStore.box(AgentSession.class).put(session);
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist sub-session", e);
        }
    }
}
