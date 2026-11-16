package com.ysulab.yapper.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import org.junit.Test;

public class WeekRangeTest {

    @Test
    public void weekRunsSundayToSaturday() {
        WeekRange w = WeekRange.of(LocalDate.of(2026, 11, 11)); // Wednesday
        assertEquals(LocalDate.of(2026, 11, 8), w.start);
        assertEquals(LocalDate.of(2026, 11, 14), w.end);
        assertTrue(w.contains(LocalDate.of(2026, 11, 14)));
        assertFalse(w.contains(LocalDate.of(2026, 11, 15)));
        assertEquals(7, w.days().size());
        assertEquals("8–14 Nov 2026", w.label());
        assertEquals("Weekly/2026-W46.md", w.notePath());
    }

    @Test
    public void reviewOnSaturdayIsThisWeekOnSundayOrMondayLastWeek() {
        LocalDate sat = LocalDate.of(2026, 11, 14);
        assertEquals(LocalDate.of(2026, 11, 8), WeekRange.forReview(sat).start);
        assertEquals(LocalDate.of(2026, 11, 8), WeekRange.forReview(sat.plusDays(1)).start); // Sunday: late
        assertEquals(LocalDate.of(2026, 11, 8), WeekRange.forReview(sat.plusDays(2)).start); // Monday: late
        assertEquals(LocalDate.of(2026, 11, 15), WeekRange.forReview(sat.plusDays(3)).start); // Tuesday
    }

    @Test
    public void saturdayNudgeTime() {
        java.time.ZoneId z = java.time.ZoneId.of("Asia/Riyadh");
        java.time.ZonedDateTime wed = java.time.ZonedDateTime.of(2026, 11, 11, 13, 0, 0, 0, z);
        assertEquals("2026-11-14T10:00+03:00[Asia/Riyadh]", WeekRange.nextSaturday(wed, 600).toString());
        java.time.ZonedDateTime satEarly = java.time.ZonedDateTime.of(2026, 11, 14, 9, 0, 0, 0, z);
        assertEquals("2026-11-14T10:00+03:00[Asia/Riyadh]", WeekRange.nextSaturday(satEarly, 600).toString());
        java.time.ZonedDateTime satLate = java.time.ZonedDateTime.of(2026, 11, 14, 11, 0, 0, 0, z);
        assertEquals("2026-11-21T10:00+03:00[Asia/Riyadh]", WeekRange.nextSaturday(satLate, 600).toString());
    }

    @Test
    public void yearBoundary() {
        WeekRange w = WeekRange.of(LocalDate.of(2026, 12, 31));
        assertEquals("2027-W01", w.id()); // the week holding 1 January is week 1
        assertEquals("27 Dec 2026 – 2 Jan 2027", w.label());
        assertEquals("2026-W52", w.previous().id());
        assertEquals("29 Nov – 5 Dec 2026", WeekRange.of(LocalDate.of(2026, 12, 1)).label());
    }
}
