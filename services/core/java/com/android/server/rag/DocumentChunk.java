package com.android.server.rag;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.relation.ToOne;

/**
 * A chunk of a SourceFile.
 * Stores a compressed summary — NOT the full content.
 * Full content stays in the original file.
 * Cactus holds the actual embedding in its binary index.
 */
@Entity
public class DocumentChunk {
    @Id
    public long id;

    public int cactusIndexId;       // pointer into Cactus binary index (index.bin/data.bin)
    public int chunkIndex;          // position in the source document
    public String summary;          // 2-3 sentence compressed summary
    public int tokenCount;
    public long embeddedAt;
    public boolean embeddingRetained; // false = Cactus index entry deleted after task completed

    // Relation
    public ToOne<SourceFile> sourceFile;

    public DocumentChunk() {}

    public DocumentChunk(int chunkIndex, String summary, int tokenCount, long embeddedAt) {
        this.chunkIndex = chunkIndex;
        this.summary = summary;
        this.tokenCount = tokenCount;
        this.embeddedAt = embeddedAt;
        this.embeddingRetained = true;
    }
}
