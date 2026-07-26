package com.mwtstudios.nexuscore.message;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The long, player-facing time forms. Dates are tested against an explicit UTC zone so the
 * result does not depend on which machine runs the build.
 */
class TimeTextTest {

    @Test
    @DisplayName("long durations render as the screenshot style: 1h. 59min. 59sec.")
    void longDurationStyle() {
        assertEquals("1h. 59min. 59sec.", TimeText.longDuration(Duration.ofSeconds(3600 + 59 * 60 + 59)));
        assertEquals("2h.", TimeText.longDuration(Duration.ofHours(2)));
        assertEquals("2min. 16sec.", TimeText.longDuration(Duration.ofSeconds(136)));
        assertEquals("45sec.", TimeText.longDuration(Duration.ofSeconds(45)));
    }

    @Test
    @DisplayName("weeks fold into days, and at most three units are shown")
    void weeksFoldAndUnitsCap() {
        assertEquals("9d.", TimeText.longDuration(Duration.ofDays(9)));
        assertEquals("12d. 4h. 30min.", TimeText.longDuration(
                Duration.ofDays(12).plusHours(4).plusMinutes(30).plusSeconds(15)),
                "the trailing seconds must be dropped once three units are shown");
    }

    @Test
    @DisplayName("zero and negative durations render as 0sec. rather than blank or negative")
    void zeroAndNegative() {
        assertEquals("0sec.", TimeText.longDuration(Duration.ZERO));
        assertEquals("0sec.", TimeText.longDuration(Duration.ofSeconds(-30)));
    }

    @Test
    @DisplayName("remaining distinguishes permanent, expired, and a live countdown")
    void remaining() {
        assertEquals("permanent", TimeText.remaining(Long.MAX_VALUE, 0L));
        assertEquals("expired", TimeText.remaining(1_000L, 1_000L));
        assertEquals("expired", TimeText.remaining(500L, 1_000L));
        assertEquals("1h. 28min. 1sec.", TimeText.remaining(1_000L + (3600 + 28 * 60 + 1) * 1000L, 1_000L));
    }

    @Test
    @DisplayName("elapsed says 'just now' under a second and the long form after")
    void elapsed() {
        assertEquals("just now", TimeText.elapsed(1_000L, 1_500L));
        assertEquals("2min. 16sec.", TimeText.elapsed(0L, 136_000L));
        assertEquals("just now", TimeText.elapsed(10_000L, 1_000L),
                "a backwards clock must not render a negative elapsed time");
    }

    @Test
    @DisplayName("dates render as dd/MM/yyyy HH:mm in the requested zone")
    void dateFormat() {
        // 2026-04-17 17:58:00 UTC
        long instant = 1_776_448_680_000L;
        assertEquals("17/04/2026 17:58", TimeText.date(instant, ZoneOffset.UTC));
    }
}
