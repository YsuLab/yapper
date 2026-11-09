package com.ysulab.yapper.sort;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.ysulab.yapper.vault.MemoryVaultFs;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** M5: plan_day and complete_task. */
public class TodayPlanTest {

    static final String ID = ExecutorTest.ID;

    static SortAction planDay() throws Exception {
        return SortAction.parse("plan_day", "{\"date\":\"2026-11-10\",\"blocks\":["
                + "{\"start\":\"9:00\",\"end\":\"10:30\",\"title\":\"Study OS\"},"
                + "{\"start\":\"13:00\",\"title\":\"Lunch\",\"alarm\":false},"
                + "{\"start\":\"17:00\",\"title\":\"Gym\"}],"
                + "\"notes\":\"- [ ] call the bank\"}");
    }

    @Test
    public void parsesPlanDay() throws Exception {
        SortAction a = planDay();
        assertEquals(3, a.blocks.size());
        assertEquals("09:00", a.blocks.get(0).start);
        assertEquals("10:30", a.blocks.get(0).end);
        assertEquals(false, a.blocks.get(1).alarm);
        SortAction back = SortAction.fromJsonArray(SortAction.toJsonArray(Collections.singletonList(a))).get(0);
        assertEquals("Gym", back.blocks.get(2).title);
        assertEquals("17:00", back.blocks.get(2).start);
    }

    @Test
    public void rejectsBadPlan() {
        for (String args : new String[] {
            "{\"date\":\"2026-11-10\",\"blocks\":[]}",
            "{\"date\":\"tomorrow\",\"blocks\":[{\"start\":\"9:00\",\"title\":\"x\"}]}",
            "{\"date\":\"2026-11-10\",\"blocks\":[{\"start\":\"9am\",\"title\":\"x\"}]}",
            "{\"date\":\"2026-11-10\",\"blocks\":[{\"start\":\"25:00\",\"title\":\"x\"}]}",
        }) {
            try {
                SortAction.parse("plan_day", args);
                fail("accepted " + args);
            } catch (Exception expected) {
                // ok
            }
        }
    }

    @Test
    public void planWritesBlocksAndAlarmReminders() throws Exception {
        MemoryVaultFs fs = new MemoryVaultFs();
        ActionLog log = new ActionLog();
        Executor ex = new Executor(fs, ID, "", ExecutorTest.NOW);
        ex.execute(Collections.singletonList(planDay()), log);
        String daily = fs.read("Daily/2026-11-10.md");
        assertTrue(daily, daily.startsWith("# 2026-11-10 · Tuesday\n\n## Plan\n%%yap:" + ID + "/0%%\n"));
        assertTrue(daily, daily.contains("- [ ] 09:00–10:30 Study OS\n- [ ] 13:00 Lunch\n- [ ] 17:00 Gym\n\n- [ ] call the bank\n"));
        // one vault write + two alarm reminders (Lunch has alarm:false)
        assertEquals(3, log.entries.size());
        ActionLog.Entry r = log.entries.get(1);
        assertEquals("reminder", r.kind);
        assertEquals("Study OS (until 10:30)", r.title);
        assertEquals("2026-11-10T09:00+03:00", r.remindAt);
        assertTrue(r.isReminder());

        // Retry after a crash: nothing written twice.
        ex.execute(Collections.singletonList(planDay()), log);
        assertEquals(3, log.entries.size());
        assertEquals(daily, fs.read("Daily/2026-11-10.md"));

        Executor.UndoResult u = ex.undo(log);
        assertEquals(3, u.removed);
        assertNull(fs.read("Daily/2026-11-10.md"));
    }

    @Test
    public void completeTaskTicksOnlyTheCheckbox() throws Exception {
        MemoryVaultFs fs = new MemoryVaultFs();
        String note = "# 2026-11-09\n\n## Plan\n- [ ] 09:00–10:30 Study OS\n- [ ] Gym\n\n## Reminders\n- [ ] ⏰ 17:00 Call Mama\n";
        fs.files.put("Daily/2026-11-09.md", note);
        ActionLog log = new ActionLog();
        Executor ex = new Executor(fs, ID, "", ExecutorTest.NOW);
        ex.execute(Arrays.asList(
                SortAction.parse("complete_task", "{\"task\":\"study os\"}"),
                SortAction.parse("complete_task", "{\"task\":\"Call mama!\"}"),
                SortAction.parse("complete_task", "{\"task\":\"Read chapter 3\"}")), log);
        String after = fs.read("Daily/2026-11-09.md");
        assertTrue(after, after.contains("- [x] 09:00–10:30 Study OS\n- [ ] Gym\n"));
        assertTrue(after, after.contains("- [x] ⏰ 17:00 Call Mama\n"));
        // No matching task: logged as done under Debrief instead of being lost.
        assertTrue(after, after.contains("## Debrief\n%%yap:" + ID + "/2%%\n- [x] Read chapter 3\n"));

        // Retry: nothing changes.
        ex.execute(Arrays.asList(
                SortAction.parse("complete_task", "{\"task\":\"study os\"}"),
                SortAction.parse("complete_task", "{\"task\":\"Call mama!\"}"),
                SortAction.parse("complete_task", "{\"task\":\"Read chapter 3\"}")), log);
        assertEquals(after, fs.read("Daily/2026-11-09.md"));
        assertEquals(3, log.entries.size());

        ex.undo(log);
        assertEquals(note, fs.read("Daily/2026-11-09.md"));
    }

    @Test
    public void findOpenTaskIgnoresDoneAndShortText() {
        String t = "- [x] Gym\n- [ ] Gym class\n";
        assertEquals("- [ ] Gym class", Executor.findOpenTask(t, "gym"));
        assertNull(Executor.findOpenTask(t, "go"));
        assertNull(Executor.findOpenTask("- [ ] Laundry\n", "Gym"));
    }

    @Test
    public void summaryMentionsPlanAndDone() throws Exception {
        String line = Summary.line(Arrays.asList(planDay(), SortAction.parse("complete_task", "{\"task\":\"x y z\"}")));
        assertEquals("Plan: 3 blocks · 1 done ✓", line);
        assertEquals("Daily/2026-11-10.md", Summary.viewPath(Collections.singletonList(planDay())));
    }

    @Test
    public void modeNotesReachThePrompt() {
        SorterInput in = new SorterInput();
        in.mode = "debrief";
        in.transcript = "خلصت مذاكرة OS";
        assertTrue(SorterPrompt.userText(in).startsWith("## Capture mode\nEvening debrief."));
        in.mode = "";
        assertTrue(SorterPrompt.userText(in).startsWith("## Transcript"));
    }
}
