package com.android.server.jarvis.core;

import android.content.Context;
import android.os.BatteryManager;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Periodic background jobs for JarvisService.
 *
 * Replaces androidx WorkManager, which is an app library: it needs an
 * Application, a Room database and manifest-registered components, none of
 * which exist inside system_server, and it pulls Kotlin and androidx resources
 * into services.jar.
 *
 * All jobs share one low-priority daemon thread, so two inference-heavy jobs
 * never run at the same time. Schedules live in memory only: they are
 * re-registered by JarvisService on every boot and periods are counted from
 * then.
 */
public final class JarvisScheduler {

    private static final String TAG = "JarvisScheduler";

    /** Outcome of one run. RETRY means "nothing done, try again next tick". */
    public enum Result {
        SUCCESS, RETRY;

        public static Result success() { return SUCCESS; }
        public static Result retry()   { return RETRY; }
    }

    private static final ScheduledExecutorService sExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jarvis-jobs");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    private static final Set<String> sScheduled = new HashSet<>();

    private JarvisScheduler() {}

    /**
     * Run {@code job} every {@code period}, starting after {@code initialDelay}.
     * A second call with the same {@code name} is ignored.
     *
     * @param requiresCharging skip ticks while the device is on battery
     */
    public static synchronized void schedulePeriodic(Context context, String name,
            long initialDelay, long period, TimeUnit unit, boolean requiresCharging,
            Supplier<Result> job) {
        if (!sScheduled.add(name)) {
            Log.d(TAG, name + " already scheduled");
            return;
        }
        sExecutor.scheduleWithFixedDelay(() -> {
            try {
                if (requiresCharging && !isCharging(context)) return;
                Result result = job.get();
                Log.d(TAG, name + " finished: " + result);
            } catch (Throwable t) {
                // Never let an exception escape: it would cancel the schedule.
                Log.e(TAG, name + " failed", t);
            }
        }, initialDelay, period, unit);
    }

    private static boolean isCharging(Context context) {
        BatteryManager bm = context.getSystemService(BatteryManager.class);
        return bm != null && bm.isCharging();
    }
}
