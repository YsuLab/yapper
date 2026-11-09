package com.ysulab.yapper.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class ScreenshotCheckTest {

    @Test
    public void allBlackIsBlank() {
        int[] px = new int[1000];
        Arrays.fill(px, 0xff000000);
        assertTrue(ScreenshotCheck.isBlank(px));
    }

    @Test
    public void nearBlackNoiseIsBlank() {
        int[] px = new int[1000];
        Arrays.fill(px, 0xff0a0a0a);
        assertTrue(ScreenshotCheck.isBlank(px));
    }

    @Test
    public void darkModeChatIsNotBlank() {
        int[] px = new int[1000];
        Arrays.fill(px, 0xff000000);
        // 3% white text pixels on a black background.
        for (int i = 0; i < 30; i++) {
            px[i * 33] = 0xffeeeeee;
        }
        assertFalse(ScreenshotCheck.isBlank(px));
    }

    @Test
    public void emptyOrNullIsBlank() {
        assertTrue(ScreenshotCheck.isBlank(null));
        assertTrue(ScreenshotCheck.isBlank(new int[0]));
    }
}
