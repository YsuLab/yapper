package com.ysulab.yapper.review;

import com.ysulab.yapper.sort.Executor;
import com.ysulab.yapper.vault.VaultFs;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the weekly review note from what is already in the vault, so the owner does not have to
 * remember his week: tasks done and still open (daily notes), what was added to which note (Yapper's
 * dated headings and entity logs), new notes, evening debriefs, captures, open questions in Inbox.md,
 * and last week's focus. No LLM, works offline. Pure Java over {@link VaultFs}, testable.
 */
public final class WeekDigest {

    public static final String REFLECTION = "## Reflection";
    public static final String FOCUS_HEADING = "Focus for next week";
    static final int MAX_TASKS = 40;
    static final int MAX_NOTES = 30;
    static final int MAX_LINES_PER_NOTE = 5;
    static final int MAX_DEBRIEF = 12;
    static final int MAX_QUESTIONS = 10;

    private static final Pattern TASK = Pattern.compile("^\\s*- \\[([ xX])\\] (.*)$");
    private static final Pattern DATED_HEADING = Pattern.compile("^#{2,4} (\\d{4}-\\d{2}-\\d{2})\\b.*");
    private static final Pattern HEADING = Pattern.compile("^#{1,6} .*");
    private static final Pattern MARKER = Pattern.compile("%%.*?%%");
    private static final Pattern MARKER_DATE = Pattern.compile("%%yap:(\\d{4})(\\d{2})(\\d{2})-\\d{6}-");
    private static final Pattern CAPTURE_LINK = Pattern.compile("\\s*\\[\\[[^\\]|]*\\|🎙\\]\\]");
    private static final Pattern CAPTURE_FILE =
            Pattern.compile("^[A-Za-z]+ (\\d{4}-\\d{2}-\\d{2}) \\d{6}\\.md$");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    /** What the digest found; the counts go into the Saturday notification. */
    public static final class Result {
        public String markdown = "";
        public int done;
        public int open;
        public int notes;
        public int captures;
        public int questions;

        /** "12 done · 5 open · 7 notes · 9 captures". */
        public String stats() {
            List<String> p = new ArrayList<>();
            p.add(done + " done");
            p.add(open + " open");
            p.add(notes + (notes == 1 ? " note" : " notes"));
            p.add(captures + (captures == 1 ? " capture" : " captures"));
            return String.join(" · ", p);
        }
    }

    private static final class Task {
        final String text;
        final LocalDate day;
        final boolean done;

        Task(String text, LocalDate day, boolean done) {
            this.text = text;
            this.day = day;
            this.done = done;
        }
    }

    private static final class Touched {
        final String path;
        final boolean entity;
        boolean isNew;
        final List<String> lines = new ArrayList<>();

        Touched(String path, boolean entity) {
            this.path = path;
            this.entity = entity;
        }
    }

    private final VaultFs fs;
    private final WeekRange week;
    private final ZoneId zone;

    public WeekDigest(VaultFs fs, WeekRange week, ZoneId zone) {
        this.fs = fs;
        this.week = week;
        this.zone = zone;
    }

