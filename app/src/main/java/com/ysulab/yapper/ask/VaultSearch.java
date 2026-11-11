package com.ysulab.yapper.ask;

import com.ysulab.yapper.vault.EntityIndex;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local keyword search over the vault for "ask your vault". Notes are split into sections (one per
 * heading, long sections into paragraphs). Each section gets a date range from what Yapper wrote:
 * {@code %%yap:20261109-...%%} markers, {@code ### 2026-11-09} headings and {@code Daily/2026-11-09.md}
 * paths. A query is a list of terms (both spellings: "GCP", "Google Cloud", "جي سي بي") and an
 * optional date range. No embeddings, no network: it runs on the phone over plain markdown.
 * Pure Java.
 */
public final class VaultSearch {

    /** Sections longer than this are split at blank lines. */
    static final int MAX_CHUNK = 1200;
    /** Default excerpt budget for one prompt (characters). */
    public static final int DEFAULT_BUDGET = 24_000;

    private static final Pattern MARKER = Pattern.compile("%%yap:(\\d{8})-(\\d{6})-[0-9a-z]{4}(?:/\\d+)?%%");
    private static final Pattern DAY_HEADING = Pattern.compile("^#{1,6}\\s+(\\d{4}-\\d{2}-\\d{2})\\b.*");
    private static final Pattern DAILY_PATH = Pattern.compile("(?:^|/)(\\d{4}-\\d{2}-\\d{2})\\.md$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*");
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]|#^]+)(?:[#^][^\\]|]*)?(?:\\|[^\\]]*)?\\]\\]");
    private static final DateTimeFormatter DAY8 = DateTimeFormatter.BASIC_ISO_DATE;

    /** Words that say nothing about the topic (English and Saudi Arabic), dropped from questions. */
    private static final Set<String> STOP = new java.util.HashSet<>(java.util.Arrays.asList(
            "what", "did", "i", "say", "said", "about", "the", "a", "an", "of", "to", "in", "on", "for", "and",
            "or", "is", "was", "were", "my", "me", "do", "does", "when", "where", "who", "how", "why", "which",
            "last", "this", "week", "month", "day", "today", "yesterday", "any", "anything", "notes", "note",
            "tell", "with", "from", "have", "has", "had", "it", "that", "there", "yapper", "please",
            "وش", "ايش", "شو", "قلت", "عن", "في", "على", "من", "الى", "إلى", "هل", "متى", "وين", "مين", "كيف",
            "ليش", "اللي", "الي", "هذا", "هذي", "هذه", "ذا", "الاسبوع", "الأسبوع", "اسبوع", "أسبوع", "امس",
            "أمس", "اليوم", "الماضي", "يوم", "شي", "شيء", "عندي", "كان", "كانت", "انا", "أنا", "و", "او", "أو"));

    /** One searchable piece of a note. */
    public static final class Chunk {
        public final String path;
        public final String heading;
        public final String text;
        /** Inclusive date range of what Yapper wrote here, or null when undated (owner-written text). */
        public LocalDate from;
        public LocalDate to;
        final String norm;

        Chunk(String path, String heading, String text) {
            this.path = path;
            this.heading = heading;
            this.text = text;
            this.norm = normalize(path + "\n" + heading + "\n" + text);
        }

        boolean dated() {
            return from != null;
        }

        boolean overlaps(LocalDate since, LocalDate until) {
            if (!dated()) return false;
            return (since == null || !to.isBefore(since)) && (until == null || !from.isAfter(until));
        }

        /** Excerpt as the model sees it: path, heading, dates, text. */
        public String render() {
            StringBuilder sb = new StringBuilder("### ").append(path);
            if (!heading.isEmpty()) sb.append(" › ").append(heading);
            if (dated()) {
                sb.append(" (").append(from);
                if (!to.equals(from)) sb.append(" → ").append(to);
                sb.append(')');
            }
            return sb.append('\n').append(text.trim()).append('\n').toString();
        }
    }

    /** What to look for. Empty terms + a date range = "everything from that time". */
    public static final class Query {
        public final List<String> terms = new ArrayList<>();
        public LocalDate since;
        public LocalDate until;
        public int budget = DEFAULT_BUDGET;
    }

    private final List<Chunk> chunks = new ArrayList<>();
    private final EntityIndex entities;
    /** Lower-cased note base name → path, for wikilink → source mapping. */
    private final Map<String, String> byName = new HashMap<>();

    /**
     * @param notes vault-relative path → full text
     * @param entities names and aliases from {@code _yapper/index.md}, to widen "GCP" to "Google Cloud"
     * @param zone time zone for {@code %%yap%%} markers (they are UTC)
     */
    public VaultSearch(Map<String, String> notes, EntityIndex entities, ZoneId zone) {
        this.entities = entities == null ? EntityIndex.fromIndex("") : entities;
        List<String> paths = new ArrayList<>(notes.keySet());
        Collections.sort(paths);
        for (String p : paths) {
            byName.putIfAbsent(EntityIndex.baseName(p).toLowerCase(Locale.ROOT), p);
            split(p, notes.get(p), zone);
        }
    }

    public int size() {
        return chunks.size();
    }

    /** The note path for a wikilink target ("Google Cloud" → Topics/Google Cloud.md), or null. */
    public String pathFor(String linkTarget) {
        String t = linkTarget == null ? "" : linkTarget.trim();
        if (t.endsWith(".md")) t = t.substring(0, t.length() - 3);
        String p = byName.get(t.toLowerCase(Locale.ROOT));
        if (p == null && t.contains("/")) {
            p = byName.get(EntityIndex.baseName(t + ".md").toLowerCase(Locale.ROOT));
        }
        return p != null ? p : entities.find(t);
    }

    /** Every [[link]] in {@code text} that points to a note in the vault, in order, without repeats. */
    public List<String> sources(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = WIKILINK.matcher(text == null ? "" : text);
        while (m.find()) {
            String p = pathFor(m.group(1));
            if (p != null) out.add(p);
        }
        return new ArrayList<>(out);
    }

    /** Full text of one note as split here (for read_note), or null. */
    public String note(String path) {
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) {
            if (c.path.equals(path)) sb.append(c.render()).append('\n');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Best sections for the query, highest score first, within the character budget. */
    public List<Chunk> search(Query q) {
        List<Group> terms = expand(q.terms);
        boolean ranged = q.since != null || q.until != null;
        List<Object[]> scored = new ArrayList<>();
        for (Chunk c : chunks) {
            double s;
            if (terms.isEmpty()) {
                // "What did I do last week?": everything Yapper wrote in that range, newest first.
                if (!ranged || !c.overlaps(q.since, q.until)) continue;
                s = c.to.toEpochDay();
            } else {
                s = score(c, terms);
                if (s <= 0) continue;
                if (ranged) {
                    if (c.dated()) {
                        if (!c.overlaps(q.since, q.until)) continue;
                        s *= 1.5;
                    } else {
                        s *= 0.5; // owner-written text: may still be the answer, but rank it lower
                    }
                }
            }
            scored.add(new Object[] {s, c});
        }
        scored.sort((a, b) -> Double.compare((Double) b[0], (Double) a[0]));
        List<Chunk> out = new ArrayList<>();
        int used = 0;
        for (Object[] o : scored) {
            Chunk c = (Chunk) o[1];
            int len = c.render().length();
            if (used + len > q.budget) {
                if (out.isEmpty() && len > q.budget) continue;
                break;
            }
            out.add(c);
            used += len;
        }
        return out;
    }

    /** Excerpts as one block of text for the prompt. */
    public static String render(List<Chunk> found) {
        if (found.isEmpty()) return "(no matching notes)";
        StringBuilder sb = new StringBuilder();
        for (Chunk c : found) {
            sb.append(c.render()).append('\n');
        }
        return sb.toString().trim();
    }

    /** Topic words of a question, for when the sorter gave no terms. */
    public static List<String> termsFromQuestion(String question) {
        List<String> out = new ArrayList<>();
        for (String w : normalize(question).split("[^\\p{L}\\p{N}+#.\\-]+")) {
            String t = w.replaceAll("^[.\\-]+|[.\\-]+$", "");
            if (t.length() >= 2 && !STOP.contains(t) && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** One topic: its spellings (an entity's name and aliases) and its note, if it has one. */
    static final class Group {
        final List<String> variants = new ArrayList<>();
        String notePath;
    }

    /** Each raw term becomes a group with every alias of a matching entity note, so one spelling finds all. */
    List<Group> expand(List<String> raw) {
        List<Group> out = new ArrayList<>();
        for (String r : raw) {
            Group g = new Group();
            add(g.variants, r);
            String p = entities.find(r);
            if (p == null) {
                p = byName.get(r.trim().toLowerCase(Locale.ROOT));
            }
            if (p != null) {
                g.notePath = p;
                add(g.variants, EntityIndex.baseName(p));
                for (String a : entities.aliasesOf(p)) add(g.variants, a);
            }
            if (!g.variants.isEmpty()) out.add(g);
        }
        return out;
    }

    private static void add(List<String> out, String term) {
        String t = normalize(term).trim();
        if (t.length() >= 2 && !out.contains(t)) out.add(t);
    }

    /**
     * Per topic, the best-matching spelling counts (synonyms do not stack): hits up to 3, weighted by
     * how rare the spelling is. A section of the topic's own note, or one that links to it, gets a
     * boost. Sections matching several topics rank higher.
     */
    private double score(Chunk c, List<Group> groups) {
        double s = 0;
        int matched = 0;
        for (Group g : groups) {
            double best = 0;
            for (String t : g.variants) {
                int hits = count(c.norm, t);
                if (hits == 0) continue;
                double v = idf(t) * Math.min(hits, 3);
                if (c.norm.contains("[[" + t)) v += idf(t);
                best = Math.max(best, v);
            }
            if (g.notePath != null && g.notePath.equals(c.path)) {
                best += 3 * Math.log(1.0 + chunks.size()); // the topic's own note
            }
            if (best > 0) {
                matched++;
                s += best;
            }
        }
        return matched == 0 ? 0 : s * (1 + 0.5 * (matched - 1));
    }

    private final Map<String, Double> idfCache = new HashMap<>();

    private double idf(String term) {
        Double v = idfCache.get(term);
        if (v == null) {
            int df = 0;
            for (Chunk c : chunks) {
                if (c.norm.contains(term)) df++;
            }
            v = Math.log(1.0 + (chunks.size() + 1.0) / (df + 1.0));
            idfCache.put(term, v);
        }
        return v;
    }

    /** Occurrences; short Latin terms ("os", "ai") must stand alone so "os" does not hit "post". */
    static int count(String hay, String needle) {
        int n = 0;
        boolean word = needle.length() <= 3 && needle.matches("[a-z0-9]+");
        for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + 1)) {
            if (word) {
                boolean left = i == 0 || !Character.isLetterOrDigit(hay.charAt(i - 1));
                int e = i + needle.length();
                boolean right = e >= hay.length() || !Character.isLetterOrDigit(hay.charAt(e));
                if (!left || !right) continue;
            }
            n++;
        }
        return n;
    }

    /**
     * Lower case, Arabic letter variants folded (أ إ آ → ا, ة → ه, ى → ي), diacritics and tatweel
     * removed, Arabic-Indic digits → 0-9. Arabic prefixes (ال، و، ب) still match because terms are
     * matched as substrings.
     */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch >= 'ً' && ch <= 'ْ' || ch == 'ـ') continue;
            if (ch == 'أ' || ch == 'إ' || ch == 'آ') ch = 'ا';
            else if (ch == 'ة') ch = 'ه';
            else if (ch == 'ى') ch = 'ي';
            else if (ch >= '٠' && ch <= '٩') ch = (char) ('0' + ch - '٠');
            else if (ch >= '۰' && ch <= '۹') ch = (char) ('0' + ch - '۰');
            sb.append(ch);
        }
        return sb.toString();
    }

    // ---- splitting -------------------------------------------------------------------------

    private void split(String path, String text, ZoneId zone) {
        if (text == null) return;
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        int i = 0;
        if (lines.length > 0 && lines[0].trim().equals("---")) {
            for (i = 1; i < lines.length && !lines[i].trim().equals("---"); i++) {
                // skip frontmatter; aliases come from the entity index
            }
            i++;
        }
        LocalDate pathDay = day(DAILY_PATH.matcher(path), 1);
        String heading = "";
        LocalDate headingDay = null;
        StringBuilder body = new StringBuilder();
        boolean fence = false;
        for (; i < lines.length; i++) {
            String l = lines[i];
            if (l.trim().startsWith("```")) fence = !fence;
            if (!fence && HEADING.matcher(l.trim()).matches()) {
                flush(path, heading, body, pathDay != null ? pathDay : headingDay, zone);
                heading = l.trim().replaceAll("^#+\\s*", "").replaceAll("%%.*?%%", "").trim();
                Matcher dm = DAY_HEADING.matcher(l.trim());
                LocalDate hd = dm.matches() ? parse(dm.group(1)) : null;
                // "### 2026-11-09" dates what follows; a "## Log" under it keeps the last date.
                if (hd != null || l.trim().startsWith("# ") || l.trim().startsWith("## ")) headingDay = hd;
                continue;
            }
            body.append(l).append('\n');
        }
        flush(path, heading, body, pathDay != null ? pathDay : headingDay, zone);
    }

    private void flush(String path, String heading, StringBuilder body, LocalDate day, ZoneId zone) {
        String text = body.toString().trim();
        body.setLength(0);
        if (text.isEmpty() || text.replaceAll("%%.*?%%", "").trim().isEmpty()) return;
        List<String> pieces = new ArrayList<>();
        if (text.length() <= MAX_CHUNK) {
            pieces.add(text);
        } else {
            StringBuilder cur = new StringBuilder();
            for (String para : text.split("\n\\s*\n")) {
                if (cur.length() > 0 && cur.length() + para.length() > MAX_CHUNK) {
                    pieces.add(cur.toString().trim());
                    cur.setLength(0);
                }
                // A single huge paragraph (a ramble transcript) is cut hard.
                while (para.length() > MAX_CHUNK) {
                    pieces.add(para.substring(0, MAX_CHUNK));
                    para = para.substring(MAX_CHUNK);
                }
                cur.append(para).append("\n\n");
            }
            if (cur.toString().trim().length() > 0) pieces.add(cur.toString().trim());
        }
        for (String p : pieces) {
            Chunk c = new Chunk(path, heading, p);
            Matcher m = MARKER.matcher(p);
            while (m.find()) {
                LocalDate d = Instant.from(java.time.ZonedDateTime.of(
                        java.time.LocalDate.parse(m.group(1), DAY8),
                        java.time.LocalTime.parse(m.group(2), DateTimeFormatter.ofPattern("HHmmss")),
                        java.time.ZoneOffset.UTC)).atZone(zone).toLocalDate();
                widen(c, d);
            }
            if (day != null && !c.dated()) widen(c, day);
            chunks.add(c);
        }
    }

    private static void widen(Chunk c, LocalDate d) {
        if (c.from == null || d.isBefore(c.from)) c.from = d;
        if (c.to == null || d.isAfter(c.to)) c.to = d;
    }

    private static LocalDate day(Matcher m, int group) {
        return m.find() ? parse(m.group(group)) : null;
    }

    private static LocalDate parse(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
