package com.android.server.jarvis.agent;

import android.util.Log;

import com.android.server.jarvis.core.ModelRegistry;
import com.android.server.jarvis.inference.CactusWrapper;
import com.android.server.jarvis.model.AgentSession;
import com.android.server.jarvis.model.AgentTurn;

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
        String plan = callModel(model.modelHandle, session.originalQuery, userFacts);
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

    private String callModel(long modelHandle, String query, String userFacts) {
        try {
            JSONArray messages = new JSONArray();
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", query);
            messages.put(userMsg);

            // userFacts is null on first boot or before DreamWorker has run — no-op
            return CactusWrapper.complete(modelHandle, messages.toString(), userFacts, null);
        } catch (JSONException e) {
            Log.e(TAG, "PlanNode: failed to build messages", e);
            return null;
        }
    }

    private ModelRegistry.ModelEntry getModel() {
        ModelRegistry registry = ModelRegistry.getInstance();
        ModelRegistry.ModelEntry entry = registry.getReady("primary");
        if (entry != null) return entry;
        // Fallback until "primary" (Gemma 4) is registered
        return registry.getReady("rag");
    }
}
