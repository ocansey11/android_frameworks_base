package com.android.server.rag.indexing;

import android.util.Log;

import com.android.server.rag.model.Chunk;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits document text into overlapping chunks for RAG indexing.
 *
 * Strategy: sentence-boundary splitting with character overlap.
 *   - Split on sentence terminators (.  !  ?)
 *   - Accumulate sentences until MAX_CHUNK_SIZE is reached
 *   - When a chunk is full, carry over the last OVERLAP_SIZE characters
 *     so the next chunk has context from the previous one
 *
 * Why overlap?
 *   A question might land exactly on a chunk boundary. The overlap
 *   ensures the answer is fully present in at least one chunk.
 *
 * Constants:
 *   MAX_CHUNK_SIZE = 400 chars ≈ 100 tokens — fits within Cactus embed context
 *   OVERLAP_SIZE   = 50 chars  — enough for one sentence of carry-over
 */
public class ChunkingStrategy {

    private static final String TAG = "ChunkingStrategy";

    private static final int MAX_CHUNK_SIZE = 400;
    private static final int OVERLAP_SIZE   = 50;

    // Singleton — stateless, no need for multiple instances
    private static final ChunkingStrategy INSTANCE = new ChunkingStrategy();

    private ChunkingStrategy() {}

    public static ChunkingStrategy getInstance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Read a file from disk and chunk its text content.
     *
     * @param filePath absolute path to the file
     * @return ordered list of Chunk objects
     * @throws IOException if the file cannot be read
     */
    public List<Chunk> chunkFile(String filePath) throws IOException {
        Log.i(TAG, "Chunking file: " + filePath);

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }

        return chunkText(content.toString(), filePath);
    }

    /**
     * Chunk a raw string.
     *
     * @param text     full document text
     * @param sourceId filePath or any identifier — stored in each Chunk
     * @return ordered list of Chunk objects
     */
    public List<Chunk> chunkText(String text, String sourceId) {
        if (text == null || text.trim().isEmpty()) {
            Log.w(TAG, "chunkText: empty text for " + sourceId);
            return new ArrayList<>();
        }

        Log.i(TAG, "Chunking text, source=" + sourceId + " length=" + text.length());

        List<Chunk> chunks   = new ArrayList<>();
        String[] sentences   = text.split("(?<=[.!?])\\s+");

        StringBuilder current = new StringBuilder();
        int chunkIndex        = 0;
        int startOffset       = 0;

        for (String sentence : sentences) {
            // If adding this sentence would overflow, flush the current chunk
            if (current.length() + sentence.length() > MAX_CHUNK_SIZE && current.length() > 0) {
                String chunkText = current.toString().trim();
                int endOffset    = startOffset + chunkText.length();

                chunks.add(new Chunk(chunkText, sourceId, chunkIndex++, startOffset, endOffset));
                Log.d(TAG, "Chunk #" + (chunkIndex - 1) + " size=" + chunkText.length());

                // Carry over tail for overlap context
                String overlap = tailOf(chunkText, OVERLAP_SIZE);
                current        = new StringBuilder(overlap);
                startOffset    = endOffset - overlap.length();
            }

            current.append(sentence).append(" ");
        }

        // Flush whatever is left
        if (current.length() > 0) {
            String chunkText = current.toString().trim();
            chunks.add(new Chunk(chunkText, sourceId, chunkIndex, startOffset,
                    startOffset + chunkText.length()));
            Log.d(TAG, "Final chunk #" + chunkIndex + " size=" + chunkText.length());
        }

        Log.i(TAG, "Done: " + chunks.size() + " chunks from " + sourceId);
        return chunks;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Return the last {@code size} characters of {@code text}. */
    private static String tailOf(String text, int size) {
        if (text.length() <= size) return text;
        return text.substring(text.length() - size);
    }
}
