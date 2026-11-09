package com.ysulab.yapper.sort;

import com.ysulab.yapper.YapId;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Human text for a sorted capture: the notification line and blocks for the capture note. Pure Java. */
public final class Summary {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH);

    private Summary() {}

    /** e.g. "2 notes → Uni/Algorithms.md, Tabby/Learning.md · 3 reminders · 1 question". */
    public static String line(List<SortAction> plan) {
        Set<String> notes = new LinkedHashSet<>();
        int reminders = 0;
        int daily = 0;
        int questions = 0;
        int blocks = 0;
        int done = 0;
        for (SortAction a : plan) {
            switch (a.tool) {
                case SortAction.APPEND:
                case SortAction.CREATE:
                    notes.add(a.path);
                    break;
                case SortAction.REMINDER:
                    reminders++;
                    break;
                case SortAction.DAILY:
                    daily++;
                    break;
                case SortAction.ASK:
                    questions++;
                    break;
                case SortAction.PLAN:
                    blocks += a.blocks.size();
                    break;
                case SortAction.CHECK:
                    done++;
                    break;
                default:
                    break;
            }
        }
        List<String> parts = new ArrayList<>();
        if (blocks > 0) {
            parts.add("Plan: " + plural(blocks, "block"));
        }
        if (done > 0) {
            parts.add(done + " done ✓");
        }
        if (!notes.isEmpty()) {
            parts.add(plural(notes.size(), "note") + " → " + String.join(", ", notes));
        }
        if (reminders > 0) {
            parts.add(plural(reminders, "reminder"));
        }
        if (daily > 0) {
            parts.add(plural(daily, "daily item"));
        }
        if (questions > 0) {
            parts.add(plural(questions, "question") + " in Inbox.md");
        }
        return parts.isEmpty() ? "Nothing to file" : String.join(" · ", parts);
    }

    /** The first note a "View" action should open: a note we wrote to, else the daily note or Inbox.md. */
    public static String viewPath(List<SortAction> plan) {
        String fallback = null;
        for (SortAction a : plan) {
            if (SortAction.APPEND.equals(a.tool) || SortAction.CREATE.equals(a.tool)) {
                return a.path;
            }
            if (fallback == null) {
                if (SortAction.DAILY.equals(a.tool) || SortAction.PLAN.equals(a.tool)) {
                    fallback = Executor.dailyPath(a.date);
                } else if (SortAction.ASK.equals(a.tool)) {
                    fallback = Executor.INBOX_NOTE;
                } else if (SortAction.REMINDER.equals(a.tool)) {
                    fallback = Executor.dailyPath(a.remindAt.substring(0, 10));
                }
            }
        }
        return fallback;
    }

    /** Appended to the capture note after a real run: where each piece went, as links. */
    public static String sortedBlock(String captureId, List<SortAction> plan, String model, long latencyMs,
            ZoneId zone, List<String> dropped) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## Sorted · ").append(model.isEmpty() ? "sorter" : model)
                .append(String.format(Locale.ROOT, " · %.1f s", latencyMs / 1000.0)).append('\n');
        sb.append(YapId.marker(captureId)).append('\n');
        for (SortAction a : plan) {
            sb.append("- ").append(describe(a, zone)).append('\n');
        }
        for (String d : dropped) {
            sb.append("- _Skipped an unsafe or invalid action: ").append(oneLine(d)).append("_\n");
        }
        return sb.toString();
    }

    /** Shadow mode: what the sorter would have done, with the content quoted. Nothing else is written. */
    public static String proposalBlock(String captureId, List<SortAction> plan, String model, long latencyMs,
            ZoneId zone, List<String> dropped) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## Sorter proposal · ").append(model.isEmpty() ? "sorter" : model)
                .append(String.format(Locale.ROOT, " · %.1f s", latencyMs / 1000.0)).append('\n');
        sb.append(YapId.marker(captureId)).append('\n');
        sb.append("_Shadow mode: nothing was written. Turn it off in Yapper to apply._\n");
        for (SortAction a : plan) {
            sb.append("- ").append(describe(a, zone)).append('\n');
            if (!a.markdown.isEmpty() && !SortAction.REMINDER.equals(a.tool)) {
                for (String l : a.markdown.trim().split("\n")) {
                    sb.append("    > ").append(l).append('\n');
                }
            }
        }
        for (String d : dropped) {
            sb.append("- _Invalid action: ").append(oneLine(d)).append("_\n");
        }
        return sb.toString();
    }

    public static String failedBlock(String captureId, String reason) {
        return "\n## Sorting failed\n" + YapId.marker(captureId) + "\n_" + oneLine(reason)
                + "_ Use \"Retry unfinished captures\" in Yapper.\n";
    }

    public static String undoneBlock(String captureId, int removed, int kept) {
        return "\n## Undone\n" + YapId.marker(captureId) + "\n_Removed " + plural(removed, "block")
                + (kept > 0 ? "; kept " + plural(kept, "block") + " you edited" : "") + "._\n";
    }

    static String describe(SortAction a, ZoneId zone) {
        switch (a.tool) {
            case SortAction.APPEND:
                return "Added to " + link(a.path) + (a.heading.isEmpty() ? "" : " (" + a.heading + ")");
            case SortAction.CREATE:
                return "New note " + link(a.path);
            case SortAction.DAILY:
                return a.heading + " in " + link(Executor.dailyPath(a.date));
            case SortAction.REMINDER: {
                String when = a.remindAt;
                try {
                    when = OffsetDateTime.parse(a.remindAt).atZoneSameInstant(zone).format(WHEN);
                } catch (RuntimeException ignored) {
                    // keep raw
                }
                return "Reminder " + when + ": " + a.title;
            }
            case SortAction.ASK:
                return "Question in " + link(Executor.INBOX_NOTE) + ": " + a.question;
            case SortAction.PLAN: {
                StringBuilder sb = new StringBuilder("Plan in ").append(link(Executor.dailyPath(a.date))).append(": ");
                for (int i = 0; i < a.blocks.size(); i++) {
                    SortAction.Block b = a.blocks.get(i);
                    sb.append(i == 0 ? "" : ", ").append(b.start.isEmpty() ? "" : b.start + " ").append(b.title);
                }
                return sb.toString();
            }
            case SortAction.CHECK:
                return "Done ✓ " + a.title;
            default:
                return a.tool;
        }
    }

    static String link(String path) {
        return "[[" + path.replaceAll("\\.md$", "") + "]]";
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    private static String oneLine(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").replace("_", "\\_").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
