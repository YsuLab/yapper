package com.ysulab.yapper.capture;

/**
 * Decides when a quick capture should end, from microphone amplitude samples.
 *
 * <p>Feed it {@code MediaRecorder.getMaxAmplitude()} values (0..32767) every ~100 ms. It learns the
 * room's noise floor, detects speech above it, and says {@link Decision#STOP_SILENCE} after
 * {@code silenceMs} of quiet once speech has started. If nobody speaks within {@code noSpeechMs}
 * it says {@link Decision#STOP_NO_SPEECH} (the capture is still saved: screen only). It always
 * stops at {@code maxMs}. Pure Java so it is unit-testable.
 */
public final class SilenceDetector {

    public enum Decision { CONTINUE, STOP_SILENCE, STOP_NO_SPEECH, STOP_MAX }

    /** Absolute floor for "speech". Below this is always treated as quiet. */
    static final int MIN_SPEECH_AMPLITUDE = 900;
    /** Speech must be this many times louder than the learned noise floor. */
    static final double FLOOR_RATIO = 2.5;
    /** Samples during the first {@code CALIBRATION_MS} only seed the noise floor. */
    static final long CALIBRATION_MS = 300;

    private final long silenceMs;
    private final long noSpeechMs;
    private final long maxMs;

    private long startMs = -1;
    private double noiseFloor = -1;
    private boolean speechStarted;
    private long lastSpeechMs = -1;
    private long speechMsTotal;
    private long lastSampleMs = -1;

    public SilenceDetector(long silenceMs, long noSpeechMs, long maxMs) {
        if (silenceMs <= 0 || noSpeechMs <= 0 || maxMs <= 0) {
            throw new IllegalArgumentException("durations must be positive");
        }
        this.silenceMs = silenceMs;
        this.noSpeechMs = noSpeechMs;
        this.maxMs = maxMs;
    }

    public Decision onSample(int amplitude, long nowMs) {
        if (startMs < 0) {
            startMs = nowMs;
        }
        long elapsed = nowMs - startMs;
        long step = lastSampleMs < 0 ? 0 : Math.max(0, nowMs - lastSampleMs);
        lastSampleMs = nowMs;
        int amp = Math.max(0, amplitude);

        if (elapsed < CALIBRATION_MS) {
            noiseFloor = noiseFloor < 0 ? amp : Math.min(noiseFloor, amp);
            return elapsed >= maxMs ? Decision.STOP_MAX : Decision.CONTINUE;
        }
        if (noiseFloor < 0) {
            noiseFloor = amp;
        }

        if (amp >= threshold()) {
            if (speechStarted) {
                speechMsTotal += step;
            }
            speechStarted = true;
            lastSpeechMs = nowMs;
        } else {
            // Track the floor slowly, so a fan turning on doesn't count as speech forever.
            noiseFloor = noiseFloor * 0.95 + amp * 0.05;
        }

        if (elapsed >= maxMs) {
            return Decision.STOP_MAX;
        }
        if (speechStarted && nowMs - lastSpeechMs >= silenceMs) {
            return Decision.STOP_SILENCE;
        }
        if (!speechStarted && elapsed >= noSpeechMs) {
            return Decision.STOP_NO_SPEECH;
        }
        return Decision.CONTINUE;
    }

    public int threshold() {
        double floor = Math.max(0, noiseFloor);
        return (int) Math.max(MIN_SPEECH_AMPLITUDE, floor * FLOOR_RATIO);
    }

    public boolean speechStarted() {
        return speechStarted;
    }

    /** Rough speaking time, for the capture metadata. */
    public long speechMs() {
        return speechMsTotal;
    }

    /** 0..1 level for the overlay's meter (log scale feels natural). */
    public static float level(int amplitude) {
        if (amplitude <= 0) {
            return 0f;
        }
        double db = 20 * Math.log10(amplitude / 32767.0); // -90..0
        return (float) Math.max(0, Math.min(1, (db + 60) / 60));
    }
}
