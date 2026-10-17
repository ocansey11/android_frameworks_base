package com.android.server.jarvis.session;

import com.android.server.jarvis.inference.Completion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * One turn of Jarvis in a session (AGENT_EXPERIENCE.md 11.8).
 *
 * The turn event wakes Jarvis; it does one short task and stops, so the
 * agent loop's five-turn ceiling does not apply here. Needle is not asked:
 * the event already says what to do, so the chat model takes the turn.
 *
 * <ol>
 *   <li>The model sees the session prompt and only the open tools. The turn
 *       tool's argument is narrowed to the event's legal values, and with
 *       {@code constrain} the engine samples under a grammar built from that
 *       schema, so a call to it cannot carry another value.
 *   <li>Every call goes through {@link Tools}, i.e. ToolDispatcher, where the
 *       fence refuses anything that is not open. The prompt is not the guard.
 *   <li>The turn is done when the turn tool succeeds. A refused call, an
 *       error from the turn tool, or a reply with no call counts as wrong;
 *       after a wrong one the model is told the legal values and offered the
 *       turn tool alone. Every call asks for tool calls only (tool_choice
 *       "required"); words without a call are passed on as say. After {@link #MAX_WRONG}
 *       wrong ones, or {@link #MAX_MODEL_CALLS} model calls, Jarvis gives up
 *       and the app decides (the sample game plays a random legal move).
 *   <li>If the turn ended without a word, one last short call with only
 *       {@code say} offered gives the person a line about the move.
 * </ol>
 */
public final class SessionTurn {

    static final int MAX_MODEL_CALLS = 4;
    static final int MAX_WRONG = 2;
    /** A turn is a few short calls; this keeps a rambling reply from costing seconds. */
    static final int MAX_REPLY_TOKENS = 160;
    /** The line after a move: one sentence. */
    static final int MAX_SAY_TOKENS = 48;

    /** The chat model. */
    public interface Model {
        /** @return the reply, or null if the engine failed */
        Completion complete(String messagesJson, String toolsJson, String optionsJson);
    }

    /** Runs a tool call; in the service this is ToolDispatcher, fence included. */
    public interface Tools {
        String call(String name, JSONObject args);
    }

    /** What happened in the turn. */
    public static final class Result {
        public final boolean done;
        public final String detail;
        /** Every model call and tool call with times, for {@code cmd jarvis session}. */
        public final JSONObject trace;

        Result(boolean done, String detail, JSONObject trace) {
            this.done = done;
            this.detail = detail;
            this.trace = trace;
        }
    }

    private final Model mModel;
    private final Tools mTools;
    private final boolean mConstrain;

    /**
     * @param constrain sample tool calls under the schema grammar (llama.cpp);
     *                  off only for the with/without comparison
     */
    public SessionTurn(Model model, Tools tools, boolean constrain) {
        mModel = model;
        mTools = tools;
        mConstrain = constrain;
    }

    /**
     * @param toolsJson  the open session tools and say, as [{name, description, parameters}]
     * @param stillOpen  checked between steps; the turn stops if the session ended
     */
    public Result run(SessionManifest manifest, String userFacts, List<String> openTools,
            SessionPrompt.Event now, List<SessionPrompt.Event> recent, String toolsJson,
            java.util.function.BooleanSupplier stillOpen) {
        final long start = System.nanoTime();
        JSONObject trace = new JSONObject();
        JSONArray steps = new JSONArray();
        JSONObject data = parseObject(now.data);
        JSONArray choices = SessionPrompt.choices(manifest, data);
        JSONArray messages = SessionPrompt.messages(manifest, userFacts, openTools, now, recent);

        int wrong = 0;
        boolean said = false;
        boolean done = false;
        String detail = "no call to " + manifest.turnTool;
        try {
            trace.put("event", now.name);
            trace.put("constrain", mConstrain);
            for (int n = 0; n < MAX_MODEL_CALLS && !done && wrong <= MAX_WRONG; n++) {
                if (!stillOpen.getAsBoolean()) {
                    detail = "session ended during the turn";
                    break;
                }
                final boolean retry = wrong > 0;
                String tools = SessionPrompt.withChoices(toolsJson, manifest, choices, retry);
                JSONObject options = new JSONObject()
                        .put("max_tokens", MAX_REPLY_TOKENS)
                        .put("temperature", 0)
                        .put("parallel_tool_calls", !retry)
                        .put("constrain_tools", mConstrain)
                        // A turn is tool calls only; with the grammar on, the
                        // model cannot answer in prose instead.
                        .put("tool_choice", "required")
                        .put("calls_only", mConstrain);

                JSONObject step = new JSONObject().put("retry", retry);
                final long modelStart = System.nanoTime();
                Completion reply = mModel.complete(messages.toString(), tools, options.toString());
                step.put("model_ms", (System.nanoTime() - modelStart) / 1_000_000);
                steps.put(step);
                if (reply == null) {
                    detail = "the model failed";
                    step.put("error", detail);
                    break;
                }
                step.put("prompt_tokens", reply.promptTokens);
                step.put("reused_tokens", reply.reusedTokens);
                step.put("first_token_ms", reply.firstTokenMs);
                step.put("constrained", reply.constrained);
                if (!reply.text.isEmpty()) step.put("text", reply.text);

                if (reply.calls.length() == 0) {
                    wrong++;
                    detail = "replied without a tool call";
                    // Words without a call (possible with the grammar off) are
                    // still what Jarvis wanted to say: pass them on as say.
                    if (!said && !reply.text.trim().isEmpty()) {
                        said = true;
                        step.put("said_text", mTools.call(SessionTools.SAY,
                                new JSONObject().put("text", reply.text.trim())));
                    }
                    messages.put(new JSONObject().put("role", "assistant").put("content", reply.text));
                    messages.put(new JSONObject().put("role", "user").put("content",
                            nudge(manifest, choices, null)));
                    continue;
                }

                JSONArray calls = new JSONArray();
                step.put("calls", calls);
                // Words beside the calls, and no say among them: those words
                // are what Jarvis wanted to say.
                if (!said && !reply.text.trim().isEmpty() && !hasCall(reply.calls, SessionTools.SAY)) {
                    said = true;
                    step.put("said_text", mTools.call(SessionTools.SAY,
                            new JSONObject().put("text", reply.text.trim())));
                }
                JSONArray asked = new JSONArray();
                JSONArray results = new JSONArray();
                for (int i = 0; i < reply.calls.length() && !done; i++) {
                    JSONObject call = reply.calls.getJSONObject(i);
                    String name = call.getString("name");
                    JSONObject args = call.optJSONObject("arguments");
                    if (args == null) args = new JSONObject();

                    String result;
                    if (SessionTools.SAY.equals(name) && said) {
                        // One line a turn: the person should see a move, not a speech.
                        result = "Error: you already said something this turn; now call "
                                + manifest.turnTool + ".";
                    } else {
                        final long toolStart = System.nanoTime();
                        result = mTools.call(name, args);
                        if (result == null) result = "Error: no result";
                        calls.put(new JSONObject().put("name", name).put("args", args)
                                .put("result", result)
                                .put("ms", (System.nanoTime() - toolStart) / 1_000_000));
                    }
                    if (SessionTools.SAY.equals(name)) said = true;

                    String id = "call_" + n + "_" + i;
                    asked.put(new JSONObject().put("id", id).put("type", "function")
                            .put("function", new JSONObject().put("name", name)
                                    .put("arguments", args.toString())));
                    results.put(new JSONObject().put("role", "tool").put("tool_call_id", id)
                            .put("name", name).put("content", result));

                    final boolean failed = result.startsWith("Error");
                    if (name.equals(manifest.turnTool)) {
                        if (failed) {
                            wrong++;
                            detail = manifest.turnTool + " failed: " + result;
                        } else {
                            done = true;
                            detail = manifest.turnTool + " " + args;
                        }
                    } else if (result.startsWith("Error: refused")) {
                        wrong++;
                        detail = "called a closed tool: " + name;
                    }
                }
                messages.put(new JSONObject().put("role", "assistant").put("content", reply.text)
                        .put("tool_calls", asked));
                for (int i = 0; i < results.length(); i++) messages.put(results.get(i));
                if (!done && wrong > 0) {
                    messages.put(new JSONObject().put("role", "user").put("content",
                            nudge(manifest, choices, detail)));
                }
            }
            // A move with no word is a poor opponent, and the model rarely
            // calls say by itself when only tool calls are allowed. One more
            // short call, say only: with the prompt already in the cache it
            // costs a few seconds, after the move is on the board.
            if (done && !said && stillOpen.getAsBoolean()) {
                messages.put(new JSONObject().put("role", "user").put("content",
                        "Now say one short line to the user about your move, in your"
                                + " manner for this app. Do not describe the board."));
                JSONObject options = new JSONObject()
                        .put("max_tokens", MAX_SAY_TOKENS)
                        .put("temperature", 0)
                        .put("constrain_tools", mConstrain)
                        .put("tool_choice", "required")
                        .put("calls_only", mConstrain);
                JSONObject step = new JSONObject().put("after_move", true);
                final long modelStart = System.nanoTime();
                Completion reply = mModel.complete(messages.toString(),
                        only(toolsJson, SessionTools.SAY), options.toString());
                step.put("model_ms", (System.nanoTime() - modelStart) / 1_000_000);
                steps.put(step);
                if (reply != null) {
                    step.put("reused_tokens", reply.reusedTokens);
                    String text = reply.text;
                    for (int i = 0; i < reply.calls.length(); i++) {
                        JSONObject call = reply.calls.getJSONObject(i);
                        if (SessionTools.SAY.equals(call.optString("name"))) {
                            JSONObject args = call.optJSONObject("arguments");
                            text = args != null ? args.optString("text", text) : text;
                            break;
                        }
                    }
                    if (!text.trim().isEmpty()) {
                        step.put("said_text", mTools.call(SessionTools.SAY,
                                new JSONObject().put("text", text.trim())));
                    }
                }
            }
            if (!done && wrong > MAX_WRONG) {
                detail = "gave up after " + wrong + " wrong calls; last: " + detail;
            } else if (!done && steps.length() >= MAX_MODEL_CALLS) {
                detail = "gave up after " + MAX_MODEL_CALLS + " model calls; last: " + detail;
            }
            trace.put("steps", steps);
            trace.put("done", done);
            trace.put("detail", detail);
            trace.put("ms", (System.nanoTime() - start) / 1_000_000);
        } catch (JSONException e) {
            done = false;
            detail = "could not read the model's reply: " + e.getMessage();
        }
        return new Result(done, detail, trace);
    }

    /** What the model is told after a wrong call: the legal values, plainly. */
    static String nudge(SessionManifest manifest, JSONArray choices, String what) {
        StringBuilder sb = new StringBuilder();
        if (what != null) sb.append("That did not end your turn (").append(what).append("). ");
        sb.append("Call ").append(manifest.turnTool).append(" now");
        if (manifest.turnArg != null && choices != null) {
            sb.append(" with ").append(manifest.turnArg).append(" set to one of ").append(choices);
        }
        return sb.append('.').toString();
    }

    /** The tools array with only the named tool in it. */
    private static String only(String toolsJson, String name) {
        try {
            JSONArray in = new JSONArray(toolsJson);
            JSONArray out = new JSONArray();
            for (int i = 0; i < in.length(); i++) {
                if (name.equals(in.getJSONObject(i).optString("name"))) out.put(in.get(i));
            }
            return out.toString();
        } catch (JSONException e) {
            return toolsJson;
        }
    }

    private static boolean hasCall(JSONArray calls, String name) {
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            if (call != null && name.equals(call.optString("name"))) return true;
        }
        return false;
    }

    private static JSONObject parseObject(String json) {
        try {
            return new JSONObject(json);
        } catch (JSONException | NullPointerException e) {
            return new JSONObject();
        }
    }
}
