package com.android.server.jarvis;

import android.content.Context;
import android.os.Binder;
import android.os.Environment;
import android.jarvis.IJarvisService;
import android.jarvis.IToolRegistry;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.jarvis.core.IndexQueue;
import com.android.server.jarvis.core.JarvisStore;
import com.android.server.jarvis.core.ModelRegistry;
import com.android.server.jarvis.indexing.JarvisFileObserver;
import com.android.server.jarvis.indexing.JarvisIndexWorker;
import com.android.server.jarvis.model.SourceFile;
import com.android.server.jarvis.model.SourceFile_;
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
 *   4. JarvisIndexWorker (WorkManager)
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

    private static final String STORE_DIR       = "/data/system/jarvis/objectbox";
    private static final String MODEL_PATH      = "/data/system/jarvis/models/embed.gguf";
    private static final String INDEX_DIR_RAG   = "/data/system/jarvis/index_rag";
    private static final String INDEX_DIR_TOOLS = "/data/system/jarvis/index_tools";
    private static final int    EMBED_DIM       = 1024;

    private static final String[] WATCH_PATHS = {
        Environment.getExternalStorageDirectory().getAbsolutePath() + "/Documents",
        Environment.getExternalStorageDirectory().getAbsolutePath() + "/Downloads",
        Environment.getExternalStorageDirectory().getAbsolutePath() + "/Pictures",
    };

    private final Context mContext;
    private volatile boolean mIsReady = false;
    private JarvisFileObserver[] mFileObservers;
    private ToolScannerService mToolScanner;
    private ToolDispatcher mToolDispatcher;

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
                ModelRegistry.ModelEntry ragModel   = registry.register("rag",   MODEL_PATH, INDEX_DIR_RAG,   EMBED_DIM);
                ModelRegistry.ModelEntry toolsModel = registry.register("tools", MODEL_PATH, INDEX_DIR_TOOLS, EMBED_DIM);

                if (!ragModel.isReady())   Log.w(TAG, "RAG model not ready — embeddings disabled");
                if (!toolsModel.isReady()) Log.w(TAG, "Tools model not ready — tool embeddings disabled");

                // Step 3 — FileObservers
                startFileObservers();

                // Step 4 — background indexing
                JarvisIndexWorker.schedule(mContext);

                // Step 5 — Tool Registry
                mToolScanner    = new ToolScannerService(mContext);
                mToolDispatcher = new ToolDispatcher(mContext);
                mToolScanner.start();

                ModelRegistry.ModelEntry tools = registry.getReady("tools");
                if (tools != null) {
                    mToolScanner.setCactusHandles(tools.modelHandle, tools.indexHandle);
                }

                mIsReady = true;
                Log.i(TAG, "JarvisService initialized");

            } catch (Exception e) {
                Log.e(TAG, "JarvisService init failed", e);
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

    @Override
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
                // Tool path — attempt tool dispatch first
                // Returns null if no tool matches, falls through to RAG
                if (mToolDispatcher != null) {
                    String toolResult = mToolDispatcher.resolveAndDispatch(query);
                    if (toolResult != null) return toolResult;
                }

                // RAG path — TODO: wire MetadataSearch + CactusWrapper.complete()
                return "Jarvis received: \"" + query + "\"\n\nTODO: Implement RAG pipeline";

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

        private void enforceCallingPermission() {
            final int callingUid = Binder.getCallingUid();
            Log.d(TAG, "Called by UID: " + callingUid);
            // TODO: mContext.enforceCallingPermission("android.permission.ACCESS_JARVIS_SERVICE", "...");
        }
    };

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
