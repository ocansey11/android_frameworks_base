package com.android.server.jarvis.session;

/**
 * Limits what an app can push at Jarvis during a session
 * (AGENT_EXPERIENCE.md 11.5, rule 3): the size of one event and how many
 * arrive per second. A token bucket: {@link #BURST} events at once, then
 * {@link #PER_SECOND} a second. Not thread-safe; the session locks around it.
 */
final class EventLimiter {

    /** Characters of JSON in one event. A board game needs a few hundred. */
    static final int MAX_DATA_CHARS = 2048;
    static final int BURST = 10;
    static final double PER_SECOND = 2.0;

    private double mTokens = BURST;
    private long mLastMs = -1;

    /**
     * Takes one event's worth of allowance.
     *
     * @param nowMs a monotonic clock in milliseconds
     * @return null if the event may go on, or why not
     */
    String admit(String dataJson, long nowMs) {
        if (dataJson != null && dataJson.length() > MAX_DATA_CHARS) {
            return "event data is " + dataJson.length() + " characters; the limit is "
                    + MAX_DATA_CHARS;
        }
        if (mLastMs >= 0) {
            mTokens = Math.min(BURST, mTokens + (nowMs - mLastMs) * PER_SECOND / 1000.0);
        }
        mLastMs = nowMs;
        if (mTokens < 1.0) return "too many events; at most " + (int) PER_SECOND + " a second";
        mTokens -= 1.0;
        return null;
    }
}
