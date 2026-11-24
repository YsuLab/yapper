package com.ysulab.yapper.sort;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ysulab.yapper.vault.MemoryVaultFs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Spec 4.2: split, then sort, then a coverage check. Six tasks in, six tasks out. */
public class SplitSortTest {

    static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 24, 21, 34, 0, 0, ZoneId.of("Asia/Riyadh"));

    static final String RAMBLE = "okay so first I need to submit the algorithms report Thursday. "
            + "also call Ahmed about the Sadeem demo. and I have to renew the car registration this week. "
            + "um what else. email Dr Khalid about the lab slot. buy printer paper. "
            + "and finally book the dentist appointment for next month. that's it";

    static String asset(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/assets/sorter/" + name)), StandardCharsets.UTF_8);
    }

    static SorterInput input(String transcript) {
        SorterInput in = new SorterInput();
        in.now = NOW;
        in.transcript = transcript;
        in.dailyPath = "Daily/2026-11-24.md";
        return in;
    }

    static String completion(String name, JSONObject args) throws Exception {
        JSONArray calls = new JSONArray().put(new JSONObject().put("id", "c1").put("type", "function")
                .put("function", new JSONObject().put("name", name).put("arguments", args.toString())));
        return completionCalls(calls);
    }

    static String completionCalls(JSONArray calls) throws Exception {
        return new JSONObject().put("model", "test").put("choices", new JSONArray().put(new JSONObject()
                .put("message", new JSONObject().put("role", "assistant").put("tool_calls", calls)))).toString();
    }

    static JSONObject call(String name, JSONObject args) throws Exception {
        return new JSONObject().put("id", "x").put("type", "function")
                .put("function", new JSONObject().put("name", name).put("arguments", args.toString()));
    }

    static JSONObject item(String kind, String quote, String summary) throws Exception {
        return new JSONObject().put("kind", kind).put("quote", quote).put("summary", summary);
    }

    static final String[][] SIX = {
        {"I need to submit the algorithms report Thursday", "Submit the Algorithms report"},
        {"call Ahmed about the Sadeem demo", "Call Ahmed about the Sadeem demo"},
        {"I have to renew the car registration this week", "Renew the car registration"},
        {"email Dr Khalid about the lab slot", "Email Dr Khalid about the lab slot"},
        {"buy printer paper", "Buy printer paper"},
        {"book the dentist appointment for next month", "Book the dentist appointment"},
    };

    static String pass1() throws Exception {
        JSONArray items = new JSONArray();
        for (String[] s : SIX) {
            items.put(item("task", s[0], s[1]));
        }
        return completion(Splitter.TOOL, new JSONObject().put("items", items)
                .put("no_action", new JSONArray().put("okay so first").put("um what else").put("that's it")));
    }

    /** A fake model: pass 1 lists six items, pass 2 files only {@code filed} of them. */
    static final class FakeModel implements Sorter.Transport {
        final List<String> bodies = new ArrayList<>();
        final String pass2;

        FakeModel(String pass2) {
            this.pass2 = pass2;
        }

        @Override
        public JsonHttp.Response post(String url, java.util.Map<String, String> headers, String body) {
            bodies.add(body);
            try {
                return new JsonHttp.Response(200, bodies.size() == 1 ? pass1() : pass2);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static int count(List<SortAction> plan, String tool) {
        int n = 0;
        for (SortAction a : plan) {
            if (a.tool.equals(tool)) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void sixTasksFiledByPass2() throws Exception {
        JSONArray calls = new JSONArray();
        for (int i = 0; i < SIX.length; i++) {
            calls.put(call("add_task", new JSONObject().put("text", SIX[i][1]).put("quote", SIX[i][0]).put("item", i + 1)));
        }
        FakeModel m = new FakeModel(completionCalls(calls));
        Sorter.Result r = new Sorter(new SorterConfig(), asset("system.md"), asset("tools.json"))
                .withTransport(m).sortSplit(input(RAMBLE), asset("split.md"));
        assertEquals(2, m.bodies.size());
        assertEquals(6, r.splitItems);
        assertEquals(0, r.filledIn);
        assertEquals(6, count(r.actions, SortAction.TASK));
        // Pass 2 saw the numbered items and every tool takes "item".
        JSONObject req = new JSONObject(m.bodies.get(1));
        String user = req.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text");
        assertTrue(user.contains("6. [task] Book the dentist appointment"));
        assertTrue(req.getJSONArray("tools").getJSONObject(0).getJSONObject("function")
                .getJSONObject("parameters").getJSONObject("properties").has("item"));
    }

    @Test
    public void itemsPass2DroppedComeBackAsTasks() throws Exception {
        // The model filed two and forgot four: nothing is lost.
        JSONArray calls = new JSONArray()
                .put(call("add_task", new JSONObject().put("text", SIX[0][1]).put("quote", SIX[0][0]).put("item", 1)))
                .put(call("append_to_note", new JSONObject().put("path", "Projects/Sadeem.md")
                        .put("markdown", "Call Ahmed about the Sadeem demo"))); // no item number: matched by words
        Sorter.Result r = new Sorter(new SorterConfig(), asset("system.md"), asset("tools.json"))
                .withTransport(new FakeModel(completionCalls(calls))).sortSplit(input(RAMBLE), asset("split.md"));
        assertEquals(4, r.filledIn);
        assertEquals(5, count(r.actions, SortAction.TASK));
        assertEquals(0, count(r.actions, SortAction.UNSORTED));
    }

    @Test
    public void sentenceNoItemCoversBecomesUnsorted() throws Exception {
        Splitter.Split split = new Splitter.Split();
        Splitter.Item it = new Splitter.Item();
        it.n = 1;
        it.kind = "task";
        it.quote = "buy printer paper";
        it.summary = "Buy printer paper";
        split.items.add(it);
        List<SortAction> actions = new ArrayList<>(Arrays.asList(SortAction.parse("add_task",
                "{\"text\":\"Buy printer paper\",\"quote\":\"buy printer paper\",\"item\":1}")));
        int added = Splitter.complete(actions, split,
                "buy printer paper. and the lab slot thing with Khalid next week. okay");
        assertEquals(1, added);
        SortAction u = actions.get(1);
        assertEquals(SortAction.UNSORTED, u.tool);
        assertEquals("and the lab slot thing with Khalid next week", u.quote);
    }

    @Test
    public void shortCapturesSharesAndBrokenPass1UseOnePass() throws Exception {
        String one = completion("add_task", new JSONObject().put("text", "Buy paper").put("quote", "buy paper"));
        List<String> seen = new ArrayList<>();
        Sorter s = new Sorter(new SorterConfig(), asset("system.md"), asset("tools.json")).withTransport((u, h, b) -> {
            seen.add(b);
            return new JsonHttp.Response(200, one);
        });
        assertEquals(1, s.sortSplit(input("buy paper"), asset("split.md")).actions.size());
        assertEquals(1, seen.size());
        SorterInput share = input(RAMBLE);
        share.mode = "share";
        s.sortSplit(share, asset("split.md"));
        assertEquals(2, seen.size());
        // Pass 1 answered without list_items: fall back to one pass (2 calls, result of the second).
        seen.clear();
        Sorter.Result r = s.sortSplit(input(RAMBLE), asset("split.md"));
        assertEquals(2, seen.size());
        assertEquals(0, r.splitItems);
    }

    @Test
    public void coverageHandlesArabicAndUnpunctuatedRuns() {
        List<String> quotes = Arrays.asList("لازم أراجع محاضرة نظم التشغيل بكرة");
        assertTrue(Coverage.uncovered("لازم اراجع محاضرة نظم التشغيل بكره. يعني خلاص", quotes).isEmpty());
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < 45; i++) {
            run.append("word").append(i).append(' ');
        }
        List<String> parts = Coverage.sentences(run.toString());
        assertEquals(2, parts.size()); // 20 + 25 (the short tail is folded in)
        assertFalse(Coverage.uncovered(run.toString(), quotes).isEmpty());
    }

    @Test
    public void addTaskWritesHistoryLineAndAlarmNotCheckbox() throws Exception {
        MemoryVaultFs fs = new MemoryVaultFs();
        ActionLog log = new ActionLog();
        List<SortAction> plan = Arrays.asList(
                SortAction.parse("add_task", "{\"text\":\"Submit the Algorithms report\",\"quote\":\"submit it\","
                        + "\"due\":\"2026-11-26T23:59:00+03:00\"}"),
                SortAction.parse("add_task", "{\"text\":\"Lab slot\",\"quote\":\"lab slot thing\",\"unsure\":true}"));
        new Executor(fs, "20261124-213400-ab12", "", NOW).execute(plan, log);
        String daily = fs.read("Daily/2026-11-24.md");
        assertTrue(daily, daily.contains("## Tasks added"));
        assertTrue(daily.contains("- Added task: Submit the Algorithms report (due 2026-11-26 23:59)"));
        assertTrue(daily.contains("- Not sure, on the board under Unsorted: Lab slot"));
        assertFalse(daily.contains("- [ ]"));
        int alarms = 0;
        for (ActionLog.Entry e : log.entries) {
            if (e.isReminder()) {
                alarms++;
                assertEquals("Submit the Algorithms report", e.title);
            }
        }
        assertEquals(1, alarms);
        // A retry writes nothing twice.
        new Executor(fs, "20261124-213400-ab12", "", NOW).execute(plan, log);
        assertEquals(daily, fs.read("Daily/2026-11-24.md"));
        assertEquals(alarms + 2, log.entries.size());
        // And it survives the plan.json round trip.
        SortAction back = SortAction.fromJsonArray(SortAction.toJsonArray(plan)).get(1);
        assertTrue(back.unsure);
        assertEquals("lab slot thing", back.quote);
    }
}
