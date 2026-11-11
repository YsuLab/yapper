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
    /** "" (quick capture), "ramble", "plan", "debrief" or "review" (M4/M5). */
    public String mode = "";
    /** Review mode: the week's note path and its auto-built summary (what he did this week). */
    public String weeklyPath = "";
    public String weekly = "";
}
