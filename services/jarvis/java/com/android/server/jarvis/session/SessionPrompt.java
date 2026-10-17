package com.android.server.jarvis.session;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Builds the prompt for one session turn (AGENT_EXPERIENCE.md 11.4). The app
 * sends data only; Jarvis puts the prompt together from:
 *
 * <ul>
 *   <li>rules and persona — the session manifest, the same for every user;
 *   <li>facts about the user — Jarvis's own memory, on the device;
 *   <li>the event — from the app, now;
 *   <li>the open tools — the registry, minus the tools the app closed;
 *   <li>recent events — the last few only.
 * </ul>
 *
 * The chat model's context is 4096 tokens (LlamaEngine), and the tool
 * definitions, tool results and the reply need room too, so the text built
 * here is kept under {@link #MAX_PROMPT_CHARS} (about 1,500 tokens) by
 * dropping the oldest recent events first. No Android in here: it is tested
 * on the host.
 */
public final class SessionPrompt {

    static final int MAX_PROMPT_CHARS = 6000;
    static final int MAX_FACTS_CHARS = 600;
    /** Recent events are shortened to this; the current event is sent whole. */
    static final int MAX_RECENT_EVENT_CHARS = 300;
    static final int MAX_RECENT_EVENTS = 6;

    /** One event as it is kept for later prompts. */
    public static final class Event {
        public final String name;
        public final String data;

        public Event(String name, String data) {
            this.name = name;
            this.data = data != null ? data : "{}";
        }

        @Override
        public String toString() {
            return name + " " + data;
        }
    }

    private SessionPrompt() {}

    /** Said when Jarvis has nothing stored about the person: never invent facts. */
    static final String NO_FACTS = "Nothing is stored about the user yet.";

    /**
     * The system message: who Jarvis is here, the app's rules and persona,
     * what Jarvis knows about the user, and which tools work now.
     *
     * @param userFacts Jarvis's facts about the user, or null if it has none
     */
    public static String system(SessionManifest manifest, String userFacts,
            Collection<String> openTools) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Jarvis, the assistant on this phone. You are taking part in the app \"")
                .append(manifest.title).append("\" with the user.\n");
        sb.append("Rules: ").append(manifest.rules).append('\n');
        if (!manifest.persona.isEmpty()) {
            sb.append("Your manner in this app: ").append(manifest.persona).append('\n');
        }
        sb.append("What you know about the user: ").append(facts(userFacts)).append('\n');
        List<String> tools = new ArrayList<>();
        tools.add(SessionTools.SAY);
        tools.addAll(openTools);
        sb.append("Tools that work now: ").append(String.join(", ", tools))
                .append(". No other tools work during this session.\n");
        if (manifest.turnTool != null) {
            sb.append("When it is your turn, end it by calling ").append(manifest.turnTool);
            if (manifest.turnArg != null) {
                sb.append(" with ").append(manifest.turnArg).append(" set to one of the ")
                        .append(manifest.turnChoices).append(" values in the event");
            }
            sb.append(". You may call ").append(SessionTools.SAY)
                    .append(" once first, with one short line for the user");
            if (openTools.size() > 1) {
                sb.append(", and the other tools if they help you decide");
            }
            sb.append(".\n");
        }
        return sb.toString().trim();
    }

    private static String facts(String userFacts) {
        if (userFacts == null || userFacts.trim().isEmpty()) return NO_FACTS;
        String facts = userFacts.trim();
        return facts.length() <= MAX_FACTS_CHARS ? facts : facts.substring(0, MAX_FACTS_CHARS);
    }

    /** The user message: the last few events, then the one that woke Jarvis. */
    public static String user(SessionManifest manifest, Event now, List<Event> recent,
            int budgetChars) {
        StringBuilder sb = new StringBuilder();
        List<String> earlier = new ArrayList<>();
        int used = now.toString().length() + 80;
        // Newest first while there is room, then put back in time order.
        for (int i = recent.size() - 1; i >= 0 && earlier.size() < MAX_RECENT_EVENTS; i--) {
            String line = "- " + shorten(recent.get(i).toString(), MAX_RECENT_EVENT_CHARS);
            if (used + line.length() + 1 > budgetChars) break;
            used += line.length() + 1;
            earlier.add(0, line);
        }
        if (!earlier.isEmpty()) {
            sb.append("Earlier in this session:\n");
            for (String line : earlier) sb.append(line).append('\n');
        }
        sb.append("Now: ").append(now).append('\n');
        if (now.name.equals(manifest.turnEvent)) sb.append("It is your turn.");
        return sb.toString().trim();
    }

    /**
     * The first messages of a turn: the system message and the events. Recent
     * events get whatever room the system message leaves in the budget.
     */
    public static JSONArray messages(SessionManifest manifest, String userFacts,
            Collection<String> openTools, Event now, List<Event> recent) {
        String system = system(manifest, userFacts, openTools);
        String user = user(manifest, now, recent, MAX_PROMPT_CHARS - system.length());
        try {
            return new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", system))
                    .put(new JSONObject().put("role", "user").put("content", user));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The legal values the turn event carries for the turn tool's argument,
     * e.g. {@code [1,2,3,5]} from {@code {"legal":[1,2,3,5]}}; null if the
     * manifest names none or the event has none.
     */
    public static JSONArray choices(SessionManifest manifest, JSONObject eventData) {
        if (manifest.turnChoices == null || eventData == null) return null;
        JSONArray values = eventData.optJSONArray(manifest.turnChoices);
        return values != null && values.length() > 0 ? values : null;
    }

    /**
     * Narrows the turn tool's argument in a tools array to the legal values,
     * as a JSON-schema enum. With a grammar the engine then cannot produce any
     * other value; without one it is still a strong hint.
     *
     * @param toolsJson   JSON array of {name, description, parameters}
     * @param onlyTurnTool true to keep only the turn tool (the retry after a wrong call)
     */
    public static String withChoices(String toolsJson, SessionManifest manifest,
            JSONArray choices, boolean onlyTurnTool) {
        try {
            JSONArray in = new JSONArray(toolsJson);
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                JSONObject tool = in.getJSONObject(i);
                boolean isTurnTool = tool.optString("name").equals(manifest.turnTool);
                if (onlyTurnTool && !isTurnTool) continue;
                if (isTurnTool && choices != null && manifest.turnArg != null) {
                    JSONObject parameters = tool.optJSONObject("parameters");
                    JSONObject properties = parameters != null
                            ? parameters.optJSONObject("properties") : null;
                    JSONObject arg = properties != null
                            ? properties.optJSONObject(manifest.turnArg) : null;
                    if (arg != null) arg.put("enum", choices);
                }
                out.put(tool);
            }
            return out.toString();
        } catch (JSONException e) {
            return toolsJson;
        }
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
