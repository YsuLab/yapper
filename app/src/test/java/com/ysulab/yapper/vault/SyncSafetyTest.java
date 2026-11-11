package com.ysulab.yapper.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class SyncSafetyTest {

    private static final String BLOCK = "\n%%yap:20261111-130000-ab12%%\n- buy milk\n";

    /** Simulates Syncthing renaming a fresh copy over the file: the next {@code lost} appends vanish. */
    private static final class LossyAppend implements SyncSafety.RawAppend {
        final MemoryVaultFs fs;
        int lost;
        int calls;

        LossyAppend(MemoryVaultFs fs, int lost) {
            this.fs = fs;
            this.lost = lost;
        }

        @Override
        public void append(String path, String text) throws IOException {
            calls++;
            if (lost > 0) {
                lost--;
                return; // written into the old, already-replaced file
            }
            fs.append(path, text);
        }
    }

    @Test
    public void plainAppendIsWrittenOnce() throws IOException {
        MemoryVaultFs fs = new MemoryVaultFs();
        fs.files.put("Uni/Algo.md", "# Algo\n");
        LossyAppend raw = new LossyAppend(fs, 0);
        SyncSafety.appendVerified(fs, raw, "Uni/Algo.md", BLOCK);
        assertEquals("# Algo\n" + BLOCK, fs.files.get("Uni/Algo.md"));
        assertEquals(1, raw.calls);
    }

    @Test
    public void appendLostToSyncIsRetried() throws IOException {
        MemoryVaultFs fs = new MemoryVaultFs();
        fs.files.put("Uni/Algo.md", "# Algo\n");
        LossyAppend raw = new LossyAppend(fs, 2);
        SyncSafety.appendVerified(fs, raw, "Uni/Algo.md", BLOCK);
        assertEquals("# Algo\n" + BLOCK, fs.files.get("Uni/Algo.md"));
        assertEquals(3, raw.calls);
    }

    @Test
    public void keepsTextSyncedInFromAnotherDevice() throws IOException {
        MemoryVaultFs fs = new MemoryVaultFs();
        fs.files.put("Uni/Algo.md", "# Algo\n");
        // First append is lost because the PC's version replaced the file at the same moment.
        SyncSafety.RawAppend raw = new SyncSafety.RawAppend() {
            int calls;

            @Override
            public void append(String path, String text) throws IOException {
                if (calls++ == 0) {
                    fs.files.put(path, "# Algo\nwritten on the PC\n");
                    return;
                }
                fs.append(path, text);
            }
        };
        SyncSafety.appendVerified(fs, raw, "Uni/Algo.md", BLOCK);
        assertEquals("# Algo\nwritten on the PC\n" + BLOCK, fs.files.get("Uni/Algo.md"));
    }

    @Test
    public void lateLandingIsNotDuplicated() throws IOException {
        MemoryVaultFs fs = new MemoryVaultFs();
        fs.files.put("a.md", "x\n");
        // The append lands, but the first read-back still sees the old file (sync in flight).
        final String[] stale = {"x\n"};
        VaultFs laggy = new VaultFs() {
            int reads;

            @Override
            public String read(String path) {
                reads++;
                return reads == 2 ? stale[0] : fs.read(path);
            }

            @Override
            public void create(String path, String text) {}

            @Override
            public void append(String path, String text) {}

            @Override
            public void replace(String path, String text) {}

            @Override
            public void delete(String path) {}

            @Override
            public void move(String from, String to) {}
        };
        LossyAppend raw = new LossyAppend(fs, 0);
        SyncSafety.appendVerified(laggy, raw, "a.md", BLOCK);
        assertEquals(1, raw.calls);
        assertEquals("x\n" + BLOCK, fs.files.get("a.md"));
    }

    @Test
    public void givesUpLoudlySoTheWorkerRetriesLater() {
        MemoryVaultFs fs = new MemoryVaultFs();
        fs.files.put("a.md", "x\n");
        LossyAppend raw = new LossyAppend(fs, 99);
        try {
            SyncSafety.appendVerified(fs, raw, "a.md", BLOCK);
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals(SyncSafety.ATTEMPTS, raw.calls);
            assertEquals("x\n", fs.files.get("a.md"));
        }
    }

    @Test
    public void missingFileFails() {
        MemoryVaultFs fs = new MemoryVaultFs();
        try {
            SyncSafety.appendVerified(fs, new LossyAppend(fs, 0), "nope.md", BLOCK);
            fail("expected IOException");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void recognisesSyncthingFiles() {
        assertTrue(SyncSafety.isSyncArtifact("Uni/Algo.sync-conflict-20261111-130000-ABCDEFG.md"));
        assertTrue(SyncSafety.isSyncArtifact("~syncthing~Algo.md.tmp"));
        assertTrue(SyncSafety.isSyncArtifact("Uni/.syncthing.Algo.md.tmp"));
        assertFalse(SyncSafety.isSyncArtifact("Uni/Algo.md"));
        assertFalse(SyncSafety.isSyncArtifact("Topics/Sync conflicts I had.md"));
    }

    @Test
    public void indexSkipsConflictCopies() {
        assertTrue(VaultIndex.skip("Uni/Algo.sync-conflict-20261111-130000-ABCDEFG.md"));
        assertFalse(VaultIndex.skip("Uni/Algo.md"));
    }

    @Test
    public void reportLinksCopyAndMainNote() {
        String r = SyncSafety.report(Arrays.asList("Uni/Algo.sync-conflict-20261111-130000-ABCDEFG.md"), "2026-11-11 13:00");
        assertTrue(r.contains("[[Uni/Algo.sync-conflict-20261111-130000-ABCDEFG]] → main note: [[Uni/Algo]]"));
        assertTrue(SyncSafety.report(Collections.emptyList(), "now").contains("No conflict copies"));
    }

    @Test
    public void sameConflictsIgnoresTimestamp() {
        ArrayList<String> list = new ArrayList<>(Arrays.asList("a.sync-conflict-20261111-130000-X.md"));
        String old = SyncSafety.report(list, "2026-11-10 09:00");
        assertTrue(SyncSafety.sameConflicts(old, list));
        assertFalse(SyncSafety.sameConflicts(old, Collections.emptyList()));
        assertFalse(SyncSafety.sameConflicts("owner text", list));
    }
}
