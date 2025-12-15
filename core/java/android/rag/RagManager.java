package android.rag;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * Manager for interacting with the JarvisOS RAG Service.
 * 
 * This is the public API that apps use. It hides the Binder complexity
 * and provides a clean interface for RAG queries.
 * 
 * Example usage:
 *   RagManager ragManager = context.getSystemService(RagManager.class);
 *   String answer = ragManager.query("What did I work on yesterday?");
 */
public class RagManager {
    private static final String TAG = "RagManager";
    private static final String SERVICE_NAME = "rag";
    
    private final Context mContext;
    private IRagService mService;
    
    /**
     * @hide - System use only
     */
    public RagManager(Context context) {
        mContext = context;
    }
    
    /**
     * Get the RAG service, connecting if needed
     */
    private IRagService getService() {
        if (mService == null) {
            mService = IRagService.Stub.asInterface(
                ServiceManager.getService(SERVICE_NAME)
            );
        }
        return mService;
    }
    
    /**
     * Submit a query to the RAG service
     * 
     * @param query The user's question
     * @return The answer from the LLM with retrieved context
     * @throws RagException if the service is unavailable or query fails
     */
    public String query(String query) throws RagException {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Query cannot be null or empty");
        }
        
        try {
            IRagService service = getService();
            if (service == null) {
                throw new RagException("RAG service not available");
            }
            
            return service.processQuery(query);
            
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to query RAG service", e);
            throw new RagException("Failed to communicate with RAG service", e);
        }
    }
    
    /**
     * Index a document for RAG retrieval
     * 
     * @param path File path to index
     * @throws RagException if indexing fails
     */
    public void indexDocument(String path) throws RagException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Path cannot be null or empty");
        }
        
        try {
            IRagService service = getService();
            if (service == null) {
                throw new RagException("RAG service not available");
            }
            
            service.indexDocument(path);
            
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to index document", e);
            throw new RagException("Failed to index document", e);
        }
    }
    
    /**
     * Check if the RAG service is ready
     * 
     * @return true if the service is initialized and ready to handle queries
     */
    public boolean isReady() {
        try {
            IRagService service = getService();
            if (service == null) {
                return false;
            }
            
            return service.isReady();
            
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to check service readiness", e);
            return false;
        }
    }
}