    /**
     * @param notes every markdown file in the vault with its last-modified time (epoch ms, 0 if
     *     unknown). Files not changed since the week started are not read.
     */
    public Result build(Map<String, Long> notes) throws IOException {
        Result r = new Result();
        long since = week.start.atStartOfDay(zone).toInstant().toEpochMilli();

        // Tasks and debriefs from the daily notes. A task done on any day counts as done once;
        // an open task that rolled over several days is listed once, on its last day.
        Map<String, Task> tasks = new LinkedHashMap<>();
        List<String> debrief = new ArrayList<>();
        List<String> dailyLinks = new ArrayList<>();
        for (LocalDate d : week.days()) {
            String path = Executor.dailyPath(d.format(DAY));
            String text = fs.read(path);
            if (text == null) {
                continue;
            }
            dailyLinks.add("[[" + d.format(DAY) + "|" + d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    + "]]");
            String section = "";
            for (String line : text.split("\n")) {
                if (HEADING.matcher(line.trim()).matches()) {
                    section = line.trim();
                    continue;
                }
                Matcher m = TASK.matcher(line);
                if (m.matches() && !line.startsWith(" ") && !line.startsWith("\t")) {
                    String t = cleanTask(m.group(2));
                    String key = Executor.normTask(t);
                    if (key.length() < 2) {
                        continue;
                    }
                    boolean done = !m.group(1).equals(" ");
                    Task prev = tasks.get(key);
                    if (prev == null || !prev.done) {
                        tasks.remove(key);
                        tasks.put(key, new Task(t, d, done));
                    }
                } else if (section.equals("## Debrief") && debrief.size() < MAX_DEBRIEF) {
                    String c = clean(line);
                    if (!c.isEmpty() && !TASK.matcher(c).matches()) {
                        debrief.add(c + " (" + dayLink(d) + ")");
                    }
                }
            }
        }

        // What was added to other notes this week.
        List<Touched> touched = new ArrayList<>();
        for (Map.Entry<String, Long> e : new TreeMap<>(notes).entrySet()) {
            String path = e.getKey();
            Matcher cf = CAPTURE_FILE.matcher(name(path) + ".md");
            if ((path.startsWith("Inbox/") || path.startsWith("_yapper/captures/")) && cf.matches()) {
                if (week.contains(LocalDate.parse(cf.group(1)))) {
                    r.captures++;
                }
                continue;
            }
            if (!worthScanning(path) || (e.getValue() > 0 && e.getValue() < since)) {
                continue;
            }
            String text = fs.read(path);
            if (text == null) {
                continue;
            }
            Touched t = scan(path, text);
            if (t != null) {
                touched.add(t);
            }
        }
        touched.sort((a, b) -> Integer.compare(b.lines.size() + (b.isNew ? 1 : 0), a.lines.size() + (a.isNew ? 1 : 0)));
        if (touched.size() > MAX_NOTES) {
            touched = new ArrayList<>(touched.subList(0, MAX_NOTES));
        }

        List<String> questions = new ArrayList<>();
        String inbox = fs.read(Executor.INBOX_NOTE);
        if (inbox != null) {
            for (String line : inbox.split("\n")) {
                Matcher m = TASK.matcher(line);
                if (m.matches() && m.group(1).equals(" ") && m.group(2).contains("❓")) {
                    r.questions++;
                    if (questions.size() < MAX_QUESTIONS) {
                        questions.add("- " + clean(m.group(2)));
                    }
                }
            }
        }

        List<String> lastFocus = focusLines(fs.read(week.previous().notePath()));

        // Render.
        List<Task> done = new ArrayList<>();
        List<Task> open = new ArrayList<>();
        for (Task t : tasks.values()) {
            (t.done ? done : open).add(t);
        }
        r.done = done.size();
        r.open = open.size();
        r.notes = touched.size();

        StringBuilder sb = new StringBuilder();
        sb.append("---\ntype: weekly-review\nweek: ").append(week.id()).append("\ntags:\n  - weekly\n---\n");
        sb.append("# Week ").append(week.number()).append(" · ").append(week.label()).append("\n\n");
        sb.append("Previous: [[").append(week.previous().id()).append("]]");
        if (!dailyLinks.isEmpty()) {
            sb.append(" · Days: ").append(String.join(" · ", dailyLinks));
        }
        sb.append("\n\n");
        sb.append("**").append(r.stats()).append("**");
        if (r.questions > 0) {
            sb.append(" · ").append(r.questions).append(r.questions == 1 ? " open question" : " open questions")
                    .append(" in [[Inbox]]");
        }
        sb.append("\n");

        if (!lastFocus.isEmpty()) {
            sb.append("\n## Last week's focus\n");
            for (String l : lastFocus) {
                sb.append(l).append('\n');
            }
        }
        if (!done.isEmpty()) {
            sb.append("\n## Done\n");
            appendTasks(sb, done, "- [x] ");
        }
        if (!open.isEmpty()) {
            sb.append("\n## Still open\n");
            appendTasks(sb, open, "- [ ] ");
        }
        List<Touched> entities = new ArrayList<>();
        List<Touched> others = new ArrayList<>();
        for (Touched t : touched) {
            (t.entity ? entities : others).add(t);
        }
        if (!others.isEmpty()) {
            sb.append("\n## What I worked on\n");
            appendTouched(sb, others);
        }
        if (!entities.isEmpty()) {
            sb.append("\n## People, projects and tools\n");
            appendTouched(sb, entities);
        }
        if (!debrief.isEmpty()) {
            sb.append("\n## From my evening debriefs\n");
            for (String l : debrief) {
                sb.append(l.startsWith("- ") ? "" : "- ").append(l).append('\n');
            }
        }
        if (!questions.isEmpty()) {
            sb.append("\n## Still unanswered in Inbox\n");
            for (String q : questions) {
                sb.append(q).append('\n');
            }
        }
        if (tasks.isEmpty() && touched.isEmpty() && r.captures == 0) {
            sb.append("\nNothing was captured this week. Say what you remember, it still counts.\n");
        }
        sb.append("\n%%yap-week:").append(week.id()).append("%%\n");
        r.markdown = sb.toString();
        return r;
    }

