package com.ysulab.yapper.sort;

import com.ysulab.yapper.YapId;
import com.ysulab.yapper.vault.VaultFs;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Applies a sorter plan to the vault and undoes it. Pure Java over {@link VaultFs}, testable.
 *
 * <p>Write rules: only append or create. Each block carries {@code %%yap:<id>/<n>%%}, and the
 * executor re-reads the file right before writing, so a retried run skips blocks already written.
 * Undo removes exactly the text logged, and leaves a block alone if the owner edited it.
 */
public final class Executor {

    public static final String DAILY_DIR = "Daily";
    public static final String INBOX_NOTE = "Inbox.md";
    static final String REMINDERS_SECTION = "Reminders";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final VaultFs fs;
    private final String captureId;
    /** Capture note name without ".md", for back-links from Inbox.md questions. */
    private final String captureNote;
    private final ZonedDateTime now;

    public Executor(VaultFs fs, String captureId, String captureNote, ZonedDateTime now) {
        this.fs = fs;
        this.captureId = captureId;
        this.captureNote = captureNote;
        this.now = now;
    }

    public static String dailyPath(String date) {
        return DAILY_DIR + "/" + date + ".md";
    }

    /**
     * Runs every action, appending to {@code log} as it goes. The caller saves the log after each
     * call; entries already in the log (from an interrupted run) are not repeated.
     */
    public void execute(List<SortAction> plan, ActionLog log) throws IOException {
        for (int i = 0; i < plan.size(); i++) {
            SortAction a = plan.get(i);
            String marker = YapId.blockMarker(captureId, i);
            switch (a.tool) {
                case SortAction.APPEND:
                    write(log, a.tool, a.path, a.heading, a.markdown, marker, true, null);
                    break;
                case SortAction.CREATE:
                    create(log, a, marker);
                    break;
                case SortAction.DAILY:
                    write(log, a.tool, dailyPath(a.date), a.heading, a.markdown, marker, false, a.date);
                    break;
                case SortAction.REMINDER:
                    reminder(log, a, marker);
                    break;
                case SortAction.ASK:
                    ask(log, a, marker);
                    break;
                default:
                    break;
            }
        }
    }

    private void create(ActionLog log, SortAction a, String marker) throws IOException {
        String current = fs.read(a.path);
        if (current != null) {
            if (!current.contains(marker)) {
                // Note exists after all: append instead of overwriting the owner's text.
                write(log, a.tool, a.path, "", a.markdown, marker, true, null);
            }
            return;
        }
        String body = a.markdown.trim();
        String text = body + "\n\n" + marker + "\n";
        fs.create(a.path, text);
        log.add("create", a.path, text, a.tool);
    }

    private void reminder(ActionLog log, SortAction a, String marker) throws IOException {
        OffsetDateTime at = OffsetDateTime.parse(a.remindAt).atZoneSameInstant(now.getZone()).toOffsetDateTime();
        StringBuilder line = new StringBuilder("- [ ] ⏰ ").append(at.format(HM)).append(" ").append(a.title);
        if (!a.markdown.isEmpty()) {
            for (String l : a.markdown.trim().split("\n")) {
                line.append("\n    ").append(l.trim());
            }
        }
        String date = at.format(DAY);
        ActionLog.Entry e = write(log, a.tool, dailyPath(date), REMINDERS_SECTION, line.toString(), marker,
                false, date);
        if (e != null) {
            e.title = a.title;
            e.remindAt = a.remindAt;
        }
    }

    private void ask(ActionLog log, SortAction a, String marker) throws IOException {
        StringBuilder line = new StringBuilder("- [ ] ❓ ").append(a.question);
        if (!a.options.isEmpty()) {
            line.append(" (").append(String.join(" / ", a.options)).append(")");
        }
        if (!captureNote.isEmpty()) {
            line.append(" [[").append(captureNote).append("|🎙]]");
        }
        write(log, a.tool, INBOX_NOTE, "", line.toString(), marker, false, null);
    }

    /**
     * Appends one block under a heading. {@code dated}: heading is "### YYYY-MM-DD · topic".
     * Otherwise "## section". The heading is skipped when it is already the file's last heading,
     * so several captures on one day group together.
     */
    private ActionLog.Entry write(ActionLog log, String tool, String path, String topic, String markdown,
            String marker, boolean dated, String dailyDate) throws IOException {
        String current = fs.read(path);
        if (current != null && current.contains(marker)) {
            return null; // written by an earlier, interrupted run
        }
        String heading;
        if (dated) {
            heading = "### " + now.format(DAY) + (topic.isEmpty() ? "" : " · " + topic);
        } else {
            heading = topic.isEmpty() ? "" : "## " + topic;
        }
        StringBuilder block = new StringBuilder();
        if (current != null && !current.isEmpty() && !current.endsWith("\n")) {
            block.append('\n');
        }
        if (!heading.isEmpty() && !heading.equals(lastHeading(current))) {
            block.append('\n').append(heading).append('\n');
        }
        block.append(marker).append('\n').append(markdown.trim()).append('\n');
        if (current == null) {
            String head = dailyDate != null ? dailyTitle(dailyDate) : "";
            String text = head + block.toString().replaceFirst("^\n", head.isEmpty() ? "" : "\n");
            fs.create(path, text);
            return log.add("create", path, text, tool);
        }
        fs.append(path, block.toString());
        return log.add("append", path, block.toString(), tool);
    }

    static String dailyTitle(String date) {
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(date);
            return "# " + date + " · " + d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + "\n";
        } catch (RuntimeException e) {
            return "# " + date + "\n";
        }
    }

    /** The last markdown heading line in the text, or "" (lines inside code fences are ignored). */
    static String lastHeading(String text) {
        if (text == null) {
            return "";
        }
        String last = "";
        boolean fence = false;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("```")) {
                fence = !fence;
            } else if (!fence && t.matches("#{1,6} .*")) {
                last = t;
            }
        }
        return last;
    }

    /** Result of an undo: how many blocks were removed and how many were left because they changed. */
    public static final class UndoResult {
        public int removed;
        public int kept;
    }

    /** Removes what the log says we wrote, newest first. Marks entries undone in the log. */
    public UndoResult undo(ActionLog log) throws IOException {
        UndoResult r = new UndoResult();
        for (int i = log.entries.size() - 1; i >= 0; i--) {
            ActionLog.Entry e = log.entries.get(i);
            if (e.undone) {
                continue;
            }
            String current = fs.read(e.path);
            if (current == null) {
                e.undone = true; // already gone
                r.removed++;
                continue;
            }
            if ("create".equals(e.kind) && current.equals(e.text)) {
                fs.delete(e.path);
                e.undone = true;
                r.removed++;
                continue;
            }
            int at = current.lastIndexOf(e.text);
            if (at < 0) {
                r.kept++; // the owner edited our block: leave it
                continue;
            }
            fs.replace(e.path, current.substring(0, at) + current.substring(at + e.text.length()));
            e.undone = true;
            r.removed++;
        }
        return r;
    }
}
