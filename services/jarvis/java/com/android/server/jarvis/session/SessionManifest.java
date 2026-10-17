package com.android.server.jarvis.session;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The static half of a session: what an app declares once, in its APK, for
 * any agent and any user (AGENT_EXPERIENCE.md section 11.3). Read when the
 * app is installed, next to its tools.
 *
 * <pre>
 * &lt;!-- AndroidManifest.xml, inside &lt;application&gt; --&gt;
 * &lt;meta-data android:name="com.jarvisos.session" android:resource="@xml/jarvis_session" /&gt;
 *
 * &lt;!-- res/xml/jarvis_session.xml --&gt;
 * &lt;session name="tic_tac_toe" title="Tic-tac-toe" start-tool="start_tic_tac_toe"&gt;
 *     &lt;rules&gt;Players take turns on cells 0-8. Three in a row wins.&lt;/rules&gt;
 *     &lt;persona&gt;Cheeky, competitive, one-liners. Never mean.&lt;/persona&gt;
 *     &lt;tools&gt;get_state, evaluate_moves, move&lt;/tools&gt;
 *     &lt;events&gt;player_moved, your_turn, game_over&lt;/events&gt;
 *     &lt;turn event="your_turn" tool="move" arg="cell" choices="legal" /&gt;
 * &lt;/session&gt;
 * </pre>
 *
 * {@code <turn>} says which event wakes Jarvis for a turn and which tool call
 * ends it; {@code arg}/{@code choices} name the argument whose legal values
 * the turn event carries (used to constrain the model, see SessionTurn).
 *
 * Plain data: nothing here is run, and the text is size-limited because all
 * of it goes into the prompt of every turn.
 */
public final class SessionManifest {

    public static final String META_SESSION = "com.jarvisos.session";

