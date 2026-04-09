package com.android.server.jarvis.model;

import io.objectbox.EntityInfo;
import io.objectbox.Property;
import io.objectbox.internal.CursorFactory;
import io.objectbox.internal.IdGetter;

/**
 * ObjectBox entity meta-info for DocumentChunk.
 * Hand-written stub — replace with processor-generated version when
 * objectbox-processor is wired into Android.bp.
 */
public final class DocumentChunk_ implements EntityInfo<DocumentChunk> {

    public static final String __ENTITY_NAME = "DocumentChunk";
    public static final int    __ENTITY_ID   = 10;

    public static final DocumentChunk_ INSTANCE = new DocumentChunk_();

    public static final Property<DocumentChunk> id;
    public static final Property<DocumentChunk> cactusIndexId;
    public static final Property<DocumentChunk> chunkIndex;
    public static final Property<DocumentChunk> summary;
    public static final Property<DocumentChunk> tokenCount;
    public static final Property<DocumentChunk> embeddedAt;
    public static final Property<DocumentChunk> embeddingRetained;

    @SuppressWarnings("unchecked")
    private static final Property<DocumentChunk>[] ALL_PROPERTIES;

    static {
        id               = new Property<>(INSTANCE, 0, 6 /* Long */,    long.class,    "id",               true,  "__id");
        cactusIndexId    = new Property<>(INSTANCE, 1, 5 /* Int */,     int.class,     "cactusIndexId",    false, "cactusIndexId");
        chunkIndex       = new Property<>(INSTANCE, 2, 5 /* Int */,     int.class,     "chunkIndex",       false, "chunkIndex");
        summary          = new Property<>(INSTANCE, 3, 9 /* String */,  String.class,  "summary",          false, "summary");
        tokenCount       = new Property<>(INSTANCE, 4, 5 /* Int */,     int.class,     "tokenCount",       false, "tokenCount");
        embeddedAt       = new Property<>(INSTANCE, 5, 6 /* Long */,    long.class,    "embeddedAt",       false, "embeddedAt");
        embeddingRetained = new Property<>(INSTANCE, 6, 1 /* Boolean */, boolean.class, "embeddingRetained", false, "embeddingRetained");
        ALL_PROPERTIES   = new Property[]{id, cactusIndexId, chunkIndex, summary,
                tokenCount, embeddedAt, embeddingRetained};
    }

    private DocumentChunk_() {}

    @Override public String getEntityName()           { return __ENTITY_NAME; }
    @Override public String getDbName()               { return __ENTITY_NAME; }
    @Override public Class<DocumentChunk> getEntityClass() { return DocumentChunk.class; }
    @Override public int getEntityId()                { return __ENTITY_ID; }

    @Override
    @SuppressWarnings("unchecked")
    public Property<DocumentChunk>[] getAllProperties() { return ALL_PROPERTIES; }

    @Override public Property<DocumentChunk> getIdProperty() { return id; }

    @Override
    public IdGetter<DocumentChunk> getIdGetter() {
        return entity -> entity.id;
    }

    @Override
    public CursorFactory<DocumentChunk> getCursorFactory() {
        throw new UnsupportedOperationException(
                "DocumentChunk cursor factory not generated. "
                + "Run objectbox-processor to produce DocumentChunkCursor.");
    }
}
