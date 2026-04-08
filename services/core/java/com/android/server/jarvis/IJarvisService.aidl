package com.android.server.jarvis;

/**
 * Server-side Binder stub for JarvisOS.
 *
 * Apps talk to this via JarvisManager — never directly.
 * Implementation lives in JarvisService.mBinder.
 *
 * Adding a method here requires:
 *   1. Add the method signature below
 *   2. Implement it in JarvisService.mBinder
 *   3. Add the public wrapper in JarvisManager (android/jarvis/)
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
