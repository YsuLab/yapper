package com.ysulab.yapper.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ysulab.yapper.capture.CaptureMeta;
import java.time.ZonedDateTime;
import org.junit.Test;

public class InboxNoteTest {

    private static CaptureMeta meta() {
        CaptureMeta m = new CaptureMeta();
        m.id = "20261109-112305-ab12";
        // 2026-11-09 11:23:05 UTC = 14:23:05 in Riyadh (UTC+3)
        m.createdAtMs = ZonedDateTime.parse("2026-11-09T11:23:05Z").toInstant().toEpochMilli();
        m.zoneId = "Asia/Riyadh";
        m.audioFile = "audio.ogg";
        m.durationMs = 12_400;
        m.screenshot = "saved";
        m.sourceApp = "com.whatsapp";
        return m;
    }

    @Test
    public void fileNameUsesRiyadhTime() {
        assertEquals("Capture 2026-11-09 142305.md", InboxNote.fileName(meta()));
    }

    @Test
    public void attachmentNames() {
        CaptureMeta m = meta();
        assertEquals("20261109-112305-ab12.ogg", InboxNote.audioName(m));
        m.audioFile = "audio.m4a";
        assertEquals("20261109-112305-ab12.m4a", InboxNote.audioName(m));
        assertEquals("20261109-112305-ab12.jpg", InboxNote.screenshotName(m));
    }

    @Test
    public void buildsFullNote() {
        String md = InboxNote.build(meta(), "Algorithms\nSun 10:00", true, true);
        assertTrue(md.startsWith("---\nyapper: capture\nyap_id: 20261109-112305-ab12\n"));
        assertTrue(md.contains("captured: 2026-11-09T14:23:05+03:00\n"));
        assertTrue(md.contains("source_app: \"com.whatsapp\"\n"));
        assertTrue(md.contains("duration_s: 12\n"));
        assertTrue(md.contains("%%yap:20261109-112305-ab12%%\n"));
        assertTrue(md.contains("![[20261109-112305-ab12.ogg]]\n"));
        assertTrue(md.contains("![[20261109-112305-ab12.jpg|300]]\n"));
        assertTrue(md.contains("> [!quote]- Screen text\n> Algorithms\n> Sun 10:00\n"));
    }

    @Test
    public void blankScreenshotAndNoAudio() {
        CaptureMeta m = meta();
        m.screenshot = "blank";
        String md = InboxNote.build(m, "", false, false);
        assertTrue(md.contains("No audio recorded"));
        assertTrue(md.contains("secure screen"));
        assertFalse(md.contains("![["));
        assertFalse(md.contains("Screen text"));
    }

    @Test
    public void longScreenTextIsCut() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < InboxNote.MAX_SCREEN_TEXT + 500; i++) {
            sb.append('x');
        }
        String md = InboxNote.build(meta(), sb.toString(), false, true);
        assertTrue(md.contains("…"));
        assertTrue(md.length() < InboxNote.MAX_SCREEN_TEXT + 1000);
    }

    @Test
    public void transcriptBlocks() {
        String b = InboxNote.transcriptBlock("20261109-112305-ab12", "OpenAI gpt-4o-transcribe", 2345,
                "ذكرني بكرة الساعة 10 عن الـ meeting");
        assertEquals("\n## Transcript · OpenAI gpt-4o-transcribe · 2.3 s\n%%yap:20261109-112305-ab12%%\n"
                + "ذكرني بكرة الساعة 10 عن الـ meeting\n", b);
        assertTrue(InboxNote.transcriptBlock("20261109-112305-ab12", "X", 0, " ").contains("(empty transcript)"));
        String f = InboxNote.transcriptFailedBlock("20261109-112305-ab12", "Groq", "HTTP 401:\n bad_key");
        assertTrue(f.contains("· failed\n"));
        assertTrue(f.contains("_HTTP 401: bad\\_key_"));
    }

    @Test
    public void yamlEscaping() {
        assertEquals("\"a\\\"b\\\\c\"", InboxNote.yamlScalar("a\"b\\c"));
    }

    @Test
    public void badZoneFallsBackToRiyadh() {
        CaptureMeta m = meta();
        m.zoneId = "Not/AZone";
        assertEquals("Capture 2026-11-09 142305.md", InboxNote.fileName(m));
    }
}
