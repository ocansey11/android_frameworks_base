package com.android.server.jarvis.agent;

import android.util.Log;

import com.android.server.jarvis.core.JarvisStore;
import com.android.server.jarvis.core.ModelRegistry;
import com.android.server.jarvis.inference.CactusWrapper;
import com.android.server.jarvis.model.AgentSession;
import com.android.server.jarvis.model.AgentTurn;
import com.android.server.jarvis.model.DocumentChunk;
import com.android.server.jarvis.model.SourceFile;
import com.android.server.jarvis.model.SourceFile_;
import com.android.server.jarvis.search.MetadataSearch;

import java.util.List;

/**
 * RetrieveNode — fetches relevant context from the RAG index.
 *
 * Stage 1: MetadataSearch (keyword) over ObjectBox — fast, no model call.
 * Stage 2: CactusWrapper.embed(query) + indexQuery — semantic HNSW search.
 *
 * Results are appended to session.accumulatedContext.
 * RouterNode will not route to RetrieveNode again if accumulatedContext is non-empty,
 * preventing retrieval loops.
 *
 * Abstraction note: RetrieveNode does NOT call back into JarvisService via Binder.
 * It calls the RAG internals directly — it is inside the same process.
 * (AGENTIC_LOOP.md says "via Binder" but that would be slower and is only needed
 * for sub-agent isolation, which is Phase 6.)
 */
public class RetrieveNode {

    private static final String TAG = "RetrieveNode";
    private static final int TOP_K = 5;

    /**
     * Run the retrieval step.
     *
     * @param session current session — originalQuery must be set
     * @return retrieved context string, or null on failure
     */
    public String execute(AgentSession session) {
        String query = session.originalQuery;
        StringBuilder context = new StringBuilder();

        // Stage 1 — keyword search
        try {
            List<SourceFile> keywordHits = MetadataSearch.search(query);
            for (SourceFile sf : keywordHits) {
                context.append("[file: ").append(sf.fileName).append("]\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "Stage 1 metadata search failed", e);
        }

        // Stage 2 — semantic search
        ModelRegistry.ModelEntry ragModel = ModelRegistry.getInstance().getReady("rag");
        if (ragModel != null) {
            try {
                float[] embedding = CactusWrapper.embed(ragModel.modelHandle, query);
                if (embedding != null) {
                    int[] chunkIds = CactusWrapper.indexQuery(
                            ragModel.indexHandle, embedding, TOP_K);
                    if (chunkIds != null) {
                        for (int id : chunkIds) {
                            DocumentChunk chunk = JarvisStore.box(DocumentChunk.class).get(id);
                            if (chunk != null && chunk.summary != null) {
                                context.append(chunk.summary).append("\n");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Stage 2 semantic search failed", e);
            }
        }

        if (context.length() == 0) {
            Log.d(TAG, "No context retrieved for query");
            return null;
        }

        String retrieved = context.toString().trim();

        // Append to accumulated context
        if (session.accumulatedContext == null) {
            session.accumulatedContext = retrieved;
        } else {
            session.accumulatedContext += "\n" + retrieved;
        }

        session.status = "RETRIEVING";

        AgentTurn turn = new AgentTurn();
        turn.role = "model";
        turn.content = "[retrieved " + retrieved.length() + " chars of context]";
        turn.timestamp = System.currentTimeMillis();
        session.turns.add(turn);

        Log.d(TAG, "Retrieved " + retrieved.length() + " chars");
        return retrieved;
    }
}
