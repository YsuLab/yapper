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
        String kind;
        switch (m.mode) {
            case "ramble": kind = "Ramble "; break;
            case "plan": kind = "Plan "; break;
            case "debrief": kind = "Debrief "; break;
            default: kind = "Capture "; break;
        }
        return kind + local(m).format(FILE_STAMP) + ".md";
    }

    public static String audioName(CaptureMeta m) {
        String ext = m.audioFile.contains(".") ? m.audioFile.substring(m.audioFile.lastIndexOf('.')) : ".ogg";
        return m.id + ext;
    }

    /**
     * Vault file name of one audio part: {@code <id>.ogg} for a quick capture, {@code <id>-02.ogg}
     * for ramble part 2.
     */
    public static String audioName(CaptureMeta m, String part) {
        if (m.audioParts.isEmpty()) {
            return audioName(m);
        }
        String ext = part.contains(".") ? part.substring(part.lastIndexOf('.')) : ".ogg";
        String base = part.contains(".") ? part.substring(0, part.lastIndexOf('.')) : part;
        String n = base.startsWith("audio-") ? base.substring(6) : base;
        return m.id + "-" + n + ext;
    }

    /** Every vault audio name of this capture, in order. */
    public static java.util.List<String> audioNames(CaptureMeta m) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : m.parts()) {
            out.add(audioName(m, p));
        }
        return out;
    }

    public static String screenshotName(CaptureMeta m) {
        return m.id + ".jpg";
    }

    public static String build(CaptureMeta m, String screenText, boolean hasScreenshot, boolean hasAudio) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("yapper: ").append(m.mode.isEmpty() ? "capture" : m.mode).append('\n');
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
            for (String name : audioNames(m)) {
                sb.append("![[").append(name).append("]]\n");
            }
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
