package com.ysulab.yapper.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ysulab.yapper.sort.ActionLog;
import com.ysulab.yapper.sort.Executor;
import com.ysulab.yapper.sort.SortAction;
import com.ysulab.yapper.vault.MemoryVaultFs;
import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import org.junit.Test;

public class WeekDigestTest {

    static final ZoneId ZONE = ZoneId.of("Asia/Riyadh");
    static final WeekRange WEEK = WeekRange.of(LocalDate.of(2026, 11, 11));

    static MemoryVaultFs vault() {
        MemoryVaultFs fs = new MemoryVaultFs();
        fs.files.put("Daily/2026-11-09.md", "# 2026-11-09 · Monday\n\n## Plan\n%%yap:20261109-050000-aaaa/0%%\n"
                + "- [x] 09:00–10:30 Study OS\n- [ ] 17:00 Gym\n\n## Reminders\n%%yap:20261109-050000-aaaa/1%%\n"
                + "- [ ] ⏰ 20:00 Call mama\n    bring the papers\n");
        fs.files.put("Daily/2026-11-10.md", "# 2026-11-10 · Tuesday\n\n## Rolled over\n- [ ] Gym\n"
                + "- [x] Call mama\n\n## Debrief\n%%yap:20261110-190000-bbbb/2%%\nTired but the OS chapter clicked.\n");
        fs.files.put("Daily/2026-11-01.md", "- [x] Old task from last week\n");
        fs.files.put("Tabby/Learning.md", "# Learning\n\n### 2026-11-02 · Old\n%%yap:20261102-100000-cccc/0%%\n"
                + "- old thing\n\n### 2026-11-09 · GCP\n%%yap:20261109-100000-dddd/0%%\n"
                + "- Set up [[GCP]] IAM for staging [[Capture 2026-11-09 130000|🎙]]\n");
        fs.files.put("Topics/GCP.md", "---\ntype: tool\ntags:\n  - entity\n---\n# GCP\n\n## Log\n\n"
                + "### 2026-11-09\n- Used at [[Tabby]] for staging [[Capture 2026-11-09 130000|🎙]]\n"
                + "%%yap:20261109-100000-dddd/1%%\n");
        fs.files.put("Projects/Sadeem.md", "# Sadeem\nIdea list\n\n%%yap:20261112-100000-eeee/0%%\n");
        fs.files.put("Uni/Untouched.md", "# Untouched\n### 2026-10-01\n- nope\n");
        fs.files.put("Inbox.md", "- [ ] ❓ Which course is the quiz for? (OS / Algo) [[Capture x|🎙]]\n- [x] ❓ done one\n");
        fs.files.put("Inbox/Capture 2026-11-12 101500.md", "x");
        fs.files.put("_yapper/captures/Ramble 2026-11-09 080000.md", "x");
        fs.files.put("_yapper/captures/Capture 2026-11-01 080000.md", "x");
        fs.files.put("Weekly/2026-W45.md", "# Week 45\n\n## Reflection\n%%yap:x%%\n### 🎯 Focus for next week\n"
                + "- [ ] Sleep before 1am\n- [ ] Start Sadeem API\n");
        return fs;
    }

    @Test
    public void summarizesTheWeek() throws Exception {
        MemoryVaultFs fs = vault();
        WeekDigest.Result r = new WeekDigest(fs, WEEK, ZONE).build(fs.listNotes());
        String md = r.markdown;
        assertEquals(md, 2, r.done);        // Study OS, Call mama (open Monday, done Tuesday)
        assertEquals(md, 1, r.open);        // Gym, rolled over: listed once
        assertEquals(md, 3, r.notes);       // Learning, GCP, Sadeem (new)
        assertEquals(md, 2, r.captures);
        assertEquals(md, 1, r.questions);
        assertTrue(md, md.startsWith("---\ntype: weekly-review\nweek: 2026-W46\n"));
        assertTrue(md, md.contains("# Week 46 · 8–14 Nov 2026"));
        assertTrue(md, md.contains("- [x] 09:00–10:30 Study OS ([[2026-11-09|Mon]])"));
        assertTrue(md, md.contains("- [x] Call mama ([[2026-11-10|Tue]])"));
        assertTrue(md, md.contains("- [ ] Gym ([[2026-11-10|Tue]])"));
        assertFalse(md, md.contains("Old task"));
        assertFalse(md, md.contains("bring the papers"));
        assertTrue(md, md.contains("## What I worked on\n"));
        assertTrue(md, md.contains("- [[Learning]]\n    - Set up [[GCP]] IAM for staging\n"));
        assertFalse(md, md.contains("old thing"));
        assertFalse(md, md.contains("🎙"));
        assertFalse(md, md.contains("Untouched"));
        assertTrue(md, md.contains("- [[Sadeem]] (new)"));
        assertTrue(md, md.contains("## People, projects and tools\n- [[GCP]] (new)\n    - Used at [[Tabby]] for staging\n"));
        assertTrue(md, md.contains("## From my evening debriefs\n- Tired but the OS chapter clicked. ([[2026-11-10|Tue]])"));
        assertTrue(md, md.contains("## Last week's focus\n- [ ] Sleep before 1am\n- [ ] Start Sadeem API\n"));
        assertTrue(md, md.contains("## Still unanswered in Inbox\n- ❓ Which course is the quiz for? (OS / Algo)\n"));
        assertTrue(md, md.contains("1 open question in [[Inbox]]"));
        assertFalse(md, md.contains("%%yap:"));
    }

    @Test
    public void skipsNotesNotChangedThisWeek() throws Exception {
        MemoryVaultFs fs = vault();
        fs.modified.put("Tabby/Learning.md", 1L); // 1970: not read at all
        WeekDigest.Result r = new WeekDigest(fs, WEEK, ZONE).build(fs.listNotes());
        assertFalse(r.markdown, r.markdown.contains("[[Learning]]"));
    }

