package com.android.server.jarvis.agent;

import android.util.Log;

import com.android.server.jarvis.core.JarvisStore;
import com.android.server.jarvis.model.UserContext;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.List;

/**
 * Loads UserContext.facts from ObjectBox and formats them for model injection.
 *
 * Used by PlanNode and RespondNode to personalise prompts with what DreamWorker
 * has learned about the user across previous sessions.
 *
 * Facts are injected into the context string passed to CactusWrapper.complete(),
 * not as a system message — this respects Architecture Rule #6 (small models
 * get zero-shot, no system prompts). With Gemma 4 this is fine; with smaller
 * models the facts still flow in via the context parameter.
 *
 * Returns null silently if:
 *   - ObjectBox is not ready
 *   - No UserContext row exists yet (first boot, DreamWorker hasn't run)
 *   - facts field is empty or malformed JSON
 * Callers treat null as "no personalisation available" and proceed normally.
 */
final class UserContextHelper {

    private static final String TAG = "UserContextHelper";

    private UserContextHelper() {}

    /**
     * Load and format UserContext.facts as a bullet-pointed string.
     *
     * Example output:
     *   "Known facts about this user:\n- User is a software engineer\n- User prefers concise answers"
     *
     * @return formatted facts string, or null if no facts are available
     */
    static String loadFormattedFacts() {
        if (!JarvisStore.isReady()) return null;

        try {
            List<UserContext> all = JarvisStore.box(UserContext.class).getAll();
            if (all.isEmpty()) return null;

            UserContext ctx = all.get(0);
            if (ctx.facts == null || ctx.facts.trim().isEmpty()) return null;

            JSONArray facts = new JSONArray(ctx.facts);
            if (facts.length() == 0) return null;

            StringBuilder sb = new StringBuilder("Known facts about this user:\n");
            for (int i = 0; i < facts.length(); i++) {
                String fact = facts.optString(i, "").trim();
                if (!fact.isEmpty()) {
                    sb.append("- ").append(fact).append("\n");
                }
            }
            return sb.toString().trim();

        } catch (JSONException e) {
            Log.w(TAG, "UserContext.facts is malformed JSON — skipping personalisation", e);
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to load UserContext", e);
            return null;
        }
    }
}
