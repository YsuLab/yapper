package com.ysulab.yapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Random;
import org.junit.Test;

public class YapIdTest {

    private static final Instant T = Instant.parse("2026-11-09T11:03:49Z");

    @Test
    public void generatesUtcStampAndSuffix() {
        String id = YapId.generate(T, new Random(1));
        assertTrue(id, id.startsWith("20261109-110349-"));
        assertTrue(id, YapId.isValid(id));
    }

    @Test
    public void sameSeedSameId() {
        assertEquals(YapId.generate(T, new Random(7)), YapId.generate(T, new Random(7)));
    }

    @Test
    public void idsSortByTime() {
        String a = YapId.generate(T, new Random(1));
        String b = YapId.generate(T.plusSeconds(1), new Random(1));
        assertTrue(a.compareTo(b) < 0);
    }

    @Test
    public void rejectsBadIds() {
        assertFalse(YapId.isValid(null));
        assertFalse(YapId.isValid("hello"));
        assertFalse(YapId.isValid("20261109-110349-ABCD"));
    }

    @Test
    public void markerIsObsidianComment() {
        assertEquals("%%yap:20261109-110349-ab12%%", YapId.marker("20261109-110349-ab12"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void markerRejectsBadId() {
        YapId.marker("nope");
    }
}
