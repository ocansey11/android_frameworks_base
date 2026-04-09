package com.android.server.jarvis.model;

import io.objectbox.EntityInfo;
import io.objectbox.Property;
import io.objectbox.internal.CursorFactory;
import io.objectbox.internal.IdGetter;

/**
 * ObjectBox entity meta-info for UserContext.
 * Hand-written stub — replace with processor-generated version when
 * objectbox-processor is wired into Android.bp.
 */
public final class UserContext_ implements EntityInfo<UserContext> {

    public static final String __ENTITY_NAME = "UserContext";
    public static final int    __ENTITY_ID   = 9;

    public static final UserContext_ INSTANCE = new UserContext_();

    public static final Property<UserContext> id;
    public static final Property<UserContext> preferredName;
    public static final Property<UserContext> interests;
    public static final Property<UserContext> frequentFolders;
    public static final Property<UserContext> lastActiveAt;
    public static final Property<UserContext> deviceLocale;
    public static final Property<UserContext> facts;
    public static final Property<UserContext> consolidatedAt;

    @SuppressWarnings("unchecked")
    private static final Property<UserContext>[] ALL_PROPERTIES;

    static {
        id              = new Property<>(INSTANCE, 0, 6 /* Long */,   long.class,   "id",             true,  "__id");
        preferredName   = new Property<>(INSTANCE, 1, 9 /* String */, String.class, "preferredName",  false, "preferredName");
        interests       = new Property<>(INSTANCE, 2, 9 /* String */, String.class, "interests",      false, "interests");
        frequentFolders = new Property<>(INSTANCE, 3, 9 /* String */, String.class, "frequentFolders",false, "frequentFolders");
        lastActiveAt    = new Property<>(INSTANCE, 4, 6 /* Long */,   long.class,   "lastActiveAt",   false, "lastActiveAt");
        deviceLocale    = new Property<>(INSTANCE, 5, 9 /* String */, String.class, "deviceLocale",   false, "deviceLocale");
        facts           = new Property<>(INSTANCE, 6, 9 /* String */, String.class, "facts",          false, "facts");
        consolidatedAt  = new Property<>(INSTANCE, 7, 6 /* Long */,   long.class,   "consolidatedAt", false, "consolidatedAt");
        ALL_PROPERTIES  = new Property[]{id, preferredName, interests, frequentFolders,
                lastActiveAt, deviceLocale, facts, consolidatedAt};
    }

    private UserContext_() {}

    @Override public String getEntityName()       { return __ENTITY_NAME; }
    @Override public String getDbName()           { return __ENTITY_NAME; }
    @Override public Class<UserContext> getEntityClass() { return UserContext.class; }
    @Override public int getEntityId()            { return __ENTITY_ID; }

    @Override
    @SuppressWarnings("unchecked")
    public Property<UserContext>[] getAllProperties() { return ALL_PROPERTIES; }

    @Override public Property<UserContext> getIdProperty() { return id; }

    @Override
    public IdGetter<UserContext> getIdGetter() {
        return entity -> entity.id;
    }

    @Override
    public CursorFactory<UserContext> getCursorFactory() {
        throw new UnsupportedOperationException(
                "UserContext cursor factory not generated. "
                + "Run objectbox-processor to produce UserContextCursor.");
    }
}
