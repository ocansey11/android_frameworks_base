package com.android.server.rag;

import android.util.Slog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits documents into chunks for RAG processing
 */
public class ChunkingStrategy {
    private static final String TAG = "ChunkingStrategy";
    
    // Configuration
    private static final int MAX_CHUNK_SIZE = 400;  // ~512 tokens worth of chars
    private static final int OVERLAP_SIZE = 50;     // overlap between chunks
    
    /**
     * Chunk a file into processable segments
     * @param filePath Path to document
     * @return List of chunks with metadata
     */
    public List<Chunk> chunkFile(String filePath) throws IOException {
        Slog.i(TAG, "Chunking file: " + filePath);
        
        // Read entire file
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
     * Chunk raw text using sentence-based strategy with overlap
     * @param text Text content
     * @param sourceId Identifier for source
     * @return List of chunks
     */
    public List<Chunk> chunkText(String text, String sourceId) {
        Slog.i(TAG, "Chunking text from: " + sourceId + " (length: " + text.length() + ")");
        
        List<Chunk> chunks = new ArrayList<>();
        
        // Split into sentences (simple approach)
        String[] sentences = text.split("(?<=[.!?])\\s+");
        
        StringBuilder currentChunk = new StringBuilder();
        int chunkPosition = 0;
        int startOffset = 0;
        
        for (int i = 0; i < sentences.length; i++) {
            String sentence = sentences[i];
            
            // If adding this sentence exceeds max size, save current chunk
            if (currentChunk.length() + sentence.length() > MAX_CHUNK_SIZE && currentChunk.length() > 0) {
                // Create chunk
                String chunkText = currentChunk.toString().trim();
                int endOffset = startOffset + chunkText.length();
                
                chunks.add(new Chunk(
                    chunkText,
                    sourceId,
                    chunkPosition++,
                    startOffset,
                    endOffset
                ));
                
                Slog.d(TAG, "Created chunk #" + (chunkPosition - 1) + " (size: " + chunkText.length() + ")");
                
                // Start new chunk with overlap
                currentChunk = new StringBuilder();
                
                // Add last OVERLAP_SIZE characters for context
                String overlap = getOverlap(chunkText, OVERLAP_SIZE);
                currentChunk.append(overlap);
                startOffset = endOffset - overlap.length();
            }
            
            currentChunk.append(sentence).append(" ");
        }
        
        // Add final chunk if there's remaining content
        if (currentChunk.length() > 0) {
            String chunkText = currentChunk.toString().trim();
            chunks.add(new Chunk(
                chunkText,
                sourceId,
                chunkPosition,
                startOffset,
                startOffset + chunkText.length()
            ));
            
            Slog.d(TAG, "Created final chunk #" + chunkPosition + " (size: " + chunkText.length() + ")");
        }
        
        Slog.i(TAG, "Chunking complete: " + chunks.size() + " chunks created");
        return chunks;
    }
    
    /**
     * Get last N characters for overlap
     */
    private String getOverlap(String text, int size) {
        if (text.length() <= size) {
            return text;
        }
        return text.substring(text.length() - size);
    }
}


