package com.ysulab.yapper.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class SmokeSceneTest {

    private static final int W = 100;
    private static final int H = 150;

    @Test
    public void bayerThresholdsAreAll64LevelsAndTile() {
        Set<Float> seen = new HashSet<>();
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                float t = Dither.threshold(x, y);
                assertTrue(t > 0f && t < 1f);
                seen.add(t);
                assertEquals(t, Dither.threshold(x + 8, y + 16), 0f);
            }
        }
        assertEquals(64, seen.size());
    }

    @Test
    public void noiseStaysInRange() {
        for (int i = 0; i < 2000; i++) {
            float v = Dither.fbm(i * 0.137f, i * 0.071f);
            assertTrue(v >= 0f && v <= 1f);
            float s = Dither.smoke(i % 50 / 50f, i / 2000f, i * 0.01f);
            assertTrue(s >= 0f && s <= 1f);
        }
    }

    @Test
    public void restingSceneIsPlainGround() {
        SmokeScene s = new SmokeScene(3f);
        byte[] out = render(s);
        for (byte b : out) {
            assertEquals(SmokeScene.INK_GROUND, b);
        }
    }

    @Test
    public void smokeRisesFromTheBottomFirst() {
        SmokeScene s = new SmokeScene(3f);
        s.setHeld(true);
        // Half a second in (the rise starts slow on purpose).
        for (int i = 0; i < 30; i++) {
            s.step(0.016f, 0f);
        }
        byte[] out = render(s);
        assertTrue("bottom half has smoke", countNonGround(out, H / 2, H) > 0);
        assertEquals("top third still clear", 0, countNonGround(out, 0, H / 3));
    }

    @Test
    public void fullRiseShowsTheMoonAtItsCentre() {
        SmokeScene s = new SmokeScene(3f);
        s.setHeld(true);
        for (int i = 0; i < 300; i++) {
            s.step(0.016f, 0.3f);
        }
        assertTrue(s.emerge() > 0.95f);
        byte[] out = render(s);
        int cx = Math.round(s.moonX * W);
        int cy = Math.round((s.moonY - s.moonR * 0.5f) * H);
        int red = 0;
        for (int y = cy - 2; y <= cy + 2; y++) {
            for (int x = cx - 2; x <= cx + 2; x++) {
                byte b = out[y * W + x];
                if (b == SmokeScene.INK_MOON || b == SmokeScene.INK_MOON_LIT) {
                    red++;
                }
            }
        }
        assertTrue("upper moon is mostly lit, got " + red, red >= 15);
    }

    @Test
    public void textBandsNeverUseTheBrightSmokeInk() {
        SmokeScene s = new SmokeScene(7f);
        s.density = 3f;
        s.snap(1f, 1f);
        byte[] out = render(s);
        int top = (int) Math.ceil(s.quietTop * H);
        int bottom = (int) Math.floor((1f - s.quietBottom) * H) + 1;
        for (int y = 0; y < H; y++) {
            if (y < top || y >= bottom) {
                for (int x = 0; x < W; x++) {
                    assertTrue(out[y * W + x] <= SmokeScene.INK_SMOKE);
                }
            }
        }
    }

    @Test
    public void loudSyllablesSendRingsThatExpire() {
        SmokeScene s = new SmokeScene(0f);
        s.setHeld(true);
        for (int i = 0; i < 30; i++) {
            s.step(0.016f, 1f);
        }
        assertFalse(s.rings.isEmpty());
        int count = s.rings.size();
        assertTrue("rings are spaced out, got " + count, count <= 3);
        s.setHeld(false);
        for (int i = 0; i < 200; i++) {
            s.step(0.016f, 0f);
        }
        assertTrue(s.rings.isEmpty());
        assertFalse(s.isAnimating());
    }

    @Test
    public void parkedSceneStaysStill() {
        SmokeScene s = new SmokeScene(2f);
        s.rest = 1f;
        s.snap(1f, 0f);
        assertFalse(s.isAnimating());
        s.setHeld(true);
        assertTrue(s.isAnimating());
        s.setHeld(false);
        for (int i = 0; i < 100; i++) {
            s.step(0.016f, 0f);
        }
        assertEquals(1f, s.emerge(), 0.001f);
    }

    @Test
    public void quietVoiceSendsNoRings() {
        SmokeScene s = new SmokeScene(0f);
        s.setHeld(true);
        for (int i = 0; i < 100; i++) {
            s.step(0.016f, 0.3f);
        }
        assertTrue(s.rings.isEmpty());
    }

    @Test
    public void transparentRestIsAllClear() {
        SmokeScene s = new SmokeScene(3f);
        s.transparent = true;
        for (byte b : render(s)) {
            assertEquals(SmokeScene.INK_CLEAR, b);
        }
    }

    @Test
    public void transparentSheetShowsTheScreenAtTopAndDarkensTheTextBand() {
        SmokeScene s = new SmokeScene(4f);
        s.transparent = true;
        s.quietTop = 0f;
        s.quietBottom = 0.25f;
        s.snap(1f, 0f);
        byte[] out = render(s);
        int clearTop = count(out, 0, H / 6, SmokeScene.INK_CLEAR);
        assertTrue("top is mostly see-through, got " + clearTop, clearTop > W * (H / 6) * 0.6f);
        int band = W * (H / 10);
        int seeThrough = count(out, H - H / 10, H, SmokeScene.INK_CLEAR);
        assertTrue("words sit on near-solid dark, got " + seeThrough, seeThrough < band * 0.05f);
    }

    @Test
    public void lettingGoDissolvesUpwardThenStops() {
        SmokeScene s = new SmokeScene(1f);
        s.setHeld(true);
        for (int i = 0; i < 200; i++) {
            s.step(0.016f, 0.4f);
        }
        s.setHeld(false);
        for (int i = 0; i < 40; i++) {
            s.step(0.016f, 0f);
        }
        assertTrue("still dissolving", s.isAnimating());
        byte[] out = render(s);
        assertTrue("bottom clears before the top",
                countNonGround(out, H - H / 6, H) < countNonGround(out, H / 3, H / 2));
        for (int i = 0; i < 60; i++) {
            s.step(0.016f, 0f);
        }
        assertFalse(s.isAnimating());
        assertEquals(0f, s.emerge(), 0f);
        for (byte b : render(s)) {
            assertEquals(SmokeScene.INK_GROUND, b);
        }
    }

    @Test
    public void renderingIsDeterministic() {
        SmokeScene a = new SmokeScene(5f);
        SmokeScene b = new SmokeScene(5f);
        a.snap(1f, 0.5f);
        b.snap(1f, 0.5f);
        assertArrayEquals(render(a), render(b));
    }

    private static byte[] render(SmokeScene s) {
        byte[] out = new byte[W * H];
        s.render(W, H, out);
        return out;
    }

    private static int count(byte[] out, int y0, int y1, byte ink) {
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < W; x++) {
                if (out[y * W + x] == ink) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int countNonGround(byte[] out, int y0, int y1) {
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < W; x++) {
                if (out[y * W + x] != SmokeScene.INK_GROUND) {
                    n++;
                }
            }
        }
        return n;
    }
}
