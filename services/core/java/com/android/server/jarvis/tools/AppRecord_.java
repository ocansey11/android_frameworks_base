package com.android.server.jarvis.tools;

import io.objectbox.EntityInfo;
import io.objectbox.Property;
import io.objectbox.internal.CursorFactory;
import io.objectbox.internal.IdGetter;

/**
 * ObjectBox entity meta-info for AppRecord.
 *
 * Hand-written stub — replace with processor-generated version.
 * To generate properly: add objectbox-processor + objectbox-generator JARs
 * to vendor/jarvisos/prebuilts/objectbox/ and wire as java_plugin in Android.bp.
 *
 * getCursorFactory() throws UnsupportedOperationException — store initializes
 * but box reads/writes will fail until this is properly generated.
 */
public final class AppRecord_ implements EntityInfo<AppRecord> {

    public static final String __ENTITY_NAME = "AppRecord";
    public static final int    __ENTITY_ID   = 1;

    // Singleton — Properties reference this instance; avoids circular init.
    public static final AppRecord_ INSTANCE = new AppRecord_();

    public static final Property<AppRecord> id;
    public static final Property<AppRecord> packageName;
    public static final Property<AppRecord> appLabel;
    public static final Property<AppRecord> sourceType;
    public static final Property<AppRecord> lastScanTime;
    public static final Property<AppRecord> isActive;

    @SuppressWarnings("unchecked")
    private static final Property<AppRecord>[] ALL_PROPERTIES;

    static {
        id           = new Property<>(INSTANCE, 0, 6 /* Long */,   long.class,    "id",           true,  "__id");
        packageName  = new Property<>(INSTANCE, 1, 9 /* String */, String.class,  "packageName",  false, "packageName");
        appLabel     = new Property<>(INSTANCE, 2, 9 /* String */, String.class,  "appLabel",     false, "appLabel");
        sourceType   = new Property<>(INSTANCE, 3, 9 /* String */, String.class,  "sourceType",   false, "sourceType");
        lastScanTime = new Property<>(INSTANCE, 4, 6 /* Long */,   long.class,    "lastScanTime", false, "lastScanTime");
        isActive     = new Property<>(INSTANCE, 5, 1 /* Bool */,   boolean.class, "isActive",     false, "isActive");
        ALL_PROPERTIES = new Property[]{id, packageName, appLabel, sourceType, lastScanTime, isActive};
    }

    private AppRecord_() {}

    @Override public String getEntityName()  { return __ENTITY_NAME; }
    @Override public String getDbName()      { return __ENTITY_NAME; }
    @Override public Class<AppRecord> getEntityClass() { return AppRecord.class; }
    @Override public int getEntityId()       { return __ENTITY_ID; }

    @Override
    @SuppressWarnings("unchecked")
    public Property<AppRecord>[] getAllProperties() { return ALL_PROPERTIES; }

    @Override public Property<AppRecord> getIdProperty() { return id; }

    @Override
    public IdGetter<AppRecord> getIdGetter() {
        return entity -> entity.id;
    }

    @Override
    public CursorFactory<AppRecord> getCursorFactory() {
        throw new UnsupportedOperationException(
                "AppRecord cursor factory not generated. "
                + "Run objectbox-processor to produce AppRecordCursor.");
    }
}
