package com.android.server.jarvis.session;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The session manifests of the installed apps, read at install time beside
 * their tools (ToolScannerService calls in here). Kept in memory, not in
 * ObjectBox: every app is scanned again at boot, and a manifest is small.
 */
public final class SessionRegistry {

    private static final String TAG = "JarvisSessions";

    private final PackageManager mPackageManager;
    /** Package name to the sessions it declares (one today; the meta-data holds one file). */
    private final Map<String, List<SessionManifest>> mByPackage = new ConcurrentHashMap<>();

    public SessionRegistry(PackageManager packageManager) {
        mPackageManager = packageManager;
    }

    /** Reads every installed app's session manifest. */
    public void scanAll() {
        List<ApplicationInfo> apps = mPackageManager.getInstalledApplications(
                PackageManager.GET_META_DATA | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        mByPackage.clear();
        for (ApplicationInfo app : apps) read(app);
        Log.i(TAG, "Scan: " + mByPackage.size() + " app(s) declare a session");
    }

    /** Reads one app again, after it was installed or updated. */
    public void scan(String packageName) {
        mByPackage.remove(packageName);
        try {
            read(mPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA));
        } catch (PackageManager.NameNotFoundException e) {
            // Gone again already.
        }
    }

    public void remove(String packageName) {
        if (mByPackage.remove(packageName) != null) {
            Log.i(TAG, "Removed the session of " + packageName);
        }
    }

    private void read(ApplicationInfo app) {
        Bundle meta = app.metaData;
        if (meta == null) return;
        int resId = meta.getInt(SessionManifest.META_SESSION, 0);
        if (resId == 0) return;
        try {
            Resources res = mPackageManager.getResourcesForApplication(app);
            try (XmlResourceParser parser = res.getXml(resId)) {
                SessionManifest manifest = SessionManifest.parse(app.packageName, parser);
                for (SessionManifest other : all()) {
                    if (manifest.startTool != null && manifest.startTool.equals(other.startTool)
                            && !other.packageName.equals(app.packageName)) {
                        // TODO(AX 11.7): two apps answer the same request; Jarvis
                        // must ask the person which one. Not designed yet ("we
                        // will sort this out later"); today the tool registry's
                        // first match wins.
                        Log.w(TAG, "Start tool " + manifest.startTool + " is declared by both "
                                + other.packageName + " and " + app.packageName);
                    }
                }
                List<SessionManifest> list = new ArrayList<>();
                list.add(manifest);
                mByPackage.put(app.packageName, list);
                Log.i(TAG, "Session declared: " + manifest);
            }
        } catch (IllegalArgumentException e) {
            // A broken manifest is refused whole; its tools stay ordinary tools.
            Log.w(TAG, app.packageName + ": session manifest refused: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, app.packageName + ": could not read the session manifest", e);
        }
    }

    public SessionManifest get(String packageName, String sessionName) {
        List<SessionManifest> list = mByPackage.get(packageName);
        if (list == null) return null;
        for (SessionManifest manifest : list) {
            if (manifest.name.equals(sessionName)) return manifest;
        }
        return null;
    }

    /** True if {@code toolName} of {@code packageName} is a session tool (closed outside it). */
    public boolean isSessionTool(String packageName, String toolName) {
        List<SessionManifest> list = packageName != null ? mByPackage.get(packageName) : null;
        if (list == null) return false;
        for (SessionManifest manifest : list) {
            if (manifest.tools.contains(toolName)) return true;
        }
        return false;
    }

    /** The session {@code toolName} starts in {@code packageName}, or null if it is no start tool. */
    public SessionManifest byStartTool(String packageName, String toolName) {
        List<SessionManifest> list = packageName != null ? mByPackage.get(packageName) : null;
        if (list == null) return null;
        for (SessionManifest manifest : list) {
            if (toolName.equals(manifest.startTool)) return manifest;
        }
        return null;
    }

    public List<SessionManifest> all() {
        List<SessionManifest> all = new ArrayList<>();
        for (List<SessionManifest> list : mByPackage.values()) all.addAll(list);
        return all;
    }

    void dump(PrintWriter pw) {
        List<SessionManifest> all = all();
        if (all.isEmpty()) pw.println("declared: none");
        for (SessionManifest manifest : all) pw.println("declared: " + manifest);
    }
}
