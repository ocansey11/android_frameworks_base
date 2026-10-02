import com.android.server.jarvis.core.MyObjectBox;
import com.android.server.jarvis.model.*;
import com.android.server.jarvis.tools.*;

import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.query.QueryBuilder.StringOrder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Host-side check that the generated ObjectBox code opens a real store and
 * that every store call the service makes works against it. Run by
 * objectbox/test.sh on the build machine; it is not part of the ROM.
 */
public class StoreSmokeTest {
    static int sChecks;

    static void check(boolean ok, String what) {
        sChecks++;
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("jarvis-obx").toFile();
        BoxStore store = MyObjectBox.builder().directory(dir).build();
        try {
            // Every entity: put, get, getAll.
            Class<?>[] plain = {AccessLog.class, AgentSession.class, AgentTurn.class,
                    AppRecord.class, Conversation.class, DocumentChunk.class, Folder.class,
                    Message.class, SourceFile.class, TaskMemory.class, ToolRecord.class,
                    UserContext.class};
            for (Class<?> c : plain) roundTrip(store, c);
            for (Class<?> c : plain) store.boxFor(c).removeAll();

            tools(store);
            indexing(store);
            sessions(store);
            metadataSearch(store);
        } finally {
            store.close();
        }

        // Reopen: the on-disk schema must match the generated model.
        store = MyObjectBox.builder().directory(dir).build();
        check(store.boxFor(ToolRecord.class).count() == 1, "tool survives reopen");
        check(store.boxFor(AgentSession.class).getAll().get(0).turns.size() == 2,
                "turns survive reopen");
        store.close();
        BoxStore.deleteAllFiles(dir);
        System.out.println("OK: " + sChecks + " checks, ObjectBox " + BoxStore.getVersion()
                + " / native " + BoxStore.getVersionNative());
    }

    static <T> void roundTrip(BoxStore store, Class<T> c) throws Exception {
        Box<T> box = store.boxFor(c);
        long id = box.put(c.getDeclaredConstructor().newInstance());
        check(id != 0 && box.get(id) != null && box.getAll().size() == 1, "round trip " + c);
    }

    // ToolScannerService + ToolDispatcher + JarvisService
    static void tools(BoxStore store) {
        Box<AppRecord> appBox = store.boxFor(AppRecord.class);
        Box<ToolRecord> toolBox = store.boxFor(ToolRecord.class);
        AppRecord app = new AppRecord("com.example.app", "Example", "manifest");
        appBox.put(app);
        ToolRecord tool = new ToolRecord("send_sms", "Send an SMS", "[]", "{}", "com.example.Rx");
        tool.app.setTarget(app);
        tool.cactusIndexId = 7;
        toolBox.put(tool);
        ToolRecord gone = new ToolRecord("old_tool", "", "[]", "{}", "com.example.Rx");
        gone.app.setTarget(app);
        toolBox.put(gone);

        check(appBox.query().equal(AppRecord_.packageName, "COM.EXAMPLE.APP",
                StringOrder.CASE_INSENSITIVE).build().findFirst() != null, "app by package");
        ToolRecord found = toolBox.query()
                .equal(ToolRecord_.toolName, "SEND_SMS", StringOrder.CASE_INSENSITIVE)
                .equal(ToolRecord_.receiverClass, "com.example.rx", StringOrder.CASE_INSENSITIVE)
                .build().findFirst();
        check(found != null && found.id == tool.id, "tool by name+receiver");
        check(toolBox.query().equal(ToolRecord_.toolName, "SEND_SMS",
                StringOrder.CASE_SENSITIVE).build().findFirst() == null, "case-sensitive miss");
        check(toolBox.query().equal(ToolRecord_.cactusIndexId, 7).build().findFirst().id
                == tool.id, "tool by cactusIndexId");
        check("com.example.app".equals(toolBox.get(tool.id).app.getTarget().packageName),
                "tool.app resolves after reload");

        // removeApp(): iterate app.tools, remove each, then the app.
        AppRecord reloaded = appBox.get(app.id);
        check(reloaded.tools.size() == 2, "app.tools backlink sees both tools");
        toolBox.remove(gone.id);
        check(appBox.get(app.id).tools.size() == 1, "app.tools after remove");
    }

