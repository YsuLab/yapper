package com.ysulab.yapper.prayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ysulab.yapper.prayer.PrayerTimes.Prayer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.Test;

public class PrayerTimesTest {

    /** Published Umm al-Qura table for Jeddah (Fajr 18.5°, Isha 90 min), November 2026. */
    private static final String[][] JEDDAH_NOV_2026 = {
        {"2026-11-01", "05:08", "06:26", "12:07", "15:23", "17:48", "19:18"},
        {"2026-11-06", "05:10", "06:28", "12:07", "15:21", "17:45", "19:15"},
        {"2026-11-11", "05:13", "06:31", "12:07", "15:19", "17:43", "19:13"},
        {"2026-11-15", "05:15", "06:34", "12:08", "15:19", "17:42", "19:12"},
        {"2026-11-30", "05:23", "06:43", "12:12", "15:18", "17:40", "19:10"},
    };

    @Test
    public void matchesPublishedJeddahTableWithinOneMinute() {
        for (String[] row : JEDDAH_NOV_2026) {
            PrayerTimes p = PrayerTimes.jeddah(LocalDate.parse(row[0]));
            for (Prayer pr : Prayer.values()) {
                LocalTime want = LocalTime.parse(row[1 + pr.ordinal()]);
                LocalTime got = p.get(pr).toLocalTime();
                long diff = Math.abs(Duration.between(want, got).toMinutes());
                assertTrue(row[0] + " " + pr + ": want " + want + ", got " + got, diff <= 1);
            }
        }
    }

    @Test
    public void timesAreInSaudiTimeAndInOrder() {
        PrayerTimes p = PrayerTimes.jeddah(LocalDate.of(2026, 6, 21));
        assertEquals(PrayerTimes.SAUDI, p.get(Prayer.FAJR).getZone());
        for (int i = 1; i < Prayer.values().length; i++) {
            assertTrue(p.get(Prayer.values()[i - 1]).isBefore(p.get(Prayer.values()[i])));
        }
        assertEquals(LocalDate.of(2026, 6, 21), p.get(Prayer.ISHA).toLocalDate());
    }

    @Test
    public void ishaIs90MinutesAfterMaghribAnd120InRamadan() {
        PrayerTimes normal = PrayerTimes.jeddah(LocalDate.of(2026, 11, 11));
        assertEquals(90, Duration.between(normal.get(Prayer.MAGHRIB), normal.get(Prayer.ISHA)).toMinutes());
        // Ramadan 1448 starts around 2027-02-08 in the Umm al-Qura calendar; the 20th is well inside.
        LocalDate ramadan = LocalDate.of(2027, 2, 20);
        assertTrue(PrayerTimes.isRamadan(ramadan));
        PrayerTimes r = PrayerTimes.jeddah(ramadan);
        assertEquals(120, Duration.between(r.get(Prayer.MAGHRIB), r.get(Prayer.ISHA)).toMinutes());
        assertFalse(PrayerTimes.isRamadan(LocalDate.of(2026, 11, 11)));
    }

    @Test
    public void tableHasOneLinePerDayAndMarksJumuah() {
        String t = PrayerTimes.table(LocalDate.of(2026, 11, 11), 8);
        String[] lines = t.trim().split("\n");
        assertEquals(8, lines.length);
        assertTrue(lines[0], lines[0].startsWith("- Wed 2026-11-11: Fajr 05:1"));
        assertTrue(lines[0], lines[0].contains("· Asr 15:"));
        assertTrue(lines[2], lines[2].startsWith("- Fri 2026-11-13:"));
        assertTrue(lines[2], lines[2].contains("Dhuhr (Jumuah) 12:0"));
        assertFalse(lines[0].contains("Jumuah"));
    }
}
