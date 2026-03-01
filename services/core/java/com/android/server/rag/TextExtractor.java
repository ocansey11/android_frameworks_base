package com.android.server.rag;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Extracts raw text from files for chunking and indexing.
 *
 * Supported formats:
 *   .txt  — read directly
 *   .md   — read directly (markdown is plain text)
 *   .csv  — read directly
 *   .pdf  — basic extraction (TODO: use PdfRenderer or pdfbox)
 *   .docx — TODO: requires Apache POI or similar
 *
 * Returns null if the file type is unsupported or extraction fails.
 * Caller (RagIndexWorker) should skip null results gracefully.
 */
public class TextExtractor {

    private static final String TAG = "TextExtractor";
    private static final int MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB cap

    public static String extract(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            Log.w(TAG, "File does not exist: " + file);
            return null;
        }

        if (file.length() > MAX_FILE_SIZE_BYTES) {
            Log.w(TAG, "File too large, skipping: " + file.getName() + " (" + file.length() + " bytes)");
            return null;
        }

        String name = file.getName().toLowerCase();

        if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv")) {
            return extractPlainText(file);
        }

        if (name.endsWith(".pdf")) {
            return extractPdf(file);
        }

        if (name.endsWith(".docx")) {
            return extractDocx(file);
        }

        Log.d(TAG, "Unsupported file type, skipping: " + file.getName());
        return null;
    }

    // Plain text — read line by line
    private static String extractPlainText(File file) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            Log.d(TAG, "Extracted plain text from: " + file.getName() + " (" + sb.length() + " chars)");
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract plain text: " + file.getName(), e);
            return null;
        }
    }

    // PDF — basic byte-level text extraction
    // TODO: replace with PdfRenderer or Apache PDFBox for proper extraction
    private static String extractPdf(File file) {
        Log.w(TAG, "PDF extraction not yet fully implemented: " + file.getName());

        // Basic fallback — attempt to read raw bytes and pull ASCII text
        // This works for simple PDFs but will miss formatted content
        try {
            byte[] bytes = readBytes(file);
            if (bytes == null) return null;

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bytes.length - 1; i++) {
                char c = (char) bytes[i];
                if (c >= 32 && c < 127) { // printable ASCII only
                    sb.append(c);
                } else if (c == '\n' || c == '\r') {
                    sb.append('\n');
                }
            }
            String raw = sb.toString().replaceAll("\\s{3,}", "\n\n").trim();
            Log.d(TAG, "Extracted raw PDF text from: " + file.getName() + " (" + raw.length() + " chars)");
            return raw.isEmpty() ? null : raw;

        } catch (Exception e) {
            Log.e(TAG, "Failed to extract PDF: " + file.getName(), e);
            return null;
        }
    }

    // DOCX — TODO: requires Apache POI (not yet in build)
    private static String extractDocx(File file) {
        Log.w(TAG, "DOCX extraction not yet implemented: " + file.getName());
        // TODO: add Apache POI to vendor/jarvisos/prebuilts and use
        // XWPFDocument to extract paragraphs
        return null;
    }

    private static byte[] readBytes(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            return bytes;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read bytes: " + file.getName(), e);
            return null;
        }
    }
}
