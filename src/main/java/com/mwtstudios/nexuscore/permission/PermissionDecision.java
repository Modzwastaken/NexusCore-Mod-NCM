package com.mwtstudios.nexuscore.permission;

/**
 * The result of one permission evaluation, carrying enough context to explain itself.
 *
 * <p>§9.1 requires a command that <em>explains</em> a decision rather than merely reporting
 * it. An operator debugging "why can this moderator still ban" needs the matched pattern and
 * where it came from, not a boolean.</p>
 *
 * @param node the request that was evaluated
 * @param result the outcome
 * @param matchedPattern the pattern that decided it, or null when nothing matched
 * @param source where that pattern came from, for example {@code direct} or {@code group:moderator}
 * @param explanation a sentence an operator can act on
 */
public record PermissionDecision(String node, Result result, String matchedPattern, String source, String explanation) {

    /** Possible outcomes. */
    public enum Result {
        /** A matching pattern granted it. */
        ALLOW,
        /** A matching pattern explicitly denied it. */
        DENY,
        /**
         * Nothing matched. Never treated as granted — it exists only so the explain command
         * can distinguish "explicitly denied" from "never granted", which need different fixes.
         */
        UNDEFINED
    }

    /** @return true only for {@link Result#ALLOW} */
    public boolean allowed() {
        return result == Result.ALLOW;
    }

    /**
     * Builds the decision for a request that nothing matched.
     *
     * @param node the request
     * @return an UNDEFINED decision
     */
    public static PermissionDecision undefined(String node) {
        return new PermissionDecision(node, Result.UNDEFINED, null, null,
                "no group or direct grant matches '" + node + "', so it is not granted (default deny)");
    }

    /**
     * Builds the decision for a source that bypasses evaluation entirely.
     *
     * @param node the request
     * @param why for example {@code console is root}
     * @return an ALLOW decision naming the bypass
     */
    public static PermissionDecision root(String node, String why) {
        return new PermissionDecision(node, Result.ALLOW, PermissionNode.WILDCARD, why,
                "granted because " + why);
    }
}
