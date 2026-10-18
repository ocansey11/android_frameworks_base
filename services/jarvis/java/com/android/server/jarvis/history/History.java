package com.android.server.jarvis.history;

import android.os.SystemProperties;
import android.util.Log;

import com.android.server.jarvis.core.JarvisStore;
import com.android.server.jarvis.model.AgentSession;
import com.android.server.jarvis.model.AgentSession_;
import com.android.server.jarvis.model.Conversation;
import com.android.server.jarvis.model.Conversation_;
import com.android.server.jarvis.model.Message;
import com.android.server.jarvis.model.Message_;

import io.objectbox.Box;
import io.objectbox.query.QueryBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.UUID;

/**
 * What Jarvis keeps of what happened: each chat exchange and each app session
 * (a game of tic-tac-toe), as a Conversation with its Messages, for the
 * History tab of the Jarvis app.
 *
 * Kept for a short time only. Raw conversations are the sensitive part; what
 * Jarvis learns from them is kept in UserContext by DreamWorker. So each
 * record also writes an AgentSession (DreamWorker's input), and everything
 * raw older than {@link #PROP_HOURS} (default 24 h) is deleted, whether or
 * not DreamWorker has run. The facts stay.
 *
 * Message.source in a conversation:
 * <ul>
 *   <li>"text", "voice": what the person typed or said, or Jarvis's reply;
 *   <li>"tool": a tool Jarvis ran, "name(args) → result";
 *   <li>"event": an app event in a session (data from the app, never a prompt);
 *   <li>"say": a line Jarvis said in a session;
 *   <li>"end": how a session ended.
 * </ul>
 * Conversation.mode: "text", "voice" or "session".
 *
 * Never throws: keeping history must not break a query or a session.
 */
public final class History {

    private static final String TAG = "JarvisHistory";

    /** Hours raw conversations are kept. Kevin, 17 Oct 2026: "definitely dont last after a day". */
    public static final String PROP_HOURS = "persist.sys.jarvis.history_hours";
    private static final int DEFAULT_HOURS = 24;
    /** A chat request within this time of the last one continues the same conversation. */
    private static final long CONTINUE_MS = 30 * 60_000L;
    private static final int TITLE_CHARS = 48;
    private static final int PREVIEW_CHARS = 80;
    /** Tool results and event data are cut to this; a conversation is a record, not a dump. */
    private static final int CONTENT_CHARS = 600;

    private History() {}

    // -------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------

    /**
     * One chat request and its reply. Continues the last conversation if it
     * is recent, else starts a new one.
     *
     * @param tools the trace's tool calls: [{name, args, result}], or null
     */
    public static synchronized void recordChat(String request, boolean spoken,
            JSONArray tools, String reply) {
        if (!JarvisStore.isReady() || request == null || request.trim().isEmpty()) return;
        try {
            final long now = System.currentTimeMillis();
            final String mode = spoken ? "voice" : "text";
            Box<Conversation> conversations = JarvisStore.box(Conversation.class);
            Conversation conversation = conversations.query()
                    .notEqual(Conversation_.mode, "session", QueryBuilder.StringOrder.CASE_SENSITIVE)
                    .orderDesc(Conversation_.lastMessageAt)
                    .build().findFirst();
            if (conversation == null || now - conversation.lastMessageAt > CONTINUE_MS) {
                conversation = new Conversation(cut(request, TITLE_CHARS), mode);
                conversations.put(conversation);
            }

            StringBuilder summary = new StringBuilder();
            add(conversation, request, true, mode);
            for (int i = 0; tools != null && i < tools.length(); i++) {
                JSONObject call = tools.optJSONObject(i);
                if (call == null) continue;
                String line = callLine(call.optString("name"), call.opt("args"),
                        call.optString("result", null));
                add(conversation, line, false, "tool");
                summary.append("Tool called: ").append(line).append('\n');
            }
            if (reply != null) add(conversation, reply, false, mode);

            conversation.lastMessageAt = now;
            conversation.lastMessagePreview = cut(reply != null ? reply : request, PREVIEW_CHARS);
            conversations.put(conversation);

            dreamInput(request, summary.toString(), reply);
            prune();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not keep the chat", e);
        }
    }

