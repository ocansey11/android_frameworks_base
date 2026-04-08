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

    /**
     * Submit a query with an image attachment.
     * imageData is raw JPEG or PNG bytes. Pass null for text-only queries.
     *
     * Requires Gemma 4 E2B/E4B loaded in Cactus (multimodal model).
     * Returns an error string if no multimodal model is available.
     *
     * Phase 6 — implemented in JarvisService, Cactus layer wired by Sam after upstream pull.
     */
    String processQueryWithImage(String query, in byte[] imageData);

    /**
     * Submit a query with audio input.
     * pcmData is 16kHz mono 16-bit PCM. Pass null to use query string only.
     *
     * Requires Gemma 4 E2B or a Whisper model loaded in Cactus.
     * If no audio model is ready, falls back to processing query as text.
     *
     * Phase 6 — implemented in JarvisService, Cactus layer wired by Sam after upstream pull.
     */
    String processQueryWithAudio(String query, in byte[] pcmData);
}
