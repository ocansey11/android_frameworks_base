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
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager job that drains IndexQueue and indexes pending files.
 *
 * Schedule: every 15 minutes, charging constraint only.
 * Batch:    up to 10 tasks per run to keep CPU time bounded.
 *
 * INDEX task pipeline:
 *   1. Check file exists
 *   2. Hash file — skip if unchanged since last index
 *   3. Extract text via TextExtractor
 *   4. Chunk text via ChunkingStrategy
 *   5. For each chunk: embed via CactusWrapper → add to Cactus index
 *   6. Persist DocumentChunk entities to ObjectBox
 *   7. Update SourceFile.isIndexed = true, fileHash = currentHash
 *
 * REMOVE task pipeline:
 *   1. Look up SourceFile by filePath in ObjectBox
 *   2. For each DocumentChunk: call CactusWrapper.indexDelete()
 *   3. Delete DocumentChunk entities from ObjectBox
 *   4. Delete SourceFile entity from ObjectBox
 *
 * Note: CactusWrapper calls are blocking — this worker already runs on a
 * background thread (WorkManager's executor), so no extra threading needed.
 */
public class RagIndexWorker extends Worker {

    private static final String TAG       = "RagIndexWorker";
    private static final String WORK_NAME = "jarvis_index_worker";
    private static final int    BATCH_SIZE = 10;

    // Shared Cactus handles — initialized lazily on first use.
    // In a real build these would be managed by RagService and passed in,
    // but for Phase 2 we initialize them locally here.
    private static volatile long sModelHandle = 0L;
    private static volatile long sIndexHandle = 0L;
    private static final Object sHandleLock  = new Object();

    private static final String MODEL_PATH = "/data/system/jarvis/models/embed.gguf";
    private static final String INDEX_DIR  = "/data/system/jarvis/cactus_index";
    private static final int    EMBED_DIM  = 1024; // Qwen embed dimension

    public RagIndexWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    // -------------------------------------------------------------------------
    // Worker entry point
    // -------------------------------------------------------------------------

    @NonNull
    @Override
    public Result doWork() {
        Log.i(TAG, "RagIndexWorker started");

        if (!JarvisStore.isReady()) {
            Log.w(TAG, "ObjectBox store not ready — retrying later");
            return Result.retry();
        }

        ensureHandles();

        List<JarvisFileObserver.IndexTask> batch =
                IndexQueue.getInstance().drainBatch(BATCH_SIZE);

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
                Log.e(TAG, "Failed task: " + task.filePath, e);
                // Continue — don't fail entire batch for one bad file
            }
        }

        Log.i(TAG, "Batch of " + batch.size() + " complete");
        return Result.success();
    }

    // -------------------------------------------------------------------------
    // INDEX task
    // -------------------------------------------------------------------------

    private void processIndexTask(JarvisFileObserver.IndexTask task) throws Exception {
        File file = new File(task.filePath);

        if (!file.exists() || !file.isFile()) {
            Log.w(TAG, "File gone, skipping: " + task.filePath);
            return;
        }

        // --- Step 2: Hash check — skip if unchanged ---
        String currentHash = hashFile(file);
        io.objectbox.Box<SourceFile> fileBox = JarvisStore.box(SourceFile.class);

        SourceFile existing = fileBox.query()
                .equal(SourceFile_.filePath, task.filePath,
                        io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                .build().findFirst();

        if (existing != null && currentHash.equals(existing.fileHash) && existing.isIndexed) {
            Log.d(TAG, "Unchanged, skipping: " + task.filePath);
            return;
        }

        Log.i(TAG, "Indexing: " + task.filePath);

        // --- Step 3: Extract text ---
        String text = TextExtractor.extract(file);
        if (text == null || text.trim().isEmpty()) {
            Log.w(TAG, "No extractable text in: " + task.filePath);
            return;
        }

        // --- Step 4: Chunk ---
        List<Chunk> chunks = ChunkingStrategy.getInstance().chunkText(text, task.filePath);
        if (chunks.isEmpty()) {
            Log.w(TAG, "No chunks produced from: " + task.filePath);
            return;
        }

        // --- Upsert SourceFile entity ---
        if (existing == null) {
            existing = new SourceFile(
                    task.filePath,
                    file.getName(),
                    resolveMimeType(file.getName()),
                    file.length(),
                    currentHash);
        } else {
            existing.fileHash       = currentHash;
            existing.lastModifiedAt = System.currentTimeMillis();
        }
        // Save first so we have a valid id for relations
        fileBox.put(existing);

        io.objectbox.Box<DocumentChunk> chunkBox = JarvisStore.box(DocumentChunk.class);

        // --- Step 5+6: Embed each chunk, persist DocumentChunk ---
        for (Chunk chunk : chunks) {
            if (sModelHandle == 0L || sIndexHandle == 0L) {
                Log.w(TAG, "Cactus handles not ready — storing chunk without embedding");
                // Still persist the chunk so text is at least searchable via metadata
                DocumentChunk entity = new DocumentChunk(
                        chunk.getPosition(), truncate(chunk.getText(), 200),
                        chunk.estimateTokenCount(), System.currentTimeMillis());
                entity.sourceFile.setTarget(existing);
                chunkBox.put(entity);
                continue;
            }

            float[] embedding = CactusWrapper.embed(sModelHandle, chunk.getText());
            if (embedding == null) {
                Log.w(TAG, "Embed failed for chunk " + chunk.getPosition()
                        + " of " + task.filePath);
                continue;
            }

            int cactusId = (int) (existing.id * 10000 + chunk.getPosition());
            int addResult = CactusWrapper.indexAdd(
                    sIndexHandle, cactusId, chunk.getText(), null, embedding);

            if (addResult != 0) {
                Log.w(TAG, "indexAdd failed for chunk " + chunk.getPosition());
            }

            DocumentChunk entity = new DocumentChunk(
                    chunk.getPosition(),
                    truncate(chunk.getText(), 200),
                    chunk.estimateTokenCount(),
                    System.currentTimeMillis());
            entity.cactusIndexId   = cactusId;
            entity.embeddingRetained = (addResult == 0);
            entity.sourceFile.setTarget(existing);
            chunkBox.put(entity);
        }

        // --- Step 7: Mark file indexed ---
        existing.isIndexed      = true;
        existing.fileHash       = currentHash;
        existing.lastModifiedAt = System.currentTimeMillis();
        fileBox.put(existing);

        Log.i(TAG, "Indexed " + chunks.size() + " chunks from: " + task.filePath);
    }

    // -------------------------------------------------------------------------
    // REMOVE task
    // -------------------------------------------------------------------------

    private void processRemoveTask(JarvisFileObserver.IndexTask task) {
        Log.i(TAG, "Removing from index: " + task.filePath);

        io.objectbox.Box<SourceFile> fileBox  = JarvisStore.box(SourceFile.class);
        io.objectbox.Box<DocumentChunk> chunkBox = JarvisStore.box(DocumentChunk.class);

        SourceFile sf = fileBox.query()
                .equal(SourceFile_.filePath, task.filePath,
                        io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                .build().findFirst();

        if (sf == null) {
            Log.d(TAG, "Not in index, nothing to remove: " + task.filePath);
            return;
        }

        // Delete embeddings from Cactus index
        if (sIndexHandle != 0L) {
            List<DocumentChunk> chunks = sf.chunks.getAll();
            int[] ids = new int[chunks.size()];
            for (int i = 0; i < chunks.size(); i++) {
                ids[i] = chunks.get(i).cactusIndexId;
            }
            if (ids.length > 0) {
                CactusWrapper.indexDelete(sIndexHandle, ids);
            }
            // Delete DocumentChunk entities
            for (DocumentChunk c : chunks) {
                chunkBox.remove(c.id);
            }
        }

        fileBox.remove(sf.id);
        Log.i(TAG, "Removed: " + task.filePath);
    }

    // -------------------------------------------------------------------------
    // Cactus handle management
    // -------------------------------------------------------------------------

    private static void ensureHandles() {
        if (sModelHandle != 0L && sIndexHandle != 0L) return;
        synchronized (sHandleLock) {
            if (sModelHandle == 0L) {
                sModelHandle = CactusWrapper.init(MODEL_PATH, null, true);
                if (sModelHandle == 0L) {
                    Log.w(TAG, "Cactus model not ready — indexing will skip embeddings");
                }
            }
            if (sIndexHandle == 0L && sModelHandle != 0L) {
                new File(INDEX_DIR).mkdirs();
                sIndexHandle = CactusWrapper.indexInit(INDEX_DIR, EMBED_DIM);
                if (sIndexHandle == 0L) {
                    Log.w(TAG, "Cactus index not ready — indexing will skip embeddings");
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String hashFile(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer        = new byte[8192];
        try (FileInputStream fis = new FileInputStream(file)) {
            int n;
            while ((n = fis.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String resolveMimeType(String fileName) {
        if (fileName.endsWith(".pdf"))  return "application/pdf";
        if (fileName.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (fileName.endsWith(".txt"))  return "text/plain";
        if (fileName.endsWith(".md"))   return "text/markdown";
        if (fileName.endsWith(".csv"))  return "text/csv";
        return "application/octet-stream";
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }

    // -------------------------------------------------------------------------
    // Schedule
    // -------------------------------------------------------------------------

    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiresCharging(true)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                RagIndexWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);

        Log.i(TAG, "Scheduled (charging, 15min)");
    }
}
