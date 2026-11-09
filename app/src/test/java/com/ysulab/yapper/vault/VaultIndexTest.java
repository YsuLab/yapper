package com.ysulab.yapper.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class VaultIndexTest {

    @Test
    public void entryHasTitleAliasesAndHeadings() {
        String note = "---\naliases: [Algo, \"الخوارزميات\"]\ntags: [uni]\n---\n# Algorithms (CPCS 324)\n"
                + "## Exams\ntext\n```\n## not a heading\n```\n### 2026-11-09 · Exam %%yap:x%%\n## Lectures\n";
        assertEquals("- Uni/Algorithms.md · title: Algorithms (CPCS 324) · aliases: Algo, الخوارزميات"
                + " · ## Exams, ## Lectures", VaultIndex.entry("Uni/Algorithms.md", note));
    }

    @Test
    public void yamlListAliases() {
        String note = "---\naliases:\n  - KStack\n  - k-stack\nstatus: active\n---\nbody";
        assertEquals("- Projects/KStack.md · aliases: KStack, k-stack", VaultIndex.entry("Projects/KStack.md", note));
    }

    @Test
    public void skipsAppAndHiddenFolders() {
        assertTrue(VaultIndex.skip(".obsidian/app.json"));
        assertTrue(VaultIndex.skip("_yapper/rules.md"));
        assertTrue(VaultIndex.skip("Inbox/Capture 2026-11-09 101010.md"));
        assertTrue(VaultIndex.skip("Uni/.trash/x.md"));
        assertFalse(VaultIndex.skip("Inbox.md"));
        assertFalse(VaultIndex.skip("Uni/Algorithms.md"));
    }

    @Test
    public void renderIsSorted() {
        String out = VaultIndex.render(Arrays.asList("Uni", "Tabby"), Arrays.asList("- Uni/B.md", "- Tabby/A.md"));
        assertTrue(out.indexOf("- Tabby/") < out.indexOf("- Uni/"));
        assertTrue(out.indexOf("- Tabby/A.md") < out.indexOf("- Uni/B.md"));
    }
}
