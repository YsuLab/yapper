package com.ysulab.yapper.hub;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Decision 5: at night, the tasks struck on the board today go into the daily note as one appended
 * block. One way only: nothing on the board changes, and the block is written once per day. Pure Java.
 */
public final class DoneBlock {

    public static final String SECTION = "## Done today";

    private DoneBlock() {}

    public static String marker(String day) {
        return "%%yap:done-" + day + "%%";
    }

    /** The block for {@code GET /api/tasks?view=done&day=...}, or null when nothing was done. */
    public static String build(String day, String hubJson) throws JSONException {
        JSONArray tasks = new JSONObject(hubJson).optJSONArray("tasks");
        if (tasks == null || tasks.length() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder("\n").append(SECTION).append('\n').append(marker(day)).append('\n');
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.getJSONObject(i);
            String text = t.optString("text", "").replaceAll("\\s+", " ").trim();
            sb.append("- ✓ ").append(text.isEmpty() ? "(handwritten task)" : text).append('\n');
        }
        return sb.toString();
    }
}
