package com.android.server.jarvis.inference;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A model's reply, in the one shape the rest of JarvisOS reads: the text, and
 * at most one tool call. Both engines answer with a JSON envelope
 * ({"success", "error", "response", "function_calls":[{"name","arguments"}]});
 * this is the only place that envelope is taken apart.
 */
public final class Completion {

    /** The model's words; empty when it only asked for a tool. Never null. */
    public final String text;
    /** Name of the tool the model asked for, or null. */
    public final String toolName;
    /** That tool's arguments; an empty object when it gave none. Never null. */
    public final JSONObject toolArguments;
    /** Why the engine failed, or null if it did not. */
    public final String error;

    private Completion(String text, String toolName, JSONObject toolArguments, String error) {
        this.text = text != null ? text : "";
        this.toolName = toolName;
        this.toolArguments = toolArguments != null ? toolArguments : new JSONObject();
        this.error = error;
    }

    public boolean hasToolCall() {
        return toolName != null;
    }

    /**
     * Reads an engine's reply. Anything that is not the envelope is taken to
     * be the model's text as is.
     *
     * @return the completion, or null if {@code raw} is null (the engine gave nothing)
     */
    public static Completion parse(String raw) {
        if (raw == null) return null;
        JSONObject envelope;
        try {
            envelope = new JSONObject(raw);
        } catch (JSONException e) {
            return new Completion(raw, null, null, null);
        }
        if (!envelope.has("response") && !envelope.has("function_calls")
                && !envelope.has("error")) {
            return new Completion(raw, null, null, null);
        }

        String error = envelope.isNull("error") ? null : envelope.optString("error", null);
        if (error != null && error.isEmpty()) error = null;
        if (!envelope.optBoolean("success", error == null) && error == null) {
            error = "engine reported failure";
        }

        String toolName = null;
        JSONObject toolArguments = null;
        JSONArray calls = envelope.optJSONArray("function_calls");
        if (calls != null && calls.length() > 0) {
            JSONObject call = calls.optJSONObject(0);
            if (call != null && !call.optString("name").isEmpty()) {
                toolName = call.optString("name");
                toolArguments = call.optJSONObject("arguments");
            }
        }
        return new Completion(envelope.optString("response", ""), toolName, toolArguments, error);
    }
}
