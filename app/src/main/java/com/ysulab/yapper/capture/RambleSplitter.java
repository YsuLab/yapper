package com.ysulab.yapper.capture;

/**
 * Decides when a ramble recording starts its next audio part. Parts keep each upload small and a
 * crash costs at most the current part. The cut is made in a pause, so no word is split. Pure Java.
 */
public final class RambleSplitter {

    /** After this long, cut at the next pause. */
    public static final long TARGET_MS = 5 * 60_000L;
    /** Cut here even if he never pauses. */
    public static final long MAX_MS = 7 * 60_000L;
    /** A pause is this long below {@link #QUIET_AMPLITUDE}. */
    static final long PAUSE_MS = 600;
    static final int QUIET_AMPLITUDE = 900;

    private final long targetMs;
    private final long maxMs;
    private long partStartMs = -1;
    private long quietSinceMs = -1;

    public RambleSplitter() {
        this(TARGET_MS, MAX_MS);
    }

    public RambleSplitter(long targetMs, long maxMs) {
        this.targetMs = targetMs;
        this.maxMs = maxMs;
    }

    /** Call when a part starts. */
    public void partStarted(long nowMs) {
        partStartMs = nowMs;
        quietSinceMs = -1;
    }

    /** Feed amplitude samples; true = cut now and start the next part. */
    public boolean onSample(int amplitude, long nowMs) {
        if (partStartMs < 0) {
            partStarted(nowMs);
        }
        if (amplitude < QUIET_AMPLITUDE) {
            if (quietSinceMs < 0) {
                quietSinceMs = nowMs;
            }
        } else {
            quietSinceMs = -1;
        }
        long len = nowMs - partStartMs;
        if (len >= maxMs) {
            return true;
        }
        return len >= targetMs && quietSinceMs >= 0 && nowMs - quietSinceMs >= PAUSE_MS;
    }

    /** "audio-01", "audio-02" … for part {@code n} (1-based). */
    public static String partBase(int n) {
        return String.format(java.util.Locale.ROOT, "audio-%02d", n);
    }
}
