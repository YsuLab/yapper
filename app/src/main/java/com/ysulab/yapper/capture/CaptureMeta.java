package com.ysulab.yapper.capture;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Metadata of one capture bundle, stored as {@code meta.properties} next to the audio.
 *
 * <p>The bundle on disk is the source of truth. {@link State} only moves forward, so a crash at
 * any point leaves enough to resume: recording → recorded → inbox → done.
 */
public final class CaptureMeta {

    public enum State {
        /** Audio is being recorded. If the app dies here, recovery promotes it to RECORDED. */
        RECORDING,
        /** Bundle complete on the phone. Next: write the Inbox note. */
        RECORDED,
        /** Inbox note written. Next: transcripts (needs network + a transcriber). */
        INBOX,
        /** Every configured transcriber ran (or failed permanently). */
        DONE
    }

    public String id;
    public State state = State.RECORDING;
    public long createdAtMs;
    public String zoneId = "Asia/Riyadh";
    public String audioFile = "";
    /**
     * M4 ramble: audio split into parts (audio-01.ogg, audio-02.ogg …), in order. Empty for a quick
     * capture, which has only {@link #audioFile}. Use {@link #parts()}.
     */
    public final List<String> audioParts = new ArrayList<>();
    /** "" (quick capture), "ramble", "plan" (morning plan), "debrief" (evening) or "review" (weekly). */
    public String mode = "";
    public long durationMs;
    public long speechMs;
    public String stopReason = "";
    /** "saved", "blank" (FLAG_SECURE), "none" (not delivered / disabled), or "error". */
    public String screenshot = "none";
    public int screenTextChars;
    public String sourceApp = "";
    public String webUri = "";
    public String inboxPath = "";
    /** Transcriber ids that already appended a transcript (or a final failure) to the note. */
    public final Set<String> transcribedBy = new LinkedHashSet<>();
    /**
     * Sorter (M2) outcome: "" (not yet), "planned" (plan saved, writing), "done", "shadow"
     * (proposal only), "failed", "undone", or "skipped" (nothing to sort, e.g. an M1 capture).
     */
    public String sorted = "";
    /** One-line result for notifications, e.g. "1 note → Tabby/Learning.md · 2 reminders". */
    public String sortSummary = "";

    public String serialize() {
        Properties p = new Properties();
        p.setProperty("id", nz(id));
        p.setProperty("state", state.name());
        p.setProperty("createdAtMs", Long.toString(createdAtMs));
        p.setProperty("zoneId", nz(zoneId));
        p.setProperty("audioFile", nz(audioFile));
        p.setProperty("audioParts", String.join(",", audioParts));
        p.setProperty("mode", nz(mode));
        p.setProperty("durationMs", Long.toString(durationMs));
        p.setProperty("speechMs", Long.toString(speechMs));
        p.setProperty("stopReason", nz(stopReason));
        p.setProperty("screenshot", nz(screenshot));
        p.setProperty("screenTextChars", Integer.toString(screenTextChars));
        p.setProperty("sourceApp", nz(sourceApp));
        p.setProperty("webUri", nz(webUri));
        p.setProperty("inboxPath", nz(inboxPath));
        p.setProperty("transcribedBy", String.join(",", transcribedBy));
        p.setProperty("sorted", nz(sorted));
        p.setProperty("sortSummary", nz(sortSummary));
        StringWriter w = new StringWriter();
        try {
            p.store(w, "yapper capture");
        } catch (IOException e) {
            throw new IllegalStateException(e); // StringWriter never throws
        }
        return w.toString();
    }

    public static CaptureMeta parse(String text) throws IOException {
        Properties p = new Properties();
        try (Reader r = new StringReader(text)) {
            p.load(r);
        }
        CaptureMeta m = new CaptureMeta();
        m.id = p.getProperty("id", "");
        try {
            m.state = State.valueOf(p.getProperty("state", "RECORDING"));
        } catch (IllegalArgumentException e) {
            m.state = State.RECORDED; // unknown future state: safest is to (re)process
        }
        m.createdAtMs = parseLong(p.getProperty("createdAtMs"));
        m.zoneId = p.getProperty("zoneId", "Asia/Riyadh");
        m.audioFile = p.getProperty("audioFile", "");
        String parts = p.getProperty("audioParts", "");
        if (!parts.isEmpty()) {
            m.audioParts.addAll(Arrays.asList(parts.split(",")));
        }
        m.mode = p.getProperty("mode", "");
        m.durationMs = parseLong(p.getProperty("durationMs"));
        m.speechMs = parseLong(p.getProperty("speechMs"));
        m.stopReason = p.getProperty("stopReason", "");
        m.screenshot = p.getProperty("screenshot", "none");
        m.screenTextChars = (int) parseLong(p.getProperty("screenTextChars"));
        m.sourceApp = p.getProperty("sourceApp", "");
        m.webUri = p.getProperty("webUri", "");
        m.inboxPath = p.getProperty("inboxPath", "");
        m.sorted = p.getProperty("sorted", "");
        m.sortSummary = p.getProperty("sortSummary", "");
        String by = p.getProperty("transcribedBy", "");
        if (!by.isEmpty()) {
            m.transcribedBy.addAll(Arrays.asList(by.split(",")));
        }
        return m;
    }

    /** Audio files of this capture, in order: the ramble parts, or the single quick-capture file. */
    public List<String> parts() {
        if (!audioParts.isEmpty()) {
            return new ArrayList<>(audioParts);
        }
        List<String> one = new ArrayList<>();
        if (audioFile != null && !audioFile.isEmpty()) {
            one.add(audioFile);
        }
        return one;
    }

    /** Transcriber ids from {@code configured} that still have to run for this capture. */
    public List<String> pendingTranscribers(List<String> configured) {
        List<String> out = new ArrayList<>();
        for (String id : configured) {
            if (!transcribedBy.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** Move forward only. Returns false (and changes nothing) for a backwards move. */
    public boolean advanceTo(State next) {
        if (next.ordinal() < state.ordinal()) {
            return false;
        }
        state = next;
        return true;
    }

    private static long parseLong(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
