package com.android.server.jarvis.model;

import io.objectbox.EntityInfo;
import io.objectbox.Property;
import io.objectbox.internal.CursorFactory;
import io.objectbox.internal.IdGetter;

/**
 * ObjectBox entity meta-info for Folder.
 * Hand-written stub — see AppRecord_ for generation instructions.
 */
public final class Folder_ implements EntityInfo<Folder> {

    public static final String __ENTITY_NAME = "Folder";
    public static final int    __ENTITY_ID   = 4;

    public static final Folder_ INSTANCE = new Folder_();

    public static final Property<Folder> id;
    public static final Property<Folder> folderPath;
    public static final Property<Folder> displayName;
    public static final Property<Folder> tags;
    public static final Property<Folder> summary;
    public static final Property<Folder> fileCount;
    public static final Property<Folder> lastUpdatedAt;

    @SuppressWarnings("unchecked")
    private static final Property<Folder>[] ALL_PROPERTIES;

    static {
        id            = new Property<>(INSTANCE, 0, 6 /* Long */,   long.class,   "id",            true,  "__id");
        folderPath    = new Property<>(INSTANCE, 1, 9 /* String */, String.class, "folderPath",    false, "folderPath");
        displayName   = new Property<>(INSTANCE, 2, 9 /* String */, String.class, "displayName",   false, "displayName");
        tags          = new Property<>(INSTANCE, 3, 9 /* String */, String.class, "tags",          false, "tags");
        summary       = new Property<>(INSTANCE, 4, 9 /* String */, String.class, "summary",       false, "summary");
        fileCount     = new Property<>(INSTANCE, 5, 5 /* Int */,    int.class,    "fileCount",     false, "fileCount");
        lastUpdatedAt = new Property<>(INSTANCE, 6, 6 /* Long */,   long.class,   "lastUpdatedAt", false, "lastUpdatedAt");
        ALL_PROPERTIES = new Property[]{id, folderPath, displayName, tags, summary, fileCount, lastUpdatedAt};
    }

    private Folder_() {}

    @Override public String getEntityName()  { return __ENTITY_NAME; }
    @Override public String getDbName()      { return __ENTITY_NAME; }
    @Override public Class<Folder> getEntityClass() { return Folder.class; }
    @Override public int getEntityId()       { return __ENTITY_ID; }

    @Override
    @SuppressWarnings("unchecked")
    public Property<Folder>[] getAllProperties() { return ALL_PROPERTIES; }

    @Override public Property<Folder> getIdProperty() { return id; }

    @Override
    public IdGetter<Folder> getIdGetter() {
        return entity -> entity.id;
    }

    @Override
    public CursorFactory<Folder> getCursorFactory() {
        throw new UnsupportedOperationException(
                "Folder cursor factory not generated. "
                + "Run objectbox-processor to produce FolderCursor.");
    }
}
