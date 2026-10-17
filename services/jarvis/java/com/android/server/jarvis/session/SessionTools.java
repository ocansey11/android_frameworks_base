package com.android.server.jarvis.session;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;

/**
 * The tool fence of a session (AGENT_EXPERIENCE.md 11.5, rule 2), as plain
 * rules with no Android in them so they can be tested on the host.
 * {@link SessionManager} applies them inside ToolDispatcher, so they hold for
 * every route a tool call can take: the agent loop, the fast path, a session
 * turn and {@code cmd jarvis tool}.
 */
public final class SessionTools {

    /** The one tool Jarvis itself gives every session: a line for the person. */
    public static final String SAY = "say";
    static final int MAX_SAY_CHARS = 160;

    private SessionTools() {}

    /**
     * Whether a tool call may run now.
     *
     * @param sessionTitle  title of the open session, or null if none is open
     * @param openTools     tools open in that session (ignored if none is open)
     * @param toolName      the tool asked for
     * @param sessionOnly   true if the tool is a session tool of the app that owns it
     * @return null to allow the call, or the refusal, which starts with "Error: refused"
     */
    public static String refusal(String sessionTitle, Set<String> openTools, String toolName,
            boolean sessionOnly) {
        if (sessionTitle != null) {
            if (SAY.equals(toolName) || openTools.contains(toolName)) return null;
            return "Error: refused. During the " + sessionTitle + " session Jarvis can only use "
                    + SAY + (openTools.isEmpty() ? "" : ", " + String.join(", ", openTools))
                    + "; '" + toolName + "' is closed until the session ends.";
        }
        if (sessionOnly) {
            return "Error: refused. '" + toolName + "' is open only during its app's session.";
        }
        return null;
    }

    /** The text of a say call, trimmed and cut to a speech-bubble length; null if empty. */
    public static String sayText(JSONObject args) {
        String text = args != null ? args.optString("text", "") : "";
        // Reasoning tags a model leaks into its words are not for the person.
        text = text.replaceAll("</?think>", "").trim();
        if (text.isEmpty()) return null;
        if (text.codePointCount(0, text.length()) <= MAX_SAY_CHARS) return text;
        // Cut between characters, never inside one: half an emoji is not
        // valid text, and the screen reader (and uiautomator) choke on it.
        return text.substring(0, text.offsetByCodePoints(0, MAX_SAY_CHARS - 1)) + "…";
    }

    /** The definition of {@link #SAY} the model is given, in ToolDispatcher's tool format. */
    public static JSONObject sayDefinition() {
        try {
            JSONObject text = new JSONObject()
                    .put("type", "string")
                    .put("description", "One short line, under 20 words.");
            return new JSONObject()
                    .put("name", SAY)
                    .put("description", "Say one short line to the user. It is shown in the app.")
                    .put("parameters", new JSONObject()
                            .put("type", "object")
                            .put("properties", new JSONObject().put("text", text))
                            .put("required", new JSONArray().put("text")));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
