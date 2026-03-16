package com.android.server.rag;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;

/**
 * ObjectBox entity representing a tool registered by a third-party app.
 *
 * Tools are discovered by ToolScannerService on APK install via PackageManager.
 * Each entry corresponds to one <receiver> in an app's AndroidManifest.xml
 * that declares:
 *   <action android:name="com.jarvisos.TOOL" />
 *
 * The tool description is embedded via CactusWrapper and stored in the Cactus
 * index (cactusIndexId) for Stage 2 semantic tool routing.
 *
 * See TOOL_REGISTRY.md for full design.
 */
@Entity
public class ToolDefinition {

    @Id
    public long id;

    /** e.g. com.borderless.app */
    public String packageName;

    /** e.g. .BorderlessToolReceiver — the BroadcastReceiver class to invoke */
    public String receiverClass;

    /** e.g. check_visa_status — unique within the package */
    public String toolName;

    /**
     * Natural language description of what the tool does.
     * This is what gets embedded and used for semantic tool routing.
     * e.g. "Check the visa application status for a given passport number"
     */
    public String description;

    /**
     * JSON string of input parameters, parsed from the app's XML schema resource.
     * Format: [{"name":"passport_number","type":"string","required":true,"description":"..."}]
     */
    public String inputSchemaJson;

    /**
     * Pointer to this tool's embedding in the Cactus binary index.
     * Used by Stage 2 semantic search to find the right tool for a query.
     * 0 = not yet embedded.
     */
    public int cactusIndexId;

    /** Unix timestamp (ms) when this tool was first registered (APK install time). */
    public long installedAt;

    /** Unix timestamp (ms) of the last update (APK update time). */
    public long lastUpdatedAt;

    /** Default constructor required by ObjectBox. */
    public ToolDefinition() {}

    public ToolDefinition(
            String packageName,
            String receiverClass,
            String toolName,
            String description,
            String inputSchemaJson) {
        this.packageName    = packageName;
        this.receiverClass  = receiverClass;
        this.toolName       = toolName;
        this.description    = description;
        this.inputSchemaJson = inputSchemaJson;
        this.installedAt    = System.currentTimeMillis();
        this.lastUpdatedAt  = this.installedAt;
    }
}