    /**
     * Builds the digest and writes the week's note: creates it, or refreshes it while it is still
     * exactly what Yapper wrote last time (so a review on Saturday night sees the whole week). Once the
     * owner or a reflection changed the note, it is left alone: append/create only.
     *
     * @param stateDir app-private folder that remembers what was written, per week
     */
    public static Result prepare(VaultFs fs, WeekRange week, ZoneId zone, java.io.File stateDir) throws IOException {
        Result r = new WeekDigest(fs, week, zone).build(fs.listNotes());
        String path = week.notePath();
        java.io.File state = new java.io.File(stateDir, week.id() + ".md");
        String last = state.isFile()
                ? new String(java.nio.file.Files.readAllBytes(state.toPath()), java.nio.charset.StandardCharsets.UTF_8)
                : null;
        String current = fs.read(path);
        boolean wrote = false;
        if (current == null) {
            fs.create(path, r.markdown);
            wrote = true;
        } else if (current.equals(last) && !current.equals(r.markdown)) {
            fs.replace(path, r.markdown);
            wrote = true;
        }
        if (wrote) {
            stateDir.mkdirs();
            java.nio.file.Files.write(state.toPath(), r.markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } else if (current != null) {
            r.markdown = current;
        }
        return r;
    }

    private static void appendTasks(StringBuilder sb, List<Task> tasks, String box) {
        int n = 0;
        for (Task t : tasks) {
            if (n++ == MAX_TASKS) {
                sb.append("- …and ").append(tasks.size() - MAX_TASKS).append(" more\n");
                break;
            }
            sb.append(box).append(t.text).append(" (").append(dayLink(t.day)).append(")\n");
        }
    }

    private static void appendTouched(StringBuilder sb, List<Touched> list) {
        for (Touched t : list) {
            sb.append("- [[").append(linkTarget(t.path)).append("]]").append(t.isNew ? " (new)" : "").append('\n');
            for (String l : t.lines) {
                String body = l.startsWith("- ") ? l.substring(2) : l;
                sb.append("    - ").append(body).append('\n');
            }
        }
    }

    /** Lines Yapper added under this week's dated headings, or null when the note was not touched. */
    private Touched scan(String path, String text) {
        boolean entity = text.startsWith("---") && text.contains("\n  - entity\n");
        Touched t = new Touched(path, entity);
        boolean inWeek = false;
        boolean found = false;
        boolean fence = false;
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.startsWith("```")) {
                fence = !fence;
            }
            Matcher md = MARKER_DATE.matcher(s);
            if (md.find() && week.contains(LocalDate.of(Integer.parseInt(md.group(1)),
                    Integer.parseInt(md.group(2)), Integer.parseInt(md.group(3))))) {
                found = true;
            }
            if (!fence && HEADING.matcher(s).matches()) {
                Matcher h = DATED_HEADING.matcher(s);
                inWeek = h.matches() && inWeekSafe(h.group(1));
                found |= inWeek;
                continue;
            }
            if (inWeek && t.lines.size() < MAX_LINES_PER_NOTE) {
                String c = clean(line);
                if (!c.isEmpty() && !line.startsWith("    ") && !line.startsWith("\t")) {
                    t.lines.add(c);
                }
            }
        }
        if (!found) {
            return null;
        }
        // Marker from this week but no dated heading: a note Yapper created this week.
        t.isNew = t.lines.isEmpty() || isNewThisWeek(text);
        return t;
    }

    private boolean isNewThisWeek(String text) {
        // Entity notes start with "## Log" + this week's date as the only log entry.
        Matcher m = DATED_HEADING.matcher("");
        int dated = 0;
        boolean allInWeek = true;
        for (String line : text.split("\n")) {
            m.reset(line.trim());
            if (m.matches()) {
                dated++;
                allInWeek &= inWeekSafe(m.group(1));
            }
        }
        return dated > 0 && allInWeek && text.contains("## Log");
    }

    private boolean inWeekSafe(String date) {
        try {
            return week.contains(LocalDate.parse(date));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** "- [ ] " lines under "### … Focus for next week" in a weekly note (last one wins). */
    public static List<String> focusLines(String weeklyNote) {
        List<String> out = new ArrayList<>();
        if (weeklyNote == null) {
            return out;
        }
        boolean in = false;
        for (String line : weeklyNote.split("\n")) {
            String s = line.trim();
            if (HEADING.matcher(s).matches()) {
                in = s.contains(FOCUS_HEADING);
                if (in) {
                    out.clear();
                }
                continue;
            }
            if (in) {
                String c = clean(line);
                if (c.startsWith("- ")) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    static boolean worthScanning(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        for (String seg : p.split("/")) {
            if (seg.startsWith(".")) {
                return false;
            }
        }
        return p.endsWith(".md") && !p.startsWith("_yapper/") && !p.startsWith("inbox/")
                && !p.startsWith((Executor.DAILY_DIR + "/").toLowerCase(Locale.ROOT))
                && !p.startsWith((WeekRange.WEEKLY_DIR + "/").toLowerCase(Locale.ROOT))
                && !p.equals(Executor.INBOX_NOTE.toLowerCase(Locale.ROOT));
    }

    /** Without Yapper's markers and the 🎙 link back to the capture. */
    static String clean(String line) {
        return CAPTURE_LINK.matcher(MARKER.matcher(line).replaceAll("")).replaceAll("").trim();
    }

    /** Task text without the alarm clock and its time ("⏰ 17:00 Call mama" → "Call mama"). */
    static String cleanTask(String text) {
        return clean(text).replaceFirst("^⏰\\s*\\d{1,2}:\\d{2}\\s*", "").trim();
    }

    private static String dayLink(LocalDate d) {
        return "[[" + d.format(DAY) + "|" + d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + "]]";
    }

    private static String name(String path) {
        String n = path.substring(path.lastIndexOf('/') + 1);
        return n.endsWith(".md") ? n.substring(0, n.length() - 3) : n;
    }

    /** Note name for a wikilink; Obsidian resolves by name. */
    private static String linkTarget(String path) {
        return name(path);
    }
}