    /** Limits on what an app may put in front of the model on every turn. */
    static final int MAX_RULES_CHARS   = 1200;
    static final int MAX_PERSONA_CHARS = 300;
    static final int MAX_TITLE_CHARS   = 60;
    static final int MAX_TOOLS         = 8;
    static final int MAX_EVENTS        = 12;

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,39}");

    public final String packageName;
    public final String name;
    public final String title;
    /** Tool that opens the app and leads to this session; null if none. */
    public final String startTool;
    public final String rules;
    public final String persona;
    /** Tools open only during the session; {@code say} is added by Jarvis, not listed. */
    public final List<String> tools;
    public final List<String> events;
    /** The event that wakes Jarvis for one turn; null if Jarvis never takes turns. */
    public final String turnEvent;
    /** The tool call that ends a turn when it succeeds. */
    public final String turnTool;
    /** The argument of {@link #turnTool} limited to the turn event's choices; may be null. */
    public final String turnArg;
    /** Field of the turn event's data that lists the legal values of {@link #turnArg}. */
    public final String turnChoices;

    private SessionManifest(String packageName, String name, String title, String startTool,
            String rules, String persona, List<String> tools, List<String> events,
            String turnEvent, String turnTool, String turnArg, String turnChoices) {
        this.packageName = packageName;
        this.name = name;
        this.title = title;
        this.startTool = startTool;
        this.rules = rules;
        this.persona = persona;
        this.tools = Collections.unmodifiableList(tools);
        this.events = Collections.unmodifiableList(events);
        this.turnEvent = turnEvent;
        this.turnTool = turnTool;
        this.turnArg = turnArg;
        this.turnChoices = turnChoices;
    }

    /**
     * Reads a session manifest.
     *
     * @throws IllegalArgumentException with the reason if the file breaks a rule;
     *         a broken manifest is refused as a whole, never half-used
     */
    public static SessionManifest parse(String packageName, XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String name = null, title = null, startTool = null;
        String rules = "", persona = "";
        List<String> tools = new ArrayList<>();
        List<String> events = new ArrayList<>();
        String turnEvent = null, turnTool = null, turnArg = null, turnChoices = null;

        String element = null;
        boolean sawSession = false;
        for (int type = parser.getEventType(); type != XmlPullParser.END_DOCUMENT;
                type = parser.next()) {
            if (type == XmlPullParser.START_TAG) {
                element = parser.getName();
                if ("session".equals(element)) {
                    sawSession = true;
                    name = parser.getAttributeValue(null, "name");
                    title = parser.getAttributeValue(null, "title");
                    startTool = parser.getAttributeValue(null, "start-tool");
                } else if ("turn".equals(element)) {
                    turnEvent = parser.getAttributeValue(null, "event");
                    turnTool = parser.getAttributeValue(null, "tool");
                    turnArg = parser.getAttributeValue(null, "arg");
                    turnChoices = parser.getAttributeValue(null, "choices");
                }
            } else if (type == XmlPullParser.TEXT && element != null) {
                String text = parser.getText();
                switch (element) {
                    case "rules":   rules += text; break;
                    case "persona": persona += text; break;
                    case "tools":   tools.addAll(splitNames(text)); break;
                    case "events":  events.addAll(splitNames(text)); break;
                    default: break;
                }
            } else if (type == XmlPullParser.END_TAG) {
                element = null;
            }
        }

        if (!sawSession) throw new IllegalArgumentException("no <session> element");
        requireName("session name", name);
        if (startTool != null) requireName("start-tool", startTool);
        rules = squeeze(rules);
        persona = squeeze(persona);
        title = title == null || title.trim().isEmpty() ? name : squeeze(title);
        if (title.length() > MAX_TITLE_CHARS) throw new IllegalArgumentException("title too long");
        if (rules.isEmpty()) throw new IllegalArgumentException("<rules> is empty");
        if (rules.length() > MAX_RULES_CHARS) {
            throw new IllegalArgumentException("<rules> is over " + MAX_RULES_CHARS + " characters");
        }
        if (persona.length() > MAX_PERSONA_CHARS) {
            throw new IllegalArgumentException("<persona> is over " + MAX_PERSONA_CHARS + " characters");
        }
        if (tools.isEmpty() || tools.size() > MAX_TOOLS) {
            throw new IllegalArgumentException("<tools> must list 1 to " + MAX_TOOLS + " tools");
        }
        if (events.isEmpty() || events.size() > MAX_EVENTS) {
            throw new IllegalArgumentException("<events> must list 1 to " + MAX_EVENTS + " events");
        }
        for (String tool : tools) requireName("tool", tool);
        for (String event : events) requireName("event", event);
        if (tools.contains(SessionTools.SAY)) {
            throw new IllegalArgumentException("'say' is given by Jarvis; do not list it");
        }
        if (startTool != null && tools.contains(startTool)) {
            throw new IllegalArgumentException("the start tool must not be a session tool");
        }
        if (turnEvent != null || turnTool != null) {
            if (!events.contains(turnEvent)) {
                throw new IllegalArgumentException("<turn event> is not one of <events>");
            }
            if (!tools.contains(turnTool)) {
                throw new IllegalArgumentException("<turn tool> is not one of <tools>");
            }
            if ((turnArg == null) != (turnChoices == null)) {
                throw new IllegalArgumentException("<turn> needs both arg and choices, or neither");
            }
            if (turnArg != null) {
                requireName("turn arg", turnArg);
                requireName("turn choices", turnChoices);
            }
        }
        return new SessionManifest(packageName, name, title, startTool, rules, persona,
                tools, events, turnEvent, turnTool, turnArg, turnChoices);
    }

    private static List<String> splitNames(String text) {
        List<String> names = new ArrayList<>();
        for (String part : text.split("[,\\s]+")) {
            if (!part.isEmpty()) names.add(part);
        }
        return names;
    }

    private static void requireName(String what, String value) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " '" + value
                    + "' must be lower case letters, digits and _ (max 40)");
        }
    }

    /** Collapses the indentation of multi-line XML text into single spaces. */
    private static String squeeze(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    @Override
    public String toString() {
        return packageName + "/" + name + " (" + title + "): tools " + tools + ", events " + events
                + (turnEvent != null ? ", turn " + turnEvent + " -> " + turnTool : "")
                + (startTool != null ? ", start " + startTool : "");
    }
}
