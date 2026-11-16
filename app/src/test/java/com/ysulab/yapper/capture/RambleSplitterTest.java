package com.ysulab.yapper.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RambleSplitterTest {

    @Test
    public void cutsAtThePauseAfterTarget() {
        RambleSplitter s = new RambleSplitter(1000, 3000);
        s.partStarted(0);
        // talking past the target: no cut
        for (long t = 0; t <= 1500; t += 100) {
            assertFalse(s.onSample(5000, t));
        }
        // pause starts at 1600; cut once it lasted 600 ms
        assertFalse(s.onSample(100, 1600));
        assertFalse(s.onSample(100, 2000));
        assertTrue(s.onSample(100, 2200));
    }

    @Test
    public void pauseBeforeTargetDoesNotCut() {
        RambleSplitter s = new RambleSplitter(1000, 3000);
        s.partStarted(0);
        for (long t = 0; t < 1000; t += 100) {
            assertFalse(s.onSample(0, t));
        }
    }

    @Test
    public void hardCutAtMax() {
        RambleSplitter s = new RambleSplitter(1000, 3000);
        s.partStarted(0);
        assertFalse(s.onSample(9000, 2900));
        assertTrue(s.onSample(9000, 3000));
        s.partStarted(3000);
        assertFalse(s.onSample(9000, 3100));
    }

    @Test
    public void partNames() {
        assertEquals("audio-01", RambleSplitter.partBase(1));
        assertEquals("audio-12", RambleSplitter.partBase(12));
    }

    @Test
    public void metaKeepsPartsAndMode() throws Exception {
        CaptureMeta m = new CaptureMeta();
        m.id = "20261109-113000-ab12";
        assertTrue(m.parts().isEmpty());
        m.audioFile = "audio.ogg";
        assertEquals(1, m.parts().size());
        m.audioFile = "audio-01.ogg";
        m.audioParts.add("audio-01.ogg");
        m.audioParts.add("audio-02.ogg");
        m.mode = "plan";
        CaptureMeta back = CaptureMeta.parse(m.serialize());
        assertEquals(java.util.Arrays.asList("audio-01.ogg", "audio-02.ogg"), back.parts());
        assertEquals("plan", back.mode);
        assertEquals("20261109-113000-ab12-02.ogg",
                com.ysulab.yapper.vault.InboxNote.audioName(back, "audio-02.ogg"));
        assertEquals("Plan ", com.ysulab.yapper.vault.InboxNote.fileName(back).substring(0, 5));
    }
}