    /**
     * One app session that has ended, from SessionManager's log: events from
     * the app and Jarvis's turns, oldest first.
     *
     * @param log [{kind:"event", name, data}, {kind:"turn", trace}], oldest first
     */
    public static synchronized void recordSession(String appLabel, String title,
            JSONArray log, int turns, long seconds, String endReason) {
        if (!JarvisStore.isReady()) return;
        try {
            final long now = System.currentTimeMillis();
            Box<Conversation> conversations = JarvisStore.box(Conversation.class);
            Conversation conversation = new Conversation(title + " with Jarvis", "session");
            conversation.createdAt = now - seconds * 1000;
            conversations.put(conversation);

            StringBuilder summary = new StringBuilder();
            JSONObject lastEvent = null;
            for (int i = 0; log != null && i < log.length(); i++) {
                JSONObject entry = log.optJSONObject(i);
                if (entry == null) continue;
                if ("event".equals(entry.optString("kind"))) {
                    String line = entry.optString("name") + " " + entry.opt("data");
                    add(conversation, cut(line, CONTENT_CHARS), true, "event");
                    summary.append("Event: ").append(cut(line, 200)).append('\n');
                    lastEvent = entry;
                } else {
                    addTurn(conversation, entry.optJSONObject("trace"), summary);
                }
            }
            String end = "Ended: " + endReason + " · " + turns + " turn(s), "
                    + seconds / 60 + " m " + seconds % 60 + " s";
            add(conversation, end, false, "end");

            conversation.lastMessageAt = now;
            conversation.lastMessagePreview = cut(preview(lastEvent, endReason), PREVIEW_CHARS);
            conversations.put(conversation);

            dreamInput("Played " + title + " (" + appLabel + ") with Jarvis",
                    summary.toString(), end);
            prune();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not keep the session", e);
        }
    }

    /**
     * The list line for a session: its last event with only its result, if
     * the data has one ({@code game_over {"winner":"draw"}}), else how it ended.
     */
    private static String preview(JSONObject lastEvent, String endReason) {
        JSONObject data = lastEvent != null ? lastEvent.optJSONObject("data") : null;
        if (data == null || !data.has("winner")) return endReason;
        try {
            return lastEvent.optString("name") + " "
                    + new JSONObject().put("winner", data.opt("winner"));
        } catch (JSONException e) {
            return endReason;
        }
    }

