package com.ysulab.yapper.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * The capture sheet's picture, as ink indices per dither cell: smoke that rises softly from the
 * bottom, a scarlet moon, and rings that leave the moon on loud syllables ("moon ripples").
 * Pure Java: {@link DitherView} maps the inks to colours and draws the cells.
 *
 * <p>Time is driven from outside with {@link #step}, so tests are deterministic.
 */
public final class SmokeScene {

    public static final byte INK_GROUND = 0;
    public static final byte INK_SMOKE = 1;
    public static final byte INK_ASH = 2;
    public static final byte INK_MOON = 3;
    public static final byte INK_MOON_LIT = 4;

    /** Seconds a ring lives. */
    static final float RING_LIFE = 1.4f;
    /** Level that counts as a loud syllable. */
    static final float PEAK = 0.6f;
    /** Minimum gap between rings, seconds. */
    static final float RING_GAP = 0.22f;

    static final class Ring {
        final float born;
        final float strength;

        Ring(float born, float strength) {
            this.born = born;
            this.strength = strength;
        }
    }

    // Layout, in 0..1 of the sheet.
    public float moonX = 0.5f;
    public float moonY = 0.42f;
    /** Moon radius as a fraction of the sheet height. */
    public float moonR = 0.09f;
    /** Bands at the top and bottom (0..1 of height) where text sits: smoke stays in the two darkest inks. */
    public float quietTop = 0.1f;
    public float quietBottom = 0.1f;
    /** Draw the moon and rings at all (off for a plain smoke header). */
    public boolean moon = true;
    /** Overall smoke density multiplier. */
    public float density = 1f;
    /** Where the smoke settles when nothing is held: 0 clears the sheet, 1 keeps a still field (headers). */
    public float rest = 0f;

    float emerge;
    float level;
    float time;
    boolean held;
    private float lastPeak = -10f;
    final List<Ring> rings = new ArrayList<>();

    public SmokeScene(float startTime) {
        this.time = startTime;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    public boolean isHeld() {
        return held;
    }

    public float emerge() {
        return emerge;
    }

    /** Jump to a state without animating (reduced motion, or a still frame). */
    public void snap(float emerge, float level) {
        this.emerge = Dither.clamp(emerge);
        this.level = Dither.clamp(level);
    }

    /**
     * Advance by {@code dt} seconds. {@code targetLevel} is the voice level 0..1 (0 when not held).
     * The rise takes about 1.4 s to fill; letting go settles in about 0.9 s.
     */
    public void step(float dt, float targetLevel) {
        dt = Math.max(0f, Math.min(0.05f, dt));
        time += dt;
        float goal = held ? 1f : rest;
        emerge += (goal - emerge) * (1f - (float) Math.exp(-dt * (held ? 2.2f : 3.2f)));
        float target = held ? Dither.clamp(targetLevel) : 0f;
        level += (target - level) * (1f - (float) Math.exp(-dt * (target > level ? 22f : 7f)));
        if (held && moon && level > PEAK && time - lastPeak > RING_GAP) {
            lastPeak = time;
            rings.add(new Ring(time, level));
        }
        for (int i = rings.size() - 1; i >= 0; i--) {
            if (time - rings.get(i).born >= RING_LIFE) {
                rings.remove(i);
            }
        }
    }

    /** True while anything on screen still moves; when false the view can stop asking for frames. */
    public boolean isAnimating() {
        return held || Math.abs(emerge - rest) > 0.01f || !rings.isEmpty();
    }

    /** Fills {@code out} (length w*h, row-major) with ink indices. */
    public void render(int w, int h, byte[] out) {
        float aspect = w / (float) h;
        float lift = 0.85f + level * 0.3f;
        float moonFade = Dither.smoothstep(0f, 0.4f, emerge);
        int n = rings.size();
        float[] ringR = new float[n];
        float[] ringA = new float[n];
        float[] ringW = new float[n];
        for (int i = 0; i < n; i++) {
            Ring g = rings.get(i);
            float age = time - g.born;
            ringR[i] = moonR + age * 0.32f;
            ringA[i] = g.strength * (1f - age / RING_LIFE);
            ringW[i] = 0.012f + age * 0.006f;
        }
        for (int y = 0; y < h; y++) {
            float ny = y / (float) h;
            boolean quiet = ny < quietTop || ny > 1f - quietBottom;
            float rise = Dither.rise(ny, emerge);
            for (int x = 0; x < w; x++) {
                float nx = x / (float) w;
                float th = Dither.threshold(x, y);
                int idx = y * w + x;
                byte ink = -1;
                float push = 0f;
                if (moon) {
                    float dx = (nx - moonX) * aspect;
                    float dy = ny - moonY;
                    float dd = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dd < moonR && emerge > 0.05f) {
                        float lit = Dither.clamp(0.95f - (dy / moonR + 1f) * 0.28f) * moonFade;
                        if (lit > th) {
                            ink = lit - 0.62f > th * 0.45f ? INK_MOON_LIT : INK_MOON;
                        }
                    }
                    if (ink < 0 && !quiet) {
                        for (int i = 0; i < n; i++) {
                            if (Math.abs(dd - ringR[i]) < ringW[i]) {
                                if (ringA[i] > th) {
                                    ink = ringA[i] > 0.55f + th * 0.4f ? INK_MOON_LIT : INK_MOON;
                                    break;
                                }
                            }
                            if (dd < ringR[i] && dd > ringR[i] - 0.08f) {
                                push = Math.max(push, ringA[i]);
                            }
                        }
                    }
                }
                if (ink < 0) {
                    if (rise <= 0f) {
                        ink = INK_GROUND;
                    } else {
                        float s = Dither.smoke(nx, ny, time) * rise * lift * density;
                        s *= 1f - push * 0.8f;
                        int max = 2;
                        if (quiet) {
                            s *= 0.4f;
                            max = 1;
                        }
                        float v = s * 2f;
                        int base = (int) v;
                        int i = base + ((v - base) > th ? 1 : 0);
                        ink = (byte) Math.min(max, i);
                    }
                }
                out[idx] = ink;
            }
        }
    }
}
