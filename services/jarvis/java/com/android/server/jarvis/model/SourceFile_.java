package com.android.server.jarvis.model;

import io.objectbox.EntityInfo;
import io.objectbox.Property;
import io.objectbox.internal.CursorFactory;
import io.objectbox.internal.IdGetter;

/**
 * ObjectBox entity meta-info for SourceFile.
 * Hand-written stub — see AppRecord_ for generation instructions.
 */
public final class SourceFile_ implements EntityInfo<SourceFile> {

    public static final String __ENTITY_NAME = "SourceFile";
    public static final int    __ENTITY_ID   = 3;

    public static final SourceFile_ INSTANCE = new SourceFile_();

    public static final Property<SourceFile> id;
    public static final Property<SourceFile> filePath;
    public static final Property<SourceFile> fileName;
    public static final Property<SourceFile> userAlias;
    public static final Property<SourceFile> tags;
    public static final Property<SourceFile> mimeType;
    public static final Property<SourceFile> fileSizeBytes;
    public static final Property<SourceFile> fileHash;
    public static final Property<SourceFile> isIndexed;
    public static final Property<SourceFile> createdAt;
    public static final Property<SourceFile> lastModifiedAt;
    public static final Property<SourceFile> lastAccessedAt;

    @SuppressWarnings("unchecked")
    private static final Property<SourceFile>[] ALL_PROPERTIES;

    static {
        id             = new Property<>(INSTANCE,  0, 6 /* Long */,   long.class,    "id",             true,  "__id");
        filePath       = new Property<>(INSTANCE,  1, 9 /* String */, String.class,  "filePath",       false, "filePath");
        fileName       = new Property<>(INSTANCE,  2, 9 /* String */, String.class,  "fileName",       false, "fileName");
        userAlias      = new Property<>(INSTANCE,  3, 9 /* String */, String.class,  "userAlias",      false, "userAlias");
        tags           = new Property<>(INSTANCE,  4, 9 /* String */, String.class,  "tags",           false, "tags");
        mimeType       = new Property<>(INSTANCE,  5, 9 /* String */, String.class,  "mimeType",       false, "mimeType");
        fileSizeBytes  = new Property<>(INSTANCE,  6, 6 /* Long */,   long.class,    "fileSizeBytes",  false, "fileSizeBytes");
        fileHash       = new Property<>(INSTANCE,  7, 9 /* String */, String.class,  "fileHash",       false, "fileHash");
        isIndexed      = new Property<>(INSTANCE,  8, 1 /* Bool */,   boolean.class, "isIndexed",      false, "isIndexed");
        createdAt      = new Property<>(INSTANCE,  9, 6 /* Long */,   long.class,    "createdAt",      false, "createdAt");
        lastModifiedAt = new Property<>(INSTANCE, 10, 6 /* Long */,   long.class,    "lastModifiedAt", false, "lastModifiedAt");
        lastAccessedAt = new Property<>(INSTANCE, 11, 6 /* Long */,   long.class,    "lastAccessedAt", false, "lastAccessedAt");
        ALL_PROPERTIES = new Property[]{id, filePath, fileName, userAlias, tags, mimeType,
                fileSizeBytes, fileHash, isIndexed, createdAt, lastModifiedAt, lastAccessedAt};
    }

    private SourceFile_() {}

    @Override public String getEntityName()    { return __ENTITY_NAME; }
    @Override public String getDbName()        { return __ENTITY_NAME; }
    @Override public Class<SourceFile> getEntityClass() { return SourceFile.class; }
    @Override public int getEntityId()         { return __ENTITY_ID; }

    @Override
    @SuppressWarnings("unchecked")
    public Property<SourceFile>[] getAllProperties() { return ALL_PROPERTIES; }

    @Override public Property<SourceFile> getIdProperty() { return id; }

    @Override
    public IdGetter<SourceFile> getIdGetter() {
        return entity -> entity.id;
    }

    @Override
    public CursorFactory<SourceFile> getCursorFactory() {
        throw new UnsupportedOperationException(
                "SourceFile cursor factory not generated. "
                + "Run objectbox-processor to produce SourceFileCursor.");
    }
}
