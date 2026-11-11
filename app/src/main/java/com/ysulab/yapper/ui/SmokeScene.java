package com.ysulab.yapper.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * The capture sheet's picture, as ink indices per dither cell: smoke that rises softly from the
 * bottom, a scarlet moon, and rings that leave the moon on loud syllables ("moon ripples").
 * Pure Java: {@link DitherView} maps the inks to colours and draws the cells.
 *
 * <p>The motion, in order:
 * <ol>
 *   <li>Rise (~1.8 s, eased in and out): the smoke front climbs from the bottom edge in uneven
 *       plumes, not as a flat line. A dark dithered haze climbs with it, so the app behind stays
 *       visible at the top and fades into black where the words sit.</li>
 *   <li>Moonrise: the moon lifts through the smoke into place and dithers in.</li>
 *   <li>Listening: two smoke layers drift up at different speeds (depth), swaying a little; the
 *       voice speeds the drift and brightens the moon; loud syllables send rings.</li>
 *   <li>Dissolve (~1.2 s) on release: the source shuts off, the smoke detaches from the bottom and
 *       drifts up and away while it thins; the moon sinks and fades.</li>
 * </ol>
 *
 * <p>Time is driven from outside with {@link #step}, so tests are deterministic.
 */
public final class SmokeScene {

    public static final byte INK_GROUND = 0;
    public static final byte INK_SMOKE = 1;
    public static final byte INK_ASH = 2;
    public static final byte INK_MOON = 3;
    public static final byte INK_MOON_LIT = 4;
    /** Nothing drawn: the screen behind shows through (only when {@link #transparent}). */
    public static final byte INK_CLEAR = 5;
    public static final int INKS = 6;

    /** Seconds the rise takes. */
    static final float RISE_S = 1.8f;
    /** Seconds the dissolve takes after letting go. */
    static final float EXIT_S = 1.2f;
    /** Seconds a ring lives. */
    static final float RING_LIFE = 1.4f;
    /** Level that counts as a loud syllable. */
    static final float PEAK = 0.6f;
    /** Minimum gap between rings, seconds. */
    static final float RING_GAP = 0.32f;

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
    /**
     * Let the screen behind show through: empty cells are {@link #INK_CLEAR}, and a dithered dark
     * haze rises with the smoke (none at the top, solid in the bottom text band).
     */
    public boolean transparent = false;

    /** Rise progress 0..1, linear in time; {@link #emerge()} is the eased value. */
    float progress;
    /** Dissolve progress 0..1 after letting go; 0 when not dissolving. */
    float exit;
    float level;
    float time;
    boolean held;
    private float lastPeak = -10f;
    final List<Ring> rings = new ArrayList<>();

    public SmokeScene(float startTime) {
        this.time = startTime;
    }

    public void setHeld(boolean held) {
        if (held && !this.held) {
            exit = 0f;
        } else if (!held && this.held && rest <= 0f && progress > 0f) {
            exit = 0.0001f;
        }
        this.held = held;
    }

    public boolean isHeld() {
        return held;
    }

    /** How far the smoke has risen, 0..1, eased in and out. */
    public float emerge() {
        return Dither.smoothstep(0f, 1f, progress);
    }

    /** Jump to a state without animating (reduced motion, or a still frame). */
    public void snap(float emerge, float level) {
        this.progress = Dither.clamp(emerge);
        this.level = Dither.clamp(level);
        this.exit = 0f;
    }

    /** Advance by {@code dt} seconds. {@code targetLevel} is the voice level 0..1 (0 when not held). */
    public void step(float dt, float targetLevel) {
        dt = Math.max(0f, Math.min(0.05f, dt));
        float target = held ? Dither.clamp(targetLevel) : 0f;
        level += (target - level) * (1f - (float) Math.exp(-dt * (target > level ? 18f : 6f)));
        // The voice nudges the drift; the dissolve lets the smoke hurry off.
        time += dt * (1f + level * 0.5f + exit * 0.8f);
        if (held) {
            progress = Math.min(1f, progress + dt / RISE_S);
        } else if (exit > 0f) {
            exit += dt / EXIT_S;
            if (exit >= 1f) {
                exit = 0f;
                progress = 0f;
            }
        } else if (progress != rest) {
            float d = dt / RISE_S;
            progress = Math.abs(rest - progress) <= d ? rest : progress + Math.signum(rest - progress) * d;
        }
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
        return held || exit > 0f || progress != rest || !rings.isEmpty();
    }

    /** Fills {@code out} (length w*h, row-major) with ink indices. */
    public void render(int w, int h, byte[] out) {
        float aspect = w / (float) h;
        float e = emerge();
        float gone = Dither.smoothstep(0f, 1f, exit);
        float thin = 1f - gone * gone;
        // The bottom edge of the smoke: below the sheet while the source is on, climbing when it stops.
        float cut = 1.3f - gone * 1.9f;
        float lift = 0.85f + level * 0.3f;
        byte empty = transparent ? INK_CLEAR : INK_GROUND;

        float mr = moonR * (1f + level * 0.07f);
        float rise = Dither.smoothstep(0.25f, 1f, e);
        float my = moonY + (1f - rise) * 0.4f + gone * 0.12f;
        float moonFade = Dither.smoothstep(0.3f, 0.85f, e) * (1f - Dither.smoothstep(0f, 0.7f, exit));
        float glow = 0.95f + level * 0.15f;

        int n = rings.size();
        float[] ringR = new float[n];
        float[] ringA = new float[n];
        float[] ringW = new float[n];
        for (int i = 0; i < n; i++) {
            Ring g = rings.get(i);
            float u = (time - g.born) / RING_LIFE;
            float out1 = 1f - (1f - u) * (1f - u);
            ringR[i] = mr + out1 * 0.42f;
            float a = 1f - u;
            ringA[i] = g.strength * a * a * moonFade;
            ringW[i] = 0.01f + u * 0.012f;
        }

        // Uneven front: plumes per column that wander slowly.
        float[] front = new float[w];
        for (int x = 0; x < w; x++) {
            float nx = x / (float) w;
            float f = Dither.fbm(nx * 3.2f + 11.3f, time * 0.22f) - 0.5f;
            front[x] = 1.25f - e * 1.6f + f * 0.45f;
        }

        for (int y = 0; y < h; y++) {
            float ny = y / (float) h;
            boolean quiet = ny < quietTop || ny > 1f - quietBottom;
            // Smoke is thicker near its source and thins as it climbs.
            float fall = 0.55f + 0.45f * ny;
            float detach = 1f - Dither.smoothstep(cut - 0.4f, cut, ny);
            float sway = 0.035f * (float) Math.sin(ny * 5.5f + time * 0.6f);
            // No hard top edge: smoke and haze feather out before the top of the sheet.
            float topFade = Dither.smoothstep(0.0f, 0.4f, ny);
            float hazeBase = Dither.smoothstep(0.05f, 0.92f, ny);
            // Under the words the haze holds until the very end of the dissolve, so "saved" stays readable.
            float hazeFloor = Dither.smoothstep(1f - quietBottom - 0.2f, 1f, ny) * e
                    * (1f - Dither.smoothstep(0.55f, 1f, exit));
            for (int x = 0; x < w; x++) {
                float nx = x / (float) w;
                float th = Dither.threshold(x, y);
                int idx = y * w + x;
                byte ink = -1;
                float push = 0f;
                if (moon && moonFade > 0f) {
                    float dx = (nx - moonX) * aspect;
                    float dy = ny - my;
                    float dd = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dd < mr) {
                        float lit = Dither.clamp(glow - (dy / mr + 1f) * 0.28f) * moonFade;
                        if (lit > th) {
                            ink = lit - 0.62f > th * 0.45f ? INK_MOON_LIT : INK_MOON;
                        } else if (transparent && moonFade > th) {
                            ink = INK_GROUND;
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
                            if (dd < ringR[i] && dd > ringR[i] - 0.1f) {
                                push = Math.max(push, ringA[i]);
                            }
                        }
                    }
                }
                if (ink < 0) {
                    float r = Dither.smoothstep(front[x], front[x] + 0.45f, ny) * detach * thin * topFade;
                    if (r <= 0f) {
                        ink = transparent && hazeFloor > th ? INK_GROUND : empty;
                    } else {
                        float sx = nx + sway;
                        // Near layer: brighter, faster. Far layer: dimmer, slower, larger (depth).
                        float near = Dither.smoke(sx, ny, time);
                        float far = Dither.smoke(sx * 0.7f + 3.1f, ny * 0.8f + 1.3f, time * 0.6f);
                        float k = r * fall * lift * density * (1f - push * 0.8f);
                        float v = Math.max(near * k * 2f, Math.min(1f, far * k * 1.3f));
                        int max = 2;
                        if (quiet) {
                            v *= 0.4f;
                            max = 1;
                        }
                        int base = (int) v;
                        int i = Math.min(max, base + ((v - base) > th ? 1 : 0));
                        if (i > 0) {
                            ink = (byte) i;
                        } else if (!transparent) {
                            ink = INK_GROUND;
                        } else {
                            // The dark haze rises with the smoke; the screen behind shows between its dots.
                            ink = Math.max(hazeBase * r, hazeFloor) > th ? INK_GROUND : INK_CLEAR;
                        }
                    }
                }
                out[idx] = ink;
            }
        }
    }
}
