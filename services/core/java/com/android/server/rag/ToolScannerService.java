package com.android.server.rag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans installed apps for JarvisOS tool declarations and registers them
 * in ObjectBox + Cactus index.
 *
 * How it works:
 *   1. On boot, scan all already-installed packages for com.jarvisos.TOOL receivers.
 *   2. Listen for ACTION_PACKAGE_ADDED — scan new installs immediately.
 *   3. Listen for ACTION_PACKAGE_REMOVED — clean up ObjectBox + Cactus entries.
 *   4. Listen for ACTION_PACKAGE_REPLACED — re-scan updated packages.
 *
 * Tool declaration contract (app's AndroidManifest.xml):
 *   <receiver android:name=".MyToolReceiver">
 *       <intent-filter>
 *           <action android:name="com.jarvisos.TOOL" />
 *       </intent-filter>
 *       <meta-data android:name="com.jarvisos.tool.name"
 *                  android:value="my_tool_name" />
 *       <meta-data android:name="com.jarvisos.tool.description"
 *                  android:value="What this tool does in plain English" />
 *       <meta-data android:name="com.jarvisos.tool.input_schema"
 *                  android:resource="@xml/tool_my_tool_schema" />
 *   </receiver>
 *
 * Input schema XML format (res/xml/tool_my_tool_schema.xml):
 *   <tool-schema>
 *       <param name="passport_number" type="string" required="true"
 *              description="The passport number to check" />
 *   </tool-schema>
 *
 * See TOOL_REGISTRY.md for full design and permission model.
 */
public class ToolScannerService {

    private static final String TAG = "ToolScannerService";

    /** Intent action all tool-exposing apps must declare in their manifest. */
    public static final String ACTION_JARVIS_TOOL = "com.jarvisos.TOOL";

    /** Meta-data key: tool name (e.g. check_visa_status) */
    private static final String META_TOOL_NAME        = "com.jarvisos.tool.name";
    /** Meta-data key: natural language description (used for embedding) */
    private static final String META_TOOL_DESCRIPTION = "com.jarvisos.tool.description";
    /** Meta-data key: resource ID pointing to the input schema XML */
    private static final String META_TOOL_INPUT_SCHEMA = "com.jarvisos.tool.input_schema";

    private final Context mContext;
    private final PackageManager mPackageManager;
    private PackageEventReceiver mReceiver;

    // Cactus handles — injected by RagService once initialized (Phase 3)
    // For now, 0L disables embedding. Tools are still stored in ObjectBox.
    private long mModelHandle = 0L;
    private long mIndexHandle = 0L;

    public ToolScannerService(Context context) {
        this.mContext       = context;
        this.mPackageManager = context.getPackageManager();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start the scanner.
     * Performs an initial scan of all installed packages, then registers
     * a BroadcastReceiver to handle future installs/removals.
     */
    public void start() {
        // Initial scan — pick up tools from packages already installed
        scanAllPackages();

        // Register for future package events
        mReceiver = new PackageEventReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mReceiver, filter);

        Log.i(TAG, "ToolScannerService started");
    }

    /** Stop the scanner and unregister the receiver. */
    public void stop() {
        if (mReceiver != null) {
            mContext.unregisterReceiver(mReceiver);
            mReceiver = null;
        }
    }

    /**
     * Inject Cactus handles so the scanner can embed tool descriptions.
     * Called by RagService once Cactus is initialized (Phase 3).
     */
    public void setCactusHandles(long modelHandle, long indexHandle) {
        this.mModelHandle = modelHandle;
        this.mIndexHandle = indexHandle;
        Log.i(TAG, "Cactus handles set — tool embeddings enabled");
    }

    // -------------------------------------------------------------------------
    // Scanning
    // -------------------------------------------------------------------------

    /** Query PackageManager for all receivers declaring ACTION_JARVIS_TOOL. */
    private void scanAllPackages() {
        Intent queryIntent = new Intent(ACTION_JARVIS_TOOL);
        List<ResolveInfo> resolved = mPackageManager.queryBroadcastReceivers(
                queryIntent, PackageManager.GET_META_DATA);

        Log.i(TAG, "Initial scan — found " + resolved.size() + " tool receiver(s)");
        for (ResolveInfo info : resolved) {
            processResolveInfo(info);
        }
    }

    /** Scan a single package by name (called on install/update). */
    private void scanPackage(String packageName) {
        Intent queryIntent = new Intent(ACTION_JARVIS_TOOL);
        queryIntent.setPackage(packageName);
        List<ResolveInfo> resolved = mPackageManager.queryBroadcastReceivers(
                queryIntent, PackageManager.GET_META_DATA);

        for (ResolveInfo info : resolved) {
            processResolveInfo(info);
        }
    }

    /** Parse meta-data from a ResolveInfo and upsert into ObjectBox + Cactus. */
    private void processResolveInfo(ResolveInfo info) {
        if (info.activityInfo == null) return;

        String packageName   = info.activityInfo.packageName;
        String receiverClass = info.activityInfo.name;
        Bundle metaData      = info.activityInfo.metaData;

        if (metaData == null) {
            Log.w(TAG, "No meta-data on receiver " + receiverClass + " — skipping");
            return;
        }

        String toolName    = metaData.getString(META_TOOL_NAME);
        String description = metaData.getString(META_TOOL_DESCRIPTION);

        if (toolName == null || toolName.isEmpty()) {
            Log.w(TAG, packageName + ": missing com.jarvisos.tool.name — skipping");
            return;
        }
        if (description == null || description.isEmpty()) {
            Log.w(TAG, packageName + "/" + toolName + ": missing description — skipping");
            return;
        }

        // Parse input schema XML if present
        String inputSchemaJson = null;
        int schemaResId = metaData.getInt(META_TOOL_INPUT_SCHEMA, 0);
        if (schemaResId != 0) {
            inputSchemaJson = parseInputSchema(packageName, schemaResId);
        }

        // Upsert into ObjectBox
        upsertToolDefinition(packageName, receiverClass, toolName, description, inputSchemaJson);
    }

    // -------------------------------------------------------------------------
    // ObjectBox upsert
    // -------------------------------------------------------------------------

    private void upsertToolDefinition(
            String packageName,
            String receiverClass,
            String toolName,
            String description,
            String inputSchemaJson) {

        if (!JarvisStore.isReady()) {
            Log.w(TAG, "ObjectBox not ready — cannot store tool: " + toolName);
            return;
        }

        io.objectbox.Box<ToolDefinition> box = JarvisStore.box(ToolDefinition.class);

        // Check for existing entry — match on packageName + toolName
        ToolDefinition existing = box.query()
                .equal(ToolDefinition_.packageName, packageName,
                        io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                .equal(ToolDefinition_.toolName, toolName,
                        io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                .build().findFirst();

        if (existing != null) {
            // Update
            existing.description    = description;
            existing.inputSchemaJson = inputSchemaJson;
            existing.receiverClass  = receiverClass;
            existing.lastUpdatedAt  = System.currentTimeMillis();
            box.put(existing);
            Log.i(TAG, "Updated tool: " + packageName + "/" + toolName);

            // Re-embed the updated description
            embedAndStore(existing);
        } else {
            // Insert
            ToolDefinition tool = new ToolDefinition(
                    packageName, receiverClass, toolName, description, inputSchemaJson);
            box.put(tool);
            Log.i(TAG, "Registered tool: " + packageName + "/" + toolName);

            // Embed description for semantic routing
            embedAndStore(tool);
        }
    }

    // -------------------------------------------------------------------------
    // Embedding
    // -------------------------------------------------------------------------

    /**
     * Embed the tool description and store in Cactus index.
     * No-op if Cactus handles are not yet available (Phase 3).
     */
    private void embedAndStore(ToolDefinition tool) {
        if (mModelHandle == 0L || mIndexHandle == 0L) {
            Log.d(TAG, "Cactus not ready — skipping embedding for: " + tool.toolName);
            return;
        }

        float[] embedding = CactusWrapper.embed(mModelHandle, tool.description);
        if (embedding == null) {
            Log.w(TAG, "Embed failed for tool: " + tool.toolName);
            return;
        }

        // Use ObjectBox id as the Cactus index id (tools have far fewer entries
        // than document chunks, so collision risk is negligible here)
        int cactusId = (int) tool.id;
        int result   = CactusWrapper.indexAdd(
                mIndexHandle, cactusId, tool.description, null, embedding);

        if (result == 0) {
            tool.cactusIndexId = cactusId;
            JarvisStore.box(ToolDefinition.class).put(tool);
            Log.i(TAG, "Embedded tool: " + tool.toolName);
        } else {
            Log.w(TAG, "indexAdd failed for tool: " + tool.toolName);
        }
    }

    // -------------------------------------------------------------------------
    // Removal
    // -------------------------------------------------------------------------

    private void removePackageTools(String packageName) {
        if (!JarvisStore.isReady()) return;

        io.objectbox.Box<ToolDefinition> box = JarvisStore.box(ToolDefinition.class);
        List<ToolDefinition> tools = box.query()
                .equal(ToolDefinition_.packageName, packageName,
                        io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                .build().find();

        if (tools.isEmpty()) return;

        // Remove Cactus index entries
        if (mIndexHandle != 0L) {
            int[] cactusIds = new int[tools.size()];
            for (int i = 0; i < tools.size(); i++) cactusIds[i] = tools.get(i).cactusIndexId;
            CactusWrapper.indexDelete(mIndexHandle, cactusIds);
        }

        // Remove ObjectBox entries
        for (ToolDefinition t : tools) box.remove(t.id);

        Log.i(TAG, "Removed " + tools.size() + " tool(s) for package: " + packageName);
    }

    // -------------------------------------------------------------------------
    // Input schema XML parser
    // -------------------------------------------------------------------------

    /**
     * Parse the tool input schema XML from the app's resources.
     * Returns a JSON string like:
     * [{"name":"passport_number","type":"string","required":true,"description":"..."}]
     *
     * Returns null if parsing fails.
     */
    private String parseInputSchema(String packageName, int resourceId) {
        try {
            Resources res = mPackageManager.getResourcesForApplication(packageName);
            XmlResourceParser parser = res.getXml(resourceId);

            List<String> params = new ArrayList<>();
            int event = parser.getEventType();

            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "param".equals(parser.getName())) {
                    String name        = parser.getAttributeValue(null, "name");
                    String type        = parser.getAttributeValue(null, "type");
                    String required    = parser.getAttributeValue(null, "required");
                    String description = parser.getAttributeValue(null, "description");

                    if (name != null && type != null) {
                        // Build param JSON manually — these values come from developer-controlled
                        // XML so they should be safe, but sanitise quotes just in case
                        String paramJson = "{"
                                + "\"name\":\"" + sanitise(name) + "\","
                                + "\"type\":\"" + sanitise(type) + "\","
                                + "\"required\":" + "true".equalsIgnoreCase(required) + ","
                                + "\"description\":\"" + sanitise(description != null ? description : "") + "\""
                                + "}";
                        params.add(paramJson);
                    }
                }
                event = parser.next();
            }
            parser.close();

            return "[" + String.join(",", params) + "]";

        } catch (Exception e) {
            Log.w(TAG, "Failed to parse input schema for " + packageName, e);
            return null;
        }
    }

    /** Strip double-quotes to avoid breaking the JSON output. */
    private static String sanitise(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }

    // -------------------------------------------------------------------------
    // BroadcastReceiver — package events
    // -------------------------------------------------------------------------

    private class PackageEventReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getData() == null) return;
            String packageName = intent.getData().getSchemeSpecificPart();
            if (packageName == null) return;

            String action = intent.getAction();
            if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                    || Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                Log.i(TAG, "Package installed/updated: " + packageName + " — scanning");
                scanPackage(packageName);
            } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                Log.i(TAG, "Package removed: " + packageName + " — cleaning up");
                removePackageTools(packageName);
            }
        }
    }
}