    @Test
    public void emptyWeekStillGivesANote() throws Exception {
        MemoryVaultFs fs = new MemoryVaultFs();
        WeekDigest.Result r = new WeekDigest(fs, WEEK, ZONE).build(fs.listNotes());
        assertTrue(r.markdown.contains("Nothing was captured this week"));
        assertEquals("0 done · 0 open · 0 notes · 0 captures", r.stats());
    }

    @Test
    public void prepareCreatesRefreshesThenKeepsOwnerEdits() throws Exception {
        MemoryVaultFs fs = vault();
        File state = Files.createTempDirectory("weekly").toFile();
        WeekDigest.prepare(fs, WEEK, ZONE, state);
        String first = fs.read(WEEK.notePath());
        assertTrue(first.contains("1 open"));

        // More happened since: the untouched note is refreshed.
        fs.append("Daily/2026-11-10.md", "- [ ] Buy a charger\n");
        WeekDigest.prepare(fs, WEEK, ZONE, state);
        String second = fs.read(WEEK.notePath());
        assertTrue(second, second.contains("2 open"));

        // The owner (or a reflection) changed it: never rewritten again.
        fs.append(WEEK.notePath(), "my own words\n");
        fs.append("Daily/2026-11-10.md", "- [ ] Something else\n");
        WeekDigest.Result r = WeekDigest.prepare(fs, WEEK, ZONE, state);
        assertEquals(second + "my own words\n", fs.read(WEEK.notePath()));
        assertEquals(fs.read(WEEK.notePath()), r.markdown);
    }

    @Test
    public void reflectionGoesUnderReflectionAndFeedsNextWeek() throws Exception {
        MemoryVaultFs fs = vault();
        WeekDigest.prepare(fs, WEEK, ZONE, Files.createTempDirectory("weekly").toFile());
        SortAction a = SortAction.parse("weekly_review", "{\"summary\":\"Solid week, slept late.\","
                + "\"went_well\":[\"OS chapter clicked\"],\"could_be_better\":[\"- Slept at 3am twice\"],"
                + "\"focus_next_week\":[\"- [ ] Sleep before 1am\",\"Gym 3 times\"]}");
        ZonedDateTime sat = ZonedDateTime.of(2026, 11, 14, 11, 0, 0, 0, ZONE);
        ActionLog log = new ActionLog();
        new Executor(fs, "20261114-080000-ffff", "Review 2026-11-14 110000", sat)
                .execute(Collections.singletonList(a), log);
        String note = fs.read(WEEK.notePath());
        assertTrue(note, note.contains("\n## Reflection\n%%yap:20261114-080000-ffff/0%%\n**In short:** Solid week, slept late.\n"
                + "\n### ✅ What went well\n- OS chapter clicked\n"
                + "\n### 🔧 What could be better\n- Slept at 3am twice\n"
                + "\n### 🎯 Focus for next week\n- [ ] Sleep before 1am\n- [ ] Gym 3 times\n"));
        assertEquals(WEEK.notePath(), a.path);
        assertEquals(1, log.entries.size());
        assertEquals("append", log.entries.get(0).kind);
        assertEquals("[- [ ] Sleep before 1am, - [ ] Gym 3 times]", WeekDigest.focusLines(note).toString());

        // Retry does not write twice.
        new Executor(fs, "20261114-080000-ffff", "", sat).execute(Collections.singletonList(a), new ActionLog());
        assertEquals(note, fs.read(WEEK.notePath()));

        // Next week shows it as last week's focus.
        String next = new WeekDigest(fs, WEEK.previous().previous().previous(), ZONE).build(fs.listNotes()).markdown;
        assertFalse(next.contains("Gym 3 times"));
        String following = new WeekDigest(fs, WeekRange.of(LocalDate.of(2026, 11, 18)), ZONE)
                .build(fs.listNotes()).markdown;
        assertTrue(following, following.contains("## Last week's focus\n- [ ] Sleep before 1am\n- [ ] Gym 3 times\n"));
    }

    @Test
    public void lateReviewOnSundayGoesToLastWeek() throws Exception {
        MemoryVaultFs fs = new MemoryVaultFs();
        SortAction a = SortAction.parse("weekly_review", "{\"summary\":\"ok\"}");
        new Executor(fs, "20261115-080000-ffff", "", ZonedDateTime.of(2026, 11, 15, 11, 0, 0, 0, ZONE))
                .execute(Collections.singletonList(a), new ActionLog());
        assertTrue(fs.files.containsKey("Weekly/2026-W46.md"));
    }

    @Test
    public void reviewPromptCarriesTheWeek() {
        com.ysulab.yapper.sort.SorterInput in = new com.ysulab.yapper.sort.SorterInput();
        in.mode = "review";
        in.weeklyPath = "Weekly/2026-W46.md";
        in.weekly = "## Done\n- [x] Study OS";
        in.transcript = "الأسبوع كان حلو";
        String u = com.ysulab.yapper.sort.SorterPrompt.userText(in);
        assertTrue(u, u.contains("Weekly review."));
        assertTrue(u, u.contains("weekly_review"));
        assertTrue(u, u.contains("## His week so far (Weekly/2026-W46.md, built from the vault)\n## Done\n- [x] Study OS"));
        assertTrue(u, u.indexOf("His week so far") < u.indexOf("## Transcript"));
    }

    @Test(expected = Exception.class)
    public void emptyReviewIsRejected() throws Exception {
        SortAction.parse("weekly_review", "{\"summary\":\" \"}");
    }
}
