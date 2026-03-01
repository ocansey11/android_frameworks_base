package com.android.server.rag;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager job that processes pending index tasks from IndexQueue.
 *
 * Runs every 15 minutes, only when:
 *   - Device is charging, OR
 *   - Device is idle
 *
 * Each run drains up to 10 tasks from the queue.
 * For each task:
 *   INDEX  → read file, hash check, chunk, summarise, cactus_embed, update ObjectBox
 *   REMOVE → delete from Cactus index, remove SourceFile from ObjectBox
 */
public class RagIndexWorker extends Worker {

    private static final String TAG = "RagIndexWorker";
    private static final String WORK_NAME = "jarvis_index_worker";
    private static final int BATCH_SIZE = 10;

    public RagIndexWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "RagIndexWorker started");

        List<JarvisFileObserver.IndexTask> batch = IndexQueue.getInstance().drainBatch(BATCH_SIZE);

        if (batch.isEmpty()) {
            Log.d(TAG, "Queue empty, nothing to do");
            return Result.success();
        }

        for (JarvisFileObserver.IndexTask task : batch) {
            try {
                if (task.type == JarvisFileObserver.TaskType.INDEX) {
                    processIndexTask(task);
                } else {
                    processRemoveTask(task);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to process task: " + task.filePath, e);
                // Don't fail the whole batch for one bad file
            }
        }

        Log.i(TAG, "RagIndexWorker finished batch of " + batch.size());
        return Result.success();
    }

    private void processIndexTask(JarvisFileObserver.IndexTask task) throws Exception {
        File file = new File(task.filePath);

        if (!file.exists() || !file.isFile()) {
            Log.w(TAG, "File no longer exists, skipping: " + task.filePath);
            return;
        }

        // Hash check — skip if file hasn't changed since last index
        String currentHash = hashFile(file);
        // TODO: query ObjectBox for existing SourceFile by filePath
        // if (existingFile != null && existingFile.fileHash.equals(currentHash)) {
        //     Log.d(TAG, "File unchanged, skipping: " + task.filePath);
        //     return;
        // }

        Log.i(TAG, "Indexing: " + task.filePath);

        // TODO: Step 1 — extract text from file (pdf, txt, docx etc)
        // String text = TextExtractor.extract(file);

        // TODO: Step 2 — chunk the text
        // List<Chunk> chunks = ChunkingStrategy.chunk(text, task.filePath);

        // TODO: Step 3 — for each chunk, generate summary + call cactus_embed()
        // for (Chunk chunk : chunks) {
        //     String summary = Summariser.summarise(chunk.getText());
        //     float[] embedding = CactusWrapper.embed(chunk.getText());
        //     int cactusId = CactusWrapper.indexAdd(embedding);
        //     DocumentChunk entity = new DocumentChunk(chunk.getPosition(), summary, chunk.tokenCount(), System.currentTimeMillis());
        //     entity.cactusIndexId = cactusId;
        //     // save to ObjectBox
        // }

        // TODO: Step 4 — update SourceFile.isIndexed = true, fileHash = currentHash in ObjectBox

        Log.i(TAG, "Indexed: " + task.filePath);
    }

    private void processRemoveTask(JarvisFileObserver.IndexTask task) {
        Log.i(TAG, "Removing from index: " + task.filePath);

        // TODO: query ObjectBox for SourceFile by filePath
        // if found: delete its DocumentChunks, call CactusWrapper.indexDelete() for each cactusIndexId
        // then delete the SourceFile entity itself

        Log.i(TAG, "Removed: " + task.filePath);
    }

    private String hashFile(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            int n;
            while ((n = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Schedule this worker. Call once from RagService.onStart().
     * WorkManager deduplicates — safe to call multiple times.
     */
    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiresCharging(true)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                RagIndexWorker.class,
                15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request);

        Log.i(TAG, "RagIndexWorker scheduled (charging constraint, 15min interval)");
    }
}
