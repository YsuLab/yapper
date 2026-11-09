package com.ysulab.yapper.sort;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.ysulab.yapper.vault.MemoryVaultFs;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ExecutorTest {

    static final String ID = "20261109-113000-ab12";
    static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 9, 14, 30, 0, 0, ZoneId.of("Asia/Riyadh"));

    static SortAction act(String tool, String args) throws Exception {
        return SortAction.parse(tool, args);
    }

    static List<SortAction> plan() throws Exception {
        return Arrays.asList(
                act("append_to_note", "{\"path\":\"Uni/Algorithms.md\",\"heading\":\"Exam\",\"markdown\":\"- [ ] review DP\"}"),
                act("create_note", "{\"path\":\"Projects/Sadeem.md\",\"markdown\":\"# Sadeem\\n- idea: offline mode\"}"),
                act("create_reminder", "{\"title\":\"Algorithms exam\",\"remind_at\":\"2026-11-10T07:00:00Z\"}"),
                act("add_to_daily", "{\"date\":\"2026-11-09\",\"section\":\"Plan\",\"markdown\":\"- [ ] gym 9pm\"}"),
                act("ask_owner", "{\"question\":\"Is Tabby demo internal?\",\"options\":[\"Tabby\",\"Personal\"]}"));
    }

    static MemoryVaultFs vault() {
        MemoryVaultFs fs = new MemoryVaultFs();
        fs.files.put("Uni/Algorithms.md", "# Algorithms\n\nMy own notes");
        return fs;
    }

    @Test
    public void executesEveryToolWithMarkers() throws Exception {
        MemoryVaultFs fs = vault();
        ActionLog log = new ActionLog();
        new Executor(fs, ID, "Capture 2026-11-09 143000", NOW).execute(plan(), log);

        assertEquals("# Algorithms\n\nMy own notes\n\n### 2026-11-09 · Exam\n%%yap:" + ID + "/0%%\n- [ ] review DP\n",
                fs.read("Uni/Algorithms.md"));
        assertEquals("# Sadeem\n- idea: offline mode\n\n%%yap:" + ID + "/1%%\n", fs.read("Projects/Sadeem.md"));
        // Reminder is converted to Riyadh time and filed on its own day.
        String tomorrow = fs.read("Daily/2026-11-10.md");
        assertTrue(tomorrow, tomorrow.startsWith("# 2026-11-10 · Tuesday\n\n## Reminders\n"));
        assertTrue(tomorrow.contains("- [ ] ⏰ 10:00 Algorithms exam"));
        String today = fs.read("Daily/2026-11-09.md");
        assertTrue(today, today.contains("## Plan\n%%yap:" + ID + "/3%%\n- [ ] gym 9pm\n"));
        assertEquals("%%yap:" + ID + "/4%%\n- [ ] ❓ Is Tabby demo internal? (Tabby / Personal) "
                + "[[Capture 2026-11-09 143000|🎙]]\n", fs.read("Inbox.md"));
        assertEquals(5, log.entries.size());
        assertEquals("Algorithms exam", log.entries.get(2).title);
    }

    @Test
    public void retryDoesNotDuplicate() throws Exception {
        MemoryVaultFs fs = vault();
        Executor ex = new Executor(fs, ID, "", NOW);
        ActionLog log = new ActionLog();
        ex.execute(plan(), log);
        String before = fs.files.toString();
        ex.execute(plan(), log);
        assertEquals(before, fs.files.toString());
        assertEquals(5, log.entries.size());
    }

    @Test
    public void sameDayGroupsUnderOneHeading() throws Exception {
        MemoryVaultFs fs = vault();
        List<SortAction> one = Arrays.asList(act("append_to_note", "{\"path\":\"Uni/Algorithms.md\",\"markdown\":\"a\"}"));
        new Executor(fs, ID, "", NOW).execute(one, new ActionLog());
        new Executor(fs, "20261109-113500-cd34", "", NOW).execute(one, new ActionLog());
        String t = fs.read("Uni/Algorithms.md");
        assertEquals(t.indexOf("### 2026-11-09"), t.lastIndexOf("### 2026-11-09"));
    }

    @Test
    public void createOnExistingNoteAppends() throws Exception {
        MemoryVaultFs fs = vault();
        new Executor(fs, ID, "", NOW).execute(Arrays.asList(
                act("create_note", "{\"path\":\"Uni/Algorithms.md\",\"markdown\":\"new stuff\"}")), new ActionLog());
        String t = fs.read("Uni/Algorithms.md");
        assertTrue(t.startsWith("# Algorithms\n\nMy own notes\n"));
        assertTrue(t.endsWith("new stuff\n"));
    }

    @Test
    public void undoRemovesExactlyOurText() throws Exception {
        MemoryVaultFs fs = vault();
        Executor ex = new Executor(fs, ID, "", NOW);
        ActionLog log = new ActionLog();
        ex.execute(plan(), log);
        // The owner writes more after our block; it must survive.
        fs.append("Uni/Algorithms.md", "\nowner line\n");
        Executor.UndoResult r = ex.undo(log);
        assertEquals(5, r.removed);
        assertEquals(0, r.kept);
        assertEquals("# Algorithms\n\nMy own notes\nowner line\n", fs.read("Uni/Algorithms.md"));
        assertNull(fs.read("Projects/Sadeem.md"));
        assertNull(fs.read("Daily/2026-11-10.md"));
        assertNull(fs.read("Inbox.md"));
        for (ActionLog.Entry e : log.entries) {
            assertTrue(e.undone);
        }
    }

    @Test
    public void undoLeavesEditedBlocks() throws Exception {
        MemoryVaultFs fs = vault();
        Executor ex = new Executor(fs, ID, "", NOW);
        ActionLog log = new ActionLog();
        ex.execute(new ArrayList<>(plan().subList(0, 2)), log);
        fs.replace("Projects/Sadeem.md", fs.read("Projects/Sadeem.md") + "owner added\n");
        fs.replace("Uni/Algorithms.md", fs.read("Uni/Algorithms.md").replace("review DP", "review greedy"));
        Executor.UndoResult r = ex.undo(log);
        assertEquals(1, r.removed); // Sadeem: our full text is still inside, so only our part goes
        assertEquals(1, r.kept);
        assertEquals("owner added\n", fs.read("Projects/Sadeem.md"));
        assertTrue(fs.read("Uni/Algorithms.md").contains("review greedy"));
        assertFalse(log.entries.get(0).undone);
    }

    @Test
    public void logRoundTrip() throws Exception {
        MemoryVaultFs fs = vault();
        ActionLog log = new ActionLog();
        new Executor(fs, ID, "", NOW).execute(plan(), log);
        ActionLog back = ActionLog.parse(log.serialize());
        assertEquals(log.serialize(), back.serialize());
        assertEquals(0, ActionLog.parse("").entries.size());
    }

    @Test
    public void lastHeadingIgnoresCodeFences() {
        assertEquals("## B", Executor.lastHeading("# A\n## B\n```\n## not\n```\ntext"));
        assertEquals("", Executor.lastHeading(null));
    }
}
