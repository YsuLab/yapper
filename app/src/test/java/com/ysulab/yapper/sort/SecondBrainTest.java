package com.ysulab.yapper.sort;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.ysulab.yapper.vault.EntityIndex;
import com.ysulab.yapper.vault.MemoryVaultFs;
import com.ysulab.yapper.vault.VaultIndex;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class SecondBrainTest {

    static final String ID = ExecutorTest.ID;

    static SortAction ent(String args) throws Exception {
        return SortAction.parse("link_entity", args);
    }

    @Test
    public void parsesEntityWithDefaultFolders() throws Exception {
        SortAction p = ent("{\"name\":\"Omar\",\"kind\":\"person\",\"fact\":\"runs the Docker workshop\","
                + "\"aliases\":[\"عمر\",\"omar\",\"\"],\"related\":[\"Programming Club\",\"Omar\"]}");
        assertEquals("People/Omar.md", p.path);
        assertEquals(Collections.singletonList("عمر"), p.aliases); // "omar" = own name
        assertEquals(Collections.singletonList("Programming Club"), p.related); // self dropped
        assertEquals("Topics/CI-CD.md", ent("{\"name\":\"CI/CD\",\"kind\":\"tool\"}").path);
        assertEquals("Projects/Sadeem.md", ent("{\"name\":\"Sadeem\",\"kind\":\"project\"}").path);
        assertEquals("Tabby/Tabby.md", ent("{\"name\":\"[[Tabby]]\",\"kind\":\"org\",\"path\":\"Tabby/Tabby\"}").path);
        try {
            ent("{\"kind\":\"tool\"}");
            fail("name is required");
        } catch (org.json.JSONException expected) {
            // dropped
        }
        try {
            ent("{\"name\":\"x\",\"kind\":\"tool\",\"path\":\"_yapper/x.md\"}");
            fail("app folder");
        } catch (org.json.JSONException expected) {
            // dropped
        }
    }

    @Test
    public void entityRoundTripsThroughPlanJson() throws Exception {
        SortAction a = ent("{\"name\":\"GCP\",\"kind\":\"tool\",\"summary\":\"Google Cloud\",\"fact\":\"Cloud Run\","
                + "\"aliases\":[\"Google Cloud\"],\"related\":[\"Tabby\"]}");
        SortAction b = SortAction.fromJsonArray(SortAction.toJsonArray(Collections.singletonList(a))).get(0);
        assertEquals("tool", b.kind);
        assertEquals("Google Cloud", b.summary);
        assertEquals(a.aliases, b.aliases);
        assertEquals(a.related, b.related);
        assertEquals("Topics/GCP.md", b.path);
    }

    @Test
    public void createsEntityNoteWithAliasesRelatedAndLog() throws Exception {
        MemoryVaultFs fs = new MemoryVaultFs();
        fs.files.put("Tabby/Tabby.md", "# Tabby\n\nMy internship.\n");
        EntityIndex idx = EntityIndex.fromIndex(VaultIndex.render(Collections.emptyList(),
                Collections.singletonList(VaultIndex.entry("Tabby/Tabby.md", fs.read("Tabby/Tabby.md")))));
        List<SortAction> plan = Arrays.asList(
                ent("{\"name\":\"GCP\",\"kind\":\"tool\",\"summary\":\"Google Cloud Platform.\","
                        + "\"fact\":\"New Cloud Run service for staging at [[Tabby]]\","
                        + "\"aliases\":[\"Google Cloud\",\"جي سي بي\"],\"related\":[\"tabby\"]}"),
                ent("{\"name\":\"Tabby\",\"kind\":\"org\",\"fact\":\"Staging moved to Cloud Run\",\"related\":[\"Google Cloud\"]}"));
        ActionLog log = new ActionLog();
        new Executor(fs, ID, "Capture 1", ExecutorTest.NOW).withEntities(idx).execute(plan, log);

        String gcp = fs.read("Topics/GCP.md");
        assertEquals("---\naliases:\n  - \"Google Cloud\"\n  - \"جي سي بي\"\ntype: tool\ntags:\n  - entity\n---\n"
                + "# GCP\n\nGoogle Cloud Platform.\n\n## Related\n- [[Tabby]]\n\n## Log\n\n### 2026-11-09\n"
                + "- New Cloud Run service for staging at [[Tabby]] [[Capture 1|🎙]]\n\n%%yap:" + ID + "/0%%\n", gcp);
        // Existing note: appended (never rewritten), alias resolved to the note just created.
        assertEquals("# Tabby\n\nMy internship.\n\n### 2026-11-09\n%%yap:" + ID + "/1%%\n"
                + "- Staging moved to Cloud Run · [[GCP|Google Cloud]] [[Capture 1|🎙]]\n", fs.read("Tabby/Tabby.md"));
        // The index picks up the new note's aliases for next time.
        String entry = VaultIndex.entry("Topics/GCP.md", gcp);
        assertTrue(entry, entry.contains("aliases: Google Cloud, جي سي بي"));
        assertEquals("Topics/GCP.md", EntityIndex.fromIndex(entry).find("google cloud"));
    }

    @Test
    public void reusesNoteByAliasInsteadOfDuplicating() throws Exception {
        MemoryVaultFs fs = new MemoryVaultFs();
        fs.files.put("Topics/Google Cloud.md", "---\naliases: [GCP]\n---\n# Google Cloud\n");
        EntityIndex idx = EntityIndex.fromIndex("## Notes\n" + VaultIndex.entry("Topics/Google Cloud.md",
                fs.read("Topics/Google Cloud.md")) + "\n");
        new Executor(fs, ID, "", ExecutorTest.NOW).withEntities(idx)
                .execute(Collections.singletonList(ent("{\"name\":\"gcp\",\"kind\":\"tool\",\"fact\":\"IAM roles\"}")),
                        new ActionLog());
        assertNull(fs.read("Topics/gcp.md"));
        assertTrue(fs.read("Topics/Google Cloud.md").endsWith("%%\n- IAM roles\n"));
    }

    @Test
    public void sameEntityTwiceInOneCaptureAndRetryAndUndo() throws Exception {
        MemoryVaultFs fs = new MemoryVaultFs();
        List<SortAction> plan = Arrays.asList(
                ent("{\"name\":\"Claude\",\"kind\":\"tool\",\"fact\":\"used for code review\"}"),
                ent("{\"name\":\"claude\",\"kind\":\"tool\",\"fact\":\"API via OpenRouter\"}"),
                ent("{\"name\":\"Omar\",\"kind\":\"person\"}"));
        ActionLog log = new ActionLog();
        Executor ex = new Executor(fs, ID, "", ExecutorTest.NOW);
        ex.execute(plan, log);
        String claude = fs.read("Topics/Claude.md");
        assertTrue(claude, claude.contains("- used for code review\n"));
        assertTrue(claude, claude.endsWith("%%yap:" + ID + "/1%%\n- API via OpenRouter\n"));
        assertFalse(fs.files.containsKey("Topics/claude.md"));
        String omar = fs.read("People/Omar.md");
        assertTrue(omar, omar.contains("# Omar\n\n## Log\n\n%%yap:"));

        String before = fs.files.toString();
        new Executor(fs, ID, "", ExecutorTest.NOW).execute(plan, log);
        assertEquals(before, fs.files.toString());
        assertEquals(3, log.entries.size());

        Executor.UndoResult r = ex.undo(log);
        assertEquals(3, r.removed);
        assertTrue(fs.files.isEmpty());
    }

    @Test
    public void undoRemovesOnlyOurPartOfAnEntityNoteOthersAddedTo() throws Exception {
        MemoryVaultFs fs = new MemoryVaultFs();
        ActionLog first = new ActionLog();
        new Executor(fs, ID, "", ExecutorTest.NOW)
                .execute(Collections.singletonList(ent("{\"name\":\"Daftar\",\"kind\":\"project\",\"fact\":\"a\"}")), first);
        new Executor(fs, "20261109-120000-zz99", "", ExecutorTest.NOW)
                .execute(Collections.singletonList(ent("{\"name\":\"Daftar\",\"kind\":\"project\",\"fact\":\"b\"}")),
                        new ActionLog());
        Executor.UndoResult r = new Executor(fs, ID, "", ExecutorTest.NOW).undo(first);
        assertEquals(1, r.removed);
        String left = fs.read("Projects/Daftar.md");
        assertTrue(left, left.contains("- b\n"));
        assertFalse(left, left.contains("- a\n"));
    }

    @Test
    public void summaryMentionsLinkedEntities() throws Exception {
        List<SortAction> plan = Arrays.asList(
                SortAction.parse("append_to_note", "{\"path\":\"Tabby/Log.md\",\"markdown\":\"x\"}"),
                ent("{\"name\":\"GCP\",\"kind\":\"tool\"}"), ent("{\"name\":\"Tabby\",\"kind\":\"org\"}"));
        assertEquals("1 note → Tabby/Log.md · Linked GCP, Tabby", Summary.line(plan));
        assertEquals("Linked [[Topics/GCP]]", Summary.describe(plan.get(1), ExecutorTest.NOW.getZone()));
    }

    @Test
    public void entityIndexLinks() {
        EntityIndex idx = EntityIndex.fromIndex("- Uni/Operating Systems.md · aliases: OS, نظم التشغيل · ## Exams\n"
                + "- Tabby/Tabby.md\n- (none yet)\n");
        assertEquals("[[Operating Systems|OS]]", idx.link("OS"));
        assertEquals("[[Operating Systems]]", idx.link("operating   systems"));
        assertEquals("Uni/Operating Systems.md", idx.find("نظم التشغيل"));
        assertEquals("[[Tabby]]", idx.link("Tabby"));
        assertEquals("[[Kubernetes]]", idx.link("Kubernetes"));
        assertNull(idx.find(""));
    }
}
