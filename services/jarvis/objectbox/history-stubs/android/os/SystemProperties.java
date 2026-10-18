package android.os;

import java.util.HashMap;
import java.util.Map;

/** Host stub for history-test.sh. */
public final class SystemProperties {
    private static final Map<String, String> sValues = new HashMap<>();
    public static String get(String key, String def) { return sValues.getOrDefault(key, def); }
    public static int getInt(String key, int def) {
        String v = sValues.get(key);
        return v == null ? def : Integer.parseInt(v);
    }
    public static boolean getBoolean(String key, boolean def) {
        String v = sValues.get(key);
        return v == null ? def : "1".equals(v) || "true".equals(v);
    }
    public static void set(String key, String value) { sValues.put(key, value); }
}
