package com.android.server.rag.core;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import android.app.rag.IRagService;

/**
 * Public API for the JarvisOS RAG Service.
 *
 * Apps obtain an instance via:
 *   RagManager rm = (RagManager) context.getSystemService("rag");
 *
 * This class hides Binder IPC complexity and provides a clean interface.
 * All calls are synchronous — run them on a background thread.
 *
 * Example:
 *   RagManager rm = (RagManager) context.getSystemService("rag");
 *   if (rm != null && rm.isReady()) {
 *       String answer = rm.query("What did I work on yesterday?");
 *   }
 */
public class RagManager {

    private static final String TAG          = "RagManager";
    private static final String SERVICE_NAME = "rag";

    private final Context mContext;
    private IRagService   mService;

    /** @hide — instantiated by SystemServiceRegistry, not by apps directly. */
    public RagManager(Context context) {
        mContext = context;
    }

    // -------------------------------------------------------------------------
    // Core query API
    // -------------------------------------------------------------------------

    /**
     * Submit a natural language query to the RAG pipeline.
     *
     * Internally: Stage 1 metadata search → Stage 2 semantic re-rank → LLM completion.
     *
     * @param query the user's question
     * @return LLM response with retrieved context injected
     * @throws RagException if the service is unavailable or the query fails
     */
    public String query(String query) throws RagException {
        validateNotEmpty(query, "query");
        try {
            return requireService().processQuery(query);
        } catch (RemoteException e) {
            Log.e(TAG, "query() failed", e);
            throw new RagException("Failed to communicate with RAG service", e);
        }
    }

    // -------------------------------------------------------------------------
    // Indexing API
    // -------------------------------------------------------------------------

    /**
     * Request immediate indexing of a file.
     *
     * The file is pushed to IndexQueue and processed by the next
     * RagIndexWorker run (charging constraint, 15-min interval).
     *
     * @param path absolute path to the file
     * @throws RagException if the service is unavailable
     */
    public void indexDocument(String path) throws RagException {
        validateNotEmpty(path, "path");
        try {
            requireService().indexDocument(path);
        } catch (RemoteException e) {
            Log.e(TAG, "indexDocument() failed", e);
            throw new RagException("Failed to index document", e);
        }
    }

    /**
     * Check whether a file has been indexed.
     *
     * @param path absolute path to the file
     * @return true if the file is in ObjectBox with isIndexed = true
     * @throws RagException if the service is unavailable
     */
    public boolean isIndexed(String path) throws RagException {
        validateNotEmpty(path, "path");
        try {
            return requireService().isIndexed(path);
        } catch (RemoteException e) {
            Log.e(TAG, "isIndexed() failed", e);
            throw new RagException("Failed to check index status", e);
        }
    }

    // -------------------------------------------------------------------------
    // Service health
    // -------------------------------------------------------------------------

    /**
     * Returns true if RagService has finished initialization.
     * Safe to call from any thread — returns false if service is unreachable.
     */
    public boolean isReady() {
        try {
            IRagService svc = getService();
            return svc != null && svc.isReady();
        } catch (RemoteException e) {
            Log.w(TAG, "isReady() RemoteException", e);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Get or lazily connect to the Binder service. */
    private IRagService getService() {
        if (mService == null) {
            mService = IRagService.Stub.asInterface(
                    ServiceManager.getService(SERVICE_NAME));
        }
        return mService;
    }

    /**
     * Same as getService() but throws RagException if service is null.
     * Use this in all public methods that require the service to be present.
     */
    private IRagService requireService() throws RagException {
        IRagService svc = getService();
        if (svc == null) {
            throw new RagException("RAG service not available — is JarvisOS running?");
        }
        return svc;
    }

    private static void validateNotEmpty(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " cannot be null or empty");
        }
    }
}
