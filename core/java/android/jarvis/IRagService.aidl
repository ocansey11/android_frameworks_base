package android.jarvis;

/**
 * Binder interface for JarvisOS RAG Service
 * 
 * This is the contract between apps and the system service.
 * Apps use RagManager which calls these methods via Binder IPC.
 */
interface IRagService {
    /**
     * Submit a query to the RAG service
     * 
     * @param query The user's question
     * @return The LLM response with context
     */
    String processQuery(String query);
    
    /**
     * Index a document for RAG retrieval
     * 
     * @param path File path to index
     */
    void indexDocument(String path);
    
    /**
     * Check if the RAG service is ready
     * 
     * @return true if initialized and ready
     */
    boolean isReady();
}

