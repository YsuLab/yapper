package com.ysulab.yapper.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CaptureStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void createSaveLoad() throws Exception {
        CaptureStore s = new CaptureStore(tmp.newFolder("captures"));
        CaptureMeta m = s.create("20261109-112305-ab12", 1000L, "Asia/Riyadh");
        assertEquals(CaptureMeta.State.RECORDING, m.state);
        assertTrue(s.dir(m.id).isDirectory());

        s.update(m.id, x -> {
            x.audioFile = "audio.ogg";
            x.advanceTo(CaptureMeta.State.RECORDED);
        });
        CaptureMeta r = s.load(m.id);
        assertEquals("audio.ogg", r.audioFile);
        assertEquals(CaptureMeta.State.RECORDED, r.state);
    }

    @Test
    public void textAndBytes() throws Exception {
        CaptureStore s = new CaptureStore(tmp.newFolder("captures"));
        s.create("a", 1, "UTC");
        s.writeText("a", CaptureStore.SCREEN_TEXT, "مرحبا hello");
        assertEquals("مرحبا hello", s.readText("a", CaptureStore.SCREEN_TEXT));
        assertEquals("", s.readText("a", "missing.txt"));
        s.writeBytes("a", CaptureStore.SCREENSHOT, new byte[] {1, 2, 3});
        assertArrayEquals(new byte[] {1, 2, 3},
                java.nio.file.Files.readAllBytes(s.file("a", CaptureStore.SCREENSHOT).toPath()));
        assertFalse(new File(s.dir("a"), CaptureStore.SCREENSHOT + ".tmp").exists());
    }

    @Test
    public void idsAreSortedAndIgnoreFoldersWithoutMeta() throws Exception {
        File root = tmp.newFolder("captures");
        CaptureStore s = new CaptureStore(root);
        s.create("20261109-120000-bbbb", 2, "UTC");
        s.create("20261109-110000-aaaa", 1, "UTC");
        assertTrue(new File(root, "stray").mkdirs());
        assertEquals(Arrays.asList("20261109-110000-aaaa", "20261109-120000-bbbb"), s.ids());
    }

    @Test
    public void missingRootHasNoIds() {
        CaptureStore s = new CaptureStore(new File(tmp.getRoot(), "nope"));
        assertTrue(s.ids().isEmpty());
    }
}
