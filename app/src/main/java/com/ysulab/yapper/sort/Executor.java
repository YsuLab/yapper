package com.ysulab.yapper.sort;

import com.ysulab.yapper.YapId;
import com.ysulab.yapper.vault.EntityIndex;
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
    /** Existing notes by name and alias, so link_entity reuses notes instead of duplicating them. */
    private EntityIndex entities = new EntityIndex();

    public Executor(VaultFs fs, String captureId, String captureNote, ZonedDateTime now) {
        this.fs = fs;
        this.captureId = captureId;
        this.captureNote = captureNote;
        this.now = now;
    }

    /** Names and aliases of the vault's notes (from {@code _yapper/index.md}). */
    public Executor withEntities(EntityIndex index) {
        this.entities = index == null ? new EntityIndex() : index;
        return this;
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
                case SortAction.PLAN:
                    plan(log, a, marker);
                    break;
                case SortAction.CHECK:
                    check(log, a, marker);
                    break;
                case SortAction.ENTITY:
                    entity(log, a, marker);
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

    /**
     * Second brain: the note for a person, project, tool or place. Existing note (by name or alias,
     * any case) → one dated log line with the fact, links to related notes and a link back to the
     * capture. No note yet → a new one with aliases, a summary, "## Related" and "## Log".
     * Append/create only, like every other write.
     */
    private void entity(ActionLog log, SortAction a, String marker) throws IOException {
        String path = entities.find(a.title);
        for (int k = 0; path == null && k < a.aliases.size(); k++) {
            path = entities.find(a.aliases.get(k));
        }
        if (path == null) {
            path = a.path;
        }
        String current = fs.read(path);
        if (current == null) {
            String text = newEntityNote(a) + "\n" + marker + "\n";
            fs.create(path, text);
            log.add("create", path, text, a.tool);
        } else if (!current.contains(marker)) {
            String line = entityLine(a, true);
            if (!line.isEmpty()) {
                write(log, a.tool, path, "", line, marker, true, null);
            }
        }
        entities.add(path, a.title, a.aliases);
        a.path = path; // so the summary links the note actually used
    }

    private String newEntityNote(SortAction a) {
        StringBuilder sb = new StringBuilder("---\n");
        if (!a.aliases.isEmpty()) {
            sb.append("aliases:\n");
            for (String al : a.aliases) {
                sb.append("  - \"").append(al.replace("\"", "'")).append("\"\n");
            }
        }
        String kind = a.kind.replaceAll("[^a-z0-9-]", "");
        sb.append("type: ").append(kind.isEmpty() ? "topic" : kind).append('\n');
        sb.append("tags:\n  - entity\n");
        sb.append("---\n# ").append(a.title).append("\n\n");
        if (!a.summary.isEmpty()) {
            sb.append(a.summary).append("\n\n");
        }
        if (!a.related.isEmpty()) {
            sb.append("## Related\n");
            for (String r : a.related) {
                sb.append("- ").append(entities.link(r)).append('\n');
            }
            sb.append('\n');
        }
        sb.append("## Log\n");
        String line = entityLine(a, false);
        if (!line.isEmpty()) {
            sb.append("\n### ").append(now.format(DAY)).append('\n').append(line).append('\n');
        }
        return sb.toString();
    }

    /** "- fact · [[A]], [[B]] [[capture|🎙]]" (related links only when the note already existed). */
    private String entityLine(SortAction a, boolean withRelated) {
        StringBuilder sb = new StringBuilder();
        String fact = a.markdown.trim();
        if (!fact.isEmpty()) {
            sb.append(fact.startsWith("- ") ? "" : "- ").append(fact);
        }
        if (withRelated && !a.related.isEmpty()) {
            List<String> links = new java.util.ArrayList<>();
            for (String r : a.related) {
                links.add(entities.link(r));
            }
            sb.append(sb.length() == 0 ? "- Related: " : " · ").append(String.join(", ", links));
        }
        if (sb.length() > 0 && !captureNote.isEmpty()) {
            sb.append(" [[").append(captureNote).append("|🎙]]");
        }
        return sb.toString();
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
            e.notes = a.markdown.trim();
        }
    }

    static final String PLAN_SECTION = "Plan";
    static final String DEBRIEF_SECTION = "Debrief";

    /** "- [ ] 09:00–10:30 Title" lines under "## Plan", then one reminder (no extra line) per block start. */
    private void plan(ActionLog log, SortAction a, String marker) throws IOException {
        StringBuilder md = new StringBuilder();
        for (SortAction.Block b : a.blocks) {
            md.append("- [ ] ");
            if (!b.start.isEmpty()) {
                md.append(b.start).append(b.end.isEmpty() ? "" : "–" + b.end).append(' ');
            }
            md.append(b.title).append('\n');
        }
        if (!a.markdown.isEmpty()) {
            md.append('\n').append(a.markdown.trim()).append('\n');
        }
        write(log, a.tool, dailyPath(a.date), PLAN_SECTION, md.toString(), marker, false, a.date);
        java.time.LocalDate day = java.time.LocalDate.parse(a.date);
        for (int i = 0; i < a.blocks.size(); i++) {
            SortAction.Block b = a.blocks.get(i);
            if (!b.alarm || b.start.isEmpty()) {
                continue;
            }
            String ref = marker + "#" + i;
            if (hasRef(log, ref)) {
                continue; // logged by an interrupted run
            }
            ZonedDateTime at = day.atTime(java.time.LocalTime.parse(b.start)).atZone(now.getZone());
            ActionLog.Entry e = log.add("reminder", dailyPath(a.date), ref, a.tool);
            e.title = b.title + (b.end.isEmpty() ? "" : " (until " + b.end + ")");
            e.remindAt = at.toOffsetDateTime().toString();
        }
    }

    /**
     * Debrief: ticks the first open task in that day's note that matches, changing only "[ ]" to "[x]".
     * No match → a "- [x]" line under "## Debrief", so nothing the owner said is lost.
     */
    private void check(ActionLog log, SortAction a, String marker) throws IOException {
        if (hasRef(log, marker)) {
            return;
        }
        String date = a.date.isEmpty() ? now.format(DAY) : a.date;
        String path = dailyPath(date);
        String current = fs.read(path);
        if (current != null && current.contains(marker)) {
            return; // fallback line already written
        }
        String line = current == null ? null : findOpenTask(current, a.title);
        if (line == null) {
            write(log, a.tool, path, DEBRIEF_SECTION, "- [x] " + a.title, marker, false, date);
            return;
        }
        String ticked = line.replaceFirst("- \\[ \\]", "- [x]");
        int at = current.indexOf(line);
        fs.replace(path, current.substring(0, at) + ticked + current.substring(at + line.length()));
        ActionLog.Entry e = log.add("check", path, line, a.tool);
        e.newText = ticked;
        e.notes = marker;
    }

    private static boolean hasRef(ActionLog log, String ref) {
        for (ActionLog.Entry e : log.entries) {
            if (ref.equals(e.text) && "reminder".equals(e.kind) || ref.equals(e.notes) && "check".equals(e.kind)) {
                return true;
            }
        }
        return false;
    }

    /** The first whole line "- [ ] …" whose text matches {@code task} (either contains the other). */
    static String findOpenTask(String text, String task) {
        String want = normTask(task);
        if (want.length() < 3) {
            return null;
        }
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("- [ ] ")) {
                continue;
            }
            String have = normTask(t.substring(6));
            if (have.length() >= 3 && (have.contains(want) || want.contains(have))) {
                return line;
            }
        }
        return null;
    }

    /** Lower case, no times ("09:00–10:30", "⏰ 17:00"), no punctuation, single spaces. */
    static String normTask(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("⏰", " ")
                .replaceAll("\\d{1,2}:\\d{2}\\s*([–-]\\s*\\d{1,2}:\\d{2})?", " ")
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
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
            if ("reminder".equals(e.kind)) {
                e.undone = true; // the To Do task / alarm is removed by ReminderSync
                r.removed++;
                continue;
            }
            String current = fs.read(e.path);
            if ("check".equals(e.kind)) {
                int at = current == null ? -1 : current.lastIndexOf(e.newText);
                if (at < 0) {
                    r.kept++;
                } else {
                    fs.replace(e.path, current.substring(0, at) + e.text + current.substring(at + e.newText.length()));
                    e.undone = true;
                    r.removed++;
                }
                continue;
            }
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
