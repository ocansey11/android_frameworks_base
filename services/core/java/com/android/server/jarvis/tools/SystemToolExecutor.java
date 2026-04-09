package com.android.server.jarvis.tools;

import android.app.AlarmManager;
import android.app.INotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.ServiceManager;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;

/**
 * Executes system-level tools in-process using Android framework APIs.
 *
 * Called by ToolDispatcher when ToolRecord.receiverClass starts with "@system/".
 * No broadcast is fired — the action happens directly inside system_server.
 *
 * This is what makes default Android apps and system services agentic:
 * Jarvis drives AlarmManager, MediaSessionManager, TelecomManager, WifiManager etc.
 * directly as a privileged system service. No custom APK required.
 *
 * All tools converge into the same ObjectBox ToolRecord via ToolScannerService.
 * ToolDispatcher routes here when receiverClass.startsWith("@system/").
 * When Desmond's apps ship with real manifest receivers, they replace JSON stubs —
 * this class is not involved for those tools.
 *
 * Supported tools (@system/<name>):
 *
 *   Device state (read)
 *     get_battery_status      — battery level + charging state
 *     get_notifications       — summary of active notifications
 *     what_is_playing         — current media track from active session
 *
 *   Media
 *     media_control           — play / pause / next / previous
 *     set_volume              — music / ring / alarm stream volumes
 *
 *   Communication
 *     send_sms                — send SMS via SmsManager
 *     make_phone_call         — place a call via TelecomManager (confirm before use)
 *
 *   Scheduling / contacts
 *     set_alarm               — alarm via ACTION_SET_ALARM → default clock app
 *     create_contact          — insert into ContactsContract
 *     create_calendar_event   — insert into CalendarContract
 *
 *   System control
 *     set_dnd                 — Do Not Disturb via NotificationManager
 *     set_volume              — AudioManager stream volume
 *     toggle_wifi             — WifiManager enable/disable
 *     toggle_bluetooth        — BluetoothAdapter enable/disable
 *     set_brightness          — Settings.System screen brightness
 *     set_flashlight          — CameraManager torch mode
 *
 *   App launching
 *     open_app                — startActivity via getLaunchIntentForPackage
 */
public class SystemToolExecutor {

    private static final String TAG = "SystemToolExecutor";

    /**
     * Execute a system tool.
     *
     * @param context  system context (JarvisService's)
     * @param toolName suffix after "@system/" in ToolRecord.receiverClass
     * @param args     parsed JSON arguments from the model
     * @return result string fed back into the agentic loop context
     */
    public static String execute(Context context, String toolName, JSONObject args) {
        Log.i(TAG, "Executing @system/" + toolName);
        try {
            switch (toolName) {
                // --- device state (read) ---
                case "get_battery_status":    return getBatteryStatus(context);
                case "get_notifications":     return getNotifications(context);
                case "what_is_playing":       return whatIsPlaying(context);

                // --- media ---
                case "media_control":         return mediaControl(context, args);
                case "set_volume":            return setVolume(context, args);

                // --- communication ---
                case "send_sms":              return sendSms(context, args);
                case "make_phone_call":       return makePhoneCall(context, args);

                // --- scheduling / contacts ---
                case "set_alarm":             return setAlarm(context, args);
                case "create_contact":        return createContact(context, args);
                case "create_calendar_event": return createCalendarEvent(context, args);

                // --- system control ---
                case "set_dnd":               return setDnd(context, args);
                case "toggle_wifi":           return toggleWifi(context, args);
                case "toggle_bluetooth":      return toggleBluetooth(context, args);
                case "set_brightness":        return setBrightness(context, args);
                case "set_flashlight":        return setFlashlight(context, args);

                // --- app launching ---
                case "open_app":              return openApp(context, args);

                default:
                    Log.w(TAG, "Unknown system tool: " + toolName);
                    return "Error: unknown system tool '" + toolName + "'";
            }
        } catch (Exception e) {
            Log.e(TAG, "System tool failed: " + toolName, e);
            return "Error: " + e.getMessage();
        }
    }

