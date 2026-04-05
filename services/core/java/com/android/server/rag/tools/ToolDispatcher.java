package com.android.server.rag.tools;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ResultReceiver;
import android.util.Log;

import com.android.server.rag.core.JarvisStore;
import com.android.server.rag.core.ModelRegistry;
import com.android.server.rag.inference.CactusWrapper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ToolDispatcher — resolves and executes the best tool for a given query.
 *
 * Called from RagService.processQuery() after RAG retrieval when the model
 * signals a tool call is needed. Phase 5 (JarvisExecutor) will call this
 * directly as the ToolNode.
 *
 * Flow:
 *   1. Embed the query using the "tools" ModelRegistry entry
 *   2. HNSW search on ToolRecord.cactusIndexId → top-k ToolRecords
 *   3. Resolve each ToolRecord → AppRecord (which app, which receiver)
 *   4. Format top-k as JSON tool definitions
 *   5. Pass to CactusWrapper.complete() with toolsJson → model selects one
 *   6. Parse model output → identify selected tool + args
 *   7. Fire broadcast Intent to receiverClass with args as Bundle extras
 *   8. Wait for result via ResultReceiver (timeout: DISPATCH_TIMEOUT_MS)
 *   9. Return result string to caller
 *
 * Threading:
 *   resolveAndDispatch() is blocking — call on a background thread.
 *   The broadcast result is delivered on a HandlerThread and unblocked via CountDownLatch.
 *
 * See TOOL_REGISTRY.md and AGENTIC_LOOP.md for full design context.
 */
public class ToolDispatcher {

    private static final String TAG = "ToolDispatcher";

    /** Max tools returned from HNSW search before passing to model for selection. */
    private static final int TOP_K = 5;

    /** How long we wait for a tool broadcast result before timing out. */
    private static final long DISPATCH_TIMEOUT_MS = 10_000;

    /** Bundle key the app uses to return its result. */
    public static final String EXTRA_TOOL_RESULT = "com.jarvisos.tool.result";

    /** ResultReceiver result code for success. */
    public static final int RESULT_OK    = 0;
    public static final int RESULT_ERROR = 1;

    private final Context mContext;
    private final Handler mCallbackHandler;

    public ToolDispatcher(Context context) {
        this.mContext = context;
        HandlerThread ht = new HandlerThread("ToolDispatcher-callback");
        ht.start();
        this.mCallbackHandler = new Handler(ht.getLooper());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Resolve the best tool for the query and dispatch it.
     *
     * @param query the user's natural language query
     * @return the tool's result string, or an error string on failure
     */
    public String resolveAndDispatch(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "Error: empty query";
        }

        // Step 1 — semantic search for top-k matching tools
        List<ToolRecord> candidates = semanticSearch(query);
        if (candidates.isEmpty()) {
            Log.w(TAG, "No tool candidates found for query: " + query);
            return null; // caller falls back to RAG-only response
        }

        // Step 2 — format candidates as tool definitions JSON for the model
        String toolsJson = buildToolsJson(candidates);
        if (toolsJson == null) return "Error: failed to build tool definitions";

        // Step 3 — ask model to select the right tool + extract args
        ToolCall toolCall = selectTool(query, toolsJson, candidates);
        if (toolCall == null) {
            Log.w(TAG, "Model did not select a tool for query: " + query);
            return null; // no tool selected — caller handles
        }

        // Step 4 — dispatch the broadcast and wait for result
        return dispatch(toolCall);
    }

    // -------------------------------------------------------------------------
    // Semantic search
    // -------------------------------------------------------------------------

    private List<ToolRecord> semanticSearch(String query) {
        List<ToolRecord> results = new ArrayList<>();

        ModelRegistry.ModelEntry toolsModel = ModelRegistry.getInstance().getReady("tools");
        if (toolsModel == null) {
            Log.w(TAG, "Tools model not ready — falling back to metadata search");
            return metadataSearch(query);
        }

        float[] queryEmbedding = CactusWrapper.embed(toolsModel.modelHandle, query);
        if (queryEmbedding == null) {
            Log.w(TAG, "Failed to embed query — falling back to metadata search");
            return metadataSearch(query);
        }

        int[] hitIds = CactusWrapper.indexQuery(toolsModel.indexHandle, queryEmbedding, TOP_K);
        if (hitIds == null || hitIds.length == 0) return results;

        // Resolve cactusIndexIds → ToolRecords
        io.objectbox.Box<ToolRecord> box = JarvisStore.box(ToolRecord.class);
        for (int cactusId : hitIds) {
            ToolRecord tool = box.query()
                    .equal(ToolRecord_.cactusIndexId, cactusId)
                    .build().findFirst();
            if (tool != null) results.add(tool);
        }

        Log.i(TAG, "Semantic search found " + results.size() + " tool(s) for query");
        return results;
    }

