package com.android.server.jarvis.core;

import com.android.server.jarvis.model.AccessLog_;
import com.android.server.jarvis.model.AgentSession_;
import com.android.server.jarvis.model.AgentTurn_;
import com.android.server.jarvis.model.DocumentChunk_;
import com.android.server.jarvis.model.Folder_;
import com.android.server.jarvis.model.SourceFile_;
import com.android.server.jarvis.model.TaskMemory_;
import com.android.server.jarvis.model.UserContext_;
import com.android.server.jarvis.tools.AppRecord_;
import com.android.server.jarvis.tools.ToolRecord_;

import io.objectbox.BoxStoreBuilder;

/**
 * ObjectBox store builder for JarvisOS.
 *
 * Hand-written stub replacing the processor-generated MyObjectBox.
 * Uses createDebugWithoutModel() — no FlatBuffers schema bytes needed.
 *
 * LIMITATION: createDebugWithoutModel() skips ObjectBox schema validation.
 * Replace with a proper model-based builder once objectbox-processor is
 * wired into the build (see Android.bp TODO). At that point the processor
 * generates this file automatically with correct UIDs and model bytes.
 *
 * getCursorFactory() on each entity _ class will throw at first box access —
 * that is also a processor-generation gap, not a store-init gap.
 */
public final class MyObjectBox {

    private MyObjectBox() {}

    /**
     * Returns a BoxStoreBuilder with all JarvisOS entities registered.
     * Call .directory(File).build() to get the live store.
     */
    public static BoxStoreBuilder builder() {
        BoxStoreBuilder builder = BoxStoreBuilder.createDebugWithoutModel();
        builder.entity(AppRecord_.INSTANCE);
        builder.entity(ToolRecord_.INSTANCE);
        builder.entity(SourceFile_.INSTANCE);
        builder.entity(DocumentChunk_.INSTANCE);
        builder.entity(Folder_.INSTANCE);
        builder.entity(AccessLog_.INSTANCE);
        builder.entity(TaskMemory_.INSTANCE);
        builder.entity(AgentSession_.INSTANCE);
        builder.entity(AgentTurn_.INSTANCE);
        builder.entity(UserContext_.INSTANCE);
        return builder;
    }
}