    // =========================================================================
    // Device state — read tools
    // =========================================================================

    private static String getBatteryStatus(Context context) {
        BatteryManager bm = (BatteryManager)
                context.getSystemService(Context.BATTERY_SERVICE);
        if (bm == null) return "Error: BatteryManager unavailable";
        int level    = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean charging = bm.isCharging();
        return "Battery: " + level + "%" + (charging ? " (charging)" : " (not charging)");
    }

    /**
     * Returns a summary of active notifications.
     * Uses INotificationManager (system_server internal) to read all active
     * notifications regardless of which app posted them.
     */
    private static String getNotifications(Context context) {
        try {
            INotificationManager inm = INotificationManager.Stub.asInterface(
                    ServiceManager.getService(Context.NOTIFICATION_SERVICE));

            // getActiveNotifications(callingPkg) — from UID=1000, callingPkg="android"
            // allows access to all active StatusBarNotifications
            @SuppressWarnings("unchecked")
            ParceledListSlice<StatusBarNotification> slice =
                    inm.getActiveNotifications("android");

            List<StatusBarNotification> snbs = slice.getList();
            if (snbs.isEmpty()) return "No active notifications";

            // Summarise by app — avoid dumping raw notification text for privacy
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (StatusBarNotification sbn : snbs) {
                String pkg = sbn.getPackageName();
                counts.put(pkg, counts.getOrDefault(pkg, 0) + 1);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(snbs.size()).append(" notification(s): ");
            for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
                sb.append(e.getKey()).append("(").append(e.getValue()).append(") ");
            }
            return sb.toString().trim();

        } catch (Exception e) {
            Log.e(TAG, "getNotifications failed", e);
            return "Error: could not read notifications — " + e.getMessage();
        }
    }

    /**
     * Returns the current track from the most-recently-active media session.
     */
    private static String whatIsPlaying(Context context) {
        MediaSessionManager msm = (MediaSessionManager)
                context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) return "Error: MediaSessionManager unavailable";

        // null ComponentName → UID=1000 gets all sessions across all apps
        List<MediaController> controllers = msm.getActiveSessions(null);
        if (controllers.isEmpty()) return "Nothing is playing";

        MediaController mc = controllers.get(0); // most-recently-active
        android.media.MediaMetadata meta = mc.getMetadata();
        if (meta == null) return "Media session active but no track metadata";

