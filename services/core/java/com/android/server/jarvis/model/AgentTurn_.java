package com.android.server.jarvis.model;

import io.objectbox.EntityInfo;
import io.objectbox.annotation.apihint.Internal;
import io.objectbox.internal.CursorFactory;
import io.objectbox.internal.IdGetter;
import io.objectbox.model.PropertyType;
import io.objectbox.Property;

/**
 * Hand-written ObjectBox EntityInfo stub for AgentTurn.
 * See AgentSession_.java for the same pattern and caveats.
 */
@Internal
public final class AgentTurn_ implements EntityInfo<AgentTurn> {

    public static final AgentTurn_ INSTANCE = new AgentTurn_();

    public static final Property<AgentTurn> id;
    public static final Property<AgentTurn> role;
    public static final Property<AgentTurn> content;
    public static final Property<AgentTurn> toolName;
    public static final Property<AgentTurn> toolArgs;
    public static final Property<AgentTurn> timestamp;

    static {
        id        = new Property<>(INSTANCE, 0, PropertyType.Long,   long.class,   "id",        true,  "id");
        role      = new Property<>(INSTANCE, 1, PropertyType.String, String.class, "role",      false, "role");
        content   = new Property<>(INSTANCE, 2, PropertyType.String, String.class, "content",   false, "content");
        toolName  = new Property<>(INSTANCE, 3, PropertyType.String, String.class, "toolName",  false, "toolName");
        toolArgs  = new Property<>(INSTANCE, 4, PropertyType.String, String.class, "toolArgs",  false, "toolArgs");
        timestamp = new Property<>(INSTANCE, 5, PropertyType.Long,   long.class,   "timestamp", false, "timestamp");
    }

    @Override public String getEntityName()  { return "AgentTurn"; }
    @Override public String getDbName()      { return "AgentTurn"; }
    @Override public Class<AgentTurn> getEntityClass() { return AgentTurn.class; }
    @Override public int getEntityId()       { return 8; }

    @Override
    public Property<AgentTurn>[] getAllProperties() {
        return new Property[]{ id, role, content, toolName, toolArgs, timestamp };
    }

    @Override public Property<AgentTurn> getIdProperty() { return id; }

    @Override
    public IdGetter<AgentTurn> getIdGetter() {
        return entity -> entity.id;
    }

    @Override
    public CursorFactory<AgentTurn> getCursorFactory() {
        throw new UnsupportedOperationException(
                "AgentTurn_ is a hand-written stub. "
                + "Wire objectbox-processor to generate a real cursor.");
    }
}
