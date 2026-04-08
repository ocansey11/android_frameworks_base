package com.android.server.jarvis.search;

import android.util.Log;

import com.android.server.jarvis.core.JarvisStore;
import com.android.server.jarvis.model.AccessLog;
import com.android.server.jarvis.model.Folder;
import com.android.server.jarvis.model.SourceFile;
import com.android.server.jarvis.model.TaskMemory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stage 1 retrieval — metadata search.
 *
 * Searches ObjectBox entities using cheap string operations BEFORE
 * touching Cactus embeddings. Narrows thousands of files down to a
 * small shortlist that Stage 2 (cactus_embed + cactus_index_query)
 * will re-rank semantically.
 *
 * Search order (highest signal → lowest):
 *   1. userAlias     — what the user calls the file ("my biology notes")   score +3.0
 *   2. tags          — user/assistant assigned labels                       score +2.0
 *   3. fileName      — actual filename on disk                              score +1.5
 *   4. Folder.summary — per-directory breadcrumb                           score +1.0
 *   5. TaskMemory    — what the assistant did before with similar queries   score +0.8
 *   6. AccessLog     — frequency boost for files that were helpful before  score +0.2/access
 *
 * Duplicate files are merged — their scores accumulate across passes.
 * Results are capped at maxResults and sorted by score descending.
 */
public class MetadataSearch {

    private static final String TAG = "MetadataSearch";
    private static final int DEFAULT_MAX_RESULTS = 20;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static List<SourceFile> search(String query) {
        return search(query, DEFAULT_MAX_RESULTS);
    }

    public static List<SourceFile> search(String query, int maxResults) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String q = query.toLowerCase().trim();
        List<ScoredFile> scored = new ArrayList<>();

        try {
            io.objectbox.Box<SourceFile> fileBox =
                    JarvisStore.box(SourceFile.class);
            io.objectbox.Box<Folder> folderBox =
                    JarvisStore.box(Folder.class);
            io.objectbox.Box<TaskMemory> taskBox =
                    JarvisStore.box(TaskMemory.class);
            io.objectbox.Box<AccessLog> accessBox =
                    JarvisStore.box(AccessLog.class);

            // --- Pass 1: userAlias match (highest signal) ---
            List<SourceFile> aliasMatches = fileBox.query()
                    .contains(SourceFile_.userAlias, q,
                            io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                    .build().find();
            for (SourceFile f : aliasMatches) addOrBoost(scored, f, 3.0);

            // --- Pass 2: tag match ---
            List<SourceFile> tagMatches = fileBox.query()
                    .contains(SourceFile_.tags, q,
                            io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                    .build().find();
            for (SourceFile f : tagMatches) addOrBoost(scored, f, 2.0);

            // --- Pass 3: fileName match ---
            List<SourceFile> nameMatches = fileBox.query()
                    .contains(SourceFile_.fileName, q,
                            io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                    .build().find();
            for (SourceFile f : nameMatches) addOrBoost(scored, f, 1.5);

            // --- Pass 4: Folder summary match ---
            List<Folder> folderMatches = folderBox.query()
                    .contains(Folder_.summary, q,
                            io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                    .build().find();
            for (Folder folder : folderMatches) {
                for (SourceFile f : folder.files) addOrBoost(scored, f, 1.0);
            }

            // --- Pass 5: TaskMemory match — reuse past approaches ---
            List<TaskMemory> taskMatches = taskBox.query()
                    .contains(TaskMemory_.taskDescription, q,
                            io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                    .build().find();
            for (TaskMemory tm : taskMatches) {
                if (tm.fileIdsUsed == null || tm.fileIdsUsed.isEmpty()) continue;
                for (String idStr : tm.fileIdsUsed.split(",")) {
                    try {
                        SourceFile f = fileBox.get(Long.parseLong(idStr.trim()));
                        if (f != null) addOrBoost(scored, f, 0.8);
                    } catch (NumberFormatException ignored) {}
                }
            }

            // --- Pass 6: AccessLog frequency boost ---
            if (!scored.isEmpty()) {
                long[] fileIds = new long[scored.size()];
                for (int i = 0; i < scored.size(); i++) fileIds[i] = scored.get(i).file.id;

                List<AccessLog> helpfulLogs = accessBox.query()
                        .in(AccessLog_.fileId, fileIds)
                        .equal(AccessLog_.wasHelpful, true)
                        .build().find();

                java.util.Map<Long, Integer> hitCount = new java.util.HashMap<>();
                for (AccessLog log : helpfulLogs) {
                    hitCount.put(log.fileId, hitCount.getOrDefault(log.fileId, 0) + 1);
                }
                for (ScoredFile sf : scored) {
                    Integer count = hitCount.get(sf.file.id);
                    if (count != null) sf.score += count * 0.2;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "MetadataSearch failed: " + e.getMessage(), e);
            return Collections.emptyList();
        }

        // Sort by score descending
        Collections.sort(scored, (a, b) -> Double.compare(b.score, a.score));

        List<SourceFile> results = new ArrayList<>();
        int limit = Math.min(maxResults, scored.size());
        for (int i = 0; i < limit; i++) {
            results.add(scored.get(i).file);
        }

        Log.d(TAG, "search(\"" + query + "\") → " + results.size() + " candidates");
        return results;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Add file with score, or boost score if already present. */
    private static void addOrBoost(List<ScoredFile> list, SourceFile file, double boost) {
        for (ScoredFile sf : list) {
            if (sf.file.id == file.id) {
                sf.score += boost;
                return;
            }
        }
        list.add(new ScoredFile(file, boost));
    }

    private static class ScoredFile {
        final SourceFile file;
        double score;

        ScoredFile(SourceFile file, double score) {
            this.file  = file;
            this.score = score;
        }
    }
}
