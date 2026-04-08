package com.android.server.jarvis.core;

/**
 * Exception thrown when RAG operations fail
 */
public class RagException extends Exception {
    
    public RagException(String message) {
        super(message);
    }
    
    public RagException(String message, Throwable cause) {
        super(message, cause);
    }
}
