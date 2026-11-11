package com.ysulab.yapper.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.ysulab.yapper.sort.JsonHttp;
import com.ysulab.yapper.sort.SortException;
import com.ysulab.yapper.sort.SorterConfig;
import com.ysulab.yapper.vault.EntityIndex;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AskerTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 11, 10, 0, 0, 0, ZoneId.of("Asia/Riyadh"));

    /** Replays canned responses and keeps every request. */
    static final class Script implements Asker.Chat {
        final Deque<JsonHttp.Response> replies = new ArrayDeque<>();
        final List<JSONObject> requests = new ArrayList<>();

        Script reply(String body) {
            replies.add(new JsonHttp.Response(200, body));
            return this;
        }

        @Override
        public JsonHttp.Response post(String json) throws IOException {
            requests.add(new JSONObject(json));
            if (replies.isEmpty()) throw new IOException("no more replies");
            return replies.poll();
        }
    }

    private static String text(String content) {
        return new JSONObject().put("model", "anthropic/claude-sonnet-5.5").put("choices", new JSONArray().put(
                new JSONObject().put("message", new JSONObject().put("role", "assistant").put("content", content))))
                .toString();
    }

    private static String toolCall(String name, JSONObject args) {
        JSONObject call = new JSONObject().put("id", "call_1").put("type", "function")
                .put("function", new JSONObject().put("name", name).put("arguments", args.toString()));
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message",
                new JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                        .put("tool_calls", new JSONArray().put(call))))).toString();
    }

    private static VaultSearch search() {
        Map<String, String> v = new TreeMap<>();
        v.put("Topics/Google Cloud.md", "# Google Cloud\n\n### 2026-11-09\n%%yap:20261109-090000-bbbb/1%%\n"
                + "- Cloud Run for staging at [[Tabby]]\n");
        v.put("Uni/Operating Systems.md", "# OS\n\n## Exams\n- Midterm on 2026-11-18\n");
        return new VaultSearch(v, EntityIndex.fromIndex("- Topics/Google Cloud.md · aliases: GCP\n"),
                NOW.getZone());
    }

    private static VaultSearch.Query q(String term) {
        VaultSearch.Query q = new VaultSearch.Query();
        q.terms.add(term);
        return q;
    }

    @Test
    public void oneRoundWhenExcerptsAreEnough() throws Exception {
        Script s = new Script().reply(text("You set up Cloud Run for staging on Monday [[Google Cloud]]."));
        Asker.Answer a = new Asker(new SorterConfig(), "Now {{NOW}} {{WEEKDAY}}", search(), NOW, s)
                .ask("what did I say about GCP?", q("GCP"));
        assertEquals(1, a.rounds);
        assertEquals(List.of("Topics/Google Cloud.md"), a.sources);
        assertEquals("anthropic/claude-sonnet-5.5", a.model);
        JSONObject req = s.requests.get(0);
        JSONArray msgs = req.getJSONArray("messages");
        assertTrue(msgs.getJSONObject(0).getString("content").contains("Wednesday"));
        String user = msgs.getJSONObject(1).getString("content");
        assertTrue(user.contains("what did I say about GCP?"));
        assertTrue("excerpt is in the prompt", user.contains("Cloud Run for staging"));
        assertEquals("auto", req.getString("tool_choice"));
    }

    @Test
    public void modelCanSearchAgainThenAnswers() throws Exception {
        Script s = new Script()
                .reply(toolCall("search_vault", new JSONObject().put("terms", new JSONArray().put("midterm"))))
                .reply(text("Your OS midterm is on 18 Nov [[Operating Systems]]."));
        Asker.Answer a = new Asker(new SorterConfig(), "x", search(), NOW, s).ask("when is my OS exam?", q("OS exam"));
        assertEquals(2, a.rounds);
        assertEquals(List.of("Uni/Operating Systems.md"), a.sources);
        assertEquals(1, a.steps.size());
        JSONArray msgs = s.requests.get(1).getJSONArray("messages");
        JSONObject toolMsg = msgs.getJSONObject(msgs.length() - 1);
        assertEquals("tool", toolMsg.getString("role"));
        assertEquals("call_1", toolMsg.getString("tool_call_id"));
        assertTrue(toolMsg.getString("content").contains("Midterm on 2026-11-18"));
        assertEquals("assistant", msgs.getJSONObject(msgs.length() - 2).getString("role"));
    }

    @Test
    public void lastRoundForcesAnAnswer() throws Exception {
        JSONObject read = new JSONObject().put("path", "Operating Systems");
        Script s = new Script().reply(toolCall("read_note", read)).reply(toolCall("read_note", read))
                .reply(text("18 Nov."));
        Asker.Answer a = new Asker(new SorterConfig(), "x", search(), NOW, s).ask("exam?", q("exam"));
        assertEquals(Asker.MAX_ROUNDS, a.rounds);
        assertEquals("18 Nov.", a.text);
        JSONObject last = s.requests.get(Asker.MAX_ROUNDS - 1);
        assertEquals("none", last.getString("tool_choice"));
        assertTrue("tools stay declared for Anthropic", last.has("tools"));
        assertTrue(s.requests.get(1).toString().contains("Midterm"));
    }

    @Test
    public void emptyAnswerSaysNothingFound() throws Exception {
        Script s = new Script().reply(text(""));
        Asker.Answer a = new Asker(new SorterConfig(), "x", search(), NOW, s).ask("q", q("zzz"));
        assertTrue(a.text.contains("couldn't find"));
        assertTrue(a.sources.isEmpty());
    }

    @Test
    public void httpErrorsAreRetryableOnlyWhenTransient() {
        for (int code : new int[] {429, 503, 401}) {
            Asker.Chat c = json -> new JsonHttp.Response(code, "{\"error\":\"x\"}");
            try {
                new Asker(new SorterConfig(), "x", search(), NOW, c).ask("q", q("a"));
                fail();
            } catch (SortException e) {
                assertEquals(code != 401, e.retryable);
            }
        }
    }

    @Test
    public void networkFailureIsRetryable() {
        Asker.Chat c = json -> {
            throw new IOException("offline");
        };
        try {
            new Asker(new SorterConfig(), "x", search(), NOW, c).ask("q", q("a"));
            fail();
        } catch (SortException e) {
            assertTrue(e.retryable);
        }
    }

    @Test
    public void badToolArgumentsDoNotCrash() {
        Asker asker = new Asker(new SorterConfig(), "x", search(), NOW, null);
        Asker.Answer a = new Asker.Answer();
        assertTrue(asker.runTool(new JSONObject().put("name", "search_vault").put("arguments", "{oops"), a)
                .startsWith("error"));
        assertTrue(asker.runTool(new JSONObject().put("name", "rm_rf").put("arguments", "{}"), a)
                .startsWith("error"));
        assertTrue(asker.runTool(new JSONObject().put("name", "read_note")
                .put("arguments", "{\"path\":\"../../etc/passwd\"}"), a).startsWith("No note"));
    }

    @Test
    public void queryIgnoresBadDates() {
        VaultSearch.Query q = Asker.query(new JSONArray().put("GCP").put("GCP").put(" "), "last week", "2026-11-11");
        assertEquals(List.of("GCP"), q.terms);
        assertEquals(null, q.since);
        assertEquals("2026-11-11", q.until.toString());
        assertFalse(Asker.describe(q).isEmpty());
    }
}
