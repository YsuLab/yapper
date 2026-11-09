package com.ysulab.yapper.vault;

import com.ysulab.yapper.YapId;
import com.ysulab.yapper.capture.CaptureMeta;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Builds the markdown for a capture's note in the vault's {@code Inbox/}. Pure Java, testable.
 *
 * <p>The note is written once. Transcripts are appended later as their own blocks, each tagged with
 * the capture's {@code %%yap:<id>%%} marker, so nothing is ever rewritten.
 */
public final class InboxNote {

    public static final String INBOX_DIR = "Inbox";
    public static final String ATTACHMENTS_DIR = "attachments";
    /** Screen text kept in the note; the full text stays in the bundle. */
    static final int MAX_SCREEN_TEXT = 8000;

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss", Locale.ROOT);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private InboxNote() {}

    /** e.g. {@code Capture 2026-11-09 142305.md} in the owner's time zone. */
    public static String fileName(CaptureMeta m) {
        return "Capture " + local(m).format(FILE_STAMP) + ".md";
    }

    public static String audioName(CaptureMeta m) {
        String ext = m.audioFile.contains(".") ? m.audioFile.substring(m.audioFile.lastIndexOf('.')) : ".ogg";
        return m.id + ext;
    }

    public static String screenshotName(CaptureMeta m) {
        return m.id + ".jpg";
    }

    public static String build(CaptureMeta m, String screenText, boolean hasScreenshot, boolean hasAudio) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("yapper: capture\n");
        sb.append("yap_id: ").append(m.id).append('\n');
        sb.append("captured: ").append(local(m).format(ISO)).append('\n');
        if (!m.sourceApp.isEmpty()) {
            sb.append("source_app: ").append(yamlScalar(m.sourceApp)).append('\n');
        }
        if (!m.webUri.isEmpty()) {
            sb.append("source_url: ").append(yamlScalar(m.webUri)).append('\n');
        }
        sb.append("duration_s: ").append(Math.round(m.durationMs / 1000.0)).append('\n');
        sb.append("screenshot: ").append(m.screenshot).append('\n');
        sb.append("tags: [yapper/inbox]\n");
        sb.append("---\n");
        sb.append(YapId.marker(m.id)).append('\n');
        if (hasAudio) {
            sb.append("![[").append(audioName(m)).append("]]\n");
        } else {
            sb.append("_No audio recorded (microphone unavailable)._\n");
        }
        if (hasScreenshot) {
            sb.append("\n![[").append(screenshotName(m)).append("|300]]\n");
        } else if ("blank".equals(m.screenshot)) {
            sb.append("\n_Screenshot blocked by the app (secure screen)._\n");
        }
        String text = screenText == null ? "" : screenText.trim();
        if (!text.isEmpty()) {
            if (text.length() > MAX_SCREEN_TEXT) {
                text = text.substring(0, MAX_SCREEN_TEXT) + "\n…";
            }
            sb.append("\n> [!quote]- Screen text\n");
            for (String line : text.split("\n", -1)) {
                sb.append("> ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** Block appended once per transcriber. */
    public static String transcriptBlock(String captureId, String label, long latencyMs, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## Transcript · ").append(label)
                .append(String.format(Locale.ROOT, " · %.1f s", latencyMs / 1000.0)).append('\n');
        sb.append(YapId.marker(captureId)).append('\n');
        String t = text == null ? "" : text.trim();
        sb.append(t.isEmpty() ? "_(empty transcript)_" : t).append('\n');
        return sb.toString();
    }

    /** Block appended when a transcriber gave up for good (bad key, unsupported file...). */
    public static String transcriptFailedBlock(String captureId, String label, String reason) {
        return "\n## Transcript · " + label + " · failed\n" + YapId.marker(captureId) + "\n_"
                + oneLine(reason) + "_\n";
    }

    static ZonedDateTime local(CaptureMeta m) {
        ZoneId zone;
        try {
            zone = ZoneId.of(m.zoneId == null || m.zoneId.isEmpty() ? "Asia/Riyadh" : m.zoneId);
        } catch (RuntimeException e) {
            zone = ZoneId.of("Asia/Riyadh");
        }
        return Instant.ofEpochMilli(m.createdAtMs).atZone(zone);
    }

    static String yamlScalar(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String oneLine(String s) {
        if (s == null) {
            return "unknown error";
        }
        String t = s.replaceAll("\\s+", " ").replace("_", "\\_").trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}
