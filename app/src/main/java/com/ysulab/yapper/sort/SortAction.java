package com.ysulab.yapper.sort;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One tool call from the sorter, already validated. Pure Java (org.json), testable. */
public final class SortAction {

    public static final String APPEND = "append_to_note";
    public static final String CREATE = "create_note";
    public static final String DAILY = "add_to_daily";
    public static final String REMINDER = "create_reminder";
    public static final String ASK = "ask_owner";

    public final String tool;
    /** Target note path (for every tool except REMINDER and ASK, which are resolved by the executor). */
    public String path = "";
    public String heading = "";
    public String markdown = "";
    /** add_to_daily: YYYY-MM-DD. */
    public String date = "";
    public String title = "";
    /** create_reminder: ISO 8601 with offset. */
    public String remindAt = "";
    public String question = "";
    public final List<String> options = new ArrayList<>();

    public SortAction(String tool) {
        this.tool = tool;
    }

    /**
     * Parses one tool call's name + JSON arguments. Throws for unknown tools or missing required
     * fields, so a bad call is dropped instead of writing garbage into the vault.
     */
    public static SortAction parse(String name, String argumentsJson) throws JSONException {
        JSONObject a = new JSONObject(argumentsJson == null || argumentsJson.trim().isEmpty()
                ? "{}" : argumentsJson);
        SortAction s = new SortAction(name);
        switch (name) {
            case APPEND:
                s.path = SafePath.normalize(req(a, "path"));
                s.heading = oneLine(a.optString("heading", ""));
                s.markdown = req(a, "markdown");
                break;
            case CREATE:
                s.path = SafePath.normalize(req(a, "path"));
                s.markdown = req(a, "markdown");
                break;
            case DAILY:
                s.date = req(a, "date").trim();
                if (!s.date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    throw new JSONException("bad date " + s.date);
                }
                s.heading = oneLine(a.optString("section", ""));
                s.markdown = req(a, "markdown");
                break;
            case REMINDER:
                s.title = oneLine(req(a, "title"));
                s.remindAt = req(a, "remind_at").trim();
                s.markdown = a.optString("notes", "").trim();
                java.time.OffsetDateTime.parse(s.remindAt); // throws DateTimeParseException if bad
                break;
            case ASK:
                s.question = oneLine(req(a, "question"));
                JSONArray o = a.optJSONArray("options");
                if (o != null) {
                    for (int i = 0; i < o.length(); i++) {
                        String opt = oneLine(o.optString(i, ""));
                        if (!opt.isEmpty()) {
                            s.options.add(opt);
                        }
                    }
                }
                break;
            default:
                throw new JSONException("unknown tool " + name);
        }
        return s;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("tool", tool);
        o.put("path", path);
        o.put("heading", heading);
        o.put("markdown", markdown);
        o.put("date", date);
        o.put("title", title);
        o.put("remindAt", remindAt);
        o.put("question", question);
        o.put("options", new JSONArray(options));
        return o;
    }

    public static SortAction fromJson(JSONObject o) {
        SortAction s = new SortAction(o.optString("tool"));
        s.path = o.optString("path", "");
        s.heading = o.optString("heading", "");
        s.markdown = o.optString("markdown", "");
        s.date = o.optString("date", "");
        s.title = o.optString("title", "");
        s.remindAt = o.optString("remindAt", "");
        s.question = o.optString("question", "");
        JSONArray opts = o.optJSONArray("options");
        if (opts != null) {
            for (int i = 0; i < opts.length(); i++) {
                s.options.add(opts.optString(i));
            }
        }
        return s;
    }

    public static String toJsonArray(List<SortAction> actions) throws JSONException {
        JSONArray a = new JSONArray();
        for (SortAction s : actions) {
            a.put(s.toJson());
        }
        return a.toString(2);
    }

    public static List<SortAction> fromJsonArray(String json) throws JSONException {
        JSONArray a = new JSONArray(json);
        List<SortAction> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            out.add(fromJson(a.getJSONObject(i)));
        }
        return out;
    }

    private static String req(JSONObject a, String key) throws JSONException {
        String v = a.optString(key, "");
        if (v.trim().isEmpty()) {
            throw new JSONException("missing " + key);
        }
        return v;
    }

    static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
