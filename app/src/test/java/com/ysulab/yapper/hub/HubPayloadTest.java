package com.ysulab.yapper.hub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.ysulab.yapper.sort.SortAction;
import com.ysulab.yapper.transcribe.FakeServer;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class HubPayloadTest {

    static final ZonedDateTime AT = ZonedDateTime.of(2026, 11, 24, 21, 34, 55, 0, ZoneId.of("Asia/Riyadh"));

    @Test
    public void captureCarriesTranscriptTasksAndUnsortedWithStableIds() throws Exception {
        List<SortAction> plan = Arrays.asList(
                SortAction.parse("append_to_note", "{\"path\":\"Uni/OS.md\",\"markdown\":\"idea\"}"),
                SortAction.parse("add_task", "{\"text\":\"Submit the Algorithms report\",\"quote\":\"submit the "
                        + "algorithms report Thursday\",\"due\":\"2026-11-26T23:59:00+03:00\"}"),
                SortAction.parse("add_task", "{\"text\":\"Lab slot\",\"quote\":\"the lab slot thing\",\"unsure\":true}"));
        SortAction u = new SortAction(SortAction.UNSORTED);
        u.quote = "and something about the gym";
        plan = new java.util.ArrayList<>(plan);
        plan.add(u);
        JSONObject p = HubPayload.build("20261124-213455-ab12", AT, "okay so ...", plan);
        assertEquals("20261124-213455-ab12", p.getString("id"));
        assertEquals("2026-11-24T21:34:55+03:00", p.getString("capturedAt"));
        assertEquals("okay so ...", p.getString("transcript"));
        JSONArray items = p.getJSONArray("items");
        assertEquals(3, items.length());
        assertEquals("20261124-213455-ab12-2", items.getJSONObject(0).getString("id"));
        assertEquals("task", items.getJSONObject(0).getString("kind"));
        assertEquals("2026-11-26T23:59:00+03:00", items.getJSONObject(0).getString("due"));
        assertEquals("unsorted", items.getJSONObject(1).getString("kind"));
        assertEquals("and something about the gym", items.getJSONObject(2).getString("text"));
        assertEquals(Arrays.asList("20261124-213455-ab12-2", "20261124-213455-ab12-3", "20261124-213455-ab12-4"),
                HubPayload.itemIds(p.toString()));
    }

    @Test
    public void clientSendsBearerTokenAndBody() throws Exception {
        try (FakeServer s = new FakeServer()) {
            s.response = "{\"ok\":true}";
            HubClient c = new HubClient(s.url() + "/", "tok-123");
            HubClient.Response r = c.postCapture("{\"id\":\"x\"}");
            assertTrue(r.ok());
            assertTrue(s.path, s.path.endsWith("/api/captures"));
            assertEquals("Bearer tok-123", s.auth);
            assertEquals("{\"id\":\"x\"}", s.body);
            s.status = 401;
            assertEquals(401, c.health().code);
        }
        assertTrue(HubClient.retryable(503));
        assertTrue(HubClient.retryable(429));
        assertTrue(!HubClient.retryable(401));
        assertTrue(!new HubClient("", " ").isConfigured());
    }

    @Test
    public void doneBlockListsTodaysStrikes() throws Exception {
        String json = "{\"tasks\":[{\"text\":\"Submit the  report\"},{\"text\":\"\",\"ink\":{}}]}";
        String b = DoneBlock.build("2026-11-24", json);
        assertEquals("\n## Done today\n%%yap:done-2026-11-24%%\n- ✓ Submit the report\n- ✓ (handwritten task)\n", b);
        assertNull(DoneBlock.build("2026-11-24", "{\"tasks\":[]}"));
    }

    @Test
    public void vaultImportFindsOpenTasksOnlyWithStableIds() throws Exception {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("Daily/2026-11-20.md", "# 20\n- [ ] Call [[People/Ahmed|Ahmed]] %%yap:a/1%%\n- [x] done one\n"
                + "## Reminders\n- [ ] ⏰ 17:00 Call Mama\n```\n- [ ] in code\n```\n");
        notes.put("Inbox.md", "- [ ] ❓ Is this Tabby? [[Capture|🎙]]\n");
        notes.put("_yapper/rules.md", "- [ ] not mine\n");
        notes.put("Uni/OS.md", "  * [ ] Review chapter 4\n");
        List<VaultTasks.Found> f = VaultTasks.find(notes);
        assertEquals(2, f.size());
        assertEquals("Call Ahmed", f.get(0).text);
        assertEquals("Review chapter 4", f.get(1).text);
        assertEquals(f.get(0).id, VaultTasks.find(notes).get(0).id);
        assertTrue(f.get(0).id.matches("v-[0-9a-f]{16}"));
        List<JSONObject> p = VaultTasks.payloads(f, "2026-11-24T21:00:00+03:00");
        assertEquals(1, p.size());
        assertEquals("vault-import-1", p.get(0).getString("id"));
        assertEquals("vault", p.get(0).getString("src"));
        assertEquals(2, p.get(0).getJSONArray("items").length());
    }
}
