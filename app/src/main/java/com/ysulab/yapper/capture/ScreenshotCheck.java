package com.ysulab.yapper.capture;

/**
 * Detects the black screenshot Android returns for {@code FLAG_SECURE} apps (banking, some chats).
 * Then the capture falls back to voice + screen text only. Pure Java so it is unit-testable.
 */
public final class ScreenshotCheck {

    /** A pixel brighter than this (0..255 luma) counts as "content". */
    static final int LUMA_THRESHOLD = 16;
    /** Fewer than this share of bright pixels → treat the image as blank. */
    static final double MIN_CONTENT_SHARE = 0.005;

    private ScreenshotCheck() {}

    /** @param argb sampled pixels in {@code Bitmap.getPixel} ARGB format. */
    public static boolean isBlank(int[] argb) {
        if (argb == null || argb.length == 0) {
            return true;
        }
        int bright = 0;
        for (int p : argb) {
            int r = (p >> 16) & 0xff;
            int g = (p >> 8) & 0xff;
            int b = p & 0xff;
            int luma = (r * 299 + g * 587 + b * 114) / 1000;
            if (luma > LUMA_THRESHOLD) {
                bright++;
            }
        }
        return bright < Math.max(1, argb.length * MIN_CONTENT_SHARE);
    }
}
