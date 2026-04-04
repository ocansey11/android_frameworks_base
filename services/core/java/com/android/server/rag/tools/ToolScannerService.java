package com.android.server.rag.tools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.Log;

import com.android.server.rag.core.JarvisStore;
import com.android.server.rag.inference.CactusWrapper;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans installed apps for JarvisOS tool declarations and persists them
 * as AppRecord + ToolRecord pairs in ObjectBox, with embeddings in Cactus.
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
 * See TOOL_REGISTRY.md for full design and permission model.
 */
public class ToolScannerService {

    private static final String TAG = "ToolScannerService";

    public static final String ACTION_JARVIS_TOOL     = "com.jarvisos.TOOL";
    private static final String META_TOOL_NAME        = "com.jarvisos.tool.name";
    private static final String META_TOOL_DESCRIPTION = "com.jarvisos.tool.description";
    private static final String META_TOOL_INPUT_SCHEMA = "com.jarvisos.tool.input_schema";

    private final Context mContext;
    private final PackageManager mPackageManager;
    private PackageEventReceiver mReceiver;

    private long mModelHandle = 0L;
    private long mIndexHandle = 0L;

    public ToolScannerService(Context context) {
        this.mContext        = context;
        this.mPackageManager = context.getPackageManager();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void start() {
        scanAllPackages();

        mReceiver = new PackageEventReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mReceiver, filter);

        Log.i(TAG, "ToolScannerService started");
    }

    public void stop() {
        if (mReceiver != null) {
            mContext.unregisterReceiver(mReceiver);
            mReceiver = null;
        }
    }

    /** Called by RagService once Cactus is initialized. Enables embedding. */
    public void setCactusHandles(long modelHandle, long indexHandle) {
        this.mModelHandle = modelHandle;
        this.mIndexHandle = indexHandle;
        Log.i(TAG, "Cactus handles set — tool embeddings enabled");
    }

    // -------------------------------------------------------------------------
    // Scanning
    // -------------------------------------------------------------------------

    private void scanAllPackages() {
        Intent queryIntent = new Intent(ACTION_JARVIS_TOOL);
        List<ResolveInfo> resolved = mPackageManager.queryBroadcastReceivers(
                queryIntent, PackageManager.GET_META_DATA);

        Log.i(TAG, "Initial scan — found " + resolved.size() + " tool receiver(s)");
        for (ResolveInfo info : resolved) {
            processResolveInfo(info, "declared");
        }
    }

    private void scanPackage(String packageName) {
        Intent queryIntent = new Intent(ACTION_JARVIS_TOOL);
        queryIntent.setPackage(packageName);
        List<ResolveInfo> resolved = mPackageManager.queryBroadcastReceivers(
                queryIntent, PackageManager.GET_META_DATA);
        for (ResolveInfo info : resolved) {
            processResolveInfo(info, "declared");
        }
    }

    // -------------------------------------------------------------------------
    // Processing
    // -------------------------------------------------------------------------

    private void processResolveInfo(ResolveInfo info, String sourceType) {
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

        String paramsJson = null;
        int schemaResId = metaData.getInt(META_TOOL_INPUT_SCHEMA, 0);
        if (schemaResId != 0) {
            paramsJson = parseInputSchema(packageName, schemaResId);
        }

        String appLabel = resolveAppLabel(packageName);
        upsert(packageName, appLabel, sourceType, receiverClass, toolName, description, paramsJson);
    }

    // -------------------------------------------------------------------------
    // Upsert — AppRecord + ToolRecord
    // -------------------------------------------------------------------------