    // JarvisIndexWorker + RetrieveNode
    static void indexing(BoxStore store) {
        Box<SourceFile> fileBox = store.boxFor(SourceFile.class);
        Box<DocumentChunk> chunkBox = store.boxFor(DocumentChunk.class);
        SourceFile sf = new SourceFile("/sdcard/Notes/Bio.txt", "Bio.txt", "text/plain", 10, "h");
        sf.userAlias = "my biology notes";
        sf.tags = "university,2024";
        fileBox.put(sf);
        for (int i = 0; i < 3; i++) {
            DocumentChunk c = new DocumentChunk(i, "summary " + i, 5, 1L);
            c.cactusIndexId = 100 + i;
            c.sourceFile.setTarget(sf);
            chunkBox.put(c);
        }
        sf.isIndexed = true;
        fileBox.put(sf);

        SourceFile again = fileBox.query().equal(SourceFile_.filePath, "/sdcard/notes/bio.txt",
                StringOrder.CASE_INSENSITIVE).build().findFirst();
        check(again != null && again.isIndexed && "h".equals(again.fileHash), "file by path");
        check(again.chunks.size() == 3, "file.chunks backlink");
        DocumentChunk first = again.chunks.get(0);
        check(chunkBox.get(first.id).sourceFile.getTarget().id == sf.id, "chunk.sourceFile");
        for (DocumentChunk c : again.chunks) chunkBox.remove(c.id);
        check(chunkBox.count() == 0, "chunks removed");
    }

    // JarvisExecutor / *Node / DreamWorker / UserContextHelper
    static void sessions(BoxStore store) {
        Box<AgentSession> box = store.boxFor(AgentSession.class);
        AgentSession s = new AgentSession();
        s.sessionId = "abc";
        s.status = "DONE";
        AgentTurn t1 = new AgentTurn();
        t1.role = "tool";
        t1.toolName = "send_sms";
        s.turns.add(t1);
        AgentTurn t2 = new AgentTurn();
        t2.role = "model";
        t2.content = "done";
        s.turns.add(t2);
        box.put(s);

        List<AgentSession> pending = box.query()
                .equal(AgentSession_.status, "DONE", StringOrder.CASE_SENSITIVE)
                .equal(AgentSession_.consolidated, false)
                .build().find(0, 20);
        check(pending.size() == 1 && pending.get(0).turns.size() == 2, "unconsolidated sessions");
        check(store.boxFor(AgentTurn.class).getAll().get(0).session.getTargetId() == s.id,
                "turn.session set by turns.add + put(session)");
        AgentSession loaded = pending.get(0);
        loaded.consolidated = true;
        box.put(loaded);
        check(box.query().equal(AgentSession_.consolidated, false).build().count() == 0,
                "consolidated flag saved");

        Box<UserContext> ctx = store.boxFor(UserContext.class);
        ctx.put(new UserContext("en-GB"));
        check(ctx.getAll().size() == 1, "user context");
    }

    // MetadataSearch
    static void metadataSearch(BoxStore store) {
        Box<SourceFile> fileBox = store.boxFor(SourceFile.class);
        Box<Folder> folderBox = store.boxFor(Folder.class);
        Folder f = new Folder("/sdcard/Notes", "Notes");
        f.summary = "University notes from 2024";
        folderBox.put(f);
        SourceFile sf = fileBox.getAll().get(0);
        sf.folder.setTarget(f);
        fileBox.put(sf);
        store.boxFor(TaskMemory.class).put(new TaskMemory("summarise biology", "rag", "" + sf.id));
        AccessLog log = new AccessLog(sf.id, 1);
        log.wasHelpful = true;
        store.boxFor(AccessLog.class).put(log);

        check(fileBox.query().contains(SourceFile_.userAlias, "BIOLOGY",
                StringOrder.CASE_INSENSITIVE).build().find().size() == 1, "alias contains");
        check(fileBox.query().contains(SourceFile_.tags, "2024",
                StringOrder.CASE_INSENSITIVE).build().find().size() == 1, "tags contains");
        check(fileBox.query().contains(SourceFile_.fileName, "bio",
                StringOrder.CASE_INSENSITIVE).build().find().size() == 1, "name contains");
        check(folderBox.query().contains(Folder_.summary, "university",
                StringOrder.CASE_INSENSITIVE).build().find().size() == 1, "folder summary");
        check(store.boxFor(TaskMemory.class).query().contains(TaskMemory_.taskDescription,
                "Biology", StringOrder.CASE_INSENSITIVE).build().find().size() == 1, "task");
        check(store.boxFor(AccessLog.class).query().in(AccessLog_.fileId, new long[]{sf.id})
                .equal(AccessLog_.wasHelpful, true).build().find().size() == 1, "access log");
        check(folderBox.get(f.id).files.size() == 1, "folder.files backlink");
    }
}
