package com.android.server.jarvis.model;

import io.objectbox.BoxStore;
import io.objectbox.annotation.Backlink;
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

    /** Set by the generated cursor; the relation fields below need it to resolve their targets. */
    transient BoxStore __boxStore;

    public String folderPath;
    public String displayName;
    public String tags;           // comma-separated tags
    public String summary;        // e.g. "Contains Kevin's university notes from 2024"
    public int fileCount;
    public long lastUpdatedAt;

    // Relations
    @Backlink(to = "folder")
    public ToMany<SourceFile> files = new ToMany<>(this, Folder_.files);
    public ToOne<Folder> parentFolder = new ToOne<>(this, Folder_.parentFolder);
    @Backlink(to = "parentFolder")
    public ToMany<Folder> subFolders = new ToMany<>(this, Folder_.subFolders);

    public Folder() {}

    public Folder(String folderPath, String displayName) {
        this.folderPath = folderPath;
        this.displayName = displayName;
        this.lastUpdatedAt = System.currentTimeMillis();
    }
}
