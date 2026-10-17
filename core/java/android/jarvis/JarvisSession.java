package android.jarvis;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * A session with Jarvis, seen from the app (Agent Experience, see
 * vendor/jarvisos/AGENT_EXPERIENCE.md section 11).
 *
 * The app declares the session once, in its manifest:
 *
 * <pre>
 *   &lt;meta-data android:name="com.jarvisos.session" android:resource="@xml/jarvis_session" /&gt;
 * </pre>
 *
 * and at run time only sends events, which are data, never prompts:
 *
 * <pre>
 *   JarvisSession s = JarvisSession.start(context, "tic_tac_toe", null, listener);
 *   s.event("player_moved", "{\"cell\":0,\"board\":\"X........\"}");
 *   s.event("your_turn",    "{\"board\":\"X........\",\"legal\":[1,2,3,4,5,6,7,8]}");
 *   s.end("game_over",      "{\"winner\":\"player\"}");
 * </pre>
 *
 * Jarvis answers by calling the app's session tools (ordinary Jarvis tools,
 * declared as receivers) and through the {@link Listener}. The session ends
 * when the app calls {@link #end}, goes to the background, or dies.
 *
 * Calls block briefly on a Binder round trip; listener methods run on the
 * main thread.
 *
 * @hide
 */
public final class JarvisSession {

    private static final String TAG = "JarvisSession";
    private static final String SERVICE_NAME = "jarvis_session";

    /** What Jarvis tells the app during a session. All methods run on the main thread. */
    public interface Listener {
        /** Jarvis woke up for a turn. */
        default void onTurnStarted(String event) {}
        /** Jarvis said something; show it. */
        default void onSay(String text) {}
        /** Jarvis finished its turn; {@code done} is false if it gave up. */
        default void onTurnEnded(boolean done, String detail) {}
        /** The session is over and its tools are closed. */
        default void onEnded(String reason) {}
    }

    private final IJarvisSessionService mService;
    private final String mId;
    private final String mName;
    private volatile boolean mOpen = true;

    private JarvisSession(IJarvisSessionService service, String id, String name) {
        mService = service;
        mId = id;
        mName = name;
    }

    /**
     * Starts the session {@code name} that this app declares.
     *
     * @param closedTools session tools Jarvis must not use this time, or null
     * @throws JarvisException if Jarvis is not there, or refuses (wrong name,
     *         app not in the foreground)
     */
    public static JarvisSession start(Context context, String name, String[] closedTools,
            Listener listener) throws JarvisException {
        IBinder binder = ServiceManager.getService(SERVICE_NAME);
        if (binder == null) throw new JarvisException("Jarvis is not running on this device");
        IJarvisSessionService service = IJarvisSessionService.Stub.asInterface(binder);
        final Handler main = new Handler(Looper.getMainLooper());
        final JarvisSession[] self = new JarvisSession[1];
        IJarvisSessionCallback callback = new IJarvisSessionCallback.Stub() {
            @Override public void onTurnStarted(String event) {
                if (listener != null) main.post(() -> listener.onTurnStarted(event));
            }
            @Override public void onSay(String text) {
                if (listener != null) main.post(() -> listener.onSay(text));
            }
            @Override public void onTurnEnded(boolean done, String detail) {
                if (listener != null) main.post(() -> listener.onTurnEnded(done, detail));
            }
            @Override public void onSessionEnded(String reason) {
                main.post(() -> {
                    if (self[0] != null) self[0].mOpen = false;
                    if (listener != null) listener.onEnded(reason);
                });
            }
        };
        try {
            String id = service.startSession(context.getPackageName(), name, closedTools, callback);
            self[0] = new JarvisSession(service, id, name);
            return self[0];
        } catch (RemoteException | RuntimeException e) {
            throw new JarvisException("Jarvis refused the session '" + name + "': "
                    + e.getMessage(), e);
        }
    }

    /**
     * Sends an event that the session manifest lists.
     *
     * @param dataJson a JSON object, or null; Jarvis limits its size
     * @return false if Jarvis refused it (the reason is logged)
     */
    public boolean event(String event, String dataJson) {
        if (!mOpen) {
            Log.w(TAG, mName + ": event '" + event + "' after the session ended");
            return false;
        }
        try {
            mService.sendEvent(mId, event, dataJson);
            return true;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, mName + ": event '" + event + "' refused: " + e.getMessage());
            return false;
        }
    }

    /** Ends the session, with an optional last event; both arguments may be null. */
    public void end(String event, String dataJson) {
        if (!mOpen) return;
        mOpen = false;
        try {
            mService.endSession(mId, event, dataJson);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, mName + ": end refused: " + e.getMessage());
        }
    }

    /** False once the session ended, from either side. */
    public boolean isOpen() {
        return mOpen;
    }

    public String getName() {
        return mName;
    }
}
