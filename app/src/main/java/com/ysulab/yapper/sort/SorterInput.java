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
    /** "" (quick capture), "ramble", "plan" or "debrief" (M4/M5). */
    public String mode = "";
}
