package com.ysulab.yapper.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ysulab.yapper.capture.SilenceDetector.Decision;
import org.junit.Test;

public class SilenceDetectorTest {

    /** Feeds `amp` every 100 ms from `from` (inclusive) to `to` (exclusive); returns first non-CONTINUE. */
    private static Decision feed(SilenceDetector d, int amp, long from, long to) {
        for (long t = from; t < to; t += 100) {
            Decision x = d.onSample(amp, t);
            if (x != Decision.CONTINUE) {
                return x;
            }
        }
        return Decision.CONTINUE;
    }

    @Test
    public void stopsAfterSilenceFollowingSpeech() {
        SilenceDetector d = new SilenceDetector(2000, 6000, 300_000);
        assertEquals(Decision.CONTINUE, feed(d, 200, 0, 400));      // quiet room
        assertEquals(Decision.CONTINUE, feed(d, 8000, 400, 3000));  // speaking
        assertTrue(d.speechStarted());
        assertEquals(Decision.CONTINUE, feed(d, 200, 3000, 4900));  // 1.9 s pause: keep going
        assertEquals(Decision.STOP_SILENCE, d.onSample(200, 5000)); // 2.1 s since last speech
    }

    @Test
    public void shortPauseDoesNotStop() {
        SilenceDetector d = new SilenceDetector(2000, 6000, 300_000);
        feed(d, 200, 0, 400);
        feed(d, 8000, 400, 1000);
        assertEquals(Decision.CONTINUE, feed(d, 200, 1000, 2500)); // 1.5 s breath
        assertEquals(Decision.CONTINUE, feed(d, 8000, 2500, 3000));
        assertEquals(Decision.CONTINUE, feed(d, 200, 3000, 4500));
    }

    @Test
    public void noSpeechStopsAfterTimeout() {
        SilenceDetector d = new SilenceDetector(2000, 6000, 300_000);
        assertEquals(Decision.CONTINUE, feed(d, 300, 0, 6000));
        assertEquals(Decision.STOP_NO_SPEECH, d.onSample(300, 6000));
        assertFalse(d.speechStarted());
    }

    @Test
    public void noisyRoomRaisesThreshold() {
        SilenceDetector d = new SilenceDetector(2000, 6000, 300_000);
        feed(d, 2000, 0, 300); // calibration in a loud café
        assertTrue(d.threshold() >= 5000);
        // Steady background noise alone is not speech.
        assertEquals(Decision.CONTINUE, feed(d, 2200, 300, 5000));
        assertFalse(d.speechStarted());
        // Clear speech above it is.
        d.onSample(15000, 5100);
        assertTrue(d.speechStarted());
    }

    @Test
    public void quietRoomUsesAbsoluteMinimum() {
        SilenceDetector d = new SilenceDetector(2000, 6000, 300_000);
        feed(d, 0, 0, 300);
        assertEquals(SilenceDetector.MIN_SPEECH_AMPLITUDE, d.threshold());
    }

    @Test
    public void maxDurationAlwaysStops() {
        SilenceDetector d = new SilenceDetector(2000, 6000, 5000);
        feed(d, 200, 0, 300);
        assertEquals(Decision.STOP_MAX, feed(d, 9000, 300, 10_000));
    }

    @Test
    public void speechTimeIsCounted() {
        SilenceDetector d = new SilenceDetector(2000, 6000, 300_000);
        feed(d, 100, 0, 300);
        feed(d, 9000, 300, 1300);
        assertTrue(d.speechMs() >= 800);
    }

    @Test
    public void levelIsClamped() {
        assertEquals(0f, SilenceDetector.level(0), 0f);
        assertEquals(1f, SilenceDetector.level(32767), 0.001f);
        float mid = SilenceDetector.level(1000);
        assertTrue(mid > 0f && mid < 1f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroDurations() {
        new SilenceDetector(0, 1, 1);
    }
}
