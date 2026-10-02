package com.android.server.jarvis.model;

import io.objectbox.BoxStore;
import io.objectbox.annotation.Backlink;
import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.relation.ToOne;
import io.objectbox.relation.ToMany;

/**
 * Represents any file on the device JarvisOS is aware of.
 * No content stored — just a pointer and metadata.
 * JarvisOS never renames files without user permission.
 * Aliases and tags handle how the user refers to files.
 */
@Entity
public class SourceFile {
    @Id
    public long id;

    /** Set by the generated cursor; the relation fields below need it to resolve their targets. */
    transient BoxStore __boxStore;

    public String filePath;
    public String fileName;
    public String userAlias;      // what the user calls this file e.g. "my biology notes"
    public String tags;           // comma-separated tags e.g. "university,2024"
    public String mimeType;
    public long fileSizeBytes;
    public String fileHash;       // SHA-256 to detect changes, avoid re-embedding
    public boolean isIndexed;
    public long createdAt;
    public long lastModifiedAt;
    public long lastAccessedAt;

    // Relations
    public ToOne<Folder> folder = new ToOne<>(this, SourceFile_.folder);
    @Backlink(to = "sourceFile")
    public ToMany<DocumentChunk> chunks = new ToMany<>(this, SourceFile_.chunks);
    @Backlink(to = "referencedFiles")
    public ToMany<Conversation> conversations = new ToMany<>(this, SourceFile_.conversations);

    public SourceFile() {}

    public SourceFile(String filePath, String fileName, String mimeType, long fileSizeBytes, String fileHash) {
        this.filePath = filePath;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.fileSizeBytes = fileSizeBytes;
        this.fileHash = fileHash;
        this.isIndexed = false;
        this.createdAt = System.currentTimeMillis();
        this.lastModifiedAt = this.createdAt;
        this.lastAccessedAt = this.createdAt;
    }
}
