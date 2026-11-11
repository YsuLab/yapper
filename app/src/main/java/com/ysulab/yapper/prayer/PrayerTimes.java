package com.ysulab.yapper.prayer;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.chrono.HijrahDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * The five daily prayers (plus sunrise) for one day, computed on the phone with the Umm al-Qura method:
 * Fajr at 18.5° below the horizon, Asr at shadow length 1 (standard), Isha 90 minutes after Maghrib
 * (120 in Ramadan). No network. Pure Java, testable.
 *
 * <p>Sun position uses the low-precision US Naval Observatory formulas (the ones PrayTimes.org uses),
 * good to about a minute, which is the precision prayer tables are printed with.
 */
public final class PrayerTimes {

    public enum Prayer {
        FAJR("Fajr"), SUNRISE("Sunrise"), DHUHR("Dhuhr"), ASR("Asr"), MAGHRIB("Maghrib"), ISHA("Isha");

        public final String label;

        Prayer(String label) {
            this.label = label;
        }
    }

    /** Jeddah, the owner's city. */
    public static final double JEDDAH_LAT = 21.4858;
    public static final double JEDDAH_LON = 39.1925;
    public static final ZoneId SAUDI = ZoneId.of("Asia/Riyadh");

    static final double FAJR_ANGLE = 18.5;
    static final double RISE_SET_ANGLE = 0.833;
    static final int ISHA_MINUTES = 90;
    static final int ISHA_MINUTES_RAMADAN = 120;

    public final LocalDate date;
    private final ZonedDateTime[] times;

    private PrayerTimes(LocalDate date, ZonedDateTime[] times) {
        this.date = date;
        this.times = times;
    }

    public ZonedDateTime get(Prayer p) {
        return times[p.ordinal()];
    }

    public static PrayerTimes jeddah(LocalDate date) {
        return ummAlQura(date, JEDDAH_LAT, JEDDAH_LON, SAUDI);
    }

    public static PrayerTimes ummAlQura(LocalDate date, double lat, double lon, ZoneId zone) {
        double jd = julian(date) - lon / (15 * 24.0);
        // Hours in local solar time. Start from rough guesses and refine: the sun moves during the day.
        double fajr = 5, sunrise = 6, dhuhr = 12, asr = 13, sunset = 18;
        for (int i = 0; i < 2; i++) {
            fajr = sunAngleTime(jd, lat, FAJR_ANGLE, fajr / 24, true);
            sunrise = sunAngleTime(jd, lat, RISE_SET_ANGLE, sunrise / 24, true);
            dhuhr = midDay(jd, dhuhr / 24);
            asr = asrTime(jd, lat, 1, asr / 24);
            sunset = sunAngleTime(jd, lat, RISE_SET_ANGLE, sunset / 24, false);
        }
        ZonedDateTime[] t = new ZonedDateTime[6];
        t[0] = toZoned(date, fajr, lon, zone);
        t[1] = toZoned(date, sunrise, lon, zone);
        t[2] = toZoned(date, dhuhr, lon, zone);
        t[3] = toZoned(date, asr, lon, zone);
        t[4] = toZoned(date, sunset, lon, zone);
        t[5] = t[4].plusMinutes(isRamadan(date) ? ISHA_MINUTES_RAMADAN : ISHA_MINUTES);
        return new PrayerTimes(date, t);
    }

    /** Ramadan by the Umm al-Qura calendar, which is what {@link HijrahDate} implements. */
    static boolean isRamadan(LocalDate date) {
        try {
            return HijrahDate.from(date).get(ChronoField.MONTH_OF_YEAR) == 9;
        } catch (RuntimeException e) {
            return false; // outside the calendar's range
        }
    }

    /**
     * Prayer times for {@code days} days starting at {@code from}, as markdown lines for the sorter
     * prompt, e.g. {@code - Wed 2026-11-11: Fajr 04:58 · Sunrise 06:16 · … · Isha 18:56}.
     */
    public static String table(LocalDate from, int days) {
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < days; i++) {
            LocalDate d = from.plusDays(i);
            PrayerTimes p = jeddah(d);
            sb.append("- ").append(d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                    .append(' ').append(d).append(':');
            for (Prayer pr : Prayer.values()) {
                String label = pr == Prayer.DHUHR && d.getDayOfWeek() == DayOfWeek.FRIDAY
                        ? "Dhuhr (Jumuah)" : pr.label;
                sb.append(pr == Prayer.FAJR ? " " : " · ").append(label).append(' ')
                        .append(p.get(pr).format(hm));
            }
            if (isRamadan(d)) {
                sb.append(" (Ramadan)");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---- astronomy (angles in degrees) ----

    /** Julian day at 0h UT of the date. */
    static double julian(LocalDate date) {
        int y = date.getYear();
        int m = date.getMonthValue();
        int d = date.getDayOfMonth();
        if (m <= 2) {
            y -= 1;
            m += 12;
        }
        double a = Math.floor(y / 100.0);
        double b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

    /** {declination in degrees, equation of time in hours} at Julian day jd. */
    static double[] sunPosition(double jd) {
        double d = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * d);
        double q = fixAngle(280.459 + 0.98564736 * d);
        double l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g));
        double e = 23.439 - 0.00000036 * d;
        double ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15;
        double eqt = q / 15 - fixHour(ra);
        double decl = darcsin(dsin(e) * dsin(l));
        return new double[] {decl, eqt};
    }

    static double midDay(double jd, double dayFraction) {
        double eqt = sunPosition(jd + dayFraction)[1];
        return fixHour(12 - eqt);
    }

    static double sunAngleTime(double jd, double lat, double angle, double dayFraction, boolean beforeNoon) {
        double decl = sunPosition(jd + dayFraction)[0];
        double noon = midDay(jd, dayFraction);
        double t = darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))) / 15;
        return noon + (beforeNoon ? -t : t);
    }

    static double asrTime(double jd, double lat, double shadowFactor, double dayFraction) {
        double decl = sunPosition(jd + dayFraction)[0];
        double angle = -darccot(shadowFactor + dtan(Math.abs(lat - decl)));
        return sunAngleTime(jd, lat, angle, dayFraction, false);
    }

    /** Local solar hours at longitude lon → wall-clock time in zone, rounded to the nearest minute. */
    static ZonedDateTime toZoned(LocalDate date, double localSolarHours, double lon, ZoneId zone) {
        double utcHours = localSolarHours - lon / 15;
        long seconds = Math.round(utcHours * 3600);
        long minutes = Math.floorDiv(seconds + 30, 60);
        return date.atStartOfDay(ZoneOffset.UTC).plusMinutes(minutes).withZoneSameInstant(zone);
    }

    private static double dsin(double d) {
        return Math.sin(Math.toRadians(d));
    }

    private static double dcos(double d) {
        return Math.cos(Math.toRadians(d));
    }

    private static double dtan(double d) {
        return Math.tan(Math.toRadians(d));
    }

    private static double darcsin(double x) {
        return Math.toDegrees(Math.asin(x));
    }

    private static double darccos(double x) {
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, x))));
    }

    private static double darctan2(double y, double x) {
        return Math.toDegrees(Math.atan2(y, x));
    }

    private static double darccot(double x) {
        return Math.toDegrees(Math.atan(1 / x));
    }

    private static double fixAngle(double a) {
        return a - 360 * Math.floor(a / 360);
    }

    private static double fixHour(double h) {
        return h - 24 * Math.floor(h / 24);
    }
}
