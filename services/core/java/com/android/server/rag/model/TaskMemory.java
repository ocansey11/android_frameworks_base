package com.android.server.rag.model;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;

/**
 * What the assistant did and how — retained after task completion.
 * Embeddings are discarded after task. Only the pattern is kept.
 * Allows future tasks to reuse known approaches without re-embedding.
 */
@Entity
public class TaskMemory {
    @Id
    public long id;

    public String taskDescription;    // what was asked
    public String approach;           // how it was handled
    public String fileIdsUsed;        // comma-separated SourceFile IDs referenced
    public boolean embeddingsDiscarded; // true once Cactus index entries are cleaned up
    public long completedAt;

    public TaskMemory() {}

    public TaskMemory(String taskDescription, String approach, String fileIdsUsed) {
        this.taskDescription = taskDescription;
        this.approach = approach;
        this.fileIdsUsed = fileIdsUsed;
        this.embeddingsDiscarded = false;
        this.completedAt = System.currentTimeMillis();
    }
}
