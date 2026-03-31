package com.android.server.rag.model;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.relation.ToOne;
import io.objectbox.relation.ToMany;

/**
 * Per-directory metadata — the breadcrumb layer.
 * Each folder gets a lightweight summary of its contents.
 * No embeddings stored here.
 */
@Entity
public class Folder {
    @Id
    public long id;

    public String folderPath;
    public String displayName;
    public String tags;           // comma-separated tags
    public String summary;        // e.g. "Contains Kevin's university notes from 2024"
    public int fileCount;
    public long lastUpdatedAt;

    // Relations
    public ToMany<SourceFile> files;
    public ToOne<Folder> parentFolder;
    public ToMany<Folder> subFolders;

    public Folder() {}

    public Folder(String folderPath, String displayName) {
        this.folderPath = folderPath;
        this.displayName = displayName;
        this.lastUpdatedAt = System.currentTimeMillis();
    }
}
