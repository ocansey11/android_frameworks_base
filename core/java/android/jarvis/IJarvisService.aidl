package android.jarvis;

/**
 * Binder interface for JarvisOS system service.
 *
 * Apps talk to this via JarvisManager — never directly.
 * The service implementation lives in JarvisService.java (com.android.server.jarvis).
 *
 * Adding a method here requires:
 *   1. Add the method signature below
 *   2. Implement it in JarvisService.mBinder
 *   3. Add the public wrapper in JarvisManager
 */
interface IJarvisService {

    /** Submit a natural language query. Returns LLM response string. */
    String processQuery(String query);

    /** Push a file path into IndexQueue for background indexing. */
    void indexDocument(String path);

    /** Returns true if the file is in ObjectBox with isIndexed = true. */
    boolean isIndexed(String path);

    /** Returns true if JarvisService has finished initialization. */
    boolean isReady();
}
