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
    /** Second brain: keep a note per person/project/tool/place he mentions, with facts and links. */
    public static final String ENTITY = "link_entity";
    /** Weekly review: his reflection → "## Reflection" in this week's Weekly/ note. */
    public static final String REVIEW = "weekly_review";

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
    /** link_entity: person, project, org, course, tool, place or topic. */
    public String kind = "";
    /** link_entity: one line about what this is (only used when the note is new). */
    public String summary = "";
    /** link_entity: other names for it (Arabic spelling, short forms). */
    public final List<String> aliases = new ArrayList<>();
    /** link_entity: names of related entities or notes to link to. */
    public final List<String> related = new ArrayList<>();
    /** weekly_review: what went well. */
    public final List<String> wentWell = new ArrayList<>();
    /** weekly_review: what could be better. */
    public final List<String> better = new ArrayList<>();
    /** weekly_review: focus for next week (become "- [ ]" lines). */
    public final List<String> focus = new ArrayList<>();

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
            case ENTITY:
                s.title = com.ysulab.yapper.vault.EntityIndex.clean(req(a, "name"));
                if (s.title.isEmpty() || s.title.length() > 80) {
                    throw new JSONException("bad name " + s.title);
                }
                s.kind = oneLine(a.optString("kind", "topic")).toLowerCase(java.util.Locale.ROOT);
                s.summary = oneLine(a.optString("summary", ""));
                s.markdown = a.optString("fact", "").trim();
                String p = a.optString("path", "").trim();
                // No path: a default folder per kind. The executor still prefers an existing note
                // with this name or alias, so "GCP" lands in the note that already has that alias.
                s.path = SafePath.normalize(p.isEmpty() ? entityFolder(s.kind) + "/" + s.title.replace('/', '-') : p);
                names(a.optJSONArray("aliases"), s.aliases, s.title);
                names(a.optJSONArray("related"), s.related, s.title);
                break;
            case REVIEW:
                s.summary = a.optString("summary", "").trim();
                lines(a.optJSONArray("went_well"), s.wentWell);
                lines(a.optJSONArray("could_be_better"), s.better);
                lines(a.optJSONArray("focus_next_week"), s.focus);
                s.markdown = a.optString("notes", "").trim();
                if (s.summary.isEmpty() && s.wentWell.isEmpty() && s.better.isEmpty() && s.focus.isEmpty()
                        && s.markdown.isEmpty()) {
                    throw new JSONException("empty review");
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
        JSONArray b = new JSONArray();
        for (Block k : blocks) {
            b.put(new JSONObject().put("start", k.start).put("end", k.end).put("title", k.title)
                    .put("alarm", k.alarm));
        }
        o.put("blocks", b);
        if (ENTITY.equals(tool)) {
            o.put("kind", kind);
            o.put("summary", summary);
            o.put("aliases", new JSONArray(aliases));
            o.put("related", new JSONArray(related));
        }
        if (REVIEW.equals(tool)) {
            o.put("summary", summary);
            o.put("wentWell", new JSONArray(wentWell));
            o.put("better", new JSONArray(better));
            o.put("focus", new JSONArray(focus));
        }
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
        s.kind = o.optString("kind", "");
        s.summary = o.optString("summary", "");
        strings(o.optJSONArray("aliases"), s.aliases);
        strings(o.optJSONArray("related"), s.related);
        strings(o.optJSONArray("wentWell"), s.wentWell);
        strings(o.optJSONArray("better"), s.better);
        strings(o.optJSONArray("focus"), s.focus);
        return s;
    }

    private static void strings(JSONArray a, List<String> out) {
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                out.add(a.optString(i));
            }
        }
    }

    /** Default home for a new entity note when the rules and the index do not give one. */
    static String entityFolder(String kind) {
        switch (kind) {
            case "person":
                return "People";
            case "project":
                return "Projects";
            case "course":
                return "Uni";
            default:
                return "Topics";
        }
    }

    /** Non-empty one-line items, without a leading "- " or "- [ ] " (the executor adds those). */
    private static void lines(JSONArray a, List<String> out) {
        if (a == null) {
            return;
        }
        for (int i = 0; i < a.length() && out.size() < 15; i++) {
            String t = oneLine(a.optString(i, "")).replaceFirst("^-\\s*(\\[[ xX]\\]\\s*)?", "").trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
    }

    /** Clean, unique names, skipping the entity's own name. */
    private static void names(JSONArray a, List<String> out, String self) {
        if (a == null) {
            return;
        }
        for (int i = 0; i < a.length() && out.size() < 20; i++) {
            String n = com.ysulab.yapper.vault.EntityIndex.clean(a.optString(i, ""));
            if (!n.isEmpty() && n.length() <= 80 && !n.equalsIgnoreCase(self) && !out.contains(n)) {
                out.add(n);
            }
        }
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
