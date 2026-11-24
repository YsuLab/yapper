package com.ysulab.yapper.hub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tasks, captures and pages in SQLite. Writes are idempotent by id: a retried send never makes a
 * duplicate. Transcripts are never edited and captures are never deleted.
 */
@Service
public class HubStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final HubProperties props;
    private final org.springframework.jdbc.core.RowMapper<Task> ROW = (rs, n) -> mapTask(rs);

    public HubStore(JdbcTemplate jdbc, ObjectMapper json, Clock clock, HubProperties props) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.props = props;
    }

    /** Today's date where Yasir lives (Asia/Riyadh). */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(props.zoneId()));
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    // ---------------------------------------------------------------- captures

    /** One item of a capture: a task, or something the sorter was not sure about. */
    public record Item(String id, String kind, String text, String quote, String due) {}

    public record CaptureIn(String id, String capturedAt, String transcript, String src, List<Item> items) {}

    public record CaptureResult(String captureId, boolean newCapture, List<String> added, List<String> existing,
            List<String> skipped) {}

    /**
     * Stores the raw transcript (first time only, never edited) and inserts its items as tasks. Items
     * that already exist are left alone, so the board's changes (done, strike line) survive a resend.
     * Kinds other than task/unsorted (notes) are skipped: they live in the vault.
     */
    @Transactional
    public CaptureResult saveCapture(CaptureIn in) {
        String id = Check.id(in.id(), "capture id");
        String capturedAt = Check.dateTime(in.capturedAt());
        if (capturedAt == null) {
            throw Check.bad("capturedAt is required");
        }
        String transcript = Check.text(in.transcript(), Check.MAX_TRANSCRIPT, "transcript");
        String src = Check.src(in.src() == null ? "voice" : in.src());
        List<Item> items = in.items() == null ? List.of() : in.items();
        if (items.size() > Check.MAX_ITEMS) {
            throw Check.bad("at most " + Check.MAX_ITEMS + " items per capture");
        }
        // Check everything before writing anything.
        for (Item it : items) {
            Check.id(it.id(), "item id");
            Check.text(it.text(), Check.MAX_TEXT, "text");
            Check.text(it.quote(), Check.MAX_QUOTE, "quote");
            Check.dateTime(it.due());
        }
        int inserted = jdbc.update("INSERT OR IGNORE INTO capture (id, captured_at, transcript, received_at) "
                + "VALUES (?, ?, ?, ?)", id, capturedAt, transcript, now());
        long base = OffsetDateTime.parse(capturedAt).toInstant().toEpochMilli();
        List<String> added = new ArrayList<>();
        List<String> existing = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            String status = switch (it.kind() == null ? "" : it.kind()) {
                case "task" -> Task.OPEN;
                case "unsorted" -> Task.UNSORTED;
                default -> null;
            };
            if (status == null) {
                skipped.add(it.id());
                continue;
            }
            String text = it.text() == null || it.text().isBlank() ? (it.quote() == null ? "" : it.quote()) : it.text();
            String ts = now();
            int n = jdbc.update("INSERT OR IGNORE INTO task (id, text, status, ord, src, due, quote, capture_id, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    it.id(), text.strip(), status, base + i, src, Check.dateTime(it.due()), it.quote(), id, ts, ts);
            (n == 1 ? added : existing).add(it.id());
        }
        return new CaptureResult(id, inserted == 1, added, existing, skipped);
    }

    public Optional<Map<String, Object>> capture(String id) {
        List<Map<String, Object>> rows = jdbc.query("SELECT id, captured_at, transcript FROM capture WHERE id = ?",
                (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString(1));
                    m.put("capturedAt", rs.getString(2));
                    m.put("transcript", rs.getString(3));
                    return m;
                }, id);
        return rows.stream().findFirst();
    }

    // ---------------------------------------------------------------- tasks

    private static final String COLS = "id, text, ink, status, done_day, seam, ord, src, due, quote, capture_id, "
            + "created_at, updated_at";

    /** Open and unsorted tasks (any day: unfinished work carries over), plus tasks done today. */
    public List<Task> todayView() {
        return jdbc.query("SELECT " + COLS + " FROM task WHERE archived = 0 AND (status IN ('open', 'unsorted') "
                + "OR (status = 'done' AND done_day = ?)) ORDER BY ord, id", ROW, today().toString());
    }

    /** Tasks finished on one day, for the nightly "Done today" block in the daily note. */
    public List<Task> doneOn(String day) {
        return jdbc.query("SELECT " + COLS + " FROM task WHERE status = 'done' AND done_day = ? ORDER BY ord, id",
                ROW, Check.day(day));
    }

    /** Everything not archived. */
    public List<Task> all() {
        return jdbc.query("SELECT " + COLS + " FROM task WHERE archived = 0 ORDER BY ord, id", ROW);
    }

    public Optional<Task> task(String id) {
        return jdbc.query("SELECT " + COLS + " FROM task WHERE id = ?", ROW, id).stream().findFirst();
    }

    public record TaskIn(String id, String text, JsonNode ink, String src, Long order, String status) {}

    /** Adds a typed or handwritten task from the board. A repeated id returns the stored task unchanged. */
    @Transactional
    public Task add(TaskIn in) {
        String id = Check.id(in.id(), "id");
        String text = Check.text(in.text(), Check.MAX_TEXT, "text").strip();
        JsonNode ink = Check.ink(in.ink());
        if (text.isEmpty() && ink == null) {
            throw Check.bad("a task needs text or ink");
        }
        String status = in.status() == null ? Task.OPEN : Check.status(in.status());
        String ts = now();
        long order = in.order() == null ? clock.millis() : in.order();
        jdbc.update("INSERT OR IGNORE INTO task (id, text, ink, status, ord, src, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, text, str(ink), status, order, Check.src(in.src()), ts, ts);
        return task(id).orElseThrow();
    }

    /**
     * Changes status, doneDay, seam, text or order. Only fields present in the body change. Marking a
     * task done without a doneDay uses today; reopening clears doneDay and the strike line.
     */
    @Transactional
    public Optional<Task> patch(String id, JsonNode body) {
        Optional<Task> found = task(id);
        if (found.isEmpty()) {
            return found;
        }
        if (body == null || !body.isObject()) {
            throw Check.bad("body must be a JSON object");
        }
        Task t = found.get();
        String status = t.status();
        String doneDay = t.doneDay();
        JsonNode seam = t.seam();
        String text = t.text();
        long order = t.order();
        if (body.has("status")) {
            status = Check.status(body.get("status").asText(null));
            if (Task.DONE.equals(status)) {
                doneDay = doneDay == null ? today().toString() : doneDay;
            } else {
                doneDay = null;
                seam = null;
            }
        }
        if (body.has("doneDay")) {
            JsonNode d = body.get("doneDay");
            doneDay = d.isNull() ? null : Check.day(d.asText());
        }
        if (body.has("seam")) {
            seam = Check.seam(body.get("seam"));
        }
        if (body.has("text")) {
            text = Check.text(body.get("text").asText(""), Check.MAX_TEXT, "text").strip();
        }
        if (body.has("order")) {
            if (!body.get("order").isNumber()) {
                throw Check.bad("order is a number");
            }
            order = body.get("order").asLong();
        }
        if (Task.DONE.equals(status) && doneDay == null) {
            doneDay = today().toString();
        }
        if (!Task.DONE.equals(status)) {
            doneDay = null;
        }
        jdbc.update("UPDATE task SET status = ?, done_day = ?, seam = ?, text = ?, ord = ?, updated_at = ?, "
                + "archived = 0 WHERE id = ?", status, doneDay, str(seam), text, order, now(), id);
        return task(id);
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM task WHERE id = ?", id) == 1;
    }

    /** Done tasks finished more than {@code days} ago leave the live views. Nothing is deleted. */
    public int archiveDoneBefore(LocalDate cutoff) {
        return jdbc.update("UPDATE task SET archived = 1 WHERE archived = 0 AND status = 'done' AND done_day < ?",
                cutoff.toString());
    }

    // ---------------------------------------------------------------- pages

    public List<Map<String, Object>> pageDays() {
        return jdbc.query("SELECT day, updated FROM page ORDER BY day", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", rs.getString(1));
            m.put("updated", rs.getLong(2));
            return m;
        });
    }

    public record Page(String day, JsonNode strokes, long updated) {}

    public Optional<Page> page(String day) {
        return jdbc.query("SELECT day, strokes, updated FROM page WHERE day = ?",
                (rs, n) -> new Page(rs.getString(1), node(rs.getString(2)), rs.getLong(3)), Check.day(day))
                .stream().findFirst();
    }

    public record PageWrite(Page page, boolean stale) {}

    /**
     * Saves a page. Last writer wins by the board's {@code updated} clock: an older copy (a tablet that
     * was offline) does not overwrite a newer one; it gets the stored page back with stale = true.
     */
    @Transactional
    public PageWrite putPage(String day, JsonNode strokes, long updated) {
        Check.day(day);
        if (strokes == null || !strokes.isArray()) {
            throw Check.bad("strokes must be an array");
        }
        String s = strokes.toString();
        if (s.length() > Check.MAX_PAGE) {
            throw Check.bad("page is too large");
        }
        Optional<Page> cur = page(day);
        if (cur.isPresent() && cur.get().updated() > updated) {
            return new PageWrite(cur.get(), true);
        }
        jdbc.update("INSERT INTO page (day, strokes, updated) VALUES (?, ?, ?) "
                + "ON CONFLICT(day) DO UPDATE SET strokes = excluded.strokes, updated = excluded.updated",
                day, s, updated);
        return new PageWrite(page(day).orElseThrow(), false);
    }

    // ---------------------------------------------------------------- helpers

    private Task mapTask(ResultSet rs) throws SQLException {
        return new Task(rs.getString("id"), rs.getString("text"), node(rs.getString("ink")), rs.getString("status"),
                rs.getString("done_day"), node(rs.getString("seam")), rs.getLong("ord"), rs.getString("src"),
                rs.getString("due"), rs.getString("quote"), rs.getString("capture_id"), rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private JsonNode node(String s) {
        if (s == null) {
            return null;
        }
        try {
            return json.readTree(s);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String str(JsonNode n) {
        return n == null || n.isNull() ? null : n.toString();
    }
}
