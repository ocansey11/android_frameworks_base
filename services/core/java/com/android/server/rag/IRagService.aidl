package android.app.rag;

/**
 * Binder interface for the JarvisOS RAG Service.
 *
 * Apps talk to this via RagManager — never directly.
 * The service implementation lives in RagService.java (com.android.server.rag).
 *
 * Adding a method here requires:
 *   1. Add the method signature below
 *   2. Implement it in RagService.mBinder
 *   3. Add the public wrapper in RagManager
 */
interface IRagService {

    /** Submit a natural language query. Returns LLM response string. */
    String processQuery(String query);

    /** Push a file path into IndexQueue for background indexing. */
    void indexDocument(String path);

    /** Returns true if the file is in ObjectBox with isIndexed = true. */
    boolean isIndexed(String path);

    /** Returns true if RagService has finished initialization. */
    boolean isReady();
}
