package com.ysulab.yapper.hub;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Input checks. Everything the hub stores passes through here first. */
final class Check {

    static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{1,120}");
    static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    static final Set<String> STATUSES = Set.of(Task.OPEN, Task.DONE, Task.UNSORTED);
    static final Set<String> SOURCES = Set.of("voice", "pen", "type", "vault");
    static final int MAX_TEXT = 2_000;
    static final int MAX_QUOTE = 4_000;
    static final int MAX_TRANSCRIPT = 400_000;
    static final int MAX_INK = 400_000;
    static final int MAX_SEAM = 8_000;
    static final int MAX_PAGE = 8_000_000;
    static final int MAX_ITEMS = 200;

    private Check() {}

    static ResponseStatusException bad(String why) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, why);
    }

    static String id(String s, String what) {
        if (s == null || !ID.matcher(s).matches()) {
            throw bad(what + " must be 1-120 of A-Z a-z 0-9 . _ : -");
        }
        return s;
    }

    static String day(String s) {
        if (s == null || !DAY.matcher(s).matches()) {
            throw bad("day must be YYYY-MM-DD");
        }
        try {
            LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw bad("day must be a real date");
        }
        return s;
    }

    static String text(String s, int max, String what) {
        if (s == null) {
            return "";
        }
        if (s.length() > max) {
            throw bad(what + " is longer than " + max + " characters");
        }
        return s;
    }

    static String dateTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw bad("date-times are ISO 8601 with an offset, e.g. 2026-11-26T23:59:00+03:00");
        }
        return s;
    }

    static String status(String s) {
        if (s == null || !STATUSES.contains(s)) {
            throw bad("status is open, done or unsorted");
        }
        return s;
    }

    static String src(String s) {
        if (s == null || s.isBlank()) {
            return "type";
        }
        if (!SOURCES.contains(s)) {
            throw bad("src is voice, pen, type or vault");
        }
        return s;
    }

    /** Handwriting: {w, h, s: [[x, y, ...], ...]} with numbers only. */
    static JsonNode ink(JsonNode ink) {
        if (ink == null || ink.isNull()) {
            return null;
        }
        if (!ink.isObject() || !ink.path("w").isNumber() || !ink.path("h").isNumber() || !ink.path("s").isArray()) {
            throw bad("ink is {w, h, s: [[x, y, ...]]}");
        }
        for (JsonNode stroke : ink.path("s")) {
            numbers(stroke, "ink stroke");
        }
        if (ink.toString().length() > MAX_INK) {
            throw bad("ink is too large");
        }
        return ink;
    }

    /** Strike line: flat [x, y, ...] in 0..1000 (a little outside is fine: the pen overshoots). */
    static JsonNode seam(JsonNode seam) {
        if (seam == null || seam.isNull()) {
            return null;
        }
        numbers(seam, "seam");
        if (seam.toString().length() > MAX_SEAM) {
            throw bad("seam is too long");
        }
        return seam;
    }

    static void numbers(JsonNode a, String what) {
        if (!a.isArray()) {
            throw bad(what + " must be an array of numbers");
        }
        for (JsonNode n : a) {
            if (!n.isNumber()) {
                throw bad(what + " must be an array of numbers");
            }
        }
    }
}
