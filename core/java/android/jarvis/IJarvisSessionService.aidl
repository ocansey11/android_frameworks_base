package android.jarvis;

import android.jarvis.IJarvisSessionCallback;

/**
 * Sessions (Agent Experience, vendor/jarvisos/AGENT_EXPERIENCE.md section 11):
 * an app lets Jarvis take part while it runs, for example as the other player
 * in a game. Published as "jarvis_session".
 *
 * Unlike IJarvisService this needs no permission: any app may call it, but
 * only for a session its own package declares in its manifest
 * (meta-data "com.jarvisos.session"), and only while it is in the foreground.
 * Apps use it through JarvisSession, never directly.
 *
 * The app sends events (data). It never sends prompt text: Jarvis builds its
 * prompt itself from the session manifest, its own memory and the events.
 *
 * Errors are thrown: SecurityException (not the caller's session, or not in
 * the foreground), IllegalArgumentException (unknown session, event or tool;
 * data too large or not JSON), IllegalStateException (session not open,
 * too many events).
 *
 * @hide
 */
interface IJarvisSessionService {

    /**
     * Opens the session {@code sessionName} declared by {@code packageName},
     * which must belong to the caller. Ends any other open session first.
     *
     * @param closedTools session tools Jarvis must not use this time (the app
     *                    may close tools it declared, never open others); may be null
     * @param callback    where Jarvis reports what it says and when a turn ends
     * @return the session id, passed to the other calls
     */
    String startSession(String packageName, String sessionName, in String[] closedTools,
            IJarvisSessionCallback callback);

    /**
     * Sends one event, e.g. "your_turn" with {"board":"X........","legal":[1,2,3]}.
     * The event must be one the manifest lists. The manifest's turn event wakes
     * Jarvis for one turn; the others are remembered for the next turn's prompt.
     */
    void sendEvent(String sessionId, String event, String dataJson);

    /** Ends the session, with an optional last event (e.g. "game_over"); both may be null. */
    void endSession(String sessionId, String event, String dataJson);
}
