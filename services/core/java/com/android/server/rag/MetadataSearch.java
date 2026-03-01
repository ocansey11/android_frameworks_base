package com.android.server.rag;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stage 1 retrieval — metadata search.
 *
 * Searches ObjectBox entities using cheap string operations before
 * touching Cactus embeddings. Narrows thousands of files down to
 * a small shortlist that Stage 2 (cactus_embed + cactus_index_query)
 * will re-rank semantically.
 *
 * Search order (highest signal first):
 *   1. userAlias     — what the user calls the file ("my biology notes")
 *   2. tags          — user/assistant assigned labels
 *   3. fileName      — actual filename on disk
 *   4. Folder.summary — per-directory breadcrumb
 *   5. TaskMemory    — what the assistant did before with similar queries
 *   6. AccessLog     — files accessed most in similar conversations (frequency boost)
 *
 * Returns a ranked list of SourceFile candidates, capped at maxResults.
 * ObjectBox queries are TODO — structure is in place for when the Box is wired up.
 */
public class MetadataSearch {

    private static final String TAG = "MetadataSearch";
    private static final int DEFAULT_MAX_RESULTS = 20;

    /**
     * Main entry point. Called by RagService.processQuery() before Stage 2.
     */
    public static List<SourceFile> search(String query, int maxResults) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String normalised = query.toLowerCase().trim();
        List<ScoredFile> scored = new ArrayList<>();

        // TODO: get ObjectBox Box<SourceFile> from store
        // Box<SourceFile> box = JarvisStore.get().boxFor(SourceFile.class);

        // --- Pass 1: userAlias match (highest signal) ---
        // TODO: List<SourceFile> aliasMatches = box.query()
        //     .contains(SourceFile_.userAlias, normalised)
        //     .build().find();
        // for (SourceFile f : aliasMatches) scored.add(new ScoredFile(f, 3.0));

        // --- Pass 2: tag match ---
        // TODO: List<SourceFile> tagMatches = box.query()
        //     .contains(SourceFile_.tags, normalised)
        //     .build().find();
        // for (SourceFile f : tagMatches) addOrBoost(scored, f, 2.0);

        // --- Pass 3: fileName match ---
        // TODO: List<SourceFile> nameMatches = box.query()
        //     .contains(SourceFile_.fileName, normalised)
        //     .build().find();
        // for (SourceFile f : nameMatches) addOrBoost(scored, f, 1.5);

        // --- Pass 4: Folder summary match ---
        // TODO: Box<Folder> folderBox = JarvisStore.get().boxFor(Folder.class);
        // List<Folder> folderMatches = folderBox.query()
        //     .contains(Folder_.summary, normalised)
        //     .build().find();
        // for (Folder folder : folderMatches) {
        //     for (SourceFile f : folder.files) addOrBoost(scored, f, 1.0);
        // }

        // --- Pass 5: TaskMemory match — reuse past approaches ---
        // TODO: Box<TaskMemory> taskBox = JarvisStore.get().boxFor(TaskMemory.class);
        // List<TaskMemory> taskMatches = taskBox.query()
        //     .contains(TaskMemory_.taskDescription, normalised)
        //     .build().find();
        // for (TaskMemory tm : taskMatches) {
        //     for (String idStr : tm.fileIdsUsed.split(",")) {
        //         SourceFile f = box.get(Long.parseLong(idStr.trim()));
        //         if (f != null) addOrBoost(scored, f, 0.8);
        //     }
        // }

        // --- Pass 6: AccessLog frequency boost ---
        // TODO: for (ScoredFile sf : scored) {
        //     long accessCount = accessLogBox.query()
        //         .equal(AccessLog_.fileId, sf.file.id)
        //         .equal(AccessLog_.wasHelpful, true)
        //         .build().count();
        //     sf.score += accessCount * 0.2; // small bump per helpful access
        // }

        // Sort by score descending
        Collections.sort(scored, (a, b) -> Double.compare(b.score, a.score));

        // Extract top results
        List<SourceFile> results = new ArrayList<>();
        int limit = Math.min(maxResults, scored.size());
        for (int i = 0; i < limit; i++) {
            results.add(scored.get(i).file);
        }

        Log.d(TAG, "Metadata search for \"" + query + "\" returned " + results.size() + " candidates");
        return results;
    }

    public static List<SourceFile> search(String query) {
        return search(query, DEFAULT_MAX_RESULTS);
    }

    // Helper to avoid duplicates — boost score if file already in list
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
            this.file = file;
            this.score = score;
        }
    }
}
