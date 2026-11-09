package com.ysulab.yapper.sort;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class SummaryTest {

    @Test
    public void lineCountsEachKind() throws Exception {
        assertEquals("2 notes → Uni/Algorithms.md, Projects/Sadeem.md · 1 reminder · 1 daily item · 1 question in Inbox.md",
                Summary.line(ExecutorTest.plan()));
        assertEquals("Nothing to file", Summary.line(Collections.emptyList()));
    }

    @Test
    public void viewPathPrefersNotes() throws Exception {
        assertEquals("Uni/Algorithms.md", Summary.viewPath(ExecutorTest.plan()));
        assertEquals("Daily/2026-11-10.md", Summary.viewPath(Arrays.asList(ExecutorTest.plan().get(2))));
    }

    @Test
    public void blocksAreTaggedAndLinked() throws Exception {
        String b = Summary.sortedBlock(ExecutorTest.ID, ExecutorTest.plan(), "anthropic/claude-sonnet-5.5", 2345,
                ZoneId.of("Asia/Riyadh"), Arrays.asList("append_to_note: unsafe path ../x"));
        assertTrue(b, b.contains("%%yap:" + ExecutorTest.ID + "%%"));
        assertTrue(b, b.contains("- Added to [[Uni/Algorithms]] (Exam)"));
        assertTrue(b, b.contains("- Reminder Tue 10:00: Algorithms exam"));
        assertTrue(b, b.contains("2.3 s"));
        String p = Summary.proposalBlock(ExecutorTest.ID, ExecutorTest.plan(), "", 0, ZoneId.of("Asia/Riyadh"),
                Collections.emptyList());
        assertTrue(p, p.contains("    > - [ ] review DP"));
    }
}
