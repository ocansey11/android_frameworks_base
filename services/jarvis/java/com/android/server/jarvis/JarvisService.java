package com.android.server.jarvis;

import android.content.Context;
import android.os.Binder;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.jarvis.IJarvisService;
import android.jarvis.IToolRegistry;
import android.util.Log;

import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import com.android.server.jarvis.core.IndexQueue;
import com.android.server.jarvis.core.JarvisStore;
import com.android.server.jarvis.core.ModelRegistry;
import com.android.server.jarvis.indexing.JarvisFileObserver;
import com.android.server.jarvis.indexing.JarvisIndexWorker;
import com.android.server.jarvis.model.SourceFile;
import com.android.server.jarvis.model.SourceFile_;
import com.android.server.jarvis.agent.DreamWorker;
import com.android.server.jarvis.agent.JarvisExecutor;
import com.android.server.jarvis.tools.AppRecord;
import com.android.server.jarvis.tools.ToolDispatcher;
import com.android.server.jarvis.tools.ToolRecord;
import com.android.server.jarvis.tools.ToolScannerService;

/**
 * JarvisOS System Service.
 *
 * The single privileged service that owns all Jarvis capabilities:
 *   - RAG: document indexing, semantic retrieval, LLM completion
 *   - Tools: app tool registry, semantic tool routing, broadcast dispatch
 *   - (Phase 5) Agentic loop via JarvisExecutor
 *
 * Startup sequence:
 *   1. ObjectBox store
 *   2. ModelRegistry — "rag" + "tools" handle pairs
 *   3. FileObservers
 *   4. JarvisIndexWorker (JarvisScheduler)
 *   5. ToolScannerService + ToolDispatcher
 *
 * Query flow:
 *   processQuery() → ToolDispatcher (tool path) | RAG pipeline (knowledge path)
 *
 * Published Binder endpoints:
 *   "jarvis"       → IJarvisService (queries, indexing)
 *   "jarvis_tools" → IToolRegistry  (tool registry inspection)
 */
public class JarvisService extends SystemService {

    private static final String TAG = "JarvisService";

    private static final String STORE_DIR            = "/data/system/jarvis/objectbox";
    private static final String MODEL_PATH           = "/data/system/jarvis/models/embed.gguf";
    private static final String GEMMA4_MODEL_PATH    = "/data/system/jarvis/models/gemma4.gguf";
    private static final String INDEX_DIR_RAG        = "/data/system/jarvis/index_rag";
    private static final String INDEX_DIR_TOOLS      = "/data/system/jarvis/index_tools";
    private static final int    EMBED_DIM            = 1024;

    // Primary user's shared storage. Spelled out rather than taken from
    // Environment.getExternalStorageDirectory(): that call needs the storage
    // service, which does not exist yet when system_server loads this class,
    // and it throws there, which stopped the whole service from starting.
    private static final String SHARED_STORAGE = "/storage/emulated/0";
    private static final String[] WATCH_PATHS = {
        SHARED_STORAGE + "/Documents",
        SHARED_STORAGE + "/Downloads",
        SHARED_STORAGE + "/Pictures",
    };

    private final Context mContext;
    private volatile boolean mIsReady = false;
    private JarvisFileObserver[] mFileObservers;
    private ToolScannerService mToolScanner;
    private ToolDispatcher mToolDispatcher;
    private JarvisExecutor mExecutor;

