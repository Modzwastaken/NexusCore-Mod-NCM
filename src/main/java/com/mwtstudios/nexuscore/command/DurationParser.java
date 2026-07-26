package com.mwtstudios.nexuscore.command;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one duration parser for the entire project (§12.4).
 *
 * <p>There is exactly one, because two parsers eventually disagree, and a ban that lasts a
 * different length depending on which code path parsed it is a real incident.</p>
 *
 * <p>Accepts {@code 30s}, {@code 15m}, {@code 2h}, {@code 7d}, {@code 1w}, combinations in
 * <em>strictly descending</em> unit order such as {@code 1d12h30m}, and a permanent keyword.</p>
 *
 * <p>Rejects, deliberately and with a message saying why: ascending or repeated units
 * ({@code 30m1d}, {@code 1h1h}), fractions, negatives, an empty string, a bare number with no
 * unit, and anything that overflows. Finite durations are capped at ten years — a "temporary"
 * ban measured in centuries is a typo, not an intention.</p>
 */
public final class DurationParser {

    /** Words that mean "no expiry". */
    public static final Set<String> PERMANENT_KEYWORDS = Set.of("permanent", "perm", "forever", "never", "infinite");

    /** The longest finite duration accepted. */
    public static final Duration MAXIMUM = Duration.ofDays(3650);

    private static final Pattern TERM = Pattern.compile("(\\d+)([smhdw])");
    private static final Pattern WHOLE = Pattern.compile("(\\d+[smhdw])+");

    /** Units in descending magnitude. The index is what enforces descending order. */
    private static final Map<Character, Duration> UNITS = new LinkedHashMap<>();
    private static final String UNIT_ORDER = "wdhms";

    static {
        UNITS.put('w', Duration.ofDays(7));
        UNITS.put('d', Duration.ofDays(1));
        UNITS.put('h', Duration.ofHours(1));
        UNITS.put('m', Duration.ofMinutes(1));
        UNITS.put('s', Duration.ofSeconds(1));
    }

    private DurationParser() {
        // Utility holder.
    }

    /**
     * Parses a duration.
     *
     * @param input the text as typed
     * @return the parsed result
     * @throws IllegalArgumentException with an operator-readable reason if the input is not accepted
     */
    public static ParsedDuration parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("a duration is required, for example 30m, 7d, or 'permanent'");
        }
        String text = input.trim().toLowerCase(Locale.ROOT);

        if (PERMANENT_KEYWORDS.contains(text)) {
            return new ParsedDuration(true, Duration.ZERO);
        }
        if (text.startsWith("-")) {
            throw new IllegalArgumentException("a duration cannot be negative: '" + input + "'");
        }
        if (text.contains(".") || text.contains(",")) {
            throw new IllegalArgumentException("fractional durations are not accepted: '" + input + "'; use 90s rather than 1.5m");
        }
        if (!WHOLE.matcher(text).matches()) {
            throw new IllegalArgumentException("'" + input + "' is not a duration; use a number and a unit such as "
                    + "30s, 15m, 2h, 7d, 1w, or a combination in descending order such as 1d12h");
        }

        Duration total = Duration.ZERO;
        int previousUnitIndex = -1;
        Matcher matcher = TERM.matcher(text);

        while (matcher.find()) {
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("the number in '" + matcher.group() + "' is too large", e);
            }
            char unit = matcher.group(2).charAt(0);
            int unitIndex = UNIT_ORDER.indexOf(unit);

            if (unitIndex <= previousUnitIndex) {
                throw new IllegalArgumentException("units must appear once, in descending order: '" + input
                        + "' repeats or reverses a unit; write 1d12h30m, not 30m1d");
            }
            previousUnitIndex = unitIndex;

            try {
                total = total.plus(UNITS.get(unit).multipliedBy(amount));
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("'" + input + "' is too long to represent", e);
            }
            if (total.compareTo(MAXIMUM) > 0) {
                throw new IllegalArgumentException("'" + input + "' exceeds the ten-year maximum for a finite duration");
            }
        }

        if (total.isZero()) {
            throw new IllegalArgumentException("a duration of zero is not accepted: '" + input + "'");
        }
        return new ParsedDuration(false, total);
    }

    /**
     * Renders a duration the way this parser would accept it.
     *
     * @param duration the duration
     * @return text such as {@code 1d12h30m}
     */
    public static String format(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return "0s";
        }
        long seconds = duration.getSeconds();
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Character, Duration> unit : UNITS.entrySet()) {
            long unitSeconds = unit.getValue().getSeconds();
            long count = seconds / unitSeconds;
            if (count > 0) {
                text.append(count).append(unit.getKey());
                seconds -= count * unitSeconds;
            }
        }
        return text.toString();
    }

    /**
     * Renders the time remaining until an expiry instant.
     *
     * @param expiresAtEpochMillis the expiry, or {@link Long#MAX_VALUE} for permanent
     * @param nowEpochMillis the current time
     * @return {@code permanent}, {@code expired}, or a formatted remainder
     */
    public static String describeRemaining(long expiresAtEpochMillis, long nowEpochMillis) {
        if (expiresAtEpochMillis == Long.MAX_VALUE) {
            return "permanent";
        }
        long remaining = expiresAtEpochMillis - nowEpochMillis;
        return remaining <= 0 ? "expired" : format(Duration.ofMillis(remaining));
    }

    /**
     * A successfully parsed duration.
     *
     * @param permanent true when the input named no expiry at all
     * @param duration the finite length; {@link Duration#ZERO} when permanent
     */
    public record ParsedDuration(boolean permanent, Duration duration) {

        /**
         * @param nowEpochMillis the current time
         * @return the expiry instant, or {@link Long#MAX_VALUE} when permanent
         */
        public long expiresAt(long nowEpochMillis) {
            if (permanent) {
                return Long.MAX_VALUE;
            }
            return Math.addExact(nowEpochMillis, duration.toMillis());
        }

        @Override
        public String toString() {
            return permanent ? "permanent" : format(duration);
        }
    }
}
