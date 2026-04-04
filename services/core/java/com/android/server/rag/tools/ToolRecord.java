package com.android.server.rag.tools;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.relation.ToOne;

/**
 * ObjectBox entity representing a single tool exposed by an app.
 *
 * Linked to its owning app via ToOne<AppRecord>. The Phase 5 agentic loop
 * uses this relation to know which app to dispatch to after a tool match.
 *
 * Embedding:
 *   rawDefinition is embedded via CactusWrapper and stored in the Cactus
 *   binary index (cactusIndexId) for Stage 2 semantic tool routing.
 *   The embedding vector itself lives in Cactus — not in ObjectBox.
 *
 * Key constraint:
 *   The model that embedded rawDefinition must be the model that queries it.
 *   Enforced by the "tools" ModelRegistry entry in RagService.
 *
 * See TOOL_REGISTRY.md for full design.
 */
@Entity
public class ToolRecord {

    @Id
    public long id;

    /** e.g. check_visa_status */
    public String toolName;

    /**
     * Natural language description — what the tool does.
     * e.g. "Check the visa application status for a given passport number"
     * Used as the primary text for embedding.
     */
    public String description;

    /**
     * JSON string of input parameters parsed from the app's XML schema resource.
     * Format: [{"name":"passport_number","type":"string","required":true,"description":"..."}]
     * Null if the app declared no input schema.
     */
    public String paramsJson;

    /**
     * Full string used for embedding — typically description + params summary.
     * Stored so we can re-embed without re-parsing the APK.
     */
    public String rawDefinition;

    /**
     * BroadcastReceiver class to invoke when this tool is dispatched.
     * e.g. com.borderless.app.BorderlessToolReceiver
     */
    public String receiverClass;

    /**
     * Pointer into the Cactus binary index for this tool's embedding.
     * 0 = not yet embedded.
     * Used by Stage 2 semantic search in ToolScannerService / future ToolNode.
     */
    public int cactusIndexId;

    /** FK → owning app. Phase 5 ToolNode uses this to know where to dispatch. */
    public ToOne<AppRecord> app;

    public ToolRecord() {}

    public ToolRecord(String toolName, String description, String paramsJson,
                      String rawDefinition, String receiverClass) {
        this.toolName      = toolName;
        this.description   = description;
        this.paramsJson    = paramsJson;
        this.rawDefinition = rawDefinition;
        this.receiverClass = receiverClass;
    }
}