    /** Fallback: keyword match on toolName + description when Cactus is unavailable. */
    private List<ToolRecord> metadataSearch(String query) {
        if (!JarvisStore.isReady()) return new ArrayList<>();
        String lower = query.toLowerCase();
        List<ToolRecord> all = JarvisStore.box(ToolRecord.class).getAll();
        List<ToolRecord> matches = new ArrayList<>();
        for (ToolRecord t : all) {
            if ((t.toolName != null && t.toolName.toLowerCase().contains(lower))
                    || (t.description != null && t.description.toLowerCase().contains(lower))) {
                matches.add(t);
                if (matches.size() >= TOP_K) break;
            }
        }
        return matches;
    }

    // -------------------------------------------------------------------------
    // Tool selection via model
    // -------------------------------------------------------------------------

    /**
     * Build OpenAI-compatible tool definitions JSON from ToolRecord candidates.
     * Passed to CactusWrapper.complete() so FunctionGemma can select one.
     */
    private String buildToolsJson(List<ToolRecord> candidates) {
        try {
            JSONArray tools = new JSONArray();
            for (ToolRecord tool : candidates) {
                JSONObject def = new JSONObject();
                def.put("name", tool.toolName);
                def.put("description", tool.description);

                JSONObject params = new JSONObject();
                params.put("type", "object");

                JSONObject properties = new JSONObject();
                JSONArray required = new JSONArray();

                if (tool.paramsJson != null && !tool.paramsJson.isEmpty()) {
                    JSONArray paramList = new JSONArray(tool.paramsJson);
                    for (int i = 0; i < paramList.length(); i++) {
                        JSONObject p = paramList.getJSONObject(i);
                        String name = p.optString("name");
                        JSONObject prop = new JSONObject();
                        prop.put("type", p.optString("type", "string"));
                        prop.put("description", p.optString("description", ""));
                        properties.put(name, prop);
                        if (p.optBoolean("required", false)) required.put(name);
                    }
                }

                params.put("properties", properties);
                params.put("required", required);
                def.put("parameters", params);
                tools.put(def);
            }
            return tools.toString();
        } catch (JSONException e) {
            Log.e(TAG, "buildToolsJson failed", e);
            return null;
        }
    }

    /**
     * Ask the model to select a tool and extract arguments.
     * Uses FunctionGemma (zero-shot, 270M) — no system prompt injection.
     *
     * Returns null if the model doesn't select a tool.
     */
    private ToolCall selectTool(String query, String toolsJson, List<ToolRecord> candidates) {
        ModelRegistry.ModelEntry ragModel = ModelRegistry.getInstance().getReady("rag");
        if (ragModel == null) {
            Log.w(TAG, "RAG model not ready — cannot select tool");
            return null;
        }

        try {
            JSONArray messages = new JSONArray();
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", query);
            messages.put(userMsg);

            String response = CactusWrapper.complete(
                    ragModel.modelHandle, messages.toString(), null, toolsJson);

            if (response == null) return null;

            return parseToolCall(response, candidates);

        } catch (JSONException e) {
            Log.e(TAG, "selectTool: failed to build messages", e);
            return null;
        }
    }

    /**
     * Parse the model's tool_call response.
     * Expects JSON like: {"tool_call":{"name":"check_visa_status","arguments":{"passport_number":"AB123"}}}
     *
     * Falls back to name-matching if format differs.
     */
    private ToolCall parseToolCall(String response, List<ToolRecord> candidates) {
        try {
            JSONObject json = new JSONObject(response);
            JSONObject toolCallJson = json.optJSONObject("tool_call");
            if (toolCallJson == null) return null;

            String name = toolCallJson.optString("name");
            JSONObject arguments = toolCallJson.optJSONObject("arguments");

            // Match name → ToolRecord → AppRecord
            for (ToolRecord tool : candidates) {
                if (tool.toolName.equals(name)) {
                    AppRecord app = tool.app.getTarget();
                    if (app == null) {
                        Log.w(TAG, "ToolRecord has no AppRecord: " + name);
                        return null;
                    }
                    return new ToolCall(tool, app, arguments != null ? arguments : new JSONObject());
                }
            }

            Log.w(TAG, "Model selected unknown tool: " + name);
            return null;

        } catch (JSONException e) {
            Log.w(TAG, "parseToolCall: response not valid JSON — " + response);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    /**
     * Fire a broadcast Intent to the app's receiver and wait for the result.
     *
     * The app's BroadcastReceiver must call resultReceiver.send(RESULT_OK, bundle)
     * with EXTRA_TOOL_RESULT containing the result string.
     */
    private String dispatch(ToolCall call) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> resultRef = new AtomicReference<>(null);

        ResultReceiver receiver = new ResultReceiver(mCallbackHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (resultCode == RESULT_OK && resultData != null) {
                    resultRef.set(resultData.getString(EXTRA_TOOL_RESULT, ""));
                } else {
                    resultRef.set("Error: tool returned error code " + resultCode);
                }
                latch.countDown();
            }
        };

        Intent intent = new Intent(ToolScannerService.ACTION_JARVIS_TOOL);
        intent.setComponent(new ComponentName(
                call.app.packageName, call.tool.receiverClass));
        intent.putExtra("com.jarvisos.tool.name", call.tool.toolName);
        intent.putExtra("com.jarvisos.tool.result_receiver", receiver);

        // Pack tool arguments as individual Bundle extras
        try {
            JSONArray keys = call.arguments.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String key = keys.getString(i);
                    intent.putExtra("com.jarvisos.tool.arg." + key,
                            call.arguments.optString(key));
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "dispatch: failed to pack arguments", e);
        }

