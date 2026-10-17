package com.android.server.jarvis.session;

import android.app.ActivityManager;
import android.app.BroadcastOptions;
import android.content.Context;
import android.content.pm.PackageManager;
import android.jarvis.IJarvisSessionCallback;
import android.jarvis.IJarvisSessionService;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;

import com.android.server.jarvis.agent.UserContextHelper;
import com.android.server.jarvis.core.ModelRegistry;
import com.android.server.jarvis.inference.Inference;
import com.android.server.jarvis.tools.ToolDispatcher;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sessions (AGENT_EXPERIENCE.md section 11): Jarvis takes part in an app while
 * it runs. This class is the "jarvis_session" Binder service apps call, the
 * tool fence ToolDispatcher applies, and the owner of the one open session.
 *
 * <ul>
 *   <li>Start: the app calls startSession for a session its own package
 *       declares, while it is in the foreground — after its "Play with
 *       Jarvis" button, or after Jarvis ran its start tool.
 *   <li>Events: data only, checked against the manifest, size- and
 *       rate-limited. The manifest's turn event wakes Jarvis for one turn on
 *       a worker thread; the call itself returns at once.
 *   <li>End: the app ends it, goes to the background, or dies. Then the
 *       session tools close again.
 * </ul>
 *
 * One session at a time: a new one ends the old one.
 */
