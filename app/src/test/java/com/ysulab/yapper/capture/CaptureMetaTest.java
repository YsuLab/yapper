package com.ysulab.yapper.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class CaptureMetaTest {

    @Test
    public void roundTrips() throws Exception {
        CaptureMeta m = new CaptureMeta();
        m.id = "20261109-112305-ab12";
        m.state = CaptureMeta.State.INBOX;
        m.createdAtMs = 1_794_223_385_000L;
        m.audioFile = "audio.ogg";
        m.durationMs = 12_345;
        m.speechMs = 9_000;
        m.stopReason = "silence";
        m.screenshot = "saved";
        m.screenTextChars = 321;
        m.sourceApp = "com.whatsapp";
        m.webUri = "https://example.com/a?b=c&d=é";
        m.inboxPath = "Inbox/Capture 2026-11-09 142305.md";
        m.transcribedBy.add("openai");
        m.transcribedBy.add("groq");

        CaptureMeta r = CaptureMeta.parse(m.serialize());
        assertEquals(m.id, r.id);
        assertEquals(m.state, r.state);
        assertEquals(m.createdAtMs, r.createdAtMs);
        assertEquals(m.audioFile, r.audioFile);
        assertEquals(m.durationMs, r.durationMs);
        assertEquals(m.speechMs, r.speechMs);
        assertEquals(m.stopReason, r.stopReason);
        assertEquals(m.screenshot, r.screenshot);
        assertEquals(m.screenTextChars, r.screenTextChars);
        assertEquals(m.sourceApp, r.sourceApp);
        assertEquals(m.webUri, r.webUri);
        assertEquals(m.inboxPath, r.inboxPath);
        assertEquals(m.transcribedBy, r.transcribedBy);
    }

    @Test
    public void stateOnlyMovesForward() {
        CaptureMeta m = new CaptureMeta();
        assertTrue(m.advanceTo(CaptureMeta.State.RECORDED));
        assertTrue(m.advanceTo(CaptureMeta.State.INBOX));
        assertFalse(m.advanceTo(CaptureMeta.State.RECORDED));
        assertEquals(CaptureMeta.State.INBOX, m.state);
    }

    @Test
    public void unknownStateIsReprocessed() throws Exception {
        CaptureMeta r = CaptureMeta.parse("id=x\nstate=SORTED_IN_FUTURE\n");
        assertEquals(CaptureMeta.State.RECORDED, r.state);
    }

    @Test
    public void garbageNumbersBecomeZero() throws Exception {
        CaptureMeta r = CaptureMeta.parse("id=x\ndurationMs=abc\n");
        assertEquals(0, r.durationMs);
        assertEquals(CaptureMeta.State.RECORDING, r.state);
    }

    @Test
    public void pendingTranscribers() {
        CaptureMeta m = new CaptureMeta();
        m.transcribedBy.add("openai");
        assertEquals(Arrays.asList("groq"), m.pendingTranscribers(Arrays.asList("openai", "groq")));
    }
}