    public JarvisService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService("jarvis", mBinder);
        publishBinderService("jarvis_tools", mToolRegistryBinder);
        initializeAsync();
    }

    private void initializeAsync() {
        new Thread(() -> {
            try {
                // Step 1 — ObjectBox (must come first)
                new java.io.File(STORE_DIR).mkdirs();
                JarvisStore.init(STORE_DIR);

                // Step 2 — ModelRegistry
                ModelRegistry registry = ModelRegistry.getInstance();
                ModelRegistry.ModelEntry ragModel     = registry.register("rag",   MODEL_PATH, INDEX_DIR_RAG,   EMBED_DIM);
                ModelRegistry.ModelEntry toolsModel   = registry.register("tools", MODEL_PATH, INDEX_DIR_TOOLS, EMBED_DIM);
                ModelRegistry.ModelEntry primaryModel = registry.registerChatModel("primary", GEMMA4_MODEL_PATH);

                if (!ragModel.isReady())     Log.w(TAG, "RAG model not ready — embeddings disabled");
                if (!toolsModel.isReady())   Log.w(TAG, "Tools model not ready — tool embeddings disabled");
                if (!primaryModel.isReady()) Log.w(TAG, "Primary model not ready — PlanNode/RespondNode will fall back to rag");

                // Step 3 — FileObservers. Not fatal: tools and queries do not
                // depend on file watching.
                try {
                    startFileObservers();
                } catch (RuntimeException e) {
                    Log.e(TAG, "File observers failed to start", e);
                }

                // Step 4 — background indexing
                JarvisIndexWorker.schedule(mContext);

                // Step 5 — Tool Registry
                mToolScanner    = new ToolScannerService(mContext);
                mToolDispatcher = new ToolDispatcher(mContext);
                mExecutor       = new JarvisExecutor(mToolDispatcher);
                mToolScanner.start();

                ModelRegistry.ModelEntry tools = registry.getReady("tools");
                if (tools != null) {
                    mToolScanner.setCactusHandles(tools.modelHandle, tools.indexHandle);
                }

                // Step 6 — DreamWorker (nightly memory consolidation)
                DreamWorker.schedule(mContext);

                mIsReady = true;
                Log.i(TAG, "JarvisService initialized");

            } catch (Throwable t) {
                // Throwable, not Exception: a missing native library surfaces as
                // an Error, and an uncaught one on this thread kills system_server.
                Log.e(TAG, "JarvisService init failed", t);
            }
        }, "JarvisServiceInit").start();
    }

    private void startFileObservers() {
        mFileObservers = new JarvisFileObserver[WATCH_PATHS.length];
        for (int i = 0; i < WATCH_PATHS.length; i++) {
            mFileObservers[i] = new JarvisFileObserver(
                    WATCH_PATHS[i], IndexQueue.getInstance().getQueue());
            mFileObservers[i].startWatching();
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            Log.i(TAG, "Boot complete");
        }
    }

    public void onDestroy() {
        if (mToolScanner != null)   mToolScanner.stop();
        if (mFileObservers != null) {
            for (JarvisFileObserver o : mFileObservers) if (o != null) o.stopWatching();
        }
        ModelRegistry.getInstance().destroyAll();
        JarvisStore.close();
    }

    // -------------------------------------------------------------------------
    // IJarvisService Binder — published as "jarvis"
    // -------------------------------------------------------------------------

    private final IJarvisService.Stub mBinder = new IJarvisService.Stub() {

        @Override
        public String processQuery(String query) {
            enforceCallingPermission();
            if (query == null || query.trim().isEmpty()) {
                throw new IllegalArgumentException("Query cannot be null or empty");
            }
            if (!mIsReady) {
                return "Error: Jarvis service is still initializing. Please try again.";
            }

            Log.i(TAG, "processQuery: " + query.substring(0, Math.min(50, query.length())));

            try {
                if (mExecutor != null) {
                    return mExecutor.execute(query);
                }
                // Executor not ready — service still initializing
                return "Error: Jarvis service is still initializing. Please try again.";
            } catch (Exception e) {
                Log.e(TAG, "processQuery failed", e);
                return "Error: " + e.getMessage();
            }
        }

        @Override
        public void indexDocument(String path) {
            enforceCallingPermission();
            if (path == null || path.trim().isEmpty()) {
                throw new IllegalArgumentException("Path cannot be null or empty");
            }
            try {
                IndexQueue.getInstance().getQueue().put(
                        new JarvisFileObserver.IndexTask(path, JarvisFileObserver.TaskType.INDEX));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean isIndexed(String path) {
            enforceCallingPermission();
            if (path == null || path.trim().isEmpty()) return false;
            try {
                SourceFile sf = JarvisStore.box(SourceFile.class).query()
                        .equal(SourceFile_.filePath, path,
                                io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                        .build().findFirst();
                return sf != null && sf.isIndexed;
            } catch (Exception e) {
                Log.w(TAG, "isIndexed() failed for: " + path, e);
                return false;
            }
        }

        @Override
        public boolean isReady() {
            return mIsReady;
        }

        @Override
        public String processQueryWithImage(String query, byte[] imageData) {
            enforceCallingPermission();
            if (!mIsReady) return "Error: Jarvis service is still initializing.";
            // TODO: pass imageData through CactusWrapper once Gemma 4 image support
            // is available in Cactus (Sam's upstream pull). For now, fall back to text.
            Log.i(TAG, "processQueryWithImage: image=" + (imageData != null
                    ? imageData.length + " bytes" : "null") + " — falling back to text query");
            if (mExecutor != null) return mExecutor.execute(query);
            return "Error: executor not ready";
        }

        @Override
        public String processQueryWithAudio(String query, byte[] pcmData) {
            enforceCallingPermission();
            if (!mIsReady) return "Error: Jarvis service is still initializing.";
            // TODO: pass pcmData to CactusWrapper.transcribe() once Gemma 4 audio
            // support is confirmed stable in Cactus. Currently Whisper path only.
            // For now: transcribe if pcmData is provided + audio model is ready,
            // otherwise fall through to text query.
            Log.i(TAG, "processQueryWithAudio: pcm=" + (pcmData != null
                    ? pcmData.length + " bytes" : "null") + " — falling back to text query");
            if (mExecutor != null) return mExecutor.execute(query);
            return "Error: executor not ready";
        }

        private void enforceCallingPermission() {
            final int callingUid = Binder.getCallingUid();
            Log.d(TAG, "Called by UID: " + callingUid);
            // TODO: mContext.enforceCallingPermission("android.permission.ACCESS_JARVIS_SERVICE", "...");
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new JarvisShellCommand().exec(this, in, out, err, args, callback, resultReceiver);
        }
    };

    // -------------------------------------------------------------------------
    // `adb shell cmd jarvis ...` — lets the service be exercised without a model
    // -------------------------------------------------------------------------

    private final class JarvisShellCommand extends ShellCommand {

        @Override
        public int onCommand(String cmd) {
            final PrintWriter pw = getOutPrintWriter();
            final int uid = Binder.getCallingUid();
            if (uid != Process.ROOT_UID && uid != Process.SHELL_UID) {
                pw.println("Error: only shell or root may use this command");
                return -1;
            }
            if (cmd == null) return handleDefaultCommands(cmd);
            switch (cmd) {
                case "status":
                    pw.println("ready: " + mIsReady);
                    pw.println("store: " + (JarvisStore.isReady() ? "open" : "not open"));
                    for (String name : new String[] {"rag", "tools", "primary"}) {
                        pw.println("model " + name + ": "
                                + (ModelRegistry.getInstance().getReady(name) != null
                                        ? "ready" : "not ready"));
                    }
                    if (JarvisStore.isReady()) {
                        pw.println("apps: " + JarvisStore.box(AppRecord.class).count());
                        pw.println("tools: " + JarvisStore.box(ToolRecord.class).count());
                    }
                    return 0;
                case "tools":
                    if (!JarvisStore.isReady()) {
                        pw.println("Error: store not open");
                        return -1;
                    }
                    for (ToolRecord tool : JarvisStore.box(ToolRecord.class).getAll()) {
                        AppRecord app = tool.app.getTarget();
                        pw.println(tool.toolName + "  [" + (app != null ? app.packageName : "?")
                                + "/" + tool.receiverClass + "]");
                    }
                    return 0;
                case "describe": {
                    String name = getNextArgRequired();
                    if (!JarvisStore.isReady()) {
                        pw.println("Error: store not open");
                        return -1;
                    }
                    for (ToolRecord tool : JarvisStore.box(ToolRecord.class).getAll()) {
                        if (name.equals(tool.toolName)) {
                            pw.println(ToolDispatcher.serializeTool(tool));
                        }
                    }
                    return 0;
                }
                case "tool": {
                    String name = getNextArgRequired();
                    String argsJson = getNextArg();
                    if (mToolDispatcher == null) {
                        pw.println("Error: tool dispatcher not started");
                        return -1;
                    }
                    pw.println(mToolDispatcher.dispatchByName(name, argsJson));
                    return 0;
                }
                case "query":
                    try {
                        pw.println(mBinder.processQuery(getNextArgRequired()));
                    } catch (android.os.RemoteException e) {
                        // In-process call: cannot happen.
                        pw.println("Error: " + e);
                    }
                    return 0;
                default:
                    return handleDefaultCommands(cmd);
            }
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Jarvis service commands:");
            pw.println("  status              service, store and model state, record counts");
            pw.println("  tools               every registered tool and the app that owns it");
            pw.println("  describe NAME       a tool's stored definition as JSON");
            pw.println("  tool NAME [JSON]    call a tool directly with JSON arguments");
            pw.println("  query TEXT          same as IJarvisService.processQuery");
        }
    }

    // -------------------------------------------------------------------------
    // IToolRegistry Binder — published as "jarvis_tools"
    // -------------------------------------------------------------------------

    private final IToolRegistry.Stub mToolRegistryBinder = new IToolRegistry.Stub() {

        @Override
        public String listTools() {
            enforceCallingPermission();
            if (!JarvisStore.isReady()) return "[]";
            try {
                java.util.List<ToolRecord> all = JarvisStore.box(ToolRecord.class).getAll();
                return ToolDispatcher.serializeTools(all);
            } catch (Exception e) {
                Log.e(TAG, "listTools() failed", e);
                return "[]";
            }
        }

        @Override
        public String getTool(long id) {
            enforceCallingPermission();
            if (!JarvisStore.isReady()) return null;
            try {
                ToolRecord tool = JarvisStore.box(ToolRecord.class).get(id);
                return ToolDispatcher.serializeTool(tool);
            } catch (Exception e) {
                Log.e(TAG, "getTool() failed for id=" + id, e);
                return null;
            }
        }

        @Override
        public String searchTools(String query) {
            enforceCallingPermission();
            if (query == null || query.trim().isEmpty()) return "[]";
            if (!mIsReady || mToolDispatcher == null) return "[]";
            try {
                return mToolDispatcher.searchTools(query);
            } catch (Exception e) {
                Log.e(TAG, "searchTools() failed", e);
                return "[]";
            }
        }

        private void enforceCallingPermission() {
            final int callingUid = Binder.getCallingUid();
            Log.d(TAG, "jarvis_tools called by UID: " + callingUid);
            // TODO: mContext.enforceCallingPermission("android.permission.ACCESS_JARVIS_SERVICE", "...");
        }
    };
}
