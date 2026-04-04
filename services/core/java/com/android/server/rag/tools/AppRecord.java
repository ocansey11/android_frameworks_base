package com.android.server.rag.tools;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.relation.ToMany;

/**
 * ObjectBox entity representing an installed app that has registered tools.
 *
 * One AppRecord per package. All tools exposed by that package are linked
 * via the ToMany<ToolRecord> relation.
 *
 * Lifecycle:
 *   ACTION_PACKAGE_ADDED   → AppRecord created (if not exists), ToolRecords inserted
 *   ACTION_PACKAGE_REPLACED → AppRecord.lastScanTime updated, ToolRecords re-scanned
 *   ACTION_PACKAGE_REMOVED  → AppRecord + all linked ToolRecords deleted
 *
 * See TOOL_REGISTRY.md for full design.
 */
@Entity
public class AppRecord {

    @Id
    public long id;

    /** e.g. com.borderless.app */
    public String packageName;

    /** Human-readable label from PackageManager, e.g. "Borderless" */
    public String appLabel;

    /** "curated" = shipped in vendor/jarvisos/tools/  |  "declared" = from manifest meta-data */
    public String sourceType;

    /** Unix timestamp (ms) of the last successful scan. */
    public long lastScanTime;

    /**
     * false when the package has been uninstalled.
     * Kept as a tombstone briefly during removal cleanup, then deleted.
     */
    public boolean isActive;

    /** All tools this app has registered. */
    public ToMany<ToolRecord> tools;

    public AppRecord() {}

    public AppRecord(String packageName, String appLabel, String sourceType) {
        this.packageName  = packageName;
        this.appLabel     = appLabel;
        this.sourceType   = sourceType;
        this.lastScanTime = System.currentTimeMillis();
        this.isActive     = true;
    }
}
