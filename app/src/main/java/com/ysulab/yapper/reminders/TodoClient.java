package com.ysulab.yapper.reminders;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Microsoft Graph To Do: list task lists, create a task with a reminder, delete it (Undo).
 * Samsung Reminder syncs with one To Do list, so tasks go there. Pure Java, testable.
 *
 * <p>Times are sent in UTC ({@code timeZone: "UTC"}): the To Do apps show them in the phone's zone.
 */
public final class TodoClient {

    public static final String GRAPH = "https://graph.microsoft.com/v1.0";
    private static final DateTimeFormatter GRAPH_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS", Locale.ROOT);

    /** HTTP error from Graph. {@code retryable}: throttling or a server error. */
    public static final class GraphException extends Exception {
        public final int code;
        public final boolean retryable;

        public GraphException(int code, String message) {
            super("Graph HTTP " + code + ": " + message);
            this.code = code;
            this.retryable = code == 429 || code >= 500 || code == 408;
        }
    }

    public static final class TaskList {
        public final String id;
        public final String name;
        public final String wellknown;

        public TaskList(String id, String name, String wellknown) {
            this.id = id;
            this.name = name;
            this.wellknown = wellknown;
        }
    }

    private final String base;
    private final String accessToken;
    private final WebCall web;

    public TodoClient(String accessToken, WebCall web) {
        this(GRAPH, accessToken, web);
    }

    public TodoClient(String base, String accessToken, WebCall web) {
        this.base = base;
        this.accessToken = accessToken;
        this.web = web;
    }

    public List<TaskList> lists() throws IOException, GraphException {
        List<TaskList> out = new ArrayList<>();
        String url = base + "/me/todo/lists";
        int pages = 0;
        while (url != null && pages++ < 10) {
            JSONObject o = json(call("GET", url, null));
            JSONArray v = o.optJSONArray("value");
            if (v != null) {
                for (int i = 0; i < v.length(); i++) {
                    JSONObject l = v.optJSONObject(i);
                    if (l != null) {
                        out.add(new TaskList(l.optString("id"), l.optString("displayName"),
                                l.optString("wellknownListName", "none")));
                    }
                }
            }
            url = o.optString("@odata.nextLink", "");
            if (url.isEmpty()) {
                url = null;
            }
        }
        return out;
    }

    /** Creates a task and returns its id. */
    public String createTask(String listId, String body) throws IOException, GraphException {
        JSONObject o = json(call("POST", base + "/me/todo/lists/" + enc(listId) + "/tasks", body));
        String id = o.optString("id", "");
        if (id.isEmpty()) {
            throw new IOException("Graph returned no task id");
        }
        return id;
    }

    /** Deletes a task. Already gone (404) counts as success. */
    public void deleteTask(String listId, String taskId) throws IOException, GraphException {
        WebCall.Response r = web.send("DELETE", base + "/me/todo/lists/" + enc(listId) + "/tasks/" + enc(taskId),
                headers(false), null);
        if (!r.ok() && r.code != 404) {
            throw new GraphException(r.code, WebCall.snippet(r.body));
        }
    }

    /**
     * Task JSON: title, optional notes, due date (the reminder's local day) and, when
     * {@code reminderOn}, a reminder at {@code at}.
     */
    public static String taskJson(String title, String notes, OffsetDateTime at, boolean reminderOn) {
        try {
            JSONObject t = new JSONObject();
            t.put("title", title.length() > 255 ? title.substring(0, 255) : title);
            if (notes != null && !notes.trim().isEmpty()) {
                t.put("body", new JSONObject().put("content", notes.trim()).put("contentType", "text"));
            }
            LocalDate day = at.toLocalDate();
            t.put("dueDateTime", new JSONObject()
                    .put("dateTime", day.atStartOfDay().format(GRAPH_TIME))
                    .put("timeZone", "UTC"));
            t.put("isReminderOn", reminderOn);
            if (reminderOn) {
                t.put("reminderDateTime", new JSONObject()
                        .put("dateTime", at.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().format(GRAPH_TIME))
                        .put("timeZone", "UTC"));
            }
            return t.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private String call(String method, String url, String body) throws IOException, GraphException {
        WebCall.Response r = web.send(method, url, headers(body != null), body);
        if (!r.ok()) {
            throw new GraphException(r.code, WebCall.snippet(r.body));
        }
        return r.body;
    }

    private Map<String, String> headers(boolean json) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + accessToken);
        h.put("Accept", "application/json");
        if (json) {
            h.put("Content-Type", "application/json; charset=utf-8");
        }
        return h;
    }

    private static JSONObject json(String body) throws IOException {
        try {
            return new JSONObject(body.isEmpty() ? "{}" : body);
        } catch (JSONException e) {
            throw new IOException("Unexpected Graph response: " + WebCall.snippet(body));
        }
    }

    private static String enc(String s) {
        return MsAuth.enc(s);
    }
}
