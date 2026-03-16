package com.android.server.rag;

import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 * Watches a directory (and all its subdirectories) for file changes
 * and queues them for trickle indexing.
 *
 * Detected events:
 *   CREATE  — new file, queue for indexing
 *   MODIFY  — file changed, queue for re-indexing (hash check will skip if unchanged)
 *   DELETE  — file removed, queue for removal from index
 *   MOVED_FROM / MOVED_TO — treat as delete + create
 *
 * Recursive watching:
 *   Android's FileObserver only watches a flat directory. To cover subdirectories,
 *   we walk the full directory tree on construction and create one FileObserver
 *   per subdirectory. New subdirectories created after startup are registered
 *   when their CREATE event fires.
 *
 * Does NOT index immediately — adds to IndexQueue.
 * WorkManager processes the queue 10 items at a time when charging/idle.
 */
public class JarvisFileObserver {

    private static final String TAG = "JarvisFileObserver";

    private static final int EVENTS =
            FileObserver.CREATE |
            FileObserver.MODIFY |
            FileObserver.DELETE |
            FileObserver.MOVED_FROM |
            FileObserver.MOVED_TO;

    public enum TaskType { INDEX, REMOVE }

    public static class IndexTask {
        public final String filePath;
        public final TaskType type;

        public IndexTask(String filePath, TaskType type) {
            this.filePath = filePath;
            this.type     = type;
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final String mRootPath;
    private final BlockingQueue<IndexTask> mIndexQueue;

    /** One SingleObserver per directory (root + all subdirs). */
    private final List<SingleObserver> mObservers = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public JarvisFileObserver(String rootPath, BlockingQueue<IndexTask> indexQueue) {
        this.mRootPath    = rootPath;
        this.mIndexQueue  = indexQueue;
    }

    /** Start watching the root directory and all existing subdirectories. */
    public void startWatching() {
        registerRecursive(new File(mRootPath));
        Log.i(TAG, "Watching (recursive): " + mRootPath
                + " — " + mObservers.size() + " director(ies)");
    }

    /** Stop all observers. */
    public void stopWatching() {
        for (SingleObserver o : mObservers) o.stopWatching();
        mObservers.clear();
    }

    // -------------------------------------------------------------------------
    // Private — recursive registration
    // -------------------------------------------------------------------------

    private void registerRecursive(File dir) {
        if (!dir.exists() || !dir.isDirectory()) return;

        SingleObserver obs = new SingleObserver(dir.getAbsolutePath());
        obs.startWatching();
        mObservers.add(obs);

        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                registerRecursive(child);
            }
        }
    }

    /** Register a newly-created subdirectory at runtime. */
    private void registerNewDir(String dirPath) {
        File dir = new File(dirPath);
        if (dir.isDirectory()) {
            registerRecursive(dir);
            Log.i(TAG, "Registered new subdirectory: " + dirPath);
        }
    }

    // -------------------------------------------------------------------------
    // Private — enqueue
    // -------------------------------------------------------------------------

    private void enqueue(String filePath, TaskType type) {
        try {
            mIndexQueue.put(new IndexTask(filePath, type));
            Log.d(TAG, "Queued [" + type + "]: " + filePath
                    + " (queue size: " + mIndexQueue.size() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted while queuing: " + filePath);
        }
    }

    // -------------------------------------------------------------------------
    // Inner class — watches a single (flat) directory
    // -------------------------------------------------------------------------

    private class SingleObserver extends FileObserver {

        private final String mDirPath;

        SingleObserver(String dirPath) {
            super(dirPath, EVENTS);
            this.mDirPath = dirPath;
        }

        @Override
        public void onEvent(int event, String relativePath) {
            if (relativePath == null) return;

            String fullPath = mDirPath + File.separator + relativePath;

            switch (event) {
                case FileObserver.CREATE:
                case FileObserver.MOVED_TO:
                    Log.d(TAG, "Created/moved in: " + fullPath);
                    // If a new directory appeared, register it recursively
                    registerNewDir(fullPath);
                    // Also queue for indexing (if it's a file, not a dir)
                    if (new File(fullPath).isFile()) {
                        enqueue(fullPath, TaskType.INDEX);
                    }
                    break;

                case FileObserver.MODIFY:
                    Log.d(TAG, "Modified: " + fullPath);
                    enqueue(fullPath, TaskType.INDEX);
                    break;

                case FileObserver.DELETE:
                case FileObserver.MOVED_FROM:
                    Log.d(TAG, "Deleted/moved out: " + fullPath);
                    enqueue(fullPath, TaskType.REMOVE);
                    break;

                default:
                    break;
            }
        }
    }
}
