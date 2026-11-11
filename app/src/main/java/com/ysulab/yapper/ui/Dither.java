package com.ysulab.yapper.ui;

/**
 * Math behind the smoke: an 8x8 Bayer threshold matrix, value noise, fractal noise and the
 * domain-warped smoke field. Pure Java so it is unit-tested; {@link SmokeScene} composes it.
 */
public final class Dither {

    private Dither() {}

    private static final int[] BAYER = {
        0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21
    };
    private static final float[] THRESHOLD = new float[64];

    static {
        for (int i = 0; i < 64; i++) {
            THRESHOLD[i] = (BAYER[i] + 0.5f) / 64f;
        }
    }

    /** Ordered-dither threshold for cell (x, y), in (0, 1). Stable: the same cell always gets the same value. */
    public static float threshold(int x, int y) {
        return THRESHOLD[((y & 7) << 3) | (x & 7)];
    }

    static float hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) >>> 1) / (float) Integer.MAX_VALUE;
    }

    /** Smooth value noise in [0, 1]. */
    static float noise(float x, float y) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        float xf = x - xi;
        float yf = y - yi;
        float u = xf * xf * (3 - 2 * xf);
        float v = yf * yf * (3 - 2 * yf);
        float a = hash(xi, yi);
        float b = hash(xi + 1, yi);
        float c = hash(xi, yi + 1);
        float d = hash(xi + 1, yi + 1);
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
    }

    /** Three octaves of value noise, normalised to [0, 1]. */
    static float fbm(float x, float y) {
        return (noise(x, y) * 0.5f + noise(x * 2, y * 2) * 0.25f + noise(x * 4, y * 4) * 0.125f) / 0.875f;
    }

    public static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    public static float smoothstep(float a, float b, float v) {
        float t = clamp((v - a) / (b - a));
        return t * t * (3 - 2 * t);
    }

    /** Smoke density at (nx, ny) in [0, 1] at time t (seconds): warped noise, stretched upward, rising slowly. */
    public static float smoke(float nx, float ny, float t) {
        float q = fbm(nx * 2.6f + t * 0.03f, ny * 1.4f + t * 0.22f);
        float r = fbm(nx * 2.6f + q * 2.4f + 1.7f, ny * 1.4f + q * 2.4f + t * 0.16f);
        return smoothstep(0.38f, 0.78f, r);
    }

    /** The soft rise: as {@code emerge} goes 0 to 1, a feathered front lifts from the bottom edge. */
    public static float rise(float ny, float emerge) {
        float top = 1.05f - emerge * 1.25f;
        return smoothstep(top, top + 0.5f, ny);
    }
}
