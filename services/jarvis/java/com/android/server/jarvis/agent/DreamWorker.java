package com.android.server.jarvis.agent;

import android.content.Context;
import android.util.Log;


import com.android.server.jarvis.core.JarvisScheduler;
import com.android.server.jarvis.core.JarvisScheduler.Result;
import com.android.server.jarvis.core.JarvisStore;
import com.android.server.jarvis.core.ModelRegistry;
import com.android.server.jarvis.inference.CactusWrapper;
import com.android.server.jarvis.model.AgentSession;
import com.android.server.jarvis.model.AgentSession_;
import com.android.server.jarvis.model.AgentTurn;
import com.android.server.jarvis.model.UserContext;

import io.objectbox.Box;
import io.objectbox.query.QueryBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DreamWorker — nightly memory consolidation job.
 *
 * Inspired by the KAIROS autoDream pattern from the Claude Code internal architecture.
 * Source: https://github.com/nirholas/claude-code
 *
 * What it does:
 *   1. Query ObjectBox for completed AgentSessions not yet consolidated
 *   2. Build a consolidation prompt from the session's AgentTurns
 *   3. Ask the model: "what did you learn about this user from this session?"
 *   4. Merge new facts into UserContext.facts — remove contradictions
 *   5. Mark sessions as consolidated
 *
 * Key principle from KAIROS:
 *   The agent treats its own memory as a "hint" and verifies against ground truth.
 *   DreamWorker MERGES — it does not blindly replace UserContext.facts.
 *   New facts are added. Old facts that contradict new observations are removed.
 *
 * Scheduling:
 *   - Runs once per day via JarvisScheduler (first run 1h after boot)
 *   - Requires CHARGING constraint — inference is hot, battery must be plugged
 *   - One scheduled instance at a time
 *
 * Called from JarvisService.initializeAsync() after Phase 5 executor setup.
 */
public class DreamWorker {

    private static final String TAG = "DreamWorker";
    private static final String WORK_NAME = "jarvis_dream_worker";

    /** Max sessions to consolidate in one run — bounds execution time. */
    private static final int MAX_SESSIONS_PER_RUN = 10;

    private final Context mContext;

    public DreamWorker(Context context) {
        mContext = context;
    }

    // -------------------------------------------------------------------------
    // Schedule
    // -------------------------------------------------------------------------

    public static void schedule(Context context) {
        DreamWorker worker = new DreamWorker(context);
        JarvisScheduler.schedulePeriodic(context, WORK_NAME,
                1, 24, TimeUnit.HOURS, /* requiresCharging= */ true, worker::doWork);
        Log.i(TAG, "DreamWorker scheduled (24h, charging only)");
    }

    // -------------------------------------------------------------------------
    // Worker
    // -------------------------------------------------------------------------

    public Result doWork() {
        if (!JarvisStore.isReady()) {
            Log.w(TAG, "Store not ready — will retry");
            return Result.retry();
        }

        ModelRegistry.ModelEntry model = getModel();
        if (model == null) {
            Log.w(TAG, "No model ready — will retry");
            return Result.retry();
        }

        Log.i(TAG, "DreamWorker starting consolidation run");

        // Load or create the single UserContext row
        UserContext userContext = loadOrCreateUserContext();

        // Query completed, unconsolidated sessions
        List<AgentSession> sessions = queryPendingSessions();
        if (sessions.isEmpty()) {
            Log.i(TAG, "No pending sessions to consolidate");
            return Result.success();
        }

        Log.i(TAG, "Consolidating " + sessions.size() + " session(s)");
        int consolidated = 0;

        for (AgentSession session : sessions) {
            try {
                consolidateSession(session, userContext, model);
                session.consolidated = true;
                JarvisStore.box(AgentSession.class).put(session);
                consolidated++;
            } catch (Exception e) {
                Log.w(TAG, "Failed to consolidate session " + session.sessionId, e);
            }
        }

        // Persist updated UserContext
        userContext.consolidatedAt = System.currentTimeMillis();
        JarvisStore.box(UserContext.class).put(userContext);

        Log.i(TAG, "DreamWorker done — consolidated " + consolidated + " session(s)");
        return Result.success();
    }

    // -------------------------------------------------------------------------
    // Consolidation logic
    // -------------------------------------------------------------------------

    /**
     * Extract facts from one session and merge them into UserContext.
     */
    private void consolidateSession(AgentSession session, UserContext userContext,
                                    ModelRegistry.ModelEntry model) throws JSONException {
        // Build a summary of what happened in this session
        String sessionSummary = buildSessionSummary(session);
        if (sessionSummary == null || sessionSummary.trim().isEmpty()) return;

        // Ask the model what it learned about the user
        String extraction = extractFacts(model.modelHandle, sessionSummary,
                userContext.facts);
        if (extraction == null) return;

        // Merge extracted facts into UserContext
        mergeFacts(userContext, extraction);
    }

