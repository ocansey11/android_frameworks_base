package com.android.server.rag;

import android.os.FileObserver;
import android.util.Log;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Watches a directory for file changes and queues them for trickle indexing.
 *
 * Detected events:
 *   CREATE  — new file, queue for indexing
 *   MODIFY  — file changed, queue for re-indexing (hash check will skip if unchanged)
 *   DELETE  — file removed, queue for removal from index
 *   MOVED_FROM / MOVED_TO — treat as delete + create
 *
 * Does NOT index immediately — adds to IndexQueue.
 * WorkManager processes the queue 10 items at a time when charging/idle.
 */
public class JarvisFileObserver extends FileObserver {

    private static final String TAG = "JarvisFileObserver";

    private static final int EVENTS =
            FileObserver.CREATE |
            FileObserver.MODIFY |
            FileObserver.DELETE |
            FileObserver.MOVED_FROM |
            FileObserver.MOVED_TO;

    private final String mWatchPath;
    private final BlockingQueue<IndexTask> mIndexQueue;

    public enum TaskType { INDEX, REMOVE }

    public static class IndexTask {
        public final String filePath;
        public final TaskType type;

        public IndexTask(String filePath, TaskType type) {
            this.filePath = filePath;
            this.type = type;
        }
    }

    public JarvisFileObserver(String path, BlockingQueue<IndexTask> indexQueue) {
        super(path, EVENTS);
        this.mWatchPath = path;
        this.mIndexQueue = indexQueue;
        Log.i(TAG, "Watching: " + path);
    }

    @Override
    public void onEvent(int event, String relativePath) {
        if (relativePath == null) return;

        String fullPath = mWatchPath + File.separator + relativePath;

        switch (event) {
            case FileObserver.CREATE:
            case FileObserver.MOVED_TO:
                Log.d(TAG, "File created/moved in: " + fullPath);
                enqueue(fullPath, TaskType.INDEX);
                break;

            case FileObserver.MODIFY:
                Log.d(TAG, "File modified: " + fullPath);
                // Hash check in RagIndexWorker will skip if content unchanged
                enqueue(fullPath, TaskType.INDEX);
                break;

            case FileObserver.DELETE:
            case FileObserver.MOVED_FROM:
                Log.d(TAG, "File deleted/moved out: " + fullPath);
                enqueue(fullPath, TaskType.REMOVE);
                break;

            default:
                break;
        }
    }

    private void enqueue(String filePath, TaskType type) {
        try {
            mIndexQueue.put(new IndexTask(filePath, type));
            Log.d(TAG, "Queued [" + type + "]: " + filePath + " (queue size: " + mIndexQueue.size() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted while queuing: " + filePath);
        }
    }
}
