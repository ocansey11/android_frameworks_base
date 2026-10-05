package com.android.server.jarvis.agent;

import android.util.Log;

import com.android.server.jarvis.core.ModelRegistry;
import com.android.server.jarvis.inference.Completion;
import com.android.server.jarvis.inference.Inference;
import com.android.server.jarvis.model.AgentSession;
import com.android.server.jarvis.model.AgentTurn;
import com.android.server.jarvis.tools.ToolDispatcher;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * PlanNode — first node in the agentic loop.
 *
 * Asks the model: given this query, what should I do?
 * The model's response is stored as currentPlan and recorded as a turn.
 *
 * RouterNode inspects the plan output:
 *   - If it contains <|tool_call|> → route to ToolNode
 *   - If it signals needing context → route to RetrieveNode
 *   - Otherwise → route directly to RespondNode (simple query, no retrieval needed)
 *
 * Uses the "primary" ModelRegistry entry (Gemma 4 once Sam pulls it).
 * Falls back to "rag" entry if "primary" is not registered yet.
 */
public class PlanNode {

    private static final String TAG = "PlanNode";
    private static final String TOOL_CALL_OPEN  = "<|tool_call|>";
    private static final String TOOL_CALL_CLOSE = "<|end_tool_call|>";

    /** Null in sub-agents, which plan without tools. */
    private final ToolDispatcher mToolDispatcher;

    public PlanNode() {
        this(null);
    }

    public PlanNode(ToolDispatcher toolDispatcher) {
        mToolDispatcher = toolDispatcher;
    }

    /**
     * Run the planning step.
     *
     * @param session current session — originalQuery must be set
     * @return the model's plan string, or null on failure
     */
    public String execute(AgentSession session) {
        ModelRegistry.ModelEntry model = getModel();
        if (model == null) {
            Log.e(TAG, "No model available for planning");
            return null;
        }

        String userFacts = UserContextHelper.loadFormattedFacts();
        String plan = callModel(model.modelHandle, session, userFacts);
        if (plan == null) {
            Log.e(TAG, "PlanNode: model returned null");
            return null;
        }

        session.currentPlan = plan;
        session.status = "PLANNING";

        AgentTurn turn = new AgentTurn();
        turn.role = "model";
        turn.content = plan;
        turn.timestamp = System.currentTimeMillis();
        session.turns.add(turn);

        Log.d(TAG, "Plan: " + plan.substring(0, Math.min(80, plan.length())));
        return plan;
    }

    private String callModel(long modelHandle, AgentSession session, String userFacts) {
        try {
            // Without the tool list the model cannot know what it may call.
            String toolsJson = mToolDispatcher != null
                    ? mToolDispatcher.toolsJsonFor(session.originalQuery) : null;

            // userFacts is null on first boot or before DreamWorker has run — no-op
            Completion completion = Inference.completeWithTools(
                    modelHandle, buildMessages(session).toString(), userFacts, toolsJson);
            if (completion == null) return null;
            if (!completion.hasToolCall()) return completion.text;

            // Hand the call on in the form RouterNode and ToolNode read.
            JSONObject call = new JSONObject();
            call.put("name", completion.toolName);
            call.put("arguments", completion.toolArguments);
            return TOOL_CALL_OPEN + call + TOOL_CALL_CLOSE;
        } catch (JSONException e) {
            Log.e(TAG, "PlanNode: failed to build messages", e);
            return null;
        }
    }

    /**
     * The conversation so far: the user's query, then each tool call already
     * made in this session with its result, so that the model continues from
     * the result instead of asking for the same tool again.
     */
    private static JSONArray buildMessages(AgentSession session) throws JSONException {
        JSONArray messages = new JSONArray();
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", session.originalQuery);
        messages.put(userMsg);

        int n = 0;
        for (AgentTurn turn : session.turns) {
            if (!"tool".equals(turn.role) || turn.toolName == null) continue;
            String id = "call_" + (++n);

            JSONObject function = new JSONObject();
            function.put("name", turn.toolName);
            function.put("arguments", turn.toolArgs != null ? turn.toolArgs : "{}");
            JSONObject call = new JSONObject();
            call.put("id", id);
            call.put("type", "function");
            call.put("function", function);
            JSONObject asked = new JSONObject();
            asked.put("role", "assistant");
            asked.put("content", "");
            asked.put("tool_calls", new JSONArray().put(call));
            messages.put(asked);

            JSONObject result = new JSONObject();
            result.put("role", "tool");
            result.put("tool_call_id", id);
            result.put("name", turn.toolName);
            result.put("content", turn.content != null ? turn.content : "");
            messages.put(result);
        }
        return messages;
    }

    private ModelRegistry.ModelEntry getModel() {
        ModelRegistry registry = ModelRegistry.getInstance();
        ModelRegistry.ModelEntry entry = registry.getReady("primary");
        if (entry != null) return entry;
        // Fallback until "primary" (Gemma 4) is registered
        return registry.getReady("rag");
    }
}
