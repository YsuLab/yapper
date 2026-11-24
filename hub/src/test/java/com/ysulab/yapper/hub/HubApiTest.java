package com.ysulab.yapper.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class HubApiTest {

    static final String PHONE = "phone-token-0123456789abcdef0123";
    static final String TABLET = "tablet-token-0123456789abcdef012";
    static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("hubtest");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 2026-11-24 22:30 in Riyadh = 19:30 UTC. */
    static final Instant NOW = Instant.parse("2026-11-24T19:30:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("hub.db", () -> DIR.resolve("hub.db").toString());
        r.add("hub.backup-dir", () -> DIR.resolve("backups").toString());
        r.add("hub.tokens", () -> "phone=" + PHONE + ", tablet=" + TABLET + ", short=abc");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Nightly nightly;
    @Autowired HubStore store;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM task");
        jdbc.update("DELETE FROM capture");
        jdbc.update("DELETE FROM page");
    }

    MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    ResultActions call(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(as(TABLET, b));
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    static final String CAPTURE = """
        {"id": "c-20261124-213455", "capturedAt": "2026-11-24T21:34:55+03:00",
         "transcript": "okay so I need to submit the algorithms report Thursday and also the lab slot thing",
         "items": [
           {"id": "c-20261124-213455-1", "kind": "task", "text": "Submit the Algorithms report",
            "quote": "I need to submit the algorithms report Thursday", "due": "2026-11-26T23:59:00+03:00"},
           {"id": "c-20261124-213455-2", "kind": "unsorted", "text": "Something about the lab slot",
            "quote": "and the lab slot thing"},
           {"id": "c-20261124-213455-3", "kind": "note", "text": "a note for the vault"}
         ]}""";

    // ---------------------------------------------------------------- auth

    @Test
    void refusesMissingOrWrongToken() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer nope")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer abc")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tasks").header("Authorization", PHONE)).andExpect(status().isUnauthorized());
    }

    @Test
    void healthNamesTheDeviceAndRiyadhDay() throws Exception {
        mvc.perform(as(PHONE, get("/api/health"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.device").value("phone"))
                .andExpect(jsonPath("$.today").value("2026-11-24"));
    }

    @Test
    void boardFilesArePublic() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- captures

    @Test
    void captureStoresTranscriptAndUpsertsTasksIdempotently() throws Exception {
        JsonNode r = body(mvc.perform(as(PHONE, post("/api/captures").content(CAPTURE))).andExpect(status().isOk()));
        assertThat(r.get("newCapture").asBoolean()).isTrue();
        assertThat(r.get("added")).hasSize(2);
        assertThat(r.get("skipped").get(0).asText()).isEqualTo("c-20261124-213455-3");

        // The board strikes the task; then Yapper resends the same capture (a retry).
        call(patch("/api/tasks/c-20261124-213455-1").content("{\"status\":\"done\",\"seam\":[0,500,1000,520]}"))
                .andExpect(status().isOk());
        JsonNode again = body(mvc.perform(as(PHONE, post("/api/captures").content(CAPTURE))).andExpect(status().isOk()));
        assertThat(again.get("newCapture").asBoolean()).isFalse();
        assertThat(again.get("added")).isEmpty();
        assertThat(again.get("existing")).hasSize(2);

        JsonNode tasks = body(call(get("/api/tasks?view=today"))).get("tasks");
        assertThat(tasks).hasSize(2);
        JsonNode t1 = tasks.get(0);
        assertThat(t1.get("status").asText()).isEqualTo("done");
        assertThat(t1.get("doneDay").asText()).isEqualTo("2026-11-24");
        assertThat(t1.get("seam")).hasSize(4);
        assertThat(t1.get("src").asText()).isEqualTo("voice");
        assertThat(t1.get("due").asText()).isEqualTo("2026-11-26T23:59:00+03:00");
        assertThat(t1.get("quote").asText()).contains("algorithms report");
        assertThat(t1.get("captureId").asText()).isEqualTo("c-20261124-213455");
        assertThat(tasks.get(1).get("status").asText()).isEqualTo("unsorted");
        assertThat(tasks.get(1).get("order").asLong()).isGreaterThan(t1.get("order").asLong());

        mvc.perform(as(PHONE, get("/api/captures/c-20261124-213455")))
                .andExpect(jsonPath("$.transcript").value(org.hamcrest.Matchers.startsWith("okay so I need")));
    }

    @Test
    void transcriptIsNeverEdited() throws Exception {
        mvc.perform(as(PHONE, post("/api/captures").content(CAPTURE))).andExpect(status().isOk());
        String changed = CAPTURE.replace("okay so I need", "EDITED");
        mvc.perform(as(PHONE, post("/api/captures").content(changed))).andExpect(status().isOk());
        assertThat(store.capture("c-20261124-213455").orElseThrow().get("transcript").toString())
                .startsWith("okay so I need");
    }

    @Test
    void badCaptureWritesNothing() throws Exception {
        String bad = CAPTURE.replace("\"c-20261124-213455-2\"", "\"bad id with spaces\"");
        mvc.perform(as(PHONE, post("/api/captures").content(bad))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM capture", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isZero();
        mvc.perform(as(PHONE, post("/api/captures").content(CAPTURE.replace("21:34:55+03:00", "21:34:55")))).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- tasks

    @Test
    void boardAddsTypedAndHandwrittenTasks() throws Exception {
        call(post("/api/tasks").content("{\"id\":\"t1\",\"text\":\" Buy milk \",\"src\":\"type\",\"order\":5}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.text").value("Buy milk"))
                .andExpect(jsonPath("$.status").value("open"));
        call(post("/api/tasks").content("{\"id\":\"t2\",\"ink\":{\"w\":40,\"h\":10,\"s\":[[0,0,40,10]]},\"src\":\"pen\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ink.w").value(40));
        // Same id again: no duplicate, original kept.
        call(post("/api/tasks").content("{\"id\":\"t1\",\"text\":\"Other\"}")).andExpect(jsonPath("$.text").value("Buy milk"));
        call(post("/api/tasks").content("{\"id\":\"t3\",\"text\":\"\"}")).andExpect(status().isBadRequest());
        call(post("/api/tasks").content("{\"id\":\"t4\",\"ink\":{\"w\":1,\"h\":1,\"s\":[[\"x\"]]}}"))
                .andExpect(status().isBadRequest());
        assertThat(body(call(get("/api/tasks"))).get("tasks")).hasSize(2);
    }

    @Test
    void todayViewCarriesOverOpenTasksAndHidesOldDoneOnes() throws Exception {
        for (String id : new String[] {"a", "b", "c"}) {
            call(post("/api/tasks").content("{\"id\":\"" + id + "\",\"text\":\"" + id + "\"}"));
        }
        call(patch("/api/tasks/a").content("{\"status\":\"done\",\"doneDay\":\"2026-11-23\"}")); // yesterday
        call(patch("/api/tasks/b").content("{\"status\":\"done\"}")); // today
        JsonNode today = body(call(get("/api/tasks?view=today"))).get("tasks");
        assertThat(today).extracting(n -> n.get("id").asText()).containsExactly("b", "c");
        JsonNode done = body(call(get("/api/tasks?view=done&day=2026-11-23"))).get("tasks");
        assertThat(done).extracting(n -> n.get("id").asText()).containsExactly("a");
        assertThat(body(call(get("/api/tasks?view=all"))).get("tasks")).hasSize(3);
        call(get("/api/tasks?view=nope")).andExpect(status().isBadRequest());
    }

    @Test
    void reopeningClearsDoneDayAndSeam() throws Exception {
        call(post("/api/tasks").content("{\"id\":\"a\",\"text\":\"a\"}"));
        call(patch("/api/tasks/a").content("{\"status\":\"done\",\"seam\":[1,2,3,4]}"));
        call(patch("/api/tasks/a").content("{\"status\":\"open\"}"))
                .andExpect(jsonPath("$.doneDay").doesNotExist()).andExpect(jsonPath("$.seam").doesNotExist());
        call(patch("/api/tasks/a").content("{\"text\":\"renamed\"}")).andExpect(jsonPath("$.text").value("renamed"));
        call(patch("/api/tasks/a").content("{\"status\":\"maybe\"}")).andExpect(status().isBadRequest());
        call(patch("/api/tasks/missing").content("{\"status\":\"done\"}")).andExpect(status().isNotFound());
    }

    @Test
    void unsortedCanBePromotedOrDismissed() throws Exception {
        mvc.perform(as(PHONE, post("/api/captures").content(CAPTURE)));
        call(patch("/api/tasks/c-20261124-213455-2").content("{\"status\":\"open\"}"))
                .andExpect(jsonPath("$.status").value("open"));
        call(delete("/api/tasks/c-20261124-213455-1")).andExpect(status().isNoContent());
        call(delete("/api/tasks/c-20261124-213455-1")).andExpect(status().isNoContent());
        // Dismissing a task never deletes its capture.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM capture", Integer.class)).isOne();
    }

    @Test
    void archivesOnlyTasksDoneLongAgo() {
        store.add(new HubStore.TaskIn("old", "old", null, "type", 1L, null));
        store.add(new HubStore.TaskIn("recent", "recent", null, "type", 2L, null));
        store.add(new HubStore.TaskIn("open", "open", null, "type", 3L, null));
        jdbc.update("UPDATE task SET status='done', done_day='2026-10-20' WHERE id='old'");
        jdbc.update("UPDATE task SET status='done', done_day='2026-11-20' WHERE id='recent'");
        assertThat(store.archiveDoneBefore(store.today().minusDays(30))).isOne();
        assertThat(store.all()).extracting(Task::id).containsExactly("recent", "open");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Integer.class)).isEqualTo(3);
    }

    // ---------------------------------------------------------------- pages

    @Test
    void pagesSaveListAndRefuseOlderCopies() throws Exception {
        call(get("/api/pages/2026-11-24")).andExpect(status().isNotFound());
        call(put("/api/pages/2026-11-24").content("{\"strokes\":[{\"c\":0,\"n\":1,\"p\":[1,2,50]}],\"updated\":200}"))
                .andExpect(status().isOk());
        call(put("/api/pages/2026-11-24").content("{\"strokes\":[],\"updated\":100}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.updated").value(200));
        call(get("/api/pages/2026-11-24")).andExpect(jsonPath("$.strokes[0].p[2]").value(50));
        call(get("/api/pages")).andExpect(jsonPath("$.days[0].day").value("2026-11-24"));
        call(put("/api/pages/2026-02-30").content("{\"strokes\":[],\"updated\":1}")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- backup + restore

    @Test
    void backupIsARestorableCopyAndOldOnesArePruned() throws Exception {
        mvc.perform(as(PHONE, post("/api/captures").content(CAPTURE)));
        Path backups = DIR.resolve("backups");
        Files.createDirectories(backups);
        for (int d = 1; d <= 20; d++) {
            Files.writeString(backups.resolve(String.format("hub-2026-10-%02d.db", d)), "old");
        }
        Path b = nightly.backup();
        assertThat(b.getFileName().toString()).isEqualTo("hub-2026-11-24.db");
        try (var files = Files.list(backups)) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".db")).count()).isEqualTo(14);
        }
        // Restore test: open the copy on its own and read the tasks back.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + b);
                ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM task")) {
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
        // Restore by marker into a fresh folder, as the hub does at start.
        Path other = Files.createTempDirectory("restore");
        Path db = other.resolve("hub.db");
        Files.writeString(db, "current");
        Files.writeString(other.resolve("RESTORE"), "../../" + b.getFileName() + "\n");
        assertThat(Restore.applyIfAsked(db, backups)).startsWith("Restored");
        assertThat(Files.exists(other.resolve("RESTORE"))).isFalse();
        try (var files = Files.list(other)) {
            assertThat(files.anyMatch(p -> p.getFileName().toString().startsWith("hub.db.before-restore-"))).isTrue();
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM task")) {
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
        // A bad name is refused and the database is left alone.
        Files.writeString(other.resolve("RESTORE"), "hub-1999-01-01.db");
        assertThat(Restore.applyIfAsked(db, backups)).startsWith("Restore NOT done");
        assertThat(Files.exists(other.resolve("RESTORE.failed"))).isTrue();
        assertThat(Restore.applyIfAsked(db, backups)).isNull();
    }

    @Test
    void corsAllowsTheAppOriginOnly() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/tasks")
                .header("Origin", "https://appassets.androidplatform.net")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Origin", "https://appassets.androidplatform.net"));
        mvc.perform(get("/api/health").header("Origin", "https://evil.example")
                .header("Authorization", "Bearer " + PHONE)).andExpect(status().isForbidden());
        // A refused token still answers with CORS headers, so the app sees 401, not a network error.
        mvc.perform(get("/api/health").header("Origin", "https://appassets.androidplatform.net"))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .exists("Access-Control-Allow-Origin"));
    }
}
