package com.mwtstudios.nexuscore.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The §12.4 ambiguity matrix. One parser, one set of rules, tested at the edges. */
class DurationParserTest {

    @ParameterizedTest(name = "{0} = {1}s")
    @CsvSource({
            "30s, 30",
            "15m, 900",
            "2h, 7200",
            "7d, 604800",
            "1w, 604800",
            "1d12h, 129600",
            "1d12h30m, 131400",
            "1w2d3h4m5s, 788645"
    })
    @DisplayName("valid durations parse to the right number of seconds")
    void validDurations(String input, long expectedSeconds) {
        DurationParser.ParsedDuration parsed = DurationParser.parse(input);

        assertFalse(parsed.permanent());
        assertEquals(expectedSeconds, parsed.duration().getSeconds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"permanent", "perm", "forever", "never", "infinite", "PERMANENT", " Forever "})
    @DisplayName("permanent keywords are recognised, case and space insensitively")
    void permanentKeywords(String input) {
        assertTrue(DurationParser.parse(input).permanent());
        assertEquals(Long.MAX_VALUE, DurationParser.parse(input).expiresAt(1_000L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"30m1d", "1h1h", "5s10m", "1s1w", "30s30s"})
    @DisplayName("units out of descending order, or repeated, are rejected")
    void ascendingOrRepeatedUnitsRejected(String input) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DurationParser.parse(input));
        assertTrue(error.getMessage().contains("descending"),
                "the message must tell the operator what the correct form is, got: " + error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5m", "1,5m", "0.5h"})
    @DisplayName("fractions are rejected with a message suggesting the whole-unit form")
    void fractionsRejected(String input) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DurationParser.parse(input));
        assertTrue(error.getMessage().contains("fractional"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-30m", "-1d"})
    @DisplayName("negative durations are rejected")
    void negativesRejected(String input) {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "30", "abc", "m30", "30x", "30 s", "s", "30sec", "1y"})
    @DisplayName("malformed input is rejected rather than guessed at")
    void malformedRejected(String input) {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(input));
    }

    @Test
    @DisplayName("a null duration is rejected")
    void nullRejected() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(null));
    }

    @Test
    @DisplayName("zero is rejected: a ban of no length is a typo, not an intention")
    void zeroRejected() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("0s"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("0d"));
    }

    @Test
    @DisplayName("the ten-year cap is enforced at its exact boundary")
    void tenYearCapEnforced() {
        assertEquals(Duration.ofDays(3650), DurationParser.parse("3650d").duration());
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("3651d"));
        // 521w is 3647 days and legitimately under the cap; 522w is 3654 days and over it.
        assertEquals(Duration.ofDays(3647), DurationParser.parse("521w").duration());
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("522w"));
    }

    @Test
    @DisplayName("a number too large to hold is rejected, not silently wrapped")
    void overflowRejected() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("99999999999999999999d"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("9223372036854775807w"));
    }

    @Test
    @DisplayName("format is the inverse of parse for values the parser accepts")
    void formatRoundTrips() {
        for (String input : new String[]{"30s", "15m", "2h", "7d", "1d12h30m"}) {
            Duration parsed = DurationParser.parse(input).duration();
            assertEquals(parsed, DurationParser.parse(DurationParser.format(parsed)).duration(),
                    "format/parse must round-trip for " + input);
        }
    }

    @Test
    @DisplayName("format prefers the largest unit, and the result still parses")
    void formatPrefersLargestUnit() {
        assertEquals("1w", DurationParser.format(Duration.ofDays(7)));
        assertEquals("1w1d", DurationParser.format(Duration.ofDays(8)));
        assertEquals(Duration.ofDays(7), DurationParser.parse(DurationParser.format(Duration.ofDays(7))).duration());
    }

    @Test
    @DisplayName("leading and trailing whitespace is trimmed rather than rejected")
    void whitespaceIsTrimmed() {
        assertEquals(30L, DurationParser.parse("  30s  ").duration().getSeconds());
    }

    @Test
    @DisplayName("expiresAt adds the duration to the supplied instant")
    void expiresAtIsRelativeToNow() {
        assertEquals(1_000L + 30_000L, DurationParser.parse("30s").expiresAt(1_000L));
    }

    @Test
    @DisplayName("describeElapsed renders how long ago something was")
    void describeElapsed() {
        assertEquals("just now", DurationParser.describeElapsed(1_000L, 1_000L));
        assertEquals("just now", DurationParser.describeElapsed(1_000L, 1_500L));
        assertEquals("30s", DurationParser.describeElapsed(1_000L, 31_000L));
        assertEquals("1d", DurationParser.describeElapsed(0L, 86_400_000L));
        assertEquals("1w2d", DurationParser.describeElapsed(0L, 777_600_000L));
    }

    @Test
    @DisplayName("formatApproximate keeps at most the two largest units, and stops at a gap")
    void formatApproximateIsReadable() {
        // The exact form of this duration is 52w2d6h37m8s, which is not a useful answer to
        // "when were they last seen".
        Duration long1 = Duration.ofSeconds(52L * 7 * 86400 + 2 * 86400 + 6 * 3600 + 37 * 60 + 8);
        assertEquals("52w2d", DurationParser.formatApproximate(long1));
        assertEquals("1d12h", DurationParser.formatApproximate(Duration.ofHours(36)));
        assertEquals("30s", DurationParser.formatApproximate(Duration.ofSeconds(30)));
        assertEquals("0s", DurationParser.formatApproximate(Duration.ZERO));
        // A gap stops the rendering rather than skipping to a much smaller unit.
        assertEquals("1w", DurationParser.formatApproximate(Duration.ofDays(7).plusSeconds(5)));
    }

    @Test
    @DisplayName("describeElapsed does not produce a negative for a clock that moved backwards")
    void describeElapsedHandlesBackwardsClock() {
        assertEquals("just now", DurationParser.describeElapsed(10_000L, 1_000L));
    }

    @Test
    @DisplayName("describeRemaining distinguishes permanent, expired, and remaining")
    void describeRemaining() {
        assertEquals("permanent", DurationParser.describeRemaining(Long.MAX_VALUE, 0L));
        assertEquals("expired", DurationParser.describeRemaining(500L, 1_000L));
        assertEquals("expired", DurationParser.describeRemaining(1_000L, 1_000L));
        assertEquals("30s", DurationParser.describeRemaining(31_000L, 1_000L));
    }

    @Test
    @DisplayName("regression: a sub-second remainder renders as 1s, not as empty text")
    void subSecondRemainderIsNotEmpty() {
        // A market listing or kit cooldown in its last second used to render "" — a blank where
        // a time should be, on the row an operator is most likely to be watching.
        assertEquals("1s", DurationParser.format(java.time.Duration.ofMillis(500)));
        assertEquals("1s", DurationParser.describeRemaining(1_000_500L, 1_000_000L));
    }

    @Test
    @DisplayName("the boundaries around one second stay exact")
    void subSecondBoundaries() {
        assertEquals("expired", DurationParser.describeRemaining(1_000_000L, 1_000_000L));
        assertEquals("1s", DurationParser.describeRemaining(1_001_000L, 1_000_000L));
        assertEquals("0s", DurationParser.format(java.time.Duration.ZERO));
    }
}
