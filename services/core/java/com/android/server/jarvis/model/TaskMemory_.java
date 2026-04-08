package com.android.server.jarvis.model;

import io.objectbox.EntityInfo;
import io.objectbox.Property;
import io.objectbox.internal.CursorFactory;
import io.objectbox.internal.IdGetter;

/**
 * ObjectBox entity meta-info for TaskMemory.
 * Hand-written stub — see AppRecord_ for generation instructions.
 */
public final class TaskMemory_ implements EntityInfo<TaskMemory> {

    public static final String __ENTITY_NAME = "TaskMemory";
    public static final int    __ENTITY_ID   = 6;

    public static final TaskMemory_ INSTANCE = new TaskMemory_();

    public static final Property<TaskMemory> id;
    public static final Property<TaskMemory> taskDescription;
    public static final Property<TaskMemory> approach;
    public static final Property<TaskMemory> fileIdsUsed;
    public static final Property<TaskMemory> embeddingsDiscarded;
    public static final Property<TaskMemory> completedAt;

    @SuppressWarnings("unchecked")
    private static final Property<TaskMemory>[] ALL_PROPERTIES;

    static {
        id                  = new Property<>(INSTANCE, 0, 6 /* Long */,   long.class,    "id",                  true,  "__id");
        taskDescription     = new Property<>(INSTANCE, 1, 9 /* String */, String.class,  "taskDescription",     false, "taskDescription");
        approach            = new Property<>(INSTANCE, 2, 9 /* String */, String.class,  "approach",            false, "approach");
        fileIdsUsed         = new Property<>(INSTANCE, 3, 9 /* String */, String.class,  "fileIdsUsed",         false, "fileIdsUsed");
        embeddingsDiscarded = new Property<>(INSTANCE, 4, 1 /* Bool */,   boolean.class, "embeddingsDiscarded", false, "embeddingsDiscarded");
        completedAt         = new Property<>(INSTANCE, 5, 6 /* Long */,   long.class,    "completedAt",         false, "completedAt");
        ALL_PROPERTIES = new Property[]{id, taskDescription, approach, fileIdsUsed, embeddingsDiscarded, completedAt};
    }

    private TaskMemory_() {}

    @Override public String getEntityName()     { return __ENTITY_NAME; }
    @Override public String getDbName()         { return __ENTITY_NAME; }
    @Override public Class<TaskMemory> getEntityClass() { return TaskMemory.class; }
    @Override public int getEntityId()          { return __ENTITY_ID; }

    @Override
    @SuppressWarnings("unchecked")
    public Property<TaskMemory>[] getAllProperties() { return ALL_PROPERTIES; }

    @Override public Property<TaskMemory> getIdProperty() { return id; }

    @Override
    public IdGetter<TaskMemory> getIdGetter() {
        return entity -> entity.id;
    }

    @Override
    public CursorFactory<TaskMemory> getCursorFactory() {
        throw new UnsupportedOperationException(
                "TaskMemory cursor factory not generated. "
                + "Run objectbox-processor to produce TaskMemoryCursor.");
    }
}