    private void upsert(String packageName, String appLabel, String sourceType,
                        String receiverClass, String toolName,
                        String description, String paramsJson) {

        if (!JarvisStore.isReady()) {
            Log.w(TAG, "ObjectBox not ready — skipping: " + packageName + "/" + toolName);
            return;
        }

        io.objectbox.Box<AppRecord> appBox   = JarvisStore.box(AppRecord.class);
        io.objectbox.Box<ToolRecord> toolBox = JarvisStore.box(ToolRecord.class);

        // --- AppRecord ---
        AppRecord app = appBox.query()
                .equal(AppRecord_.packageName, packageName,
                        io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                .build().findFirst();

        if (app == null) {
            app = new AppRecord(packageName, appLabel, sourceType);
            appBox.put(app);
            Log.i(TAG, "New app registered: " + packageName);
        } else {
            app.lastScanTime = System.currentTimeMillis();
            app.isActive     = true;
            appBox.put(app);
        }

        // --- ToolRecord ---
        // rawDefinition = what we embed: description + params summary
        String rawDefinition = buildRawDefinition(toolName, description, paramsJson);

        ToolRecord tool = toolBox.query()
                .equal(ToolRecord_.toolName, toolName,
                        io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                .equal(ToolRecord_.receiverClass, receiverClass,
                        io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                .build().findFirst();

        if (tool == null) {
            tool = new ToolRecord(toolName, description, paramsJson, rawDefinition, receiverClass);
            tool.app.setTarget(app);
            toolBox.put(tool);
            Log.i(TAG, "New tool registered: " + packageName + "/" + toolName);
        } else {
            tool.description   = description;
            tool.paramsJson    = paramsJson;
            tool.rawDefinition = rawDefinition;
            toolBox.put(tool);
            Log.i(TAG, "Tool updated: " + packageName + "/" + toolName);
        }

        embedAndStore(tool);
    }

    /**
     * Build the string we embed for semantic search.
     * Includes tool name + description + param names so queries like
     * "check my visa status" match "check_visa_status / passport_number".
     */
    private String buildRawDefinition(String toolName, String description, String paramsJson) {
        StringBuilder sb = new StringBuilder();
        sb.append(toolName.replace('_', ' ')).append(": ").append(description);
        if (paramsJson != null && !paramsJson.isEmpty()) {
            sb.append(" Parameters: ").append(paramsJson);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Embedding
    // -------------------------------------------------------------------------

    private void embedAndStore(ToolRecord tool) {
        if (mModelHandle == 0L || mIndexHandle == 0L) {
            Log.d(TAG, "Cactus not ready — skipping embedding for: " + tool.toolName);
            return;
        }

        float[] embedding = CactusWrapper.embed(mModelHandle, tool.rawDefinition);
        if (embedding == null) {
            Log.w(TAG, "Embed failed for tool: " + tool.toolName);
            return;
        }

        int cactusId = (int) tool.id;
        int result   = CactusWrapper.indexAdd(
                mIndexHandle, cactusId, tool.rawDefinition, null, embedding);

        if (result == 0) {
            tool.cactusIndexId = cactusId;
            JarvisStore.box(ToolRecord.class).put(tool);
            Log.i(TAG, "Embedded tool: " + tool.toolName);
        } else {
            Log.w(TAG, "indexAdd failed for tool: " + tool.toolName);
        }
    }

    // -------------------------------------------------------------------------
    // Removal
    // -------------------------------------------------------------------------

    private void removePackage(String packageName) {
        if (!JarvisStore.isReady()) return;

        io.objectbox.Box<AppRecord> appBox   = JarvisStore.box(AppRecord.class);
        io.objectbox.Box<ToolRecord> toolBox = JarvisStore.box(ToolRecord.class);

        AppRecord app = appBox.query()
                .equal(AppRecord_.packageName, packageName,
                        io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                .build().findFirst();

        if (app == null) return;

        // Remove Cactus index entries for all tools
        if (mIndexHandle != 0L) {
            List<ToolRecord> tools = app.tools;
            int[] cactusIds = new int[tools.size()];
            for (int i = 0; i < tools.size(); i++) cactusIds[i] = tools.get(i).cactusIndexId;
            CactusWrapper.indexDelete(mIndexHandle, cactusIds);
        }

        // Remove ToolRecords then AppRecord
        for (ToolRecord t : app.tools) toolBox.remove(t.id);
        appBox.remove(app.id);

        Log.i(TAG, "Removed app + tools for package: " + packageName);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String resolveAppLabel(String packageName) {
        try {
            ApplicationInfo info = mPackageManager.getApplicationInfo(packageName, 0);
            return mPackageManager.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

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

    private static String sanitise(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }

    // -------------------------------------------------------------------------
    // BroadcastReceiver
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
                Log.i(TAG, "Package installed/updated: " + packageName);
                scanPackage(packageName);
            } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                Log.i(TAG, "Package removed: " + packageName);
                removePackage(packageName);
            }
        }
    }
}
