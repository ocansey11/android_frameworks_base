package com.android.server.rag;

import android.content.Context;
import android.os.Binder;
import android.os.Environment;
import android.app.rag.IRagService;
import android.util.Log;

import com.android.server.SystemService;

import java.util.List;

/**
 * JarvisOS RAG System Service
 *
 * Runs in system_server. Provides RAG capabilities to all apps.
 *
 * Architecture:
 *   App → RagManager → Binder IPC → RagService → Cactus LLM
 *
 * On startup:
 *   1. Creates IndexQueue (singleton)
 *   2. Starts JarvisFileObserver watching external storage
 *   3. Schedules RagIndexWorker via WorkManager (charging constraint)
 *   4. Initializes ObjectBox store
 *
 * Query flow (immediate, no constraints):
 *   processQuery() → Stage 1 metadata search → Stage 2 cactus_embed → cactus_complete
 */
public class RagService extends SystemService {
    private static final String TAG = "RagService";

    // ObjectBox store directory — inside system data partition
    private static final String STORE_DIR = "/data/system/jarvis/objectbox";

    // Model paths and index directories
    private static final String MODEL_PATH      = "/data/system/jarvis/models/embed.gguf";
    private static final String INDEX_DIR_RAG   = "/data/system/jarvis/index_rag";
    private static final String INDEX_DIR_TOOLS = "/data/system/jarvis/index_tools";
    private static final int    EMBED_DIM       = 1024; // Qwen embed dimension

    // Directories to watch — expandable
    // Each path is watched recursively (JarvisFileObserver walks subdirectories)
    private static final String[] WATCH_PATHS = {
        Environment.getExternalStorageDirectory().getAbsolutePath() + "/Documents",
        Environment.getExternalStorageDirectory().getAbsolutePath() + "/Downloads",
        Environment.getExternalStorageDirectory().getAbsolutePath() + "/Pictures",
    };

    private final Context mContext;
    private boolean mIsReady = false;
    private JarvisFileObserver[] mFileObservers;
    private ToolScannerService mToolScanner;

    public RagService(Context context) {
        super(context);
        mContext = context;
        Log.i(TAG, "RagService created");
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Starting RAG service");
        publishBinderService("rag", mBinder);
        initializeAsync();
    }

    private void initializeAsync() {
        new Thread(() -> {
            try {
                Log.i(TAG, "Initializing RAG service...");

                // Step 1 — initialize ObjectBox store FIRST (nothing should write before this)
                new java.io.File(STORE_DIR).mkdirs();
                JarvisStore.init(STORE_DIR);

                // Step 2 — initialize Cactus via ModelRegistry
                // Two entries: "rag" for document indexing, "tools" for tool semantic search
                // Same model, separate index directories — indexes must never be mixed
                ModelRegistry registry = ModelRegistry.getInstance();
                ModelRegistry.ModelEntry ragModel   = registry.register("rag",   MODEL_PATH, INDEX_DIR_RAG,   EMBED_DIM);
                ModelRegistry.ModelEntry toolsModel = registry.register("tools", MODEL_PATH, INDEX_DIR_TOOLS, EMBED_DIM);

                if (!ragModel.isReady()) {
                    Log.w(TAG, "RAG model not ready — indexing will skip embeddings");
                }
                if (!toolsModel.isReady()) {
                    Log.w(TAG, "Tools model not ready — tool embeddings disabled");
                }

                // Pass tools handles to ToolScannerService so it can embed tool descriptions
                // (done after scanner is started below)

                // Step 3 — start file observers (store is ready to receive tasks)
                startFileObservers();

                // Step 4 — schedule background indexing worker
                RagIndexWorker.schedule(mContext);

                // Step 5 — start tool scanner (picks up already-installed apps + listens for new ones)
                mToolScanner = new ToolScannerService(mContext);
                mToolScanner.start();

                // Pass Cactus handles to tool scanner now that both are ready
                ModelRegistry.ModelEntry tools = ModelRegistry.getInstance().getReady("tools");
                if (tools != null) {
                    mToolScanner.setCactusHandles(tools.modelHandle, tools.indexHandle);
                }

                mIsReady = true;
                Log.i(TAG, "RAG service initialized successfully");

            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize RAG service", e);
                mIsReady = false;
            }
        }, "RagServiceInit").start();
    }

    private void startFileObservers() {
        mFileObservers = new JarvisFileObserver[WATCH_PATHS.length];
        for (int i = 0; i < WATCH_PATHS.length; i++) {
            mFileObservers[i] = new JarvisFileObserver(
                    WATCH_PATHS[i],
                    IndexQueue.getInstance().getQueue());
            mFileObservers[i].startWatching();
            Log.i(TAG, "FileObserver started on: " + WATCH_PATHS[i]);
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
        Log.i(TAG, "RagService shutting down");
        if (mToolScanner != null) mToolScanner.stop();
        if (mFileObservers != null) {
            for (JarvisFileObserver o : mFileObservers) {
                if (o != null) o.stopWatching();
            }
        }
        ModelRegistry.getInstance().destroyAll();
        JarvisStore.close();
    }

    private final IRagService.Stub mBinder = new IRagService.Stub() {

        @Override
        public String processQuery(String query) {
            enforceCallingPermission();

            if (query == null || query.trim().isEmpty()) {
                throw new IllegalArgumentException("Query cannot be null or empty");
            }

            if (!mIsReady) {
                return "Error: RAG service is still initializing. Please try again.";
            }

            Log.i(TAG, "Processing query: " + query.substring(0, Math.min(50, query.length())) + "...");

            try {
                // Stage 1 — metadata search (instant, free)
                // TODO: List<SourceFile> candidates = MetadataSearch.search(query);

                // Stage 2 — semantic search on shortlist (on demand)
                // TODO: float[] queryEmbedding = CactusWrapper.embed(query);
                // TODO: List<DocumentChunk> chunks = CactusWrapper.indexQuery(queryEmbedding, candidates);

                // Stage 3 — LLM completion with context
                // TODO: String context = buildContext(chunks);
                // TODO: return CactusWrapper.complete(query, context);

                return "RAG Service received: \"" + query + "\"\n\nTODO: Implement RAG pipeline";

            } catch (Exception e) {
                Log.e(TAG, "Error processing query", e);
                return "Error: " + e.getMessage();
            }
        }

        @Override
        public void indexDocument(String path) {
            enforceCallingPermission();

            if (path == null || path.trim().isEmpty()) {
                throw new IllegalArgumentException("Path cannot be null or empty");
            }

            Log.i(TAG, "Manual index request: " + path);

            // Push directly to IndexQueue — worker will pick it up
            try {
                IndexQueue.getInstance().getQueue().put(
                        new JarvisFileObserver.IndexTask(path, JarvisFileObserver.TaskType.INDEX));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Interrupted while queuing manual index: " + path);
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
            Log.d(TAG, "RAG service called by UID: " + callingUid);
            // TODO: mContext.enforceCallingPermission("android.permission.ACCESS_RAG_SERVICE", "...");
        }
    };
}
