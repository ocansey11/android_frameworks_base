package com.android.server.jarvis;

import android.content.Context;
import android.os.Binder;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.jarvis.IJarvisService;
import android.jarvis.IToolRegistry;
import android.util.Log;

import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import com.android.server.jarvis.core.IndexQueue;
import com.android.server.jarvis.core.JarvisStore;
import com.android.server.jarvis.core.ModelRegistry;
import com.android.server.jarvis.inference.Inference;
import com.android.server.jarvis.inference.ModelName;
import com.android.server.jarvis.inference.Needle;
import com.android.server.jarvis.inference.Pcm;
import com.android.server.jarvis.indexing.JarvisFileObserver;
import com.android.server.jarvis.indexing.JarvisIndexWorker;
import com.android.server.jarvis.model.SourceFile;
import com.android.server.jarvis.model.SourceFile_;
import com.android.server.jarvis.agent.DreamWorker;
import com.android.server.jarvis.agent.JarvisExecutor;
import com.android.server.jarvis.tools.AppRecord;
import com.android.server.jarvis.tools.FastPath;
import com.android.server.jarvis.tools.ToolDispatcher;
import com.android.server.jarvis.tools.ToolRecord;
import com.android.server.jarvis.tools.ToolScannerService;

/**
 * JarvisOS System Service.
 *
 * The single privileged service that owns all Jarvis capabilities:
 *   - RAG: document indexing, semantic retrieval, LLM completion
 *   - Tools: app tool registry, semantic tool routing, broadcast dispatch
 *   - (Phase 5) Agentic loop via JarvisExecutor
 *
 * Startup sequence:
 *   1. ObjectBox store
 *   2. ModelRegistry — "rag" + "tools" handle pairs
 *   3. FileObservers
 *   4. JarvisIndexWorker (JarvisScheduler)
 *   5. ToolScannerService + ToolDispatcher
 *
 * Query flow:
 *   processQuery() → ToolDispatcher (tool path) | RAG pipeline (knowledge path)
 *
 * Published Binder endpoints:
 *   "jarvis"       → IJarvisService (queries, indexing)
 *   "jarvis_tools" → IToolRegistry  (tool registry inspection)
 */
public class JarvisService extends SystemService {

    private static final String TAG = "JarvisService";

    private static final String STORE_DIR            = "/data/system/jarvis/objectbox";
    private static final String INDEX_DIR_RAG        = "/data/system/jarvis/index_rag";
    private static final String INDEX_DIR_TOOLS      = "/data/system/jarvis/index_tools";
    private static final int    EMBED_DIM            = 1024;

    // Signature permission declared in core/res/AndroidManifest.xml. Every
    // Binder call that reads user data or runs a tool requires it.
    private static final String PERMISSION = "android.permission.ACCESS_JARVIS_SERVICE";

    // Primary user's shared storage. Spelled out rather than taken from
    // Environment.getExternalStorageDirectory(): that call needs the storage
    // service, which does not exist yet when system_server loads this class,
    // and it throws there, which stopped the whole service from starting.
    private static final String SHARED_STORAGE = "/storage/emulated/0";
    private static final String[] WATCH_PATHS = {
        SHARED_STORAGE + "/Documents",
        SHARED_STORAGE + "/Downloads",
        SHARED_STORAGE + "/Pictures",
    };

    private final Context mContext;
    private volatile boolean mIsReady = false;
    private volatile boolean mToolsStarted = false;
    private JarvisFileObserver[] mFileObservers;
    private ToolScannerService mToolScanner;
    private ToolDispatcher mToolDispatcher;
    private JarvisExecutor mExecutor;
    private FastPath mFastPath;
    /** What happened inside the last request, as JSON; see {@link #recordTrace}. */
    private volatile String mLastTrace;

