package com.android.server.jarvis.model;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.relation.ToMany;

/**
 * Persisted state object for one agentic loop run.
 *
 * This is the LangGraph "State" equivalent — everything the loop needs
 * to continue or resume. Written to ObjectBox after every node completes,
 * so a system_server crash mid-loop doesn't lose the session.
 *
 * Lifecycle:
 *   JarvisExecutor creates AgentSession at the start of processQuery().
 *   Each node updates the relevant fields and calls session.save().
 *   JarvisExecutor reads status to decide what happens next.
 *   DONE/FAILED sessions are kept as memory — DreamWorker (Phase 6) consumes them.
 *
 * maxTurns: hard ceiling of 5. KV cache growth and thermal throttling on mobile
 * make it non-negotiable. Do NOT raise without profiling on target hardware.
 *
 * accumulatedContext: retrieved docs + tool results for this session.
 * Truncation strategy: keep originalQuery + last 2 tool results if context pressure hits.
 */
@Entity
public class AgentSession {

    @Id
    public long id;

    /** UUID — the LangGraph thread_id equivalent. Unique per invocation. */
    public String sessionId;

    /** What the user originally asked. Never mutated once set. */
    public String originalQuery;

    /** What PlanNode decided to do this turn (may be updated each turn). */
    public String currentPlan;

    /** Raw result string from the last ToolNode execution. */
    public String lastToolResult;

    /**
     * Accumulated retrieved context + tool results.
     * RetrieveNode and ToolNode append here. RespondNode reads this to generate the answer.
     * Truncate oldest entries (not originalQuery) if context window pressure is hit.
     */
    public String accumulatedContext;

    /** How many full turns have run. RouterNode increments this. Hard ceiling: maxTurns. */
    public int turnCount;

    /** Hard ceiling. Default 5. Never raise without profiling on target hardware. */
    public int maxTurns;

    /**
     * Current execution state. Values:
     *   PLANNING    — PlanNode running
     *   RETRIEVING  — RetrieveNode running
     *   TOOL_CALL   — ToolNode running
     *   RESPONDING  — RespondNode running
     *   DONE        — loop completed, finalAnswer is ready
     *   FAILED      — maxTurns hit, error, or unrecoverable failure
     */
    public String status;

    /** Final answer string — populated by RespondNode on DONE. */
    public String finalAnswer;

    public long createdAt;
    public long lastUpdatedAt;

    /** Full turn history — consumed by DreamWorker in Phase 6. */
    public ToMany<AgentTurn> turns;
}
