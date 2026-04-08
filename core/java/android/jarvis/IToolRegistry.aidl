package android.jarvis;

/**
 * Public Binder interface for the JarvisOS Tool Registry.
 *
 * Published as service name "jarvis_tools" from JarvisService.
 * Apps use this to inspect what tools are registered on the device.
 *
 * Return value contract:
 *   - listTools() / searchTools() return "[]" when store is not ready or no results.
 *   - getTool(id) returns null when not found or store is not ready.
 *   - All other failures return "[]" (list methods) or null (single-item methods).
 *
 * Note: listTools() returns the full registry in one Binder call. This is fine
 * for small registries but risks TransactionTooLargeException at scale.
 * Pagination (offset/limit) will be added in a future phase.
 */
interface IToolRegistry {

    /**
     * Returns a JSON array of all registered tools, or "[]" if none / not ready.
     * Each element is a tool object:
     * {id, toolName, description, paramsJson, rawDefinition, receiverClass,
     * cactusIndexId, app:{id, packageName, appLabel, sourceType}}
     *
     * Note: paramsJson is itself a JSON-encoded string, not an embedded JSON object.
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
