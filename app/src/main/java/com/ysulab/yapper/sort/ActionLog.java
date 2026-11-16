package com.ysulab.yapper.sort;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Every vault write of one capture, with the exact text written, so Undo removes exactly that.
 * Stored as {@code actions.json} in the capture bundle. Pure Java.
 */
public final class ActionLog {

    public static final class Entry {
        /**
         * "append", "create", "check" (a task line ticked: {@code text} is the line before,
         * {@code newText} after) or "reminder" (no vault write; the reminder lives in To Do / an alarm).
         */
        public String kind;
        public String path;
        /** Exact text we appended, or the full content of a note we created. */
        public String text;
        /** The tool that caused it (append_to_note, create_reminder...). */
        public String tool;
        /** create_reminder only: the reminder for M3 to send to Microsoft To Do. */
        public String title = "";
        public String remindAt = "";
        public String notes = "";
        /** "check" only: the line as we left it. */
        public String newText = "";
        /**
         * M3 reminder routing: "" (not decided), "todo" (To Do reminder), "local" (exact alarm only),
         * "both" (alarm rings; To Do task without its own reminder, to avoid a double alert),
         * "past" (time already passed: To Do task only), "undone".
         */
        public String route = "";
        public String todoListId = "";
        public String todoTaskId = "";
        /** AlarmManager request code of the local alarm, 0 = none. */
        public int alarmId;
        public boolean undone;

        public boolean isReminder() {
            return !remindAt.isEmpty();
        }
    }

    public final List<Entry> entries = new ArrayList<>();

    public Entry add(String kind, String path, String text, String tool) {
        Entry e = new Entry();
        e.kind = kind;
        e.path = path;
        e.text = text;
        e.tool = tool;
        entries.add(e);
        return e;
    }

    public String serialize() throws JSONException {
        JSONArray a = new JSONArray();
        for (Entry e : entries) {
            a.put(new JSONObject()
                    .put("kind", e.kind)
                    .put("path", e.path)
                    .put("text", e.text)
                    .put("tool", e.tool)
                    .put("title", e.title)
                    .put("remindAt", e.remindAt)
                    .put("notes", e.notes)
                    .put("newText", e.newText)
                    .put("route", e.route)
                    .put("todoListId", e.todoListId)
                    .put("todoTaskId", e.todoTaskId)
                    .put("alarmId", e.alarmId)
                    .put("undone", e.undone));
        }
        return a.toString(2);
    }

    public static ActionLog parse(String json) throws JSONException {
        ActionLog log = new ActionLog();
        if (json == null || json.trim().isEmpty()) {
            return log;
        }
        JSONArray a = new JSONArray(json);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Entry e = log.add(o.optString("kind"), o.optString("path"), o.optString("text"), o.optString("tool"));
            e.title = o.optString("title", "");
            e.remindAt = o.optString("remindAt", "");
            e.notes = o.optString("notes", "");
            e.newText = o.optString("newText", "");
            e.route = o.optString("route", "");
            e.todoListId = o.optString("todoListId", "");
            e.todoTaskId = o.optString("todoTaskId", "");
            e.alarmId = o.optInt("alarmId", 0);
            e.undone = o.optBoolean("undone", false);
        }
        return log;
    }
}
