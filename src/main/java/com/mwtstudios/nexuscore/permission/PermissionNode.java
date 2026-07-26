package com.mwtstudios.nexuscore.permission;

import java.util.Locale;

/**
 * A permission pattern and its specificity score (ADR-0004).
 *
 * <p>Patterns are validated at construction, not at evaluation. A wildcard is legal only as
 * the entire pattern ({@code *}) or as the final segment ({@code a.b.*}); {@code nexuscore.*.ban}
 * is refused outright. Mid-pattern wildcards make "longer path beats shorter" undefinable —
 * there is no defensible literal-segment count for a pattern with holes in the middle — so
 * rejecting them here means the ambiguity can never reach the resolver.</p>
 */
public record PermissionNode(String pattern, int specificity, boolean wildcard) implements Comparable<PermissionNode> {

    /** The universal pattern. Scores zero, so anything else beats it. */
    public static final String WILDCARD = "*";

    private static final String SEGMENT = "[a-z0-9_-]+";

    /**
     * Validates and scores a pattern.
     *
     * @param raw the pattern as written by an operator or a call site
     * @return the parsed node
     * @throws IllegalArgumentException if the pattern is malformed or uses a mid-pattern wildcard
     */
    public static PermissionNode of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("permission pattern is empty");
        }
        String pattern = raw.trim().toLowerCase(Locale.ROOT);

        if (WILDCARD.equals(pattern)) {
            return new PermissionNode(pattern, 0, true);
        }

        String[] segments = pattern.split("\\.", -1);
        boolean trailingWildcard = WILDCARD.equals(segments[segments.length - 1]);
        int literalCount = trailingWildcard ? segments.length - 1 : segments.length;

        if (literalCount == 0) {
            throw new IllegalArgumentException("permission pattern '" + raw + "' has no literal segments");
        }
        for (int i = 0; i < literalCount; i++) {
            if (!segments[i].matches(SEGMENT)) {
                throw new IllegalArgumentException("permission pattern '" + raw + "' has an invalid segment '"
                        + segments[i] + "'; a wildcard is only allowed as the whole pattern or the final segment, "
                        + "and segments must match " + SEGMENT);
            }
        }
        int specificity = trailingWildcard ? literalCount * 2 : literalCount * 2 + 1;
        return new PermissionNode(pattern, specificity, trailingWildcard);
    }

    /**
     * @param raw a pattern
     * @return true when {@link #of} would accept it
     */
    public static boolean isValid(String raw) {
        try {
            of(raw);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * @param request a concrete node being asked about, for example {@code nexuscore.command.moderation.ban}
     * @return true when this pattern covers that request
     */
    public boolean matches(String request) {
        String node = request.toLowerCase(Locale.ROOT);
        if (!wildcard) {
            return pattern.equals(node);
        }
        if (WILDCARD.equals(pattern)) {
            return true;
        }
        String prefix = pattern.substring(0, pattern.length() - 1);
        return node.startsWith(prefix);
    }

    /** Orders by specificity descending, then pattern, giving a total order. */
    @Override
    public int compareTo(PermissionNode other) {
        int bySpecificity = Integer.compare(other.specificity, specificity);
        return bySpecificity != 0 ? bySpecificity : pattern.compareTo(other.pattern);
    }

    @Override
    public String toString() {
        return pattern;
    }
}
