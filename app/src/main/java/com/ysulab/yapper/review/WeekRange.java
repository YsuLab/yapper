package com.ysulab.yapper.review;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One Saudi week, Sunday to Saturday. The weekly review note for it is {@code Weekly/YYYY-Www.md},
 * numbered with Sunday-start weeks (week 1 holds 1 January). Pure Java, testable.
 */
public final class WeekRange {

    public static final String WEEKLY_DIR = "Weekly";
    static final WeekFields SAUDI = WeekFields.of(DayOfWeek.SUNDAY, 1);
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** Sunday. */
    public final LocalDate start;
    /** Saturday. */
    public final LocalDate end;

    private WeekRange(LocalDate start) {
        this.start = start;
        this.end = start.plusDays(6);
    }

    /** The week (Sunday to Saturday) that holds {@code day}. */
    public static WeekRange of(LocalDate day) {
        return new WeekRange(day.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)));
    }

    /**
     * The week a review made on {@code day} is about. Saturday is the review day. A review on Sunday or
     * Monday is a late one, so it is about the week that just ended; Tuesday to Saturday it is this week.
     */
    public static WeekRange forReview(LocalDate day) {
        DayOfWeek d = day.getDayOfWeek();
        WeekRange w = of(day);
        return d == DayOfWeek.SUNDAY || d == DayOfWeek.MONDAY ? w.previous() : w;
    }

    /** Next Saturday at {@code minuteOfDay} after now (today if it is Saturday and still ahead). */
    public static java.time.ZonedDateTime nextSaturday(java.time.ZonedDateTime now, int minuteOfDay) {
        java.time.ZonedDateTime t = now.toLocalDate().atStartOfDay(now.getZone()).plusMinutes(minuteOfDay);
        if (!t.isAfter(now.plusSeconds(30))) {
            t = t.plusDays(1);
        }
        while (t.getDayOfWeek() != DayOfWeek.SATURDAY) {
            t = t.plusDays(1);
        }
        return t;
    }

    public WeekRange previous() {
        return new WeekRange(start.minusWeeks(1));
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(start) && !day.isAfter(end);
    }

    public List<LocalDate> days() {
        List<LocalDate> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            out.add(start.plusDays(i));
        }
        return out;
    }

    /** "2026-W46". */
    public String id() {
        int year = end.get(SAUDI.weekBasedYear());
        int week = end.get(SAUDI.weekOfWeekBasedYear());
        return String.format(Locale.ROOT, "%d-W%02d", year, week);
    }

    public int number() {
        return end.get(SAUDI.weekOfWeekBasedYear());
    }

    public String notePath() {
        return WEEKLY_DIR + "/" + id() + ".md";
    }

    /** "8–14 Nov 2026", or "29 Nov – 5 Dec 2026" across months. */
    public String label() {
        String y = " " + end.getYear();
        if (start.getMonth() == end.getMonth()) {
            return start.getDayOfMonth() + "–" + end.format(DAY_MONTH) + y;
        }
        return start.format(DAY_MONTH) + (start.getYear() == end.getYear() ? "" : " " + start.getYear())
                + " – " + end.format(DAY_MONTH) + y;
    }
}