    /** One Jarvis turn: each tool call, and each line said. */
    private static void addTurn(Conversation conversation, JSONObject trace, StringBuilder summary) {
        JSONArray steps = trace != null ? trace.optJSONArray("steps") : null;
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            JSONArray calls = step.optJSONArray("calls");
            for (int j = 0; calls != null && j < calls.length(); j++) {
                JSONObject call = calls.optJSONObject(j);
                if (call == null) continue;
                if ("say".equals(call.optString("name"))) {
                    // A line said is a line said, whether as a call or after the move.
                    String text = sayText(call.opt("args"));
                    if (text != null) {
                        add(conversation, text, false, "say");
                        summary.append("Jarvis said: ").append(text).append('\n');
                        continue;
                    }
                }
                String line = callLine(call.optString("name"), call.opt("args"),
                        call.optString("result", null));
                add(conversation, line, false, "tool");
                summary.append("Jarvis called: ").append(cut(line, 200)).append('\n');
            }
            String said = step.optString("said_text", null);
            if (said != null) {
                said = said.replaceFirst("^Said to the user: ", "");
                add(conversation, said, false, "say");
                summary.append("Jarvis said: ").append(said).append('\n');
            }
        }
    }

    private static void add(Conversation conversation, String content, boolean isUser,
            String source) {
        Message message = new Message(cut(content, CONTENT_CHARS), isUser, source);
        message.conversation.setTarget(conversation);
        JarvisStore.box(Message.class).put(message);
    }

    /** DreamWorker's input: a finished AgentSession it has not consolidated yet. */
    private static void dreamInput(String request, String context, String answer) {
        AgentSession session = new AgentSession();
        session.sessionId = UUID.randomUUID().toString();
        session.originalQuery = request;
        session.accumulatedContext = context;
        session.finalAnswer = answer;
        session.status = "DONE";
        session.consolidated = false;
        session.createdAt = System.currentTimeMillis();
        session.lastUpdatedAt = session.createdAt;
        JarvisStore.box(AgentSession.class).put(session);
    }

    // -------------------------------------------------------------------------
    // Forgetting
    // -------------------------------------------------------------------------

    /** Hours raw history is kept. */
    public static int keepHours() {
        return Math.max(1, SystemProperties.getInt(PROP_HOURS, DEFAULT_HOURS));
    }

    /**
     * Deletes conversations, their messages and DreamWorker inputs older than
     * {@link #keepHours}. Called after each record, on each listing and at
     * boot, so nothing older is ever shown or kept for long.
     *
     * @return the number of conversations deleted
     */
    public static synchronized int prune() {
        if (!JarvisStore.isReady()) return 0;
        try {
            final long cutoff = System.currentTimeMillis() - keepHours() * 3_600_000L;
            Box<Conversation> conversations = JarvisStore.box(Conversation.class);
            List<Conversation> old = conversations.query()
                    .less(Conversation_.lastMessageAt, cutoff).build().find();
            for (Conversation conversation : old) deleteMessages(conversation.id);
            conversations.remove(old);
            JarvisStore.box(AgentSession.class).query()
                    .less(AgentSession_.lastUpdatedAt, cutoff).build().remove();
            if (!old.isEmpty()) Log.i(TAG, "Forgot " + old.size() + " conversation(s)");
            return old.size();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not prune history", e);
            return 0;
        }
    }

    /** Deletes all conversations and DreamWorker inputs. Facts learned stay. */
    public static synchronized void clear() {
        if (!JarvisStore.isReady()) return;
        JarvisStore.box(Message.class).removeAll();
        JarvisStore.box(Conversation.class).removeAll();
        JarvisStore.box(AgentSession.class).removeAll();
    }

    private static void deleteMessages(long conversationId) {
        JarvisStore.box(Message.class).query()
                .equal(Message_.conversationId, conversationId).build().remove();
    }

    // -------------------------------------------------------------------------
    // Reading (the Jarvis app, cmd jarvis history)
    // -------------------------------------------------------------------------

    /**
     * {keepHours, conversations: [{id, title, mode, createdAt, lastMessageAt,
     * preview, messages}]}, newest first.
     */
    public static synchronized String listJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("keepHours", keepHours());
            JSONArray list = new JSONArray();
            if (JarvisStore.isReady()) {
                prune();
                for (Conversation c : JarvisStore.box(Conversation.class).query()
                        .orderDesc(Conversation_.lastMessageAt).build().find()) {
                    list.put(new JSONObject()
                            .put("id", c.id)
                            .put("title", c.title)
                            .put("mode", c.mode)
                            .put("createdAt", c.createdAt)
                            .put("lastMessageAt", c.lastMessageAt)
                            .put("preview", c.lastMessagePreview)
                            .put("messages", JarvisStore.box(Message.class).query()
                                    .equal(Message_.conversationId, c.id).build().count()));
                }
            }
            json.put("conversations", list);
        } catch (Exception e) {   // JSONException, or the store
            Log.w(TAG, "Could not list history", e);
        }
        return json.toString();
    }

    /** {id, title, mode, createdAt, messages: [{content, isUser, source, timestamp}]}, or "{}". */
    public static synchronized String conversationJson(long id) {
        try {
            if (!JarvisStore.isReady()) return "{}";
            Conversation c = JarvisStore.box(Conversation.class).get(id);
            if (c == null) return "{}";
            JSONArray messages = new JSONArray();
            for (Message m : JarvisStore.box(Message.class).query()
                    .equal(Message_.conversationId, id)
                    .order(Message_.timestamp).order(Message_.id).build().find()) {
                messages.put(new JSONObject()
                        .put("content", m.content)
                        .put("isUser", m.isUser)
                        .put("source", m.source)
                        .put("timestamp", m.timestamp));
            }
            return new JSONObject()
                    .put("id", c.id)
                    .put("title", c.title)
                    .put("mode", c.mode)
                    .put("createdAt", c.createdAt)
                    .put("messages", messages)
                    .toString();
        } catch (Exception e) {   // JSONException, or the store
            Log.w(TAG, "Could not read conversation " + id, e);
            return "{}";
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** The text of a say call's arguments ({"text": …}, as an object or as JSON text), or null. */
    private static String sayText(Object args) {
        try {
            JSONObject object = args instanceof JSONObject ? (JSONObject) args
                    : new JSONObject(String.valueOf(args));
            String text = object.optString("text", null);
            return text != null && !text.trim().isEmpty() ? text.trim() : null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** "move(cell=4) → You played cell 4. …" */
    static String callLine(String name, Object args, String result) {
        StringBuilder line = new StringBuilder(name);
        if (args instanceof String) {
            // Chat traces keep the arguments as the JSON text the model wrote.
            try {
                args = new JSONObject((String) args);
            } catch (JSONException notAnObject) {
                // Shown as written.
            }
        }
        if (args instanceof JSONObject && ((JSONObject) args).length() > 0) {
            StringBuilder list = new StringBuilder();
            for (java.util.Iterator<String> keys = ((JSONObject) args).keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (list.length() > 0) list.append(", ");
                list.append(key).append('=').append(((JSONObject) args).opt(key));
            }
            line.append('(').append(list).append(')');
        } else if (args instanceof String && !((String) args).isEmpty()
                && !"{}".equals(args)) {
            line.append('(').append(args).append(')');
        }
        if (result != null) line.append(" → ").append(cut(result, 300));
        return line.toString();
    }

    static String cut(String text, int max) {
        if (text == null) return "";
        text = text.trim();
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
