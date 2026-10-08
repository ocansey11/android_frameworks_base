package com.android.server.jarvis.inference;

import android.util.Log;

/**
 * JNI bridge to libneedle_jarvis.so (vendor/jarvisos/native/needle), the only
 * entry point to the Needle engine. Use {@link Needle}, not this class: the
 * engine holds one process-global model per kind and is not thread-safe, and
 * {@link Needle} is what keeps every call on one thread.
 */
final class NeedleWrapper {

    private static final String TAG = "NeedleWrapper";

    /** Bits of {@link #nativeModels()}. */
    static final int TEXT = 1;
    static final int SPEECH = 2;

    private static final boolean sLoaded = load();

    private NeedleWrapper() {}

    private static boolean load() {
        try {
            System.loadLibrary("needle_jarvis");
            Log.i(TAG, "Needle native library loaded");
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Needle native library not available: " + e.getMessage());
            return false;
        }
    }

    static boolean isLoaded() { return sLoaded; }

    static native int nativeLoad(String cactPath);
    static native int nativeModels();
    static native int nativeInit(String systemPrompt, String toolsJson, String toolIndexPath);
    static native String nativeComplete(String input, float[] pcm, int maxNewTokens);
    static native void nativeSetAudio(String language, String keywords, boolean wordTimestamps);
    static native String nativeTranscribe(float[] pcm, String language, String keywords);
    static native void nativeReset();
    static native String nativeGetLastError();
}
