package com.ysulab.yapper.sort;

import java.time.ZonedDateTime;

/** Everything the sorter sees for one capture. Plain data. */
public final class SorterInput {
    public ZonedDateTime now;
    public String transcript = "";
    public String screenText = "";
    public String sourceApp = "";
    public String sourceUrl = "";
    /** JPEG bytes as base64, or null when the screenshot is not sent. */
    public String screenshotBase64;
    public String rules = "";
    public String index = "";
    public String dailyPath = "";
    public String daily = "";
    /** Prayer-time table for the next days ({@code PrayerTimes.table}), so "after Asr" resolves. */
    public String prayers = "";
    /** Text shared from another app (share mode). */
    public String sharedText = "";
    /** Vault names of the shared files, so the model can embed them with ![[name]]. */
    public java.util.List<String> sharedFiles = new java.util.ArrayList<>();
    /** Shared images as JPEG base64 (downscaled), at most SharedItems.MAX_IMAGES_TO_SORTER. */
    public java.util.List<String> sharedImagesBase64 = new java.util.ArrayList<>();
    /** One shared PDF as base64, or null. Only sent to OpenRouter (it reads PDFs for any model). */
    public String sharedPdfBase64;
    public String sharedPdfName = "";
    /** "" (quick capture), "ramble", "plan", "debrief" (M4/M5), "share" or "review". */
    public String mode = "";
    /** Review mode: the week's note path and its auto-built summary (what he did this week). */
    public String weeklyPath = "";
    public String weekly = "";
}
