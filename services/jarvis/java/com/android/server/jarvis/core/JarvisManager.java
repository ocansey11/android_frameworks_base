package com.android.server.jarvis.core;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import android.jarvis.IJarvisService;

/**
 * Internal API manager for JarvisOS.
 *
 * Used inside system_server to call back into JarvisService via Binder.
 * Apps use the public android.jarvis.JarvisManager instead.
 *
 * All calls are synchronous — run on a background thread.
 */
public class JarvisManager {

    private static final String TAG          = "JarvisManager";
    private static final String SERVICE_NAME = "jarvis";

    private final Context mContext;
    private IJarvisService mService;

    /** @hide — instantiated internally, not by apps directly. */
    public JarvisManager(Context context) {
        mContext = context;
    }

    // -------------------------------------------------------------------------
    // Core query API
    // -------------------------------------------------------------------------

    /**
     * Submit a natural language query to Jarvis.
     *
     * @param query the user's question
     * @return response string (from tool or RAG)
     * @throws JarvisException if the service is unavailable or the query fails
     */
    public String query(String query) throws JarvisException {
        validateNotEmpty(query, "query");
        try {
            return requireService().processQuery(query);
        } catch (RemoteException e) {
            Log.e(TAG, "query() failed", e);
            throw new JarvisException("Failed to communicate with Jarvis service", e);
        }
    }

    // -------------------------------------------------------------------------
    // Indexing API
    // -------------------------------------------------------------------------

    /**
     * Request immediate indexing of a file.
     *
     * @param path absolute path to the file
     * @throws JarvisException if the service is unavailable
     */
    public void indexDocument(String path) throws JarvisException {
        validateNotEmpty(path, "path");
        try {
            requireService().indexDocument(path);
        } catch (RemoteException e) {
            Log.e(TAG, "indexDocument() failed", e);
            throw new JarvisException("Failed to index document", e);
        }
    }

    /**
     * Check whether a file has been indexed.
     *
     * @param path absolute path to the file
     * @return true if the file is in ObjectBox with isIndexed = true
     * @throws JarvisException if the service is unavailable
     */
    public boolean isIndexed(String path) throws JarvisException {
        validateNotEmpty(path, "path");
        try {
            return requireService().isIndexed(path);
        } catch (RemoteException e) {
            Log.e(TAG, "isIndexed() failed", e);
            throw new JarvisException("Failed to check index status", e);
        }
    }

    // -------------------------------------------------------------------------
    // Service health
    // -------------------------------------------------------------------------

    /**
     * Returns true if JarvisService has finished initialization.
     */
    public boolean isReady() {
        try {
            IJarvisService svc = getService();
            return svc != null && svc.isReady();
        } catch (RemoteException e) {
            Log.w(TAG, "isReady() RemoteException", e);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private IJarvisService getService() {
        if (mService == null) {
            mService = IJarvisService.Stub.asInterface(
                    ServiceManager.getService(SERVICE_NAME));
        }
        return mService;
    }

    private IJarvisService requireService() throws JarvisException {
        IJarvisService svc = getService();
        if (svc == null) {
            throw new JarvisException("Jarvis service not available — is JarvisOS running?");
        }
        return svc;
    }

    private static void validateNotEmpty(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be null or empty");
        }
    }
}
