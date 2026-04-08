package com.android.server.jarvis.core;

import android.util.Log;

import com.android.server.jarvis.indexing.JarvisFileObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Singleton queue that holds pending file index tasks.
 *
 * JarvisFileObserver pushes tasks in.
 * RagIndexWorker drains tasks out (10 at a time).
 *
 * Thread-safe — LinkedBlockingQueue handles concurrent access.
 */
public class IndexQueue {

    private static final String TAG = "IndexQueue";
    private static final int MAX_CAPACITY = 500; // don't let it grow unbounded

    private static volatile IndexQueue sInstance;
    private final BlockingQueue<JarvisFileObserver.IndexTask> mQueue;

    private IndexQueue() {
        mQueue = new LinkedBlockingQueue<>(MAX_CAPACITY);
    }

    public static IndexQueue getInstance() {
        if (sInstance == null) {
            synchronized (IndexQueue.class) {
                if (sInstance == null) {
                    sInstance = new IndexQueue();
                }
            }
        }
        return sInstance;
    }

    /**
     * Returns the raw queue — used by JarvisFileObserver to push tasks.
     */
    public BlockingQueue<JarvisFileObserver.IndexTask> getQueue() {
        return mQueue;
    }

    /**
     * Drain up to batchSize tasks from the queue.
     * Called by RagIndexWorker on each run.
     */
    public List<JarvisFileObserver.IndexTask> drainBatch(int batchSize) {
        List<JarvisFileObserver.IndexTask> batch = new ArrayList<>(batchSize);
        mQueue.drainTo(batch, batchSize);
        Log.d(TAG, "Drained " + batch.size() + " tasks from queue (" + mQueue.size() + " remaining)");
        return batch;
    }

    public int size() {
        return mQueue.size();
    }
}
