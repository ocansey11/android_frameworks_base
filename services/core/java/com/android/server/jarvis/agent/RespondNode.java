package com.android.server.jarvis.agent;

import android.util.Log;

import com.android.server.jarvis.core.ModelRegistry;
import com.android.server.jarvis.inference.CactusWrapper;
import com.android.server.jarvis.model.AgentSession;
import com.android.server.jarvis.model.AgentTurn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * RespondNode — generates the final answer for the user.
 *
 * Called after the loop has finished planning, retrieval, and tool use.
 * Builds a completion prompt from:
 *   - originalQuery (what the user asked)
 *   - accumulatedContext (retrieved docs + tool results, may be null)
 *
 * Uses "primary" ModelRegistry entry (Gemma 4), falls back to "rag".
 *
 * On success: sets session.finalAnswer and session.status = "DONE".
 * On failure: returns null and caller (JarvisExecutor) sets FAILED.
 */
public class RespondNode {

    private static final String TAG = "RespondNode";

    /**
     * Generate the final answer.
     *
     * @param session current session — originalQuery + accumulatedContext must be set
     * @return the final answer string, or null on failure
     */
    public String execute(AgentSession session) {
        ModelRegistry.ModelEntry model = getModel();
        if (model == null) {
            Log.e(TAG, "No model available for response generation");
            return null;
        }

        String userFacts = UserContextHelper.loadFormattedFacts();
        String answer = callModel(model.modelHandle, session, userFacts);
        if (answer == null) {
            Log.e(TAG, "RespondNode: model returned null");
            return null;
        }

        session.finalAnswer = answer;
        session.status = "DONE";

        AgentTurn turn = new AgentTurn();
        turn.role = "model";
        turn.content = answer;
        turn.timestamp = System.currentTimeMillis();
        session.turns.add(turn);

        Log.d(TAG, "Final answer: " + answer.substring(0, Math.min(80, answer.length())));
        return answer;
    }

    private String callModel(long modelHandle, AgentSession session, String userFacts) {
        try {
            JSONArray messages = new JSONArray();
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", session.originalQuery);
            messages.put(userMsg);

            // Merge user facts (DreamWorker output) with any retrieved/tool context.
            // Facts go first so the model treats them as stable background knowledge.
            // accumulatedContext (retrieved docs, tool results) goes second as session-specific detail.
            String fullContext = buildContext(userFacts, session.accumulatedContext);

            return CactusWrapper.complete(
                    modelHandle,
                    messages.toString(),
                    fullContext,
                    null);

        } catch (JSONException e) {
            Log.e(TAG, "RespondNode: failed to build messages", e);
            return null;
        }
    }

    /**
     * Combine user facts with session context.
     * Either or both may be null — returns whichever is non-null, or null if both are.
     */
    private String buildContext(String userFacts, String accumulatedContext) {
        boolean hasFacts   = userFacts != null && !userFacts.trim().isEmpty();
        boolean hasContext = accumulatedContext != null && !accumulatedContext.trim().isEmpty();
        if (hasFacts && hasContext) return userFacts + "\n\n" + accumulatedContext;
        if (hasFacts)   return userFacts;
        if (hasContext) return accumulatedContext;
        return null;
    }

    private ModelRegistry.ModelEntry getModel() {
        ModelRegistry registry = ModelRegistry.getInstance();
        ModelRegistry.ModelEntry entry = registry.getReady("primary");
        if (entry != null) return entry;
        return registry.getReady("rag");
    }
}
