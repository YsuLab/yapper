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
    /** M5: time blocks for a day → "## Plan" in the daily note, plus an alarm at each block start. */
    public static final String PLAN = "plan_day";
    /** M5 debrief: tick a "- [ ]" task in a daily note. */
    public static final String CHECK = "complete_task";

    /** One plan_day time block. */
    public static final class Block {
        public String start = "";
        public String end = "";
        public String title = "";
        public boolean alarm = true;
    }

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
    public final List<Block> blocks = new ArrayList<>();

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
                s.date = date(req(a, "date"));
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
            case PLAN:
                s.date = date(req(a, "date"));
                s.markdown = a.optString("notes", "").trim();
                JSONArray b = a.optJSONArray("blocks");
                if (b == null || b.length() == 0) {
                    throw new JSONException("missing blocks");
                }
                for (int i = 0; i < b.length(); i++) {
                    JSONObject bo = b.optJSONObject(i);
                    if (bo == null) {
                        continue;
                    }
                    Block k = new Block();
                    k.title = oneLine(bo.optString("title", ""));
                    k.start = time(bo.optString("start", ""));
                    k.end = bo.optString("end", "").trim().isEmpty() ? "" : time(bo.optString("end"));
                    k.alarm = bo.optBoolean("alarm", true);
                    if (k.title.isEmpty()) {
                        throw new JSONException("block without title");
                    }
                    s.blocks.add(k);
                }
                break;
            case CHECK:
                s.title = oneLine(req(a, "task"));
                String d = a.optString("date", "").trim();
                s.date = d.isEmpty() ? "" : date(d);
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
        JSONArray b = new JSONArray();
        for (Block k : blocks) {
            b.put(new JSONObject().put("start", k.start).put("end", k.end).put("title", k.title)
                    .put("alarm", k.alarm));
        }
        o.put("blocks", b);
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
        JSONArray b = o.optJSONArray("blocks");
        if (b != null) {
            for (int i = 0; i < b.length(); i++) {
                JSONObject x = b.optJSONObject(i);
                if (x == null) continue;
                Block k = new Block();
                k.start = x.optString("start", "");
                k.end = x.optString("end", "");
                k.title = x.optString("title", "");
                k.alarm = x.optBoolean("alarm", true);
                s.blocks.add(k);
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

    private static String date(String v) throws JSONException {
        String d = v.trim();
        try {
            java.time.LocalDate.parse(d);
        } catch (RuntimeException e) {
            throw new JSONException("bad date " + d);
        }
        return d;
    }

    /** "9:00", "09:00" or "21:30" → "HH:mm". */
    static String time(String v) throws JSONException {
        String t = v == null ? "" : v.trim();
        if (!t.matches("\\d{1,2}:\\d{2}")) {
            throw new JSONException("bad time " + t);
        }
        try {
            return java.time.LocalTime.parse(t.length() == 4 ? "0" + t : t).toString();
        } catch (RuntimeException e) {
            throw new JSONException("bad time " + t);
        }
    }

    static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
