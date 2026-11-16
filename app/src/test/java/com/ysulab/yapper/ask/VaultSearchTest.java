package com.ysulab.yapper.ask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.ysulab.yapper.vault.EntityIndex;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

public class VaultSearchTest {

    private static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

    private static Map<String, String> vault() {
        Map<String, String> v = new TreeMap<>();
        v.put("Topics/Google Cloud.md", "---\naliases:\n  - \"GCP\"\n  - \"جي سي بي\"\ntype: tool\n---\n"
                + "# Google Cloud\n\n## Log\n\n### 2026-11-03\n%%yap:20261103-090000-aaaa/1%%\n"
                + "- Set up a GKE cluster at [[Tabby]]\n\n### 2026-11-09\n%%yap:20261109-090000-bbbb/1%%\n"
                + "- Cloud Run service for staging, costs looked high\n");
        v.put("Tabby/Tabby.md", "# Tabby\n\nMy DevOps internship.\n\n### 2026-11-09\n%%yap:20261109-090000-bbbb/0%%\n"
                + "- اليوم اشتغلت على [[Google Cloud|GCP]] و Cloud Run\n");
        v.put("Daily/2026-11-10.md", "# Tuesday 10 Nov\n\n## Log\n%%yap:20261110-150000-cccc/0%%\n"
                + "- Studied operating systems for 3 hours\n");
        v.put("Daily/2026-10-20.md", "# Old\n\n## Log\n- Gym\n");
        v.put("Personal/Ideas.md", "# Ideas\n\nAn app that posts gym photos.\n");
        return v;
    }

    private static EntityIndex index() {
        return EntityIndex.fromIndex("- Topics/Google Cloud.md · aliases: GCP, جي سي بي\n- Tabby/Tabby.md\n");
    }

    @Test
    public void aliasFindsTheEntityNoteAndNotesThatLinkToIt() {
        VaultSearch s = new VaultSearch(vault(), index(), RIYADH);
        VaultSearch.Query q = new VaultSearch.Query();
        q.terms.add("GCP");
        List<VaultSearch.Chunk> found = s.search(q);
        assertFalse(found.isEmpty());
        assertEquals("Topics/Google Cloud.md", found.get(0).path);
        boolean tabby = false;
        for (VaultSearch.Chunk c : found) {
            tabby |= c.path.equals("Tabby/Tabby.md");
        }
        assertTrue("Tabby links to [[Google Cloud|GCP]]", tabby);
    }

    @Test
    public void dateRangeUsesYapMarkersAndDropsOlderBlocks() {
        VaultSearch s = new VaultSearch(vault(), index(), RIYADH);
        VaultSearch.Query q = new VaultSearch.Query();
        q.terms.add("Google Cloud");
        q.since = LocalDate.parse("2026-11-08");
        q.until = LocalDate.parse("2026-11-11");
        for (VaultSearch.Chunk c : s.search(q)) {
            assertFalse("GKE block is from 11-03: " + c.render(), c.text.contains("GKE"));
        }
    }

    @Test
    public void emptyTermsWithRangeReturnsEverythingFromThatTimeNewestFirst() {
        VaultSearch s = new VaultSearch(vault(), index(), RIYADH);
        VaultSearch.Query q = new VaultSearch.Query();
        q.since = LocalDate.parse("2026-11-08");
        q.until = LocalDate.parse("2026-11-11");
        List<VaultSearch.Chunk> found = s.search(q);
        assertEquals("Daily/2026-11-10.md", found.get(0).path);
        for (VaultSearch.Chunk c : found) {
            assertFalse(c.path.equals("Daily/2026-10-20.md"));
            assertFalse("undated owner text is not 'from last week'", c.path.equals("Personal/Ideas.md"));
        }
    }

    @Test
    public void dailyPathDatesItsSections() {
        VaultSearch s = new VaultSearch(vault(), index(), RIYADH);
        VaultSearch.Query q = new VaultSearch.Query();
        q.terms.add("gym");
        q.since = LocalDate.parse("2026-10-20");
        q.until = LocalDate.parse("2026-10-20");
        List<VaultSearch.Chunk> found = s.search(q);
        assertEquals("Daily/2026-10-20.md", found.get(0).path);
        assertEquals(LocalDate.parse("2026-10-20"), found.get(0).from);
    }

    @Test
    public void shortLatinTermsMatchWholeWordsOnly() {
        assertEquals(0, VaultSearch.count("an app that posts gym photos", "os"));
        assertEquals(1, VaultSearch.count("studied os today", "os"));
        assertEquals(1, VaultSearch.count("operating systems", "operating"));
    }

    @Test
    public void arabicIsNormalized() {
        assertEquals("الاختبار مدرسه", VaultSearch.normalize("الإختبار مدرسة"));
        assertEquals("2026", VaultSearch.normalize("٢٠٢٦"));
        assertEquals(1, VaultSearch.count(VaultSearch.normalize("عندي اختبار بكرة"), VaultSearch.normalize("إختبار")));
    }

    @Test
    public void budgetCapsTheExcerpts() {
        Map<String, String> v = new TreeMap<>();
        for (int i = 0; i < 50; i++) {
            v.put("Notes/n" + i + ".md", "# N\n\nkubernetes " + "x".repeat(800) + "\n");
        }
        VaultSearch s = new VaultSearch(v, null, RIYADH);
        VaultSearch.Query q = new VaultSearch.Query();
        q.terms.add("kubernetes");
        q.budget = 5000;
        int total = 0;
        for (VaultSearch.Chunk c : s.search(q)) {
            total += c.render().length();
        }
        assertTrue(total <= 5000 && total > 0);
    }

    @Test
    public void longSectionsAreSplit() {
        Map<String, String> v = new TreeMap<>();
        v.put("Inbox/Ramble.md", "# Ramble\n\n" + "word ".repeat(1000));
        VaultSearch s = new VaultSearch(v, null, RIYADH);
        assertTrue(s.size() >= 4);
    }

    @Test
    public void sourcesMapLinksToVaultPaths() {
        VaultSearch s = new VaultSearch(vault(), index(), RIYADH);
        assertEquals(Arrays.asList("Topics/Google Cloud.md", "Daily/2026-11-10.md", "Tabby/Tabby.md"),
                s.sources("You set it up [[Google Cloud]] on [[2026-11-10]], see [[GCP]] and [[Tabby|work]]; "
                        + "[[Nope]] is not a note."));
        assertNull(s.pathFor("Nope"));
        assertTrue(s.note("Tabby/Tabby.md").contains("DevOps"));
    }

    @Test
    public void questionTermsDropFillerWords() {
        assertEquals(Arrays.asList("gcp"), VaultSearch.termsFromQuestion("What did I say about GCP last week?"));
        assertEquals(Arrays.asList("سديم"), VaultSearch.termsFromQuestion("وش قلت عن سديم؟"));
    }

    @Test
    public void codeFencesDoNotStartSections() {
        Map<String, String> v = new TreeMap<>();
        v.put("a.md", "# A\n\n```\n# not a heading\nterraform\n```\n");
        VaultSearch s = new VaultSearch(v, null, RIYADH);
        assertEquals(1, s.size());
    }
}
