package android.jarvis;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * Public API for JarvisOS.
 *
 * Entry point for apps integrating with JarvisOS capabilities:
 * RAG retrieval, tool dispatch, and (Phase 5) the agentic loop.
 *
 * Example usage:
 *   JarvisManager jarvis = context.getSystemService(JarvisManager.class);
 *   String answer = jarvis.query("What did I work on yesterday?");
 *
 * @hide
 */
public class JarvisManager {

    private static final String TAG          = "JarvisManager";
    private static final String SERVICE_NAME = "jarvis";
    private static final String TOOLS_SERVICE_NAME = "jarvis_tools";

    private final Context mContext;
    private IJarvisService mService;
    private IToolRegistry mToolRegistry;

    /** @hide — instantiated by SystemServiceRegistry, not by apps directly. */
    public JarvisManager(Context context) {
        mContext = context;
    }

    // -------------------------------------------------------------------------
    // Core query API
    // -------------------------------------------------------------------------

    /**
     * Submit a natural language query to Jarvis.
     *
     * Internally: tool path first → RAG pipeline fallback.
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
     * The file is pushed to IndexQueue and processed by the next
     * JarvisIndexWorker run (charging constraint, 15-min interval).
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
    // Multimodal API (Phase 6)
    // -------------------------------------------------------------------------

    /**
     * Submit a query with an image.
     *
     * @param query     natural language question about the image
     * @param imageData raw JPEG or PNG bytes (null = text-only fallback)
     * @return response string
     * @throws JarvisException if the service is unavailable
     */
    public String queryWithImage(String query, byte[] imageData) throws JarvisException {
        validateNotEmpty(query, "query");
        try {
            return requireService().processQueryWithImage(query, imageData);
        } catch (RemoteException e) {
            Log.e(TAG, "queryWithImage() failed", e);
            throw new JarvisException("Failed to process image query", e);
        }
    }

    /**
     * Submit a query with audio input.
     *
     * @param query   optional text context (may be empty — model uses audio as primary input)
     * @param pcmData 16kHz mono 16-bit PCM bytes (null = text-only fallback)
     * @return response string
     * @throws JarvisException if the service is unavailable
     */
    public String queryWithAudio(String query, byte[] pcmData) throws JarvisException {
        try {
            return requireService().processQueryWithAudio(
                    query != null ? query : "", pcmData);
        } catch (RemoteException e) {
            Log.e(TAG, "queryWithAudio() failed", e);
            throw new JarvisException("Failed to process audio query", e);
        }
    }

    // -------------------------------------------------------------------------
    // Service health
    // -------------------------------------------------------------------------

    /**
     * Returns true if JarvisService has finished initialization.
     * Safe to call from any thread — returns false if service is unreachable.
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

    /**
     * Service state as a JSON object string:
     * {ready, store, models:{rag, tools, primary}, apps, tools}.
     *
     * @throws JarvisException if the service is unavailable
     */
    public String getStatus() throws JarvisException {
        try {
            return requireService().getStatus();
        } catch (RemoteException e) {
            Log.e(TAG, "getStatus() failed", e);
            throw new JarvisException("Failed to communicate with Jarvis service", e);
        }
    }

    // -------------------------------------------------------------------------
    // Tool registry API
    // -------------------------------------------------------------------------

    /**
     * Every registered tool as a JSON array string (see IToolRegistry.listTools).
     *
     * @throws JarvisException if the service is unavailable
     */
    public String listTools() throws JarvisException {
        try {
            return requireToolRegistry().listTools();
        } catch (RemoteException e) {
            Log.e(TAG, "listTools() failed", e);
            throw new JarvisException("Failed to communicate with Jarvis service", e);
        }
    }

    /**
     * Call a tool by its exact name. Blocks until the tool replies or times
     * out (10 seconds), so do not call this on the main thread.
     *
     * @param toolName exact name from the registry
     * @param argsJson JSON object of arguments, or null for none
     * @return the tool's reply, or a string starting "Error:"
     * @throws JarvisException if the service is unavailable
     */
    public String callTool(String toolName, String argsJson) throws JarvisException {
        validateNotEmpty(toolName, "toolName");
        try {
            return requireToolRegistry().callTool(toolName, argsJson);
        } catch (RemoteException e) {
            Log.e(TAG, "callTool() failed", e);
            throw new JarvisException("Failed to call tool", e);
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

    private IToolRegistry requireToolRegistry() throws JarvisException {
        if (mToolRegistry == null) {
            mToolRegistry = IToolRegistry.Stub.asInterface(
                    ServiceManager.getService(TOOLS_SERVICE_NAME));
        }
        if (mToolRegistry == null) {
            throw new JarvisException("Jarvis service not available — is JarvisOS running?");
        }
        return mToolRegistry;
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
