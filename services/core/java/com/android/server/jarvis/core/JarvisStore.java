package com.android.server.jarvis.core;

import android.util.Log;

import io.objectbox.Box;
import io.objectbox.BoxStore;

/**
 * JarvisStore — ObjectBox store singleton.
 *
 * Initializes once at RagService startup. All other classes
 * (MetadataSearch, RagIndexWorker) call JarvisStore.get() to
 * retrieve boxes.
 *
 * Store lives at: /data/system/jarvis/objectbox/
 *
 * Usage:
 *   JarvisStore.init(dataDir);          // called once from RagService
 *   Box<SourceFile> box = JarvisStore.get().boxFor(SourceFile.class);
 */
public class JarvisStore {

    private static final String TAG = "JarvisStore";
    private static volatile BoxStore sStore;

    /** Initialize the ObjectBox store. Call once from RagService.initializeAsync(). */
    public static void init(String dataDir) {
        if (sStore != null) {
            Log.w(TAG, "Store already initialized — skipping");
            return;
        }
        synchronized (JarvisStore.class) {
            if (sStore != null) return;
            try {
                sStore = MyObjectBox.builder()
                        .directory(new java.io.File(dataDir))
                        .build();
                Log.i(TAG, "ObjectBox store initialized at: " + dataDir
                        + " | version: " + BoxStore.VERSION);
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize ObjectBox store", e);
                throw new RuntimeException("ObjectBox init failed", e);
            }
        }
    }

    /** Get the live store. Throws if init() was not called first. */
    public static BoxStore get() {
        if (sStore == null) {
            throw new IllegalStateException("JarvisStore not initialized — call init() first");
        }
        return sStore;
    }

    /** Convenience — get a Box for any entity class. */
    public static <T> Box<T> box(Class<T> entityClass) {
        return get().boxFor(entityClass);
    }

    /** Close the store — call on system service shutdown. */
    public static void close() {
        if (sStore != null && !sStore.isClosed()) {
            sStore.close();
            sStore = null;
            Log.i(TAG, "ObjectBox store closed");
        }
    }

    public static boolean isReady() {
        return sStore != null && !sStore.isClosed();
    }
}
