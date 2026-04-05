package android.rag;

/**
 * Public Binder interface for the JarvisOS Tool Registry.
 *
 * Published as service name "jarvis_tools" from RagService.
 * Apps use this to inspect what tools are registered on the device.
 *
 * All return values are JSON strings. Null = not found / store not ready.
 */
interface IToolRegistry {

    /**
     * Returns a JSON array of all registered tools.
     * Each element is a tool object: {id, toolName, description, paramsJson,
     * receiverClass, cactusIndexId, app:{packageName, appLabel, sourceType}}
     */
    String listTools();

    /**
     * Returns the JSON object for a single tool by its ObjectBox id.
     * Returns null if not found.
     */
    String getTool(long id);

    /**
     * Semantic + metadata search over registered tools.
     * Returns a JSON array of matching tools (up to TOP_K), ordered by relevance.
     * Uses Cactus HNSW if available, falls back to keyword match.
     */
    String searchTools(String query);
}
