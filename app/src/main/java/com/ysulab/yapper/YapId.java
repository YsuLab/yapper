package com.ysulab.yapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Short, time-sortable id for captures and written blocks.
 *
 * <p>Format: {@code yyyyMMdd-HHmmss-xxxx} in UTC, where {@code xxxx} is 4 random base-36 chars.
 * Used later in the vault as {@code %%yap:<id>%%} so Undo can find exactly the block it wrote.
 */
public final class YapId {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final Pattern VALID = Pattern.compile("\\d{8}-\\d{6}-[0-9a-z]{4}");

    private YapId() {}

    public static String generate(Instant now, Random random) {
        StringBuilder sb = new StringBuilder(STAMP.format(now)).append('-');
        for (int i = 0; i < 4; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static boolean isValid(String id) {
        return id != null && VALID.matcher(id).matches();
    }

    /** The hidden Obsidian comment that tags a written block. */
    public static String marker(String id) {
        if (!isValid(id)) {
            throw new IllegalArgumentException("Invalid yap id: " + id);
        }
        return "%%yap:" + id + "%%";
    }
}
