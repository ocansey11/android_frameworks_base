package com.android.server.rag.model;

/**
 * A single chunk of text extracted from a SourceFile.
 *
 * Passed between ChunkingStrategy → RagIndexWorker → CactusWrapper.
 * Never persisted directly — DocumentChunk is the ObjectBox entity.
 */
public class Chunk {

    private final String text;
    private final String sourceId;   // filePath of the originating file
    private final int position;      // chunk index within the document
    private final int startOffset;   // character offset in original text
    private final int endOffset;

    public Chunk(String text, String sourceId, int position, int startOffset, int endOffset) {
        this.text = text;
        this.sourceId = sourceId;
        this.position = position;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    public String getText()      { return text; }
    public String getSourceId()  { return sourceId; }
    public int getPosition()     { return position; }
    public int getStartOffset()  { return startOffset; }
    public int getEndOffset()    { return endOffset; }

    /** Rough token count — 1 token ≈ 4 characters for English text. */
    public int estimateTokenCount() {
        return Math.max(1, text.length() / 4);
    }

    @Override
    public String toString() {
        return "Chunk{pos=" + position + ", len=" + text.length()
                + ", source=" + sourceId + "}";
    }
}