public final class SessionManager extends IJarvisSessionService.Stub
        implements ToolDispatcher.Fence {

    private static final String TAG = "JarvisSessions";

    /** How long Jarvis waits, after a start tool, for the app to start its session. */
    static final long START_TIMEOUT_MS = 15_000;
    /** "1" (default) samples a turn's tool calls under the schema grammar; "0" does not. */
    public static final String PROP_GRAMMAR = "persist.sys.jarvis.session_grammar";

    private final Context mContext;
    private final SessionRegistry mRegistry;
    private final ToolDispatcher mDispatcher;
    private final ActivityManager mActivityManager;
    /** Turns run here, one at a time, never on a Binder thread. */
    private final ExecutorService mTurns = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "JarvisSessionTurn"));

    private final Object mLock = new Object();
    private Session mActive;            // guarded by mLock
    private volatile String mLastTurn;  // trace of the last turn, JSON
    private volatile String mLastEnd;   // why the last session ended

    /** One open session. */
    private final class Session implements IBinder.DeathRecipient {
        final String id = UUID.randomUUID().toString();
        final SessionManifest manifest;
        final int uid;
        final IJarvisSessionCallback callback;
        /** Manifest tools minus those the app closed, in manifest order. */
        final Set<String> openTools;
        final ArrayDeque<SessionPrompt.Event> recent = new ArrayDeque<>();
        final EventLimiter limiter = new EventLimiter();
        final long startedAt = SystemClock.elapsedRealtime();
        volatile boolean ended;
        volatile boolean inTurn;
        int turns;

        Session(SessionManifest manifest, int uid, IJarvisSessionCallback callback,
                Set<String> openTools) {
            this.manifest = manifest;
            this.uid = uid;
            this.callback = callback;
            this.openTools = openTools;
        }

        @Override
        public void binderDied() {
            end(this, "app died");
        }
    }

    public SessionManager(Context context, SessionRegistry registry, ToolDispatcher dispatcher) {
        mContext = context;
        mRegistry = registry;
        mDispatcher = dispatcher;
        mActivityManager = context.getSystemService(ActivityManager.class);
    }

    /** Starts watching apps leave the foreground; call once, after boot. */
    public void start() {
        // IMPORTANCE_VISIBLE as the cut: still visible (under a dialog) keeps
        // the session; gone to the background ends it.
        mActivityManager.addOnUidImportanceListener((uid, importance) -> {
            if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return;
            Session session;
            synchronized (mLock) {
                session = mActive;
            }
            if (session != null && session.uid == uid) end(session, "app went to the background");
        }, ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE);
        Log.i(TAG, "Session manager started");
    }

    public SessionRegistry registry() {
        return mRegistry;
    }

    // -------------------------------------------------------------------------
    // IJarvisSessionService — any app, for its own sessions only
    // -------------------------------------------------------------------------

    @Override
    public String startSession(String packageName, String sessionName, String[] closedTools,
            IJarvisSessionCallback callback) {
        final int uid = Binder.getCallingUid();
        if (packageName == null || sessionName == null || callback == null) {
            throw new IllegalArgumentException("package, session name and callback are required");
        }
        final long token = Binder.clearCallingIdentity();
        try {
            requireOwnPackage(uid, packageName);
            SessionManifest manifest = mRegistry.get(packageName, sessionName);
            if (manifest == null) {
                throw new IllegalArgumentException(packageName + " declares no session '"
                        + sessionName + "'");
            }
            int importance = mActivityManager.getUidImportance(uid);
            if (importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                throw new SecurityException("a session can only start while the app is on screen");
            }
            Set<String> open = new LinkedHashSet<>(manifest.tools);
            if (closedTools != null) {
                for (String tool : closedTools) {
                    if (!manifest.tools.contains(tool)) {
                        throw new IllegalArgumentException("'" + tool + "' is not a tool of "
                                + sessionName);
                    }
                    if (tool.equals(manifest.turnTool)) {
                        throw new IllegalArgumentException("the turn tool cannot be closed");
                    }
                    open.remove(tool);
                }
            }

            Session session = new Session(manifest, uid, callback, open);
            try {
                callback.asBinder().linkToDeath(session, 0);
            } catch (RemoteException e) {
                throw new IllegalStateException("the app died while starting the session");
            }
            Session old;
            synchronized (mLock) {
                old = mActive;
                mActive = session;
                mLock.notifyAll();      // a start tool may be waiting for this
            }
            if (old != null) finish(old, "replaced by a new session");
            Log.i(TAG, "Session started: " + packageName + "/" + sessionName + " open tools "
                    + open + (closedTools != null && closedTools.length > 0
                            ? ", closed " + Arrays.toString(closedTools) : ""));
            return session.id;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void sendEvent(String sessionId, String event, String dataJson) {
        Session session = callersSession(sessionId);
        final SessionPrompt.Event recorded = record(session, event, dataJson);
        if (!event.equals(session.manifest.turnEvent)) return;
        synchronized (mLock) {
            if (session.inTurn) {
                throw new IllegalStateException("Jarvis is still taking its turn");
            }
            session.inTurn = true;
        }
        mTurns.execute(() -> runTurn(session, recorded));
    }

    @Override
    public void endSession(String sessionId, String event, String dataJson) {
        Session session = callersSession(sessionId);
        if (event != null) record(session, event, dataJson);
        end(session, "ended by the app" + (event != null ? " (" + event + ")" : ""));
    }

    /** The open session, if {@code sessionId} names it and it belongs to the caller. */
    private Session callersSession(String sessionId) {
        Session session;
        synchronized (mLock) {
            session = mActive;
        }
        if (session == null || !session.id.equals(sessionId)) {
            throw new IllegalStateException("no open session with this id");
        }
        if (session.uid != Binder.getCallingUid()) {
            throw new SecurityException("not the caller's session");
        }
        return session;
    }

    /** Checks an event against the manifest and the limits, then remembers it. */
    private SessionPrompt.Event record(Session session, String event, String dataJson) {
        if (event == null || !session.manifest.events.contains(event)) {
            throw new IllegalArgumentException("'" + event + "' is not an event of "
                    + session.manifest.name + "; declared: " + session.manifest.events);
        }
        String data = dataJson == null || dataJson.trim().isEmpty() ? "{}" : dataJson.trim();
        synchronized (mLock) {
            String refusal = session.limiter.admit(data, SystemClock.elapsedRealtime());
            if (refusal != null) throw new IllegalStateException(refusal);
        }
        try {
            data = new JSONObject(data).toString();   // also drops whitespace the model would pay for
        } catch (JSONException e) {
            throw new IllegalArgumentException("event data must be a JSON object");
        }
        SessionPrompt.Event recorded = new SessionPrompt.Event(event, data);
        synchronized (mLock) {
            session.recent.addLast(recorded);
            while (session.recent.size() > SessionPrompt.MAX_RECENT_EVENTS + 1) {
                session.recent.removeFirst();
            }
        }
        return recorded;
    }

    private void requireOwnPackage(int uid, String packageName) {
        try {
            int owner = mContext.getPackageManager().getPackageUidAsUser(packageName, 0,
                    UserHandle.getUserId(uid));
            if (owner != uid) throw new SecurityException(packageName + " is not the caller's");
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException(packageName + " is not installed");
        }
    }

    // -------------------------------------------------------------------------
    // Turns
    // -------------------------------------------------------------------------

    private void runTurn(Session session, SessionPrompt.Event now) {
        try {
            if (session.ended) return;
            tell(session, cb -> cb.onTurnStarted(now.name));
            ModelRegistry.ModelEntry model = ModelRegistry.getInstance().getReady("primary");
            SessionTurn.Result result;
            if (model == null) {
                result = new SessionTurn.Result(false, "no chat model installed", new JSONObject());
            } else {
                List<SessionPrompt.Event> recent;
                synchronized (mLock) {
                    recent = new ArrayList<>(session.recent);
                }
                recent.remove(recent.size() - 1);   // that is "now"
                List<String> open = new ArrayList<>(session.openTools);
                String tools = withSay(mDispatcher.toolsJsonForPackage(
                        session.manifest.packageName, open));
                SessionTurn turn = new SessionTurn(
                        (messages, toolsJson, options) -> Inference.completeWithOptions(
                                model.modelHandle, messages, toolsJson, options),
                        (name, args) -> mDispatcher.dispatchByName(name, args.toString()),
                        grammarEnabled());
                result = turn.run(session.manifest, UserContextHelper.loadFormattedFacts(), open,
                        now, recent, tools, () -> !session.ended);
            }
            session.turns++;
            try {
                result.trace.put("session", session.manifest.name);
                result.trace.put("turn", session.turns);
            } catch (JSONException e) {
                // Trace only.
            }
            mLastTurn = result.trace.toString();
            Log.i(TAG, "Turn " + session.turns + " of " + session.manifest.name + ": "
                    + (result.done ? "done" : "gave up") + ", " + result.detail + ", "
                    + result.trace.optLong("ms") + " ms");
            if (!session.ended) tell(session, cb -> cb.onTurnEnded(result.done, result.detail));
        } catch (RuntimeException e) {
            Log.e(TAG, "Turn failed", e);
            tell(session, cb -> cb.onTurnEnded(false, "error: " + e.getMessage()));
        } finally {
            session.inTurn = false;
        }
    }

    private static String withSay(String toolsJson) {
        try {
            return new org.json.JSONArray(toolsJson).put(SessionTools.sayDefinition()).toString();
        } catch (JSONException e) {
            return toolsJson;
        }
    }

    static boolean grammarEnabled() {
        return SystemProperties.getBoolean(PROP_GRAMMAR, true);
    }

    // -------------------------------------------------------------------------
    // Ending
    // -------------------------------------------------------------------------

    private void end(Session session, String reason) {
        synchronized (mLock) {
            if (mActive != session) return;
            mActive = null;
        }
        finish(session, reason);
    }

    private void finish(Session session, String reason) {
        session.ended = true;
        try {
            session.callback.asBinder().unlinkToDeath(session, 0);
        } catch (java.util.NoSuchElementException e) {
            // Already gone.
        }
        mLastEnd = session.manifest.name + ": " + reason;
        Log.i(TAG, "Session ended: " + session.manifest.packageName + "/"
                + session.manifest.name + ", " + reason + ", after "
                + (SystemClock.elapsedRealtime() - session.startedAt) / 1000 + " s and "
                + session.turns + " turn(s)");
        tell(session, cb -> cb.onSessionEnded(reason));
    }

    /** Ends the open session from the shell. */
    public boolean endFromShell() {
        Session session;
        synchronized (mLock) {
            session = mActive;
        }
        if (session == null) return false;
        end(session, "ended from the shell");
        return true;
    }

    private interface Call {
        void on(IJarvisSessionCallback callback) throws RemoteException;
    }

    private static void tell(Session session, Call call) {
        try {
            call.on(session.callback);
        } catch (RemoteException e) {
            Log.w(TAG, "Could not reach the app of " + session.manifest.name);
        }
    }

    // -------------------------------------------------------------------------
    // ToolDispatcher.Fence
    // -------------------------------------------------------------------------

    @Override
    public String refusal(String toolName, String packageName) {
        Session session;
        synchronized (mLock) {
            session = mActive;
        }
        return SessionTools.refusal(session != null ? session.manifest.title : null,
                session != null ? session.openTools : null, toolName,
                mRegistry.isSessionTool(packageName, toolName));
    }

    @Override
    public String scope() {
        Session session;
        synchronized (mLock) {
            session = mActive;
        }
        return session != null ? session.manifest.packageName : null;
    }

    @Override
    public String runBuiltIn(String toolName, JSONObject arguments) {
        if (!SessionTools.SAY.equals(toolName)) return null;
        Session session;
        synchronized (mLock) {
            session = mActive;
        }
        if (session == null) return null;   // no session: say is no tool
        String text = SessionTools.sayText(arguments);
        if (text == null) return "Error: say needs some text";
        Log.i(TAG, "say: " + text);
        tell(session, cb -> cb.onSay(text));
        return "Said to the user: " + text;
    }

    @Override
    public boolean isHidden(String toolName, String packageName) {
        return mRegistry.isSessionTool(packageName, toolName);
    }

    @Override
    public boolean isStartTool(String toolName, String packageName) {
        return mRegistry.byStartTool(packageName, toolName) != null;
    }

    @Override
    public Bundle startToolOptions() {
        BroadcastOptions options = BroadcastOptions.makeBasic();
        // For as long as its receiver runs, the app may open its activity.
        options.setBackgroundActivityStartsAllowed(true);
        return options.toBundle();
    }

    @Override
    public String afterStartTool(String toolName, String packageName, String result) {
        SessionManifest manifest = mRegistry.byStartTool(packageName, toolName);
        if (manifest == null || result == null || result.startsWith("Error")) return result;
        final long deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS;
        synchronized (mLock) {
            while (mActive == null || !mActive.manifest.packageName.equals(packageName)) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) {
                    Log.w(TAG, manifest.title + " did not start its session in time");
                    return result + " But " + manifest.title + " did not start a session with"
                            + " Jarvis within " + START_TIMEOUT_MS / 1000 + " seconds.";
                }
                try {
                    mLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            }
        }
        return result + " The " + manifest.title + " session with Jarvis is open.";
    }

    // -------------------------------------------------------------------------
    // cmd jarvis session
    // -------------------------------------------------------------------------

    public void dump(PrintWriter pw) {
        Session session;
        synchronized (mLock) {
            session = mActive;
        }
        mRegistry.dump(pw);
        if (session == null) {
            pw.println("open: none");
        } else {
            pw.println("open: " + session.manifest.packageName + "/" + session.manifest.name
                    + " for " + (SystemClock.elapsedRealtime() - session.startedAt) / 1000
                    + " s, " + session.turns + " turn(s)" + (session.inTurn ? ", in a turn" : ""));
            pw.println("open tools: say, " + String.join(", ", session.openTools));
            synchronized (mLock) {
                for (SessionPrompt.Event event : session.recent) pw.println("event: " + event);
            }
        }
        pw.println("grammar: " + (grammarEnabled() ? "on" : "off"));
        if (mLastEnd != null) pw.println("last ended: " + mLastEnd);
        if (mLastTurn != null) pw.println("last turn: " + mLastTurn);
    }
}