    public JarvisService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService("jarvis", mBinder);
        publishBinderService("jarvis_tools", mToolRegistryBinder);
        initializeAsync();
    }

    private void initializeAsync() {
        new Thread(() -> {
            try {
                // Step 1 — ObjectBox (must come first)
                new java.io.File(STORE_DIR).mkdirs();
                JarvisStore.init(STORE_DIR);

                // Step 2 — ModelRegistry
                ModelRegistry registry = ModelRegistry.getInstance();
                loadModels();

                // Step 3 — FileObservers. Not fatal: tools and queries do not
                // depend on file watching.
                try {
                    startFileObservers();
                } catch (RuntimeException e) {
                    Log.e(TAG, "File observers failed to start", e);
                }

                // Step 4 — background indexing
                JarvisIndexWorker.schedule(mContext);

                // Step 5 — Tool Registry
                mToolScanner    = new ToolScannerService(mContext);
                mToolDispatcher = new ToolDispatcher(mContext);
                mExecutor       = new JarvisExecutor(mToolDispatcher);
                mFastPath       = new FastPath(mToolDispatcher);
                mToolScanner.start();

                ModelRegistry.ModelEntry tools = registry.getReady("tools");
                if (tools != null) {
                    mToolScanner.setCactusHandles(tools.modelHandle, tools.indexHandle);
                }
                mToolsStarted = true;

                // Step 6 — DreamWorker (nightly memory consolidation)
                DreamWorker.schedule(mContext);

                mIsReady = true;
                Log.i(TAG, "JarvisService initialized");

            } catch (Throwable t) {
                // Throwable, not Exception: a missing native library surfaces as
                // an Error, and an uncaught one on this thread kills system_server.
                Log.e(TAG, "JarvisService init failed", t);
            }
        }, "JarvisServiceInit").start();
    }

    /**
     * Loads the selected engine's models: the embedding model twice ("rag" and
     * "tools" keep separate indexes) and the chat model ("primary"). A missing
     * model file is normal on a fresh device and only disables what needs it.
     */
    private void loadModels() {
        ModelRegistry registry = ModelRegistry.getInstance();
        // Each engine has its own index files: vectors from one engine's
        // embedding model mean nothing to another's.
        final String suffix = "_" + Inference.engine().name();
        final String embed = Inference.modelPath("embed");
        ModelRegistry.ModelEntry ragModel =
                registry.register("rag", embed, INDEX_DIR_RAG + suffix, EMBED_DIM);
        ModelRegistry.ModelEntry toolsModel =
                registry.register("tools", embed, INDEX_DIR_TOOLS + suffix, EMBED_DIM);
        ModelRegistry.ModelEntry primaryModel =
                registry.registerChatModel("primary", Inference.modelPath("chat"));

        Log.i(TAG, "Engine: " + Inference.engine().name());
        if (!ragModel.isReady())     Log.w(TAG, "RAG model not ready — embeddings disabled");
        if (!toolsModel.isReady())   Log.w(TAG, "Tools model not ready — tool embeddings disabled");
        if (!primaryModel.isReady()) Log.w(TAG, "Primary model not ready — no answers until a chat model is installed");

        // The small tool-calling and speech models, whichever engine is selected.
        Needle.loadModels();
    }

    /**
     * Drops every loaded model and loads the models of {@code engineName}
     * ("auto", "cactus", "llama"), or reloads the current engine's when null.
     * Queries arriving meanwhile get "still initializing".
     *
     * @return false if the engine name is unknown
     */
    private synchronized boolean reloadModels(String engineName) {
        final boolean wasReady = mIsReady;
        mIsReady = false;
        try {
            ModelRegistry.getInstance().destroyAll();
            if (engineName != null && !Inference.select(engineName)) return false;
            loadModels();
            ModelRegistry.ModelEntry tools = ModelRegistry.getInstance().getReady("tools");
            if (mToolScanner != null && mToolsStarted) {
                mToolScanner.setCactusHandles(
                        tools != null ? tools.modelHandle : 0L,
                        tools != null ? tools.indexHandle : 0L);
            }
            return true;
        } finally {
            mIsReady = wasReady;
        }
    }

    private void startFileObservers() {
        mFileObservers = new JarvisFileObserver[WATCH_PATHS.length];
        for (int i = 0; i < WATCH_PATHS.length; i++) {
            mFileObservers[i] = new JarvisFileObserver(
                    WATCH_PATHS[i], IndexQueue.getInstance().getQueue());
            mFileObservers[i].startWatching();
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            Log.i(TAG, "Boot complete");
        }
    }

    public void onDestroy() {
        if (mToolScanner != null)   mToolScanner.stop();
        if (mFileObservers != null) {
            for (JarvisFileObserver o : mFileObservers) if (o != null) o.stopWatching();
        }
        ModelRegistry.getInstance().destroyAll();
        JarvisStore.close();
    }

    // -------------------------------------------------------------------------
    // IJarvisService Binder — published as "jarvis"
    // -------------------------------------------------------------------------

    private final IJarvisService.Stub mBinder = new IJarvisService.Stub() {

        @Override
        public String processQuery(String query) {
            enforceCallingPermission();
            if (query == null || query.trim().isEmpty()) {
                throw new IllegalArgumentException("Query cannot be null or empty");
            }
            // Tools the query triggers run with the system's identity, not the caller's.
            final long token = Binder.clearCallingIdentity();
            try {
                return runQuery(query);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void indexDocument(String path) {
            enforceCallingPermission();
            if (path == null || path.trim().isEmpty()) {
                throw new IllegalArgumentException("Path cannot be null or empty");
            }
            try {
                IndexQueue.getInstance().getQueue().put(
                        new JarvisFileObserver.IndexTask(path, JarvisFileObserver.TaskType.INDEX));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public boolean isIndexed(String path) {
            enforceCallingPermission();
            if (path == null || path.trim().isEmpty()) return false;
            try {
                SourceFile sf = JarvisStore.box(SourceFile.class).query()
                        .equal(SourceFile_.filePath, path,
                                io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                        .build().findFirst();
                return sf != null && sf.isIndexed;
            } catch (Exception e) {
                Log.w(TAG, "isIndexed() failed for: " + path, e);
                return false;
            }
        }

        @Override
        public boolean isReady() {
            return mIsReady;
        }

        @Override
        public String getStatus() {
            enforceCallingPermission();
            return statusJson();
        }

        @Override
        public String processQueryWithImage(String query, byte[] imageData) {
            enforceCallingPermission();
            if (!mIsReady) return "Error: Jarvis service is still initializing.";
            // TODO: pass imageData through CactusWrapper once Gemma 4 image support
            // is available in Cactus (Sam's upstream pull). For now, fall back to text.
            Log.i(TAG, "processQueryWithImage: image=" + (imageData != null
                    ? imageData.length + " bytes" : "null") + " — falling back to text query");
            if (mExecutor != null) return mExecutor.execute(query);
            return "Error: executor not ready";
        }

        @Override
        public String processQueryWithAudio(String query, byte[] pcmData) {
            enforceCallingPermission();
            // Tools the request triggers run with the system's identity, not the caller's.
            final long token = Binder.clearCallingIdentity();
            try {
                if (pcmData == null || pcmData.length < 2) {
                    return query != null && !query.trim().isEmpty()
                            ? runQuery(query) : "Error: no audio";
                }
                return runSpokenQuery(Pcm.fromPcm16(pcmData), null);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void enforceCallingPermission() {
            mContext.enforceCallingOrSelfPermission(PERMISSION, "jarvis");
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new JarvisShellCommand().exec(this, in, out, err, args, callback, resultReceiver);
        }
    };

    /** The query path shared by the Binder call and the shell command. */
    private String runQuery(String query) {
        if (!mIsReady || mExecutor == null) {
            return "Error: Jarvis service is still initializing. Please try again.";
        }
        Log.i(TAG, "processQuery: " + query.substring(0, Math.min(50, query.length())));
        final long start = System.nanoTime();
        FastPath.Outcome fast = null;
        String reply;
        ToolDispatcher.startRecording();
        try {
            fast = mFastPath.tryText(query);
            reply = fast.handled ? fast.reply : mExecutor.execute(query);
        } catch (Exception e) {
            Log.e(TAG, "processQuery failed", e);
            reply = "Error: " + e.getMessage();
        }
        recordTrace(query, false, fast, reply, start);
        return reply;
    }

    /**
     * Keeps what happened inside one request for the status (the Jarvis app's
     * test mode): who answered, what Needle chose, every tool that ran with its
     * arguments and result, and the times. Stops the recording started by the
     * caller. Text only; for a spoken request, what was heard (audio is never kept).
     */
    private void recordTrace(String request, boolean spoken, FastPath.Outcome fast,
            String reply, long start) {
        final org.json.JSONArray tools = ToolDispatcher.stopRecording();
        try {
            org.json.JSONObject trace = new org.json.JSONObject();
            trace.put("request", request != null ? request : "");
            trace.put("spoken", spoken);
            trace.put("path", fast != null && fast.handled ? "fast" : "model");
            trace.put("model", ModelName.of(Inference.modelPath("chat")));
            if (fast != null && fast.asked) {
                trace.put("needle", new org.json.JSONObject()
                        .put("calls", fast.calls)
                        .put("confidence", fast.confidence)
                        .put("bar", FastPath.confidencePercent() / 100.0)
                        .put("ms", fast.decideMs));
            }
            trace.put("tools", tools);
            trace.put("ms", (System.nanoTime() - start) / 1_000_000);
            trace.put("reply", reply);
            mLastTrace = trace.toString();
        } catch (org.json.JSONException e) {
            Log.w(TAG, "Could not record the trace", e);
        }
    }

    /**
     * The spoken-query path shared by the Binder call and the shell command:
     * the fast path hears the request and runs the tools if it is sure;
     * otherwise what was heard goes to the agent loop as a typed request would.
     * Audio is used for the transcript only and is not kept.
     *
     * @param pcm     16 kHz mono samples in [-1, 1]
     * @param details if not null, receives what was heard and how long each step took
     */
    private String runSpokenQuery(float[] pcm, PrintWriter details) {
        if (!mIsReady || mExecutor == null) {
            return "Error: Jarvis service is still initializing. Please try again.";
        }
        if (!Needle.canHear()) {
            return "Error: no speech model installed (cmd jarvis install-model whistle)";
        }
        final long start = System.nanoTime();
        ToolDispatcher.startRecording();
        FastPath.Outcome fast = null;
        String reply = null;
        try {
            fast = mFastPath.tryAudio(pcm);
            if (fast.handled) {
                reply = fast.reply;
            } else if (fast.transcript == null || fast.transcript.trim().isEmpty()) {
                reply = "Error: I did not hear anything.";
            } else {
                reply = mExecutor.execute(fast.transcript);
            }
            final long totalMs = (System.nanoTime() - start) / 1_000_000;
            Log.i(TAG, "spoken query: " + totalMs + " ms, fast path "
                    + (fast.handled ? "handled it" : "passed"));
            if (details != null) {
                details.println("heard: " + fast.transcript);
                details.println("calls: " + (fast.calls.isEmpty() ? "none" : fast.calls));
                details.println("confidence: " + fast.confidence);
                details.println("path: " + (fast.handled ? "fast" : "agent loop"));
                details.println("hear+decide ms: " + fast.decideMs);
                details.println("tool ms: " + fast.toolMs);
                details.println("total ms: " + totalMs);
            }
        } catch (Exception e) {
            Log.e(TAG, "spoken query failed", e);
            reply = "Error: " + e.getMessage();
        }
        recordTrace(fast != null ? fast.transcript : null, true, fast, reply, start);
        return reply;
    }

    private String statusJson() {
        try {
            org.json.JSONObject models = new org.json.JSONObject();
            for (String name : new String[] {"rag", "tools", "primary"}) {
                models.put(name, ModelRegistry.getInstance().getReady(name) != null);
            }
            final boolean store = JarvisStore.isReady();
            org.json.JSONObject status = new org.json.JSONObject();
            status.put("ready", mIsReady);
            status.put("engine", Inference.engine().name());
            status.put("store", store);
            status.put("models", models);
            status.put("fastTools", Needle.canRoute());
            status.put("speech", Needle.canHear());
            status.put("chatModel", ModelName.of(Inference.modelPath("chat")));
            status.put("fastPath", FastPath.isEnabled() && Needle.canRoute());
            status.put("fastBar", FastPath.confidencePercent() / 100.0);
            final String trace = mLastTrace;
            if (trace != null) status.put("last", new org.json.JSONObject(trace));
            status.put("apps", store ? JarvisStore.box(AppRecord.class).count() : 0);
            status.put("tools", store ? JarvisStore.box(ToolRecord.class).count() : 0);
            return status.toString();
        } catch (Exception e) {
            Log.e(TAG, "statusJson() failed", e);
            return "{}";
        }
    }

    // -------------------------------------------------------------------------
    // `adb shell cmd jarvis ...` — lets the service be exercised without a model
    // -------------------------------------------------------------------------

    private final class JarvisShellCommand extends ShellCommand {

        @Override
        public int onCommand(String cmd) {
            final PrintWriter pw = getOutPrintWriter();
            final int uid = Binder.getCallingUid();
            if (uid != Process.ROOT_UID && uid != Process.SHELL_UID) {
                pw.println("Error: only shell or root may use this command");
                return -1;
            }
            if (cmd == null) return handleDefaultCommands(cmd);
            switch (cmd) {
                case "status":
                    pw.println("ready: " + mIsReady);
                    pw.println("engine: " + Inference.engine().name()
                            + (Inference.engine().isAvailable() ? "" : " (cannot run here)"));
                    pw.println("store: " + (JarvisStore.isReady() ? "open" : "not open"));
                    for (String name : new String[] {"rag", "tools", "primary"}) {
                        pw.println("model " + name + ": "
                                + (ModelRegistry.getInstance().getReady(name) != null
                                        ? "ready" : "not ready"));
                    }
                    pw.println("needle: " + Needle.describe()
                            + (FastPath.isEnabled() ? "" : " (fast path off)"));
                    if (JarvisStore.isReady()) {
                        pw.println("apps: " + JarvisStore.box(AppRecord.class).count());
                        pw.println("tools: " + JarvisStore.box(ToolRecord.class).count());
                    }
                    return 0;
                case "tools":
                    if (!JarvisStore.isReady()) {
                        pw.println("Error: store not open");
                        return -1;
                    }
                    for (ToolRecord tool : JarvisStore.box(ToolRecord.class).getAll()) {
                        AppRecord app = tool.app.getTarget();
                        pw.println(tool.toolName + "  [" + (app != null ? app.packageName : "?")
                                + "/" + tool.receiverClass + "]");
                    }
                    return 0;
                case "describe": {
                    String name = getNextArgRequired();
                    if (!JarvisStore.isReady()) {
                        pw.println("Error: store not open");
                        return -1;
                    }
                    for (ToolRecord tool : JarvisStore.box(ToolRecord.class).getAll()) {
                        if (name.equals(tool.toolName)) {
                            pw.println(ToolDispatcher.serializeTool(tool));
                        }
                    }
                    return 0;
                }
                case "tool": {
                    String name = getNextArgRequired();
                    String argsJson = getNextArg();
                    if (mToolDispatcher == null) {
                        pw.println("Error: tool dispatcher not started");
                        return -1;
                    }
                    final long token = Binder.clearCallingIdentity();   // as callTool does
                    try {
                        pw.println(mToolDispatcher.dispatchByName(name, argsJson));
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    return 0;
                }
                case "engine": {
                    String name = getNextArg();
                    if (name == null) {
                        pw.println("engine: " + Inference.engine().name());
                        pw.println("built in: " + Inference.describeEngines());
                        pw.println("chat model: " + Inference.modelPath("chat"));
                        pw.println("embedding model: " + Inference.modelPath("embed"));
                        return 0;
                    }
                    if (!reloadModels(name)) {
                        pw.println("Error: unknown engine '" + name + "' (auto, cactus, llama)");
                        return -1;
                    }
                    pw.println("engine: " + Inference.engine().name());
                    return 0;
                }
                case "install-model": {
                    // adb exec-in cmd jarvis install-model chat < model.gguf
                    String role = getNextArgRequired();
                    final boolean small = "needle".equals(role) || "whistle".equals(role);
                    if (!small && !"chat".equals(role) && !"embed".equals(role)) {
                        pw.println("Error: role must be chat, embed, needle or whistle");
                        return -1;
                    }
                    java.io.File target = new java.io.File(
                            small ? Needle.modelPath(role) : Inference.modelPath(role));
                    java.io.File tmp = new java.io.File(target.getPath() + ".tmp");
                    target.getParentFile().mkdirs();
                    long bytes = 0;
                    try (java.io.InputStream in = getRawInputStream();
                            java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buffer = new byte[1 << 16];
                        for (int n; (n = in.read(buffer)) > 0; bytes += n) out.write(buffer, 0, n);
                    } catch (java.io.IOException e) {
                        tmp.delete();
                        pw.println("Error: " + e.getMessage());
                        return -1;
                    }
                    if (bytes == 0 || !tmp.renameTo(target)) {
                        tmp.delete();
                        pw.println("Error: nothing received, or could not write " + target);
                        return -1;
                    }
                    pw.println("wrote " + bytes + " bytes to " + target);
                    if (small) {
                        Needle.loadModels();
                        pw.println("needle: " + Needle.describe());
                        return 0;
                    }
                    reloadModels(null);
                    pw.println("model " + ("chat".equals(role) ? "primary" : "rag") + ": "
                            + (ModelRegistry.getInstance().getReady(
                                    "chat".equals(role) ? "primary" : "rag") != null
                                    ? "ready" : "not ready"));
                    return 0;
                }
                case "reload":
                    reloadModels(null);
                    pw.println("models reloaded for engine " + Inference.engine().name());
                    return 0;
                case "query": {
                    // Not mBinder.processQuery(): that checks the caller's
                    // permission, and the caller here is the shell.
                    // Tools run as the system, as they do for processQuery;
                    // as the shell they are refused (roadblock 34).
                    final String text = getNextArgRequired();
                    final long token = Binder.clearCallingIdentity();
                    try {
                        pw.println(runQuery(text));
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    return 0;
                }
                case "voice": {
                    // adb shell -T cmd jarvis voice < clip.wav
                    float[] pcm;
                    try (java.io.InputStream in = getRawInputStream()) {
                        pcm = Pcm.fromWav(in.readAllBytes());
                    } catch (java.io.IOException | IllegalArgumentException e) {
                        pw.println("Error: " + e.getMessage());
                        return -1;
                    }
                    pw.println("audio seconds: " + pcm.length / (float) Pcm.SAMPLE_RATE);
                    final long token = Binder.clearCallingIdentity();   // as processQueryWithAudio does
                    String reply;
                    try {
                        reply = runSpokenQuery(pcm, pw);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    pw.println("reply: " + reply);
                    return 0;
                }
                case "route": {
                    String text = getNextArgRequired();
                    if (mFastPath == null || !Needle.canRoute()) {
                        pw.println("Error: tool-calling model not installed "
                                + "(cmd jarvis install-model needle)");
                        return -1;
                    }
                    FastPath.Outcome outcome = mFastPath.preview(text);
                    pw.println("calls: " + (outcome.calls.isEmpty() ? "none" : outcome.calls));
                    pw.println("confidence: " + outcome.confidence);
                    pw.println("decide ms: " + outcome.decideMs);
                    return 0;
                }
                case "fast": {
                    String value = getNextArg();
                    if ("on".equals(value) || "off".equals(value)) {
                        android.os.SystemProperties.set(
                                FastPath.PROP_ENABLED, "on".equals(value) ? "1" : "0");
                    } else if (value != null) {
                        pw.println("Error: fast takes on or off");
                        return -1;
                    }
                    pw.println("fast path: " + (FastPath.isEnabled() ? "on" : "off"));
                    return 0;
                }
                default:
                    return handleDefaultCommands(cmd);
            }
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Jarvis service commands:");
            pw.println("  status              service, store and model state, record counts");
            pw.println("  tools               every registered tool and the app that owns it");
            pw.println("  describe NAME       a tool's stored definition as JSON");
            pw.println("  tool NAME [JSON]    call a tool directly with JSON arguments");
            pw.println("  query TEXT          same as IJarvisService.processQuery");
            pw.println("  engine [NAME]       show the inference engine, or switch to auto|cactus|llama");
            pw.println("  install-model ROLE  store the file on standard input as the chat, embed, needle or");
            pw.println("                      whistle model, then load it");
            pw.println("                      (adb shell -T cmd jarvis install-model chat < m.gguf)");
            pw.println("  voice               answer the WAV file on standard input as a spoken request,");
            pw.println("                      and show what was heard and how long each step took");
            pw.println("  route TEXT          show the tool calls the fast path would make, without making them");
            pw.println("  fast [on|off]       show or switch the fast path (tool calls without the chat model)");
            pw.println("  reload              load the engine's model files again");
        }
    }

    // -------------------------------------------------------------------------
    // IToolRegistry Binder — published as "jarvis_tools"
    // -------------------------------------------------------------------------

    private final IToolRegistry.Stub mToolRegistryBinder = new IToolRegistry.Stub() {

        @Override
        public String listTools() {
            enforceCallingPermission();
            if (!JarvisStore.isReady()) return "[]";
            try {
                java.util.List<ToolRecord> all = JarvisStore.box(ToolRecord.class).getAll();
                return ToolDispatcher.serializeTools(all);
            } catch (Exception e) {
                Log.e(TAG, "listTools() failed", e);
                return "[]";
            }
        }

        @Override
        public String getTool(long id) {
            enforceCallingPermission();
            if (!JarvisStore.isReady()) return null;
            try {
                ToolRecord tool = JarvisStore.box(ToolRecord.class).get(id);
                return ToolDispatcher.serializeTool(tool);
            } catch (Exception e) {
                Log.e(TAG, "getTool() failed for id=" + id, e);
                return null;
            }
        }

        @Override
        public String searchTools(String query) {
            enforceCallingPermission();
            if (query == null || query.trim().isEmpty()) return "[]";
            if (!mIsReady || mToolDispatcher == null) return "[]";
            try {
                return mToolDispatcher.searchTools(query);
            } catch (Exception e) {
                Log.e(TAG, "searchTools() failed", e);
                return "[]";
            }
        }

        @Override
        public String callTool(String toolName, String argsJson) {
            enforceCallingPermission();
            if (mToolDispatcher == null) return "Error: tool dispatcher not started";
            // The tool runs with the system's identity, not the caller's.
            final long token = Binder.clearCallingIdentity();
            try {
                return mToolDispatcher.dispatchByName(toolName, argsJson);
            } catch (Exception e) {
                Log.e(TAG, "callTool() failed for " + toolName, e);
                return "Error: " + e.getMessage();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void enforceCallingPermission() {
            mContext.enforceCallingOrSelfPermission(PERMISSION, "jarvis_tools");
        }
    };
}
