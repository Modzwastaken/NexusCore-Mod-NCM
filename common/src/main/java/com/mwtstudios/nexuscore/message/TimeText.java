package com.mwtstudios.nexuscore.message;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Human-facing time rendering for the message catalogue.
 *
 * <p>Two distinct jobs live in this project and must not be merged. {@code DurationParser}
 * owns the <em>input</em> side: the compact {@code 1d12h30m} form operators type, with its
 * strict ordering rules. This class owns the <em>output</em> side: the long form players read
 * in messages — {@code 1h. 59min. 59sec.} — and calendar dates such as
 * {@code 17/04/2026 17:58}. Reusing the compact parser form in player-facing text was the
 * v1.0 shortcut; the message restyle replaces it everywhere a player reads a time.</p>
 *
 * <p>Dates render in the <b>server's</b> time zone. Players may live anywhere, but every date
 * NexusCore shows sits next to a relative countdown ("in 1h. 59min. 59sec.") that is
 * zone-independent, so the absolute date is a label rather than the only signal.</p>
 */
public final class TimeText {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** At most this many units appear in a long duration, matching "1h. 59min. 59sec.". */
    private static final int MAX_UNITS = 3;

    private TimeText() {
        // Utility holder.
    }

    /**
     * Renders an instant as a calendar date in the server's zone.
     *
     * @param epochMillis the instant
     * @return text such as {@code 17/04/2026 17:58}
     */
    public static String date(long epochMillis) {
        return date(epochMillis, ZoneId.systemDefault());
    }

    /**
     * Renders an instant as a calendar date in an explicit zone. The explicit overload exists
     * so tests are deterministic rather than dependent on the build machine's zone.
     *
     * @param epochMillis the instant
     * @param zone the zone to render in
     * @return text such as {@code 17/04/2026 17:58}
     */
    public static String date(long epochMillis, ZoneId zone) {
        return DATE.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
    }

    /**
     * Renders a duration in the long player-facing form.
     *
     * <p>Weeks are folded into days — a ban screen saying "9d." reads better than "1w. 2d." —
     * and only the {@value #MAX_UNITS} largest non-zero units are kept, so a long ban shows
     * {@code 12d. 4h. 30min.} rather than trailing seconds nobody needs.</p>
     *
     * @param duration the duration; negative is treated as zero
     * @return text such as {@code 1h. 59min. 59sec.}, or {@code 0sec.} for nothing
     */
    public static String longDuration(Duration duration) {
        long seconds = Math.max(0L, duration.getSeconds());
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;

        List<String> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(days + "d.");
        }
        if (hours > 0) {
            parts.add(hours + "h.");
        }
        if (minutes > 0) {
            parts.add(minutes + "min.");
        }
        if (secs > 0) {
            parts.add(secs + "sec.");
        }
        if (parts.isEmpty()) {
            return "0sec.";
        }
        return String.join(" ", parts.subList(0, Math.min(MAX_UNITS, parts.size())));
    }

    /**
     * Renders the time remaining until an expiry, in the long form.
     *
     * @param expiresAtEpochMillis the expiry, or {@link Long#MAX_VALUE} for permanent
     * @param nowEpochMillis the current time
     * @return {@code permanent}, {@code expired}, or text such as {@code 1h. 28min. 1sec.}
     */
    public static String remaining(long expiresAtEpochMillis, long nowEpochMillis) {
        if (expiresAtEpochMillis == Long.MAX_VALUE) {
            return "permanent";
        }
        long left = expiresAtEpochMillis - nowEpochMillis;
        return left <= 0 ? "expired" : longDuration(Duration.ofMillis(left));
    }

    /**
     * Renders how long ago an instant was, in the long form.
     *
     * @param thenEpochMillis the past instant
     * @param nowEpochMillis the current time
     * @return {@code just now} for under a second, otherwise text such as {@code 2min. 16sec.}
     */
    public static String elapsed(long thenEpochMillis, long nowEpochMillis) {
        long gone = nowEpochMillis - thenEpochMillis;
        return gone < 1_000L ? "just now" : longDuration(Duration.ofMillis(gone));
    }
}
