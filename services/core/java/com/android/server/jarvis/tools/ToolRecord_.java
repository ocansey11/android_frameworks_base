package com.android.server.jarvis.tools;

import io.objectbox.EntityInfo;
import io.objectbox.Property;
import io.objectbox.internal.CursorFactory;
import io.objectbox.internal.IdGetter;

/**
 * ObjectBox entity meta-info for ToolRecord.
 * Hand-written stub — see AppRecord_ for generation instructions.
 */
public final class ToolRecord_ implements EntityInfo<ToolRecord> {

    public static final String __ENTITY_NAME = "ToolRecord";
    public static final int    __ENTITY_ID   = 2;

    public static final ToolRecord_ INSTANCE = new ToolRecord_();

    public static final Property<ToolRecord> id;
    public static final Property<ToolRecord> toolName;
    public static final Property<ToolRecord> description;
    public static final Property<ToolRecord> paramsJson;
    public static final Property<ToolRecord> rawDefinition;
    public static final Property<ToolRecord> receiverClass;
    public static final Property<ToolRecord> cactusIndexId;
    public static final Property<ToolRecord> requiresConfirmation;

    @SuppressWarnings("unchecked")
    private static final Property<ToolRecord>[] ALL_PROPERTIES;

    static {
        id                   = new Property<>(INSTANCE, 0, 6  /* Long */,    long.class,    "id",                   true,  "__id");
        toolName             = new Property<>(INSTANCE, 1, 9  /* String */,  String.class,  "toolName",             false, "toolName");
        description          = new Property<>(INSTANCE, 2, 9  /* String */,  String.class,  "description",          false, "description");
        paramsJson           = new Property<>(INSTANCE, 3, 9  /* String */,  String.class,  "paramsJson",           false, "paramsJson");
        rawDefinition        = new Property<>(INSTANCE, 4, 9  /* String */,  String.class,  "rawDefinition",        false, "rawDefinition");
        receiverClass        = new Property<>(INSTANCE, 5, 9  /* String */,  String.class,  "receiverClass",        false, "receiverClass");
        cactusIndexId        = new Property<>(INSTANCE, 6, 5  /* Int */,     int.class,     "cactusIndexId",        false, "cactusIndexId");
        requiresConfirmation = new Property<>(INSTANCE, 7, 1  /* Boolean */, boolean.class, "requiresConfirmation", false, "requiresConfirmation");
        ALL_PROPERTIES = new Property[]{id, toolName, description, paramsJson, rawDefinition,
                receiverClass, cactusIndexId, requiresConfirmation};
    }

    private ToolRecord_() {}

    @Override public String getEntityName()   { return __ENTITY_NAME; }
    @Override public String getDbName()       { return __ENTITY_NAME; }
    @Override public Class<ToolRecord> getEntityClass() { return ToolRecord.class; }
    @Override public int getEntityId()        { return __ENTITY_ID; }

    @Override
    @SuppressWarnings("unchecked")
    public Property<ToolRecord>[] getAllProperties() { return ALL_PROPERTIES; }

    @Override public Property<ToolRecord> getIdProperty() { return id; }

    @Override
    public IdGetter<ToolRecord> getIdGetter() {
        return entity -> entity.id;
    }

    @Override
    public CursorFactory<ToolRecord> getCursorFactory() {
        throw new UnsupportedOperationException(
                "ToolRecord cursor factory not generated. "
                + "Run objectbox-processor to produce ToolRecordCursor.");
    }
}
