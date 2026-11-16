package com.ysulab.yapper.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SharedItemsTest {

    @Test
    public void fileNamesFromDisplayNameOrMime() {
        assertEquals("shared-01.jpg", SharedItems.fileName(0, "image/jpeg", null));
        assertEquals("shared-02.pdf", SharedItems.fileName(1, "application/octet-stream", "Timetable Fall.PDF"));
        assertEquals("shared-03.jpg", SharedItems.fileName(2, null, "IMG-2026.jpeg"));
        assertEquals("shared-04.png", SharedItems.fileName(3, "image/png", "no extension"));
        assertEquals("shared-05.bin", SharedItems.fileName(4, "application/x-weird", "weird.$$$"));
        assertEquals("shared-10.webp", SharedItems.fileName(9, "image/webp", ""));
    }

    @Test
    public void vaultNameIsUnique() {
        assertEquals("20261111-130000-ab12-shared-01.jpg",
                SharedItems.vaultName("20261111-130000-ab12", "shared-01.jpg"));
    }

    @Test
    public void kinds() {
        assertTrue(SharedItems.isImage("shared-01.jpg"));
        assertTrue(SharedItems.isImage("x-shared-02.HEIC"));
        assertFalse(SharedItems.isImage("shared-03.pdf"));
        assertTrue(SharedItems.isPdf("shared-03.pdf"));
        assertFalse(SharedItems.isPdf(null));
    }

    @Test
    public void textJoinsSubjectOnlyWhenNew() {
        assertEquals("hello", SharedItems.text(null, " hello "));
        assertEquals("hello", SharedItems.text("hello", "hello"));
        assertEquals("Quiz moved\n\nQuiz 2 is now Tuesday", SharedItems.text("Quiz moved", "Quiz 2 is now Tuesday"));
        assertEquals("Subject only", SharedItems.text("Subject only", ""));
        assertEquals("", SharedItems.text(null, null));
    }

    @Test
    public void sourceAppFromReferrer() {
        assertEquals("com.whatsapp", SharedItems.sourceApp("android-app://com.whatsapp"));
        assertEquals("com.whatsapp.w4b", SharedItems.sourceApp("android-app://com.whatsapp.w4b/path"));
        assertEquals("", SharedItems.sourceApp("https://example.com"));
        assertEquals("", SharedItems.sourceApp("android-app://bad name"));
        assertEquals("", SharedItems.sourceApp(null));
    }

    @Test
    public void describe() {
        assertEquals("1 image", SharedItems.describe(1, 0, false));
        assertEquals("2 images · 1 file · text", SharedItems.describe(2, 1, true));
        assertEquals("text", SharedItems.describe(0, 0, true));
        assertEquals("nothing", SharedItems.describe(0, 0, false));
    }

    @Test
    public void metaKeepsShareFields() throws Exception {
        CaptureMeta m = new CaptureMeta();
        m.id = "x";
        m.mode = "share";
        assertFalse(m.hasShared());
        m.sharedFiles.add("shared-01.jpg");
        m.sharedFiles.add("shared-02.pdf");
        m.sharedTextChars = 42;
        m.sharedSkipped = 1;
        CaptureMeta back = CaptureMeta.parse(m.serialize());
        assertEquals("share", back.mode);
        assertEquals(m.sharedFiles, back.sharedFiles);
        assertEquals(42, back.sharedTextChars);
        assertEquals(1, back.sharedSkipped);
        assertTrue(back.hasShared());
        assertTrue(back.parts().isEmpty());

        CaptureMeta textOnly = new CaptureMeta();
        textOnly.sharedTextChars = 3;
        assertTrue(textOnly.hasShared());
        assertTrue(CaptureMeta.parse(new CaptureMeta().serialize()).sharedFiles.isEmpty());
    }
}
