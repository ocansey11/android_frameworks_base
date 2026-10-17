package android.jarvis;

/**
 * How Jarvis talks back to an app during a session. One-way: Jarvis never
 * waits for the app here. Apps use JarvisSession.Listener instead.
 *
 * @hide
 */
oneway interface IJarvisSessionCallback {

    /** Jarvis started a turn after {@code event}; a good moment to show "thinking". */
    void onTurnStarted(String event);

    /** Jarvis called say(text) during a turn. Show it, e.g. as a speech bubble. */
    void onSay(String text);

    /**
     * The turn is over. {@code done} is false when Jarvis gave up (no legal
     * call after its retries); the app then decides what happens, for example
     * a random legal move. {@code detail} says why, for logs.
     */
    void onTurnEnded(boolean done, String detail);

    /** The session ended: "ended by app", "app in background", "app died", ... */
    void onSessionEnded(String reason);
}