        Log.i(TAG, "Dispatching tool: " + call.app.packageName + "/" + call.tool.toolName);
        mContext.sendBroadcast(intent);

        try {
            boolean received = latch.await(DISPATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!received) {
                Log.w(TAG, "Tool dispatch timed out: " + call.tool.toolName);
                return "Error: tool timed out after " + DISPATCH_TIMEOUT_MS + "ms";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: tool dispatch interrupted";
        }

        String result = resultRef.get();
        Log.i(TAG, "Tool result received: " + call.tool.toolName);
        return result;
    }

    // -------------------------------------------------------------------------
    // Public search API (used by IToolRegistry)
    // -------------------------------------------------------------------------

    /**
     * Search for tools matching the query without dispatching.
     * Returns a JSON array string (never null; returns "[]" on empty/failure).
     */
    public String searchTools(String query) {
        if (query == null || query.trim().isEmpty()) return "[]";
        List<ToolRecord> results = semanticSearch(query);
        return serializeTools(results);
    }

    /**
     * Serialize a single ToolRecord to a JSONObject.
     * Returns null if tool is null or serialization fails.
     */
    public static JSONObject serializeToolObject(ToolRecord tool) {
        if (tool == null) return null;
        try {
            JSONObject obj = new JSONObject();
            obj.put("id",            tool.id);
            obj.put("toolName",      tool.toolName != null ? tool.toolName : "");
            obj.put("description",   tool.description != null ? tool.description : "");
            obj.put("paramsJson",    tool.paramsJson != null ? tool.paramsJson : "");
            obj.put("rawDefinition", tool.rawDefinition != null ? tool.rawDefinition : "");
            obj.put("receiverClass", tool.receiverClass != null ? tool.receiverClass : "");
            obj.put("cactusIndexId", tool.cactusIndexId);

            AppRecord app = tool.app.getTarget();
            if (app != null) {
                JSONObject appObj = new JSONObject();
                appObj.put("id",          app.id);
                appObj.put("packageName", app.packageName != null ? app.packageName : "");
                appObj.put("appLabel",    app.appLabel != null ? app.appLabel : "");
                appObj.put("sourceType",  app.sourceType != null ? app.sourceType : "");
                obj.put("app", appObj);
            }
            return obj;
        } catch (JSONException e) {
            Log.e(TAG, "serializeToolObject failed for: " + tool.toolName, e);
            return null;
        }
    }

    /** Serialize a single ToolRecord to a JSON string, or null on failure. */
    public static String serializeTool(ToolRecord tool) {
        JSONObject obj = serializeToolObject(tool);
        return obj != null ? obj.toString() : null;
    }

    /** Serialize a list of ToolRecords to a JSON array string. */
    public static String serializeTools(List<ToolRecord> tools) {
        JSONArray arr = new JSONArray();
        for (ToolRecord t : tools) {
            JSONObject obj = serializeToolObject(t);
            if (obj != null) arr.put(obj);
        }
        return arr.toString();
    }

    // -------------------------------------------------------------------------
    // Internal data class
    // -------------------------------------------------------------------------

    private static class ToolCall {
        final ToolRecord tool;
        final AppRecord app;
        final JSONObject arguments;

        ToolCall(ToolRecord tool, AppRecord app, JSONObject arguments) {
            this.tool      = tool;
            this.app       = app;
            this.arguments = arguments;
        }
    }
}
