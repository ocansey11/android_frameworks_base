package com.android.server.jarvis.model;

import io.objectbox.EntityInfo;
import io.objectbox.Property;
import io.objectbox.internal.CursorFactory;
import io.objectbox.internal.IdGetter;

/**
 * ObjectBox entity meta-info for AccessLog.
 * Hand-written stub — see AppRecord_ for generation instructions.
 */
public final class AccessLog_ implements EntityInfo<AccessLog> {

    public static final String __ENTITY_NAME = "AccessLog";
    public static final int    __ENTITY_ID   = 5;

    public static final AccessLog_ INSTANCE = new AccessLog_();

    public static final Property<AccessLog> id;
    public static final Property<AccessLog> fileId;
    public static final Property<AccessLog> conversationId;
    public static final Property<AccessLog> accessedAt;
    public static final Property<AccessLog> wasHelpful;

    @SuppressWarnings("unchecked")
    private static final Property<AccessLog>[] ALL_PROPERTIES;

    static {
        id             = new Property<>(INSTANCE, 0, 6 /* Long */, long.class,    "id",             true,  "__id");
        fileId         = new Property<>(INSTANCE, 1, 6 /* Long */, long.class,    "fileId",         false, "fileId");
        conversationId = new Property<>(INSTANCE, 2, 6 /* Long */, long.class,    "conversationId", false, "conversationId");
        accessedAt     = new Property<>(INSTANCE, 3, 6 /* Long */, long.class,    "accessedAt",     false, "accessedAt");
        wasHelpful     = new Property<>(INSTANCE, 4, 1 /* Bool */, boolean.class, "wasHelpful",     false, "wasHelpful");
        ALL_PROPERTIES = new Property[]{id, fileId, conversationId, accessedAt, wasHelpful};
    }

    private AccessLog_() {}

    @Override public String getEntityName()    { return __ENTITY_NAME; }
    @Override public String getDbName()        { return __ENTITY_NAME; }
    @Override public Class<AccessLog> getEntityClass() { return AccessLog.class; }
    @Override public int getEntityId()         { return __ENTITY_ID; }

    @Override
    @SuppressWarnings("unchecked")
    public Property<AccessLog>[] getAllProperties() { return ALL_PROPERTIES; }

    @Override public Property<AccessLog> getIdProperty() { return id; }

    @Override
    public IdGetter<AccessLog> getIdGetter() {
        return entity -> entity.id;
    }

    @Override
    public CursorFactory<AccessLog> getCursorFactory() {
        throw new UnsupportedOperationException(
                "AccessLog cursor factory not generated. "
                + "Run objectbox-processor to produce AccessLogCursor.");
    }
}
