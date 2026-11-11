package com.ysulab.yapper.sort;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.ysulab.yapper.prayer.PrayerTimes;
import com.ysulab.yapper.transcribe.FakeServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SorterTest {

    static String asset(String name) throws Exception {
        // Unit tests run with the module dir as working directory.
        return new String(Files.readAllBytes(Paths.get("src/main/assets/sorter/" + name)), StandardCharsets.UTF_8);
    }

    static SorterInput input() {
        SorterInput in = new SorterInput();
        in.now = ZonedDateTime.of(2026, 11, 9, 14, 30, 0, 0, ZoneId.of("Asia/Riyadh"));
        in.transcript = "بكرة عندي اختبار algorithms الساعة ١٠";
        in.rules = "# Yapper rules\n- Uni/ courses";
        in.index = "- Uni/Algorithms.md";
        in.dailyPath = "Daily/2026-11-09.md";
        in.prayers = PrayerTimes.table(in.now.toLocalDate(), 8);
        return in;
    }

    static String completion(JSONArray toolCalls) throws Exception {
        JSONObject msg = new JSONObject().put("role", "assistant").put("content", JSONObject.NULL);
        if (toolCalls != null) {
            msg.put("tool_calls", toolCalls);
        }
        return new JSONObject().put("model", "anthropic/claude-sonnet-5.5")
                .put("choices", new JSONArray().put(new JSONObject().put("message", msg))).toString();
    }

    static JSONObject call(String name, JSONObject args) throws Exception {
        return new JSONObject().put("id", "c1").put("type", "function")
                .put("function", new JSONObject().put("name", name).put("arguments", args.toString()));
    }

    @Test
    public void systemPromptFillsEveryPlaceholder() throws Exception {
        String sys = SorterPrompt.system(asset("system.md"), input());
        assertFalse(sys, sys.contains("{{"));
        assertTrue(sys.contains("2026-11-09T14:30:00+03:00"));
        assertTrue(sys.contains("Monday"));
        assertTrue(sys.contains("- Uni/Algorithms.md"));
        assertTrue(sys.contains("(empty)")); // no daily note yet
        assertTrue(sys, sys.contains("- Mon 2026-11-09: Fajr "));
        assertTrue(sys, sys.contains("after X"));
    }

    @Test
    public void requestShape() throws Exception {
        SorterConfig cfg = new SorterConfig();
        SorterInput in = input();
        JSONObject req = SorterPrompt.request(cfg, asset("system.md"), asset("tools.json"), in);
        assertEquals("anthropic/claude-sonnet-5.5", req.getString("model"));
        assertEquals(8, req.getJSONArray("tools").length());
        JSONObject sys = req.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0);
        assertTrue(sys.has("cache_control"));
        JSONArray user = req.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        assertEquals(1, user.length());

        cfg.model = "qwen/qwen3.6-35b-a3b";
        in.screenshotBase64 = "AAAA";
        req = SorterPrompt.request(cfg, asset("system.md"), asset("tools.json"), in);
        sys = req.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0);
        assertFalse(sys.has("cache_control"));
        user = req.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        assertEquals("data:image/jpeg;base64,AAAA",
                user.getJSONObject(1).getJSONObject("image_url").getString("url"));
    }

    @Test
    public void endToEndAgainstFakeServer() throws Exception {
        try (FakeServer s = new FakeServer()) {
            JSONArray calls = new JSONArray()
                    .put(call("append_to_note", new JSONObject().put("path", "Uni/Algorithms.md")
                            .put("markdown", "- [ ] راجع DP")))
                    .put(call("create_reminder", new JSONObject().put("title", "اختبار Algorithms")
                            .put("remind_at", "2026-11-10T09:00:00+03:00")))
                    .put(call("append_to_note", new JSONObject().put("path", "../etc/passwd").put("markdown", "x")));
            s.response = completion(calls);
            SorterConfig cfg = new SorterConfig();
            cfg.baseUrl = s.url();
            cfg.apiKey = "sk-test";
            Sorter.Result r = new Sorter(cfg, asset("system.md"), asset("tools.json")).sort(input());
            assertEquals("/v1/chat/completions", s.path);
            assertEquals("Bearer sk-test", s.auth);
            assertTrue(s.body.contains("\"tool_choice\":\"auto\""));
            assertEquals(2, r.actions.size());
            assertEquals(1, r.dropped.size());
            assertEquals("Uni/Algorithms.md", r.actions.get(0).path);
        }
    }

    @Test
    public void errorsAreClassified() throws Exception {
        try (FakeServer s = new FakeServer()) {
            SorterConfig cfg = new SorterConfig();
            cfg.baseUrl = s.url();
            Sorter sorter = new Sorter(cfg, asset("system.md"), asset("tools.json"));
            s.status = 429;
            s.response = "{\"error\":\"slow down\"}";
            try {
                sorter.sort(input());
                fail();
            } catch (SortException e) {
                assertTrue(e.retryable);
            }
            assertNull(s.auth); // no key → no header
            s.status = 401;
            try {
                sorter.sort(input());
                fail();
            } catch (SortException e) {
                assertFalse(e.retryable);
            }
        }
    }

    @Test
    public void textOnlyAnswerBecomesAQuestion() throws Exception {
        String body = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message",
                new JSONObject().put("content", "I am not sure where this goes.")))).toString();
        Sorter.Result r = Sorter.parse(body);
        assertEquals(1, r.actions.size());
        assertEquals(SortAction.ASK, r.actions.get(0).tool);
    }

    @Test
    public void errorObjectWith200IsRetryable() {
        try {
            Sorter.parse("{\"error\":{\"code\":502,\"message\":\"upstream\"}}");
            fail();
        } catch (SortException e) {
            assertTrue(e.retryable);
        }
    }

    @Test
    public void configNeedsHttpsAndKey() {
        SorterConfig c = new SorterConfig();
        assertFalse(c.isConfigured());
        c.apiKey = "k";
        assertTrue(c.isConfigured());
        c.baseUrl = "http://gpu.local/v1";
        assertFalse(c.isConfigured());
        c.baseUrl = "https://gpu.tailnet.ts.net/v1/";
        c.apiKey = "";
        assertTrue(c.isConfigured());
        assertEquals("https://gpu.tailnet.ts.net/v1", c.url());
    }
}
