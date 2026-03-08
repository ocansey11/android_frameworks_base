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

    // Directories to watch — expandable
    private static final String[] WATCH_PATHS = {
        Environment.getExternalStorageDirectory().getAbsolutePath() + "/Documents",
        Environment.getExternalStorageDirectory().getAbsolutePath() + "/Downloads",
    };

    private final Context mContext;
    private boolean mIsReady = false;
    private JarvisFileObserver[] mFileObservers;

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

                // Step 1 — start file observers
                startFileObservers();

                // Step 2 — schedule background indexing worker
                RagIndexWorker.schedule(mContext);

                // TODO: Step 3 — initialize Cactus (CactusWrapper.init())

                // Step 4 — initialize ObjectBox store
                new java.io.File(STORE_DIR).mkdirs();
                JarvisStore.init(STORE_DIR);

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
