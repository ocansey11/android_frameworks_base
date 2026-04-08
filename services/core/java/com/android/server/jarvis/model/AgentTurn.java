package com.android.server.jarvis.model;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.relation.ToOne;

/**
 * One message turn in an AgentSession.
 *
 * Appended by each node as the loop runs. Used by DreamWorker (Phase 6)
 * to consolidate session history into UserContext facts.
 *
 * role values:
 *   "model"  — output from PlanNode, RespondNode, or model selection in ToolNode
 *   "tool"   — raw result returned by a dispatched tool
 *   "user"   — the original user query (written by JarvisExecutor at session start)
 */
@Entity
public class AgentTurn {

    @Id
    public long id;

    /** "model" | "tool" | "user" */
    public String role;

    /** What was said or returned. For tool turns, this is the raw result string. */
    public String content;

    /** Populated when role == "tool". Name of the tool that was called. */
    public String toolName;

    /** Populated when role == "tool". JSON args that were passed to the tool. */
    public String toolArgs;

    public long timestamp;

    public ToOne<AgentSession> session;
}
