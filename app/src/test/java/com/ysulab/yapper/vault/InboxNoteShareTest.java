package com.ysulab.yapper.vault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ysulab.yapper.capture.CaptureMeta;
import java.time.ZonedDateTime;
import java.util.Arrays;
import org.junit.Test;

public class InboxNoteShareTest {

    private static CaptureMeta share() {
        CaptureMeta m = new CaptureMeta();
        m.id = "20261111-100000-ab12";
        m.createdAtMs = ZonedDateTime.parse("2026-11-11T10:00:00Z").toInstant().toEpochMilli();
        m.mode = "share";
        m.sourceApp = "com.whatsapp";
        m.sharedFiles.addAll(Arrays.asList("shared-01.jpg", "shared-02.pdf"));
        return m;
    }

    @Test
    public void shareNoteName() {
        assertEquals("Share 2026-11-11 130000.md", InboxNote.fileName(share()));
    }

    @Test
    public void shareNoteEmbedsFilesAndQuotesText() {
        CaptureMeta m = share();
        m.audioFile = "audio.ogg";
        String md = InboxNote.build(m, "", false, true, "Quiz 2 moved\nto Tuesday 10:00");
        assertTrue(md.startsWith("---\nyapper: share\n"));
        assertTrue(md.contains("source_app: \"com.whatsapp\"\n"));
        assertTrue(md.contains("shared_files: 2\n"));
        assertFalse(md.contains("screenshot:"));
        assertTrue(md.contains("![[20261111-100000-ab12.ogg]]\n"));
        assertTrue(md.contains("\n## Shared\n> Quiz 2 moved\n> to Tuesday 10:00\n\n"
                + "![[20261111-100000-ab12-shared-01.jpg|400]]\n![[20261111-100000-ab12-shared-02.pdf]]\n"));
        assertEquals(Arrays.asList("20261111-100000-ab12-shared-01.jpg", "20261111-100000-ab12-shared-02.pdf"),
                InboxNote.sharedNames(m));
    }

    @Test
    public void shareWithoutVoiceNoteOrText() {
        CaptureMeta m = share();
        m.sharedSkipped = 1;
        String md = InboxNote.build(m, "", false, false, "");
        assertTrue(md.contains("_No voice note._"));
        assertFalse(md.contains("microphone unavailable"));
        assertTrue(md.contains("## Shared\n![[20261111-100000-ab12-shared-01.jpg|400]]\n"));
        assertTrue(md.contains("1 file was not copied"));
    }

    @Test
    public void normalCaptureUnchanged() {
        CaptureMeta m = share();
        m.mode = "";
        m.sharedFiles.clear();
        String md = InboxNote.build(m, "", false, false);
        assertFalse(md.contains("## Shared"));
        assertTrue(md.contains("screenshot: none\n"));
    }
}
