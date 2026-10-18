import android.os.SystemProperties;

import com.android.server.jarvis.core.JarvisStore;
import com.android.server.jarvis.history.History;
import com.android.server.jarvis.model.AgentSession;
import com.android.server.jarvis.model.Conversation;
import com.android.server.jarvis.model.Message;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;

/**
 * Host-side check of the service's History (history/History.java) against a
 * real ObjectBox store: chats and app sessions are kept in order, DreamWorker
 * gets its input, and anything older than persist.sys.jarvis.history_hours is
 * forgotten. Run by objectbox/history-test.sh; not part of the ROM.
 */
public class HistoryStoreTest {
    static int sChecks;

    static void check(boolean ok, String what) {
        sChecks++;
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        // Not ready yet: must not throw.
        History.recordChat("hello", false, null, "hi");
        check(History.prune() == 0, "prune without a store");
        check(new JSONObject(History.listJson()).getJSONArray("conversations").length() == 0,
                "list without a store");

        File dir = Files.createTempDirectory("jarvis-history").toFile();
        JarvisStore.init(dir.getPath());
        try {
            chats();
            sessions();
            forgetting();
            clearing();
        } finally {
            JarvisStore.close();
            io.objectbox.BoxStore.deleteAllFiles(dir);
        }
        System.out.println("OK: " + sChecks + " checks");
    }

    static void chats() throws Exception {
        JSONArray tools = new JSONArray().put(new JSONObject()
                .put("name", "set_flashlight")
                .put("args", "{\"enabled\":true}")
                .put("result", "Flashlight on")
                .put("ms", 12));
        History.recordChat("turn on the torch", false, tools, "Done, the torch is on.");
        JSONArray list = new JSONObject(History.listJson()).getJSONArray("conversations");
        check(list.length() == 1, "one chat conversation");
        JSONObject c = list.getJSONObject(0);
        check("text".equals(c.getString("mode")), "typed chat is text");
        check("turn on the torch".equals(c.getString("title")), "title is the request");
        check(c.getInt("messages") == 3, "request, tool, reply: " + c);
        check("Done, the torch is on.".equals(c.getString("preview")), "preview is the reply");

        JSONArray messages = new JSONObject(History.conversationJson(c.getLong("id")))
                .getJSONArray("messages");
        check(messages.getJSONObject(0).getBoolean("isUser"), "request is the user's");
        check("set_flashlight(enabled=true) → Flashlight on"
                .equals(messages.getJSONObject(1).getString("content")),
                "tool line: " + messages.getJSONObject(1));
        check("tool".equals(messages.getJSONObject(1).getString("source")), "tool source");
        check("Done, the torch is on.".equals(messages.getJSONObject(2).getString("content")),
                "reply last");

        // A second request soon after continues the same conversation.
        History.recordChat("and off", true, null, "Off.");
        list = new JSONObject(History.listJson()).getJSONArray("conversations");
        check(list.length() == 1, "continues within 30 minutes");
        check(list.getJSONObject(0).getInt("messages") == 5, "two more messages");

        // DreamWorker's input: one finished, unconsolidated session per request.
        int done = 0;
        for (AgentSession s : JarvisStore.box(AgentSession.class).getAll()) {
            if ("DONE".equals(s.status) && !s.consolidated) done++;
        }
        check(done == 2, "DreamWorker input per request: " + done);

        // A request after a long pause starts a new conversation.
        Conversation last = JarvisStore.box(Conversation.class).getAll().get(0);
        last.lastMessageAt -= 31 * 60_000L;
        JarvisStore.box(Conversation.class).put(last);
        History.recordChat("what time is it", false, null, "10 pm");
        check(new JSONObject(History.listJson()).getJSONArray("conversations").length() == 2,
                "new conversation after 30 minutes");
        check("what time is it".equals(new JSONObject(History.listJson())
                .getJSONArray("conversations").getJSONObject(0).getString("title")),
                "newest first");
    }