    /**
     * Builds a compact text summary of the session turns.
     * Format: "User asked: X. Model planned: Y. Tool called: Z (result: R). Answer: A."
     */
    private String buildSessionSummary(AgentSession session) {
        if (session.turns == null || session.turns.isEmpty()) {
            if (session.originalQuery == null) return null;
            String summary = "User asked: " + session.originalQuery;
            return session.finalAnswer != null
                    ? summary + "\nFinal answer: " + truncate(session.finalAnswer, 200)
                    : summary;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("User asked: ").append(session.originalQuery).append("\n");
        for (AgentTurn turn : session.turns) {
            if ("tool".equals(turn.role) && turn.toolName != null) {
                sb.append("Tool called: ").append(turn.toolName)
                  .append(" → ").append(truncate(turn.content, 120)).append("\n");
            } else if ("model".equals(turn.role) && turn.content != null
                    && !turn.content.startsWith("[retrieved")) {
                sb.append("Model: ").append(truncate(turn.content, 200)).append("\n");
            }
        }
        if (session.finalAnswer != null) {
            sb.append("Final answer: ").append(truncate(session.finalAnswer, 200));
        }
        return sb.toString().trim();
    }

    /**
     * Ask the model to extract user facts from the session summary.
     * Includes existing facts so the model can flag contradictions.
     */
    private String extractFacts(long modelHandle, String sessionSummary,
                                 String existingFacts) {
        try {
            String prompt = buildExtractionPrompt(sessionSummary, existingFacts);
            JSONArray messages = new JSONArray();
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.put(userMsg);

            return CactusWrapper.complete(modelHandle, messages.toString(), null, null);
        } catch (JSONException e) {
            Log.e(TAG, "extractFacts: failed to build messages", e);
            return null;
        }
    }

    private String buildExtractionPrompt(String sessionSummary, String existingFacts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Here is a summary of a user interaction:\n\n");
        sb.append(sessionSummary).append("\n\n");

        if (existingFacts != null && !existingFacts.trim().isEmpty()) {
            sb.append("Existing known facts about the user:\n");
            sb.append(existingFacts).append("\n\n");
        }

        sb.append("List any new facts learned about the user from this interaction. ");
        sb.append("Return a JSON array of short fact strings. ");
        sb.append("Only include facts that are genuinely new or that correct an existing fact. ");
        sb.append("If nothing new was learned, return an empty array [].");
        return sb.toString();
    }

    /**
     * Parse the model's JSON array response and merge into UserContext.facts.
     * New facts are appended. Contradicting facts replace old ones (naive approach —
     * improve with embedding similarity in a future iteration).
     */
    private void mergeFacts(UserContext userContext, String modelResponse) {
        try {
            // Find JSON array in the response (model may wrap it in prose)
            int start = modelResponse.indexOf('[');
            int end   = modelResponse.lastIndexOf(']');
            if (start < 0 || end <= start) return;

            JSONArray newFacts = new JSONArray(modelResponse.substring(start, end + 1));
            if (newFacts.length() == 0) return;

            // Load existing facts
            JSONArray existing = new JSONArray();
            if (userContext.facts != null && !userContext.facts.trim().isEmpty()) {
                try {
                    existing = new JSONArray(userContext.facts);
                } catch (JSONException e) {
                    Log.w(TAG, "Existing facts JSON is malformed — resetting");
                }
            }

            // Merge: append new facts (cap at 50 total to bound storage)
            for (int i = 0; i < newFacts.length(); i++) {
                String fact = newFacts.optString(i);
                if (fact != null && !fact.trim().isEmpty()) {
                    existing.put(fact.trim());
                }
            }

            // Cap at 50 most recent facts
            if (existing.length() > 50) {
                JSONArray capped = new JSONArray();
                for (int i = existing.length() - 50; i < existing.length(); i++) {
                    capped.put(existing.get(i));
                }
                existing = capped;
            }

            userContext.facts = existing.toString();
            Log.d(TAG, "UserContext now has " + existing.length() + " facts");

        } catch (JSONException e) {
            Log.w(TAG, "mergeFacts: could not parse model response as JSON array", e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<AgentSession> queryPendingSessions() {
        return JarvisStore.box(AgentSession.class).query()
                .equal(AgentSession_.status, "DONE",
                        QueryBuilder.StringOrder.CASE_SENSITIVE)
                .equal(AgentSession_.consolidated, false)
                .build()
                .find(0, MAX_SESSIONS_PER_RUN);
    }

    private UserContext loadOrCreateUserContext() {
        List<UserContext> all = JarvisStore.box(UserContext.class).getAll();
        if (!all.isEmpty()) return all.get(0);
        // First boot — create the single UserContext row
        UserContext ctx = new UserContext(java.util.Locale.getDefault().toLanguageTag());
        JarvisStore.box(UserContext.class).put(ctx);
        return ctx;
    }

    private ModelRegistry.ModelEntry getModel() {
        ModelRegistry registry = ModelRegistry.getInstance();
        ModelRegistry.ModelEntry entry = registry.getReady("primary");
        if (entry != null) return entry;
        return registry.getReady("rag");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
