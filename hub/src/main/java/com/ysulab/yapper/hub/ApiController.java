package com.ysulab.yapper.hub;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The API in spec section 3. Every call has passed {@link TokenFilter}. */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final HubStore store;

    public ApiController(HubStore store) {
        this.store = store;
    }

    @GetMapping("/health")
    public Map<String, Object> health(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("device", req.getAttribute(TokenFilter.DEVICE_ATTR));
        m.put("today", store.today().toString());
        return m;
    }

    // ---------------------------------------------------------------- captures (Yapper)

    @PostMapping("/captures")
    public HubStore.CaptureResult capture(@RequestBody HubStore.CaptureIn in) {
        return store.saveCapture(in);
    }

    @GetMapping("/captures/{id}")
    public Map<String, Object> getCapture(@PathVariable String id) {
        return store.capture(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // ---------------------------------------------------------------- tasks (board)

    /**
     * {@code view=today} (default): open + unsorted + done today. {@code view=done&day=YYYY-MM-DD}: done
     * that day. {@code view=all}: everything not archived.
     */
    @GetMapping("/tasks")
    public Map<String, Object> tasks(@RequestParam(defaultValue = "today") String view,
            @RequestParam(required = false) String day) {
        List<Task> list = switch (view) {
            case "today" -> store.todayView();
            case "done" -> store.doneOn(day == null ? store.today().toString() : day);
            case "all" -> store.all();
            default -> throw Check.bad("view is today, done or all");
        };
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("today", store.today().toString());
        m.put("tasks", list);
        return m;
    }

    @PostMapping("/tasks")
    public ResponseEntity<Task> add(@RequestBody HubStore.TaskIn in) {
        return ResponseEntity.status(HttpStatus.CREATED).body(store.add(in));
    }

    @PatchMapping("/tasks/{id}")
    public Task patch(@PathVariable String id, @RequestBody JsonNode body) {
        return store.patch(id, body).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Idempotent: deleting a task that is already gone is fine (a retried delete). */
    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        store.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- pages (board)

    @GetMapping("/pages")
    public Map<String, Object> pages() {
        return Map.of("days", store.pageDays());
    }

    @GetMapping("/pages/{day}")
    public HubStore.Page page(@PathVariable String day) {
        return store.page(day).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public record PageIn(JsonNode strokes, Long updated) {}

    /** 200 with the saved page; 409 with the newer stored page when the sent copy is older. */
    @PutMapping("/pages/{day}")
    public ResponseEntity<HubStore.Page> putPage(@PathVariable String day, @RequestBody PageIn in) {
        long updated = in.updated() == null ? System.currentTimeMillis() : in.updated();
        HubStore.PageWrite w = store.putPage(day, in.strokes(), updated);
        return ResponseEntity.status(w.stale() ? HttpStatus.CONFLICT : HttpStatus.OK).body(w.page());
    }
}