    static void sessions() throws Exception {
        History.clear();
        JSONObject trace = new JSONObject().put("steps", new JSONArray()
                .put(new JSONObject().put("calls", new JSONArray()
                        .put(new JSONObject().put("name", "evaluate_moves")
                                .put("args", new JSONObject())
                                .put("result", "{\"best\":[4]}"))))
                .put(new JSONObject().put("calls", new JSONArray()
                        .put(new JSONObject().put("name", "say")
                                .put("args", new JSONObject().put("text", "Corners again?"))
                                .put("result", "Said to the user: Corners again?"))
                        .put(new JSONObject().put("name", "move")
                                .put("args", new JSONObject().put("cell", 4))
                                .put("result", "You played cell 4."))))
                .put(new JSONObject().put("after_move", true)
                        .put("said_text", "Said to the user: Centre is mine!")))
                .put("ms", 17000);
        JSONArray log = new JSONArray()
                .put(new JSONObject().put("kind", "event").put("name", "player_moved")
                        .put("data", new JSONObject().put("cell", 0).put("board", "X........")))
                .put(new JSONObject().put("kind", "event").put("name", "your_turn")
                        .put("data", new JSONObject().put("legal", new JSONArray("[1,2,3]"))))
                .put(new JSONObject().put("kind", "turn").put("trace", trace))
                .put(new JSONObject().put("kind", "event").put("name", "game_over")
                        .put("data", new JSONObject().put("winner", "player")));
        History.recordSession("Tic-tac-toe", "Tic-tac-toe", log, 1, 75,
                "ended by the app (game_over)");

        JSONObject c = new JSONObject(History.listJson()).getJSONArray("conversations")
                .getJSONObject(0);
        check("session".equals(c.getString("mode")), "session mode");
        check("Tic-tac-toe with Jarvis".equals(c.getString("title")), "session title");
        check("game_over {\"winner\":\"player\"}".equals(c.getString("preview")),
                "preview is the result only: " + c);
        JSONArray m = new JSONObject(History.conversationJson(c.getLong("id")))
                .getJSONArray("messages");
        String[] sources = {"event", "event", "tool", "say", "tool", "say", "event", "end"};
        check(m.length() == sources.length, "eight messages: " + m);
        for (int i = 0; i < sources.length; i++) {
            check(sources[i].equals(m.getJSONObject(i).getString("source")),
                    "order " + i + ": " + m.getJSONObject(i));
        }
        check("Corners again?".equals(m.getJSONObject(3).getString("content")),
                "a say call is kept as a line said, once");
        check("move(cell=4) → You played cell 4.".equals(m.getJSONObject(4).getString("content")),
                "move line");
        check("Centre is mine!".equals(m.getJSONObject(5).getString("content")), "said line");
        check(m.getJSONObject(0).getString("content").startsWith("player_moved {"),
                "event keeps its data");
        check(m.getJSONObject(7).getString("content").contains("1 turn(s), 1 m 15 s"),
                "end line: " + m.getJSONObject(7));

        AgentSession dream = JarvisStore.box(AgentSession.class).getAll().get(0);
        check(dream.originalQuery.startsWith("Played Tic-tac-toe"), "dream input names the game");
        check(dream.accumulatedContext.contains("Jarvis called: move(cell=4)"),
                "dream input has the moves");
        check(dream.accumulatedContext.contains("Jarvis said: Centre is mine!"),
                "dream input has the lines said");
    }

    static void forgetting() throws Exception {
        History.clear();
        History.recordChat("old", false, null, "old reply");
        Conversation old = JarvisStore.box(Conversation.class).getAll().get(0);
        old.lastMessageAt = System.currentTimeMillis() - 25 * 3_600_000L;
        JarvisStore.box(Conversation.class).put(old);
        AgentSession oldDream = JarvisStore.box(AgentSession.class).getAll().get(0);
        oldDream.lastUpdatedAt = old.lastMessageAt;
        JarvisStore.box(AgentSession.class).put(oldDream);

        History.recordChat("new", false, null, "new reply");   // prunes after recording
        check(JarvisStore.box(Conversation.class).count() == 1, "25 h old conversation forgotten");
        check("new".equals(JarvisStore.box(Conversation.class).getAll().get(0).title),
                "the new one stays");
        check(JarvisStore.box(Message.class).count() == 2, "its messages forgotten too");
        check(JarvisStore.box(AgentSession.class).count() == 1, "old DreamWorker input forgotten");

        // A shorter setting forgets sooner.
        SystemProperties.set(History.PROP_HOURS, "1");
        check(History.keepHours() == 1, "setting read");
        Conversation c = JarvisStore.box(Conversation.class).getAll().get(0);
        c.lastMessageAt = System.currentTimeMillis() - 2 * 3_600_000L;
        JarvisStore.box(Conversation.class).put(c);
        check(History.prune() == 1, "2 h old forgotten at 1 h");
        check(new JSONObject(History.listJson()).getInt("keepHours") == 1, "list reports hours");
        SystemProperties.set(History.PROP_HOURS, "24");
    }

    static void clearing() throws Exception {
        History.recordChat("a", false, null, "b");
        History.clear();
        check(JarvisStore.box(Conversation.class).count() == 0, "clear: conversations");
        check(JarvisStore.box(Message.class).count() == 0, "clear: messages");
        check("{}".equals(History.conversationJson(12345)), "unknown id gives {}");
    }
}
