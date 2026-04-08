package com.android.server.jarvis.core;

/**
 * Exception thrown when JarvisOS service operations fail.
 */
public class JarvisException extends Exception {

    public JarvisException(String message) {
        super(message);
    }

    public JarvisException(String message, Throwable cause) {
        super(message, cause);
    }
}
