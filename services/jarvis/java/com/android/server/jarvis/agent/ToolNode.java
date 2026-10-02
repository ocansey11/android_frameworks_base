package com.android.server.jarvis.agent;

import android.util.Log;

import com.android.server.jarvis.model.AgentSession;
import com.android.server.jarvis.model.AgentTurn;
import com.android.server.jarvis.tools.ToolDispatcher;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * ToolNode — parses Gemma 4 tool call tokens and dispatches the named tool.
 *
 * Gemma 4 outputs tool calls between hard token boundaries:
 *   <|tool_call|>
 *   {"name": "check_visa_status", "arguments": {"passport_number": "GH123456"}}
 *   <|end_tool_call|>
 *
 * ToolNode extracts the JSON, maps the name to a registered ToolRecord via
 * ToolDispatcher.dispatchByName(), and appends the result to accumulatedContext.
 *
 * Unlike resolveAndDispatch() (which runs a semantic search to find the tool),
 * dispatchByName() skips the search — Gemma 4 already told us which tool to call.
 *
 * Source: https://ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4
 */
public class ToolNode {

    private static final String TAG = "ToolNode";

    private static final String TOOL_CALL_OPEN  = "<|tool_call|>";
    private static final String TOOL_CALL_CLOSE = "<|end_tool_call|>";

    private final ToolDispatcher mToolDispatcher;

    public ToolNode(ToolDispatcher toolDispatcher) {
        this.mToolDispatcher = toolDispatcher;
    }

    /**
     * Parse and execute the tool call in the model output.
     *
     * @param session     current session state
     * @param modelOutput model output containing <|tool_call|>...<|end_tool_call|>
     * @return the tool's result string, or an error string on failure (never null)
     */
    public String execute(AgentSession session, String modelOutput) {
        session.status = "TOOL_CALL";

        // Extract JSON between the token boundaries
        String toolCallJson = extractToolCallJson(modelOutput);
        if (toolCallJson == null) {
            Log.e(TAG, "Failed to extract tool call JSON from: " + modelOutput);
            return "Error: could not parse tool call from model output";
        }

        // Parse name + arguments
        String toolName;
        String toolArgs;
        try {
            JSONObject call = new JSONObject(toolCallJson);
            toolName = call.getString("name");
            JSONObject arguments = call.optJSONObject("arguments");
            toolArgs = arguments != null ? arguments.toString() : "{}";
        } catch (JSONException e) {
            Log.e(TAG, "ToolNode: invalid tool call JSON — " + toolCallJson, e);
            return "Error: malformed tool call JSON";
        }

        Log.d(TAG, "Dispatching tool: " + toolName + " args=" + toolArgs);

        // Dispatch via ToolDispatcher (bypasses semantic search — name already known)
        String result = mToolDispatcher.dispatchByName(toolName, toolArgs);
        if (result == null) result = "Error: tool '" + toolName + "' returned null";

        // Append to accumulated context
        String contextEntry = "\nTool result [" + toolName + "]: " + result;
        if (session.accumulatedContext == null) {
            session.accumulatedContext = contextEntry;
        } else {
            session.accumulatedContext += contextEntry;
        }
        session.lastToolResult = result;

        // Record as a turn
        AgentTurn turn = new AgentTurn();
        turn.role = "tool";
        turn.content = result;
        turn.toolName = toolName;
        turn.toolArgs = toolArgs;
        turn.timestamp = System.currentTimeMillis();
        session.turns.add(turn);

        Log.d(TAG, "Tool result: " + result.substring(0, Math.min(80, result.length())));
        return result;
    }

    /** Extract the JSON string between <|tool_call|> and <|end_tool_call|>. */
    private String extractToolCallJson(String output) {
        if (output == null) return null;
        int start = output.indexOf(TOOL_CALL_OPEN);
        if (start < 0) return null;
        start += TOOL_CALL_OPEN.length();

        int end = output.indexOf(TOOL_CALL_CLOSE, start);
        if (end < 0) {
            // Model didn't emit the close token — try to find the JSON boundary
            // by finding the last } after the opening {
            int braceStart = output.indexOf('{', start);
            if (braceStart < 0) return null;
            int depth = 0;
            for (int i = braceStart; i < output.length(); i++) {
                char c = output.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return output.substring(braceStart, i + 1).trim();
                }
            }
            return null;
        }

        return output.substring(start, end).trim();
    }
}
