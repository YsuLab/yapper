package com.ysulab.yapper.sort;

import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds the {@code /chat/completions} request: system prompt (template from
 * {@code assets/sorter/system.md} + rules + index + daily note) and the capture as the user message.
 * The stable part comes first so providers can cache it. Pure Java, testable.
 */
public final class SorterPrompt {

    static final int MAX_RULES = 12_000;
    static final int MAX_INDEX = 40_000;
    static final int MAX_DAILY = 8_000;
    static final int MAX_SCREEN = 8_000;
    static final int MAX_TRANSCRIPT = 60_000;
    /** Below this many characters of screen text, the screenshot is worth sending. */
    public static final int THIN_SCREEN_TEXT = 80;

    private SorterPrompt() {}

    public static String system(String template, SorterInput in) {
        return template
                .replace("{{NOW}}", in.now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .replace("{{ZONE}}", in.now.getZone().getId())
                .replace("{{WEEKDAY}}", in.now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                .replace("{{RULES}}", orNone(cap(in.rules, MAX_RULES)))
                .replace("{{INDEX}}", orNone(cap(in.index, MAX_INDEX)))
                .replace("{{DAILY_PATH}}", in.dailyPath)
                .replace("{{DAILY}}", orNone(cap(in.daily, MAX_DAILY)));
    }

    public static String userText(SorterInput in) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Transcript\n");
        String t = in.transcript == null ? "" : in.transcript.trim();
        sb.append(t.isEmpty() ? "(no speech)" : cap(t, MAX_TRANSCRIPT)).append("\n\n");
        if (!in.sourceApp.isEmpty() || !in.sourceUrl.isEmpty()) {
            sb.append("## Screen source\n");
            if (!in.sourceApp.isEmpty()) sb.append("App: ").append(in.sourceApp).append('\n');
            if (!in.sourceUrl.isEmpty()) sb.append("URL: ").append(in.sourceUrl).append('\n');
            sb.append('\n');
        }
        String s = in.screenText == null ? "" : in.screenText.trim();
        sb.append("## Screen text (what was on the phone screen when he started talking)\n");
        sb.append(s.isEmpty() ? "(none)" : cap(s, MAX_SCREEN)).append('\n');
        if (in.screenshotBase64 != null) {
            sb.append("\nThe screenshot is attached.\n");
        }
        return sb.toString();
    }

    public static JSONObject request(SorterConfig cfg, String template, String toolsJson, SorterInput in)
            throws JSONException {
        JSONObject sysPart = new JSONObject().put("type", "text").put("text", system(template, in));
        if (cfg.promptCaching()) {
            sysPart.put("cache_control", new JSONObject().put("type", "ephemeral"));
        }
        JSONArray userParts = new JSONArray();
        userParts.put(new JSONObject().put("type", "text").put("text", userText(in)));
        if (in.screenshotBase64 != null) {
            userParts.put(new JSONObject().put("type", "image_url").put("image_url",
                    new JSONObject().put("url", "data:image/jpeg;base64," + in.screenshotBase64)));
        }
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", new JSONArray().put(sysPart)))
                .put(new JSONObject().put("role", "user").put("content", userParts));
        return new JSONObject()
                .put("model", cfg.modelId())
                .put("messages", messages)
                .put("tools", new JSONArray(toolsJson))
                // "auto", not "required": Claude models with extended thinking reject forced tool use
                // (OpenRouter maps "required" to Anthropic's "any"). The prompt says "tool calls only",
                // and a text-only answer becomes an ask_owner question (Sorter.parse).
                .put("tool_choice", "auto")
                .put("temperature", 0.2)
                .put("max_tokens", 8000);
    }

    static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…(cut)";
    }

    private static String orNone(String s) {
        return s == null || s.trim().isEmpty() ? "(empty)" : s.trim();
    }
}
