package com.ysulab.yapper.sort;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SortActionTest {

    @Test
    public void parsesEachTool() throws Exception {
        SortAction a = SortAction.parse("append_to_note",
                "{\"path\":\"Uni/Algorithms\",\"heading\":\"Exam\\n prep\",\"markdown\":\"- [ ] review DP\"}");
        assertEquals("Uni/Algorithms.md", a.path);
        assertEquals("Exam prep", a.heading);

        SortAction r = SortAction.parse("create_reminder",
                "{\"title\":\"Call Ahmed\",\"remind_at\":\"2026-11-10T17:00:00+03:00\"}");
        assertEquals("2026-11-10T17:00:00+03:00", r.remindAt);

        SortAction d = SortAction.parse("add_to_daily",
                "{\"date\":\"2026-11-09\",\"section\":\"Plan\",\"markdown\":\"- [ ] gym\"}");
        assertEquals("Plan", d.heading);

        SortAction q = SortAction.parse("ask_owner", "{\"question\":\"Which course?\",\"options\":[\"OS\",\"Algo\",\"\"]}");
        assertEquals(Arrays.asList("OS", "Algo"), q.options);
    }

    @Test
    public void rejectsBadCalls() {
        String[][] bad = {
            {"append_to_note", "{\"path\":\"../x\",\"markdown\":\"x\"}"},
            {"append_to_note", "{\"path\":\"Uni/A.md\"}"},
            {"create_reminder", "{\"title\":\"x\",\"remind_at\":\"tomorrow 5pm\"}"},
            {"add_to_daily", "{\"date\":\"9/11/2026\",\"section\":\"Plan\",\"markdown\":\"x\"}"},
            {"delete_note", "{\"path\":\"Uni/A.md\"}"},
            {"create_note", "not json"},
        };
        for (String[] b : bad) {
            try {
                SortAction.parse(b[0], b[1]);
                fail("accepted " + b[0] + " " + b[1]);
            } catch (Exception expected) {
                // ok
            }
        }
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        SortAction q = SortAction.parse("ask_owner", "{\"question\":\"Which?\",\"options\":[\"A\",\"B\"]}");
        SortAction a = SortAction.parse("create_note", "{\"path\":\"Projects/X\",\"markdown\":\"# X\"}");
        List<SortAction> back = SortAction.fromJsonArray(SortAction.toJsonArray(Arrays.asList(q, a)));
        assertEquals(2, back.size());
        assertEquals(Arrays.asList("A", "B"), back.get(0).options);
        assertEquals("Projects/X.md", back.get(1).path);
        assertEquals("# X", back.get(1).markdown);
    }
}