        String title  = meta.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
        String artist = meta.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);

        if (title == null && artist == null) return "Playing (no track info available)";
        if (artist == null) return "Playing: " + title;
        if (title == null)  return "Playing by: " + artist;
        return "Playing: " + title + " — " + artist;
    }

    // =========================================================================
    // Media
    // =========================================================================

    /**
     * Control the active media session.
     * args.action: "play" | "pause" | "next" | "previous" | "stop"
     */
    private static String mediaControl(Context context, JSONObject args) {
        String action = args.optString("action", "").toLowerCase().trim();
        if (action.isEmpty()) return "Error: 'action' is required (play/pause/next/previous/stop)";

        MediaSessionManager msm = (MediaSessionManager)
                context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) return "Error: MediaSessionManager unavailable";

        List<MediaController> controllers = msm.getActiveSessions(null);
        if (controllers.isEmpty()) return "No active media session";

        MediaController mc = controllers.get(0);
        android.media.session.MediaController.TransportControls tc =
                mc.getTransportControls();

        switch (action) {
            case "play":     tc.play();     return "Playing";
            case "pause":    tc.pause();    return "Paused";
            case "next":     tc.skipToNext();     return "Skipped to next track";
            case "previous": tc.skipToPrevious(); return "Went to previous track";
            case "stop":     tc.stop();     return "Stopped";
            default:
                return "Error: unknown action '" + action
                        + "' — use play, pause, next, previous, or stop";
        }
    }

    /**
     * Set volume for a named audio stream.
     * args.stream:  "music" (default) | "ring" | "alarm" | "notification"
     * args.level:   0–100 (percentage)
     */
    private static String setVolume(Context context, JSONObject args) {
        String streamName = args.optString("stream", "music").toLowerCase();
        int percent = args.optInt("level", -1);
        if (percent < 0 || percent > 100) return "Error: 'level' must be 0–100";

        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "Error: AudioManager unavailable";

        int stream;
        switch (streamName) {
            case "ring":         stream = AudioManager.STREAM_RING;         break;
            case "alarm":        stream = AudioManager.STREAM_ALARM;        break;
            case "notification": stream = AudioManager.STREAM_NOTIFICATION; break;
            default:             stream = AudioManager.STREAM_MUSIC;        break;
        }

        int maxVol = am.getStreamMaxVolume(stream);
        int target = (int) Math.round(percent / 100.0 * maxVol);
        am.setStreamVolume(stream, target, 0); // 0 = no UI toast

        return streamName + " volume set to " + percent + "%";
    }

    // =========================================================================
    // Communication
    // =========================================================================

    private static String sendSms(Context context, JSONObject args) {
        String to   = args.optString("to",   null);
        String body = args.optString("body", null);
        if (to == null || to.isEmpty())   return "Error: 'to' (phone number) is required";
        if (body == null || body.isEmpty()) return "Error: 'body' (message) is required";

        SmsManager smsManager = SmsManager.getDefault();
        smsManager.sendTextMessage(to, null, body, null, null);
        return "SMS sent to " + to;
    }

    /**
     * Place a phone call via TelecomManager.
     * NOTE: requires_confirmation=true in JSON — ToolNode should confirm before
     * dispatching this in a future confirmation pass.
     */
    private static String makePhoneCall(Context context, JSONObject args) {
        String number = args.optString("number", null);
        if (number == null || number.isEmpty()) return "Error: 'number' is required";

        TelephonyManager tm = (TelephonyManager)
                context.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
            return "Error: a call is already in progress";
        }

        TelecomManager telecom = (TelecomManager)
                context.getSystemService(Context.TELECOM_SERVICE);
        if (telecom == null) return "Error: TelecomManager unavailable";

        Uri uri = Uri.fromParts("tel", number, null);
        telecom.placeCall(uri, null);
        return "Calling " + number;
    }

    // =========================================================================
    // Scheduling / contacts
    // =========================================================================

    private static String setAlarm(Context context, JSONObject args) {
        int hour   = args.optInt("hour",   -1);
        int minute = args.optInt("minute", -1);
        String label = args.optString("label", "Jarvis Alarm");

        if (hour < 0 || hour > 23)   return "Error: 'hour' must be 0–23";
        if (minute < 0 || minute > 59) return "Error: 'minute' must be 0–59";

        Intent intent = new Intent(AlarmManager.ACTION_SET_ALARM);
        intent.putExtra(AlarmManager.EXTRA_HOUR, hour);
        intent.putExtra(AlarmManager.EXTRA_MINUTES, minute);
        intent.putExtra(AlarmManager.EXTRA_MESSAGE, label);
        intent.putExtra(AlarmManager.EXTRA_SKIP_UI, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        return String.format("Alarm set for %02d:%02d — %s", hour, minute, label);
    }

    private static String createContact(Context context, JSONObject args) {
        String name  = args.optString("name",  null);
        String phone = args.optString("phone", null);
        String email = args.optString("email", null);
        if (name == null || name.isEmpty()) return "Error: 'name' is required";

        ContentResolver cr = context.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.putNull(ContactsContract.RawContacts.ACCOUNT_TYPE);
        cv.putNull(ContactsContract.RawContacts.ACCOUNT_NAME);
        Uri rawUri = cr.insert(ContactsContract.RawContacts.CONTENT_URI, cv);
        if (rawUri == null) return "Error: failed to create contact";
        long rawId = Long.parseLong(rawUri.getLastPathSegment());

        ContentValues nameCv = new ContentValues();
        nameCv.put(ContactsContract.Data.RAW_CONTACT_ID, rawId);
        nameCv.put(ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);
        nameCv.put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name);
        cr.insert(ContactsContract.Data.CONTENT_URI, nameCv);

        if (phone != null && !phone.isEmpty()) {
            ContentValues phoneCv = new ContentValues();
            phoneCv.put(ContactsContract.Data.RAW_CONTACT_ID, rawId);
            phoneCv.put(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
            phoneCv.put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone);
            phoneCv.put(ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE);
            cr.insert(ContactsContract.Data.CONTENT_URI, phoneCv);
        }

        if (email != null && !email.isEmpty()) {
            ContentValues emailCv = new ContentValues();
            emailCv.put(ContactsContract.Data.RAW_CONTACT_ID, rawId);
            emailCv.put(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE);
            emailCv.put(ContactsContract.CommonDataKinds.Email.ADDRESS, email);
            emailCv.put(ContactsContract.CommonDataKinds.Email.TYPE,
                    ContactsContract.CommonDataKinds.Email.TYPE_HOME);
            cr.insert(ContactsContract.Data.CONTENT_URI, emailCv);
        }

        return "Contact created: " + name + (phone != null ? " (" + phone + ")" : "");
    }

    private static String createCalendarEvent(Context context, JSONObject args) {
        String title       = args.optString("title",       null);
        String description = args.optString("description", "");
        String startStr    = args.optString("start_time",  null);
        int durationMins   = args.optInt("duration_minutes", 60);

        if (title == null || title.isEmpty()) return "Error: 'title' is required";
        if (startStr == null)                 return "Error: 'start_time' required (YYYY-MM-DD HH:MM)";

        long startMs = parseDateTime(startStr);
        if (startMs < 0) return "Error: could not parse start_time '" + startStr + "'";

        long calId = resolveDefaultCalendarId(context);
        if (calId < 0) return "Error: no calendar account found on device";

        ContentValues cv = new ContentValues();
        cv.put(CalendarContract.Events.CALENDAR_ID,   calId);
        cv.put(CalendarContract.Events.TITLE,          title);
        cv.put(CalendarContract.Events.DESCRIPTION,    description);
        cv.put(CalendarContract.Events.DTSTART,        startMs);
        cv.put(CalendarContract.Events.DTEND,          startMs + (long) durationMins * 60_000);
        cv.put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().getID());

        Uri uri = context.getContentResolver()
                .insert(CalendarContract.Events.CONTENT_URI, cv);
        if (uri == null) return "Error: failed to insert calendar event";

        return "Event created: '" + title + "' at " + startStr + " (" + durationMins + " min)";
    }

    // =========================================================================
    // System control
    // =========================================================================

    /**
     * Set Do Not Disturb mode.
     * args.mode: "off" | "priority" (default) | "alarms" | "total_silence"
     */
    private static String setDnd(Context context, JSONObject args) {
        String mode = args.optString("mode", "priority").toLowerCase();
        android.app.NotificationManager nm = (android.app.NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return "Error: NotificationManager unavailable";

        int filter;
        String label;
        switch (mode) {
            case "off":
                filter = android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
                label  = "DND disabled";
                break;
            case "alarms":
                filter = android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS;
                label  = "DND: alarms only";
                break;
            case "total_silence":
                filter = android.app.NotificationManager.INTERRUPTION_FILTER_NONE;
                label  = "DND: total silence";
                break;
            default: // "priority"
                filter = android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;
                label  = "DND: priority contacts only";
                break;
        }

        nm.setInterruptionFilter(filter);
        return label;
    }

    /**
     * Enable or disable WiFi.
     * args.enabled: true | false
     */
    private static String toggleWifi(Context context, JSONObject args) {
        boolean enable = args.optBoolean("enabled", true);
        WifiManager wm = (WifiManager)
                context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return "Error: WifiManager unavailable";

        wm.setWifiEnabled(enable);
        return "WiFi " + (enable ? "enabled" : "disabled");
    }

    /**
     * Enable or disable Bluetooth.
     * args.enabled: true | false
     */
    private static String toggleBluetooth(Context context, JSONObject args) {
        boolean enable = args.optBoolean("enabled", true);
        BluetoothManager bm = (BluetoothManager)
                context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) return "Error: BluetoothManager unavailable";

        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null) return "Error: Bluetooth not available on this device";

        if (enable) {
            adapter.enable();
            return "Bluetooth turning on…";
        } else {
            adapter.disable();
            return "Bluetooth turning off…";
        }
    }

    /**
     * Set screen brightness.
     * args.level: 0–100 (percentage)
     */
    private static String setBrightness(Context context, JSONObject args) {
        int percent = args.optInt("level", -1);
        if (percent < 0 || percent > 100) return "Error: 'level' must be 0–100";

        // Disable auto-brightness first so the manual value takes effect
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);

        int value = (int) Math.round(percent / 100.0 * 255);
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, value);

        return "Brightness set to " + percent + "%";
    }

    /**
     * Enable or disable the flashlight (torch).
     * args.enabled: true | false
     */
    private static String setFlashlight(Context context, JSONObject args) {
        boolean enable = args.optBoolean("enabled", true);
        android.hardware.camera2.CameraManager cm =
                (android.hardware.camera2.CameraManager)
                        context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return "Error: CameraManager unavailable";

        try {
            String torchCameraId = null;
            for (String id : cm.getCameraIdList()) {
                android.hardware.camera2.CameraCharacteristics chars =
                        cm.getCameraCharacteristics(id);
                Boolean hasFlash = chars.get(
                        android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(hasFlash)) {
                    torchCameraId = id;
                    break;
                }
            }
            if (torchCameraId == null) return "Error: no torch found on this device";

            cm.setTorchMode(torchCameraId, enable);
            return "Flashlight " + (enable ? "on" : "off");

        } catch (android.hardware.camera2.CameraAccessException e) {
            return "Error: camera in use — cannot toggle flashlight";
        }
    }

    // =========================================================================
    // App launching
    // =========================================================================

    private static String openApp(Context context, JSONObject args) {
        String packageName = args.optString("package_name", null);
        if (packageName == null || packageName.isEmpty())
            return "Error: 'package_name' is required";

        Intent launch = context.getPackageManager()
                .getLaunchIntentForPackage(packageName);
        if (launch == null) return "Error: app not installed — " + packageName;

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(launch);
        return "Opened " + packageName;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Parse "YYYY-MM-DD HH:MM" → epoch millis. Returns -1 on failure. */
    private static long parseDateTime(String s) {
        try {
            String[] parts = s.trim().split(" ");
            if (parts.length != 2) return -1;
            String[] d = parts[0].split("-");
            String[] t = parts[1].split(":");
            if (d.length != 3 || t.length != 2) return -1;
            Calendar cal = Calendar.getInstance();
            cal.set(Integer.parseInt(d[0]), Integer.parseInt(d[1]) - 1,
                    Integer.parseInt(d[2]), Integer.parseInt(t[0]),
                    Integer.parseInt(t[1]), 0);
            cal.set(Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Returns the first writable calendar ID, or -1 if none. */
    private static long resolveDefaultCalendarId(Context context) {
        String[] proj = { CalendarContract.Calendars._ID };
        String sel = CalendarContract.Calendars.VISIBLE + " = 1 AND "
                + CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= "
                + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
        try (android.database.Cursor c = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) {
            Log.w(TAG, "resolveDefaultCalendarId failed", e);
        }
        return -1;
    }
}
