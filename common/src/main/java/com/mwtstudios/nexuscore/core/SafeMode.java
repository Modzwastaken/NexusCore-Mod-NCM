package com.mwtstudios.nexuscore.core;

import java.util.List;
import java.util.Locale;

/**
 * Whether this server is starting with non-core modules disabled (§M1).
 *
 * <p><b>Triggered by a system property, deliberately, and not by a configuration key.</b> Safe mode
 * exists for the case where the server does not start properly, and a bad {@code config.json} is
 * one of the things it exists to recover from — so reading the decision out of that file would make
 * safe mode unavailable exactly when it is needed.</p>
 *
 * <p>The dependency direction settles it too. Safe mode decides which modules start, and
 * {@code ModuleManager.disable} must be called before {@code start}. A configuration key would mean
 * starting the configuration module to find out which modules to start, which is circular. A
 * property is readable before anything is wired.</p>
 *
 * <pre>
 * java -Dnexuscore.safemode=true -jar server.jar nogui
 * </pre>
 *
 * <p>Accepted values are {@code true} and {@code 1}, case-insensitively. Anything else — including
 * a typo like {@code -Dnexuscore.safemode=yes} — leaves safe mode off, which is why
 * {@link #describe()} is logged at startup: an operator who mistyped the flag needs to see that
 * safe mode is <em>not</em> on rather than assume it is.</p>
 */
public final class SafeMode {

    /** The system property that turns safe mode on. */
    public static final String PROPERTY = "nexuscore.safemode";

    /**
     * Modules left out in safe mode: every module {@code StandardModules} marks non-core.
     *
     * <p>Named explicitly rather than derived from {@code core()} so that the set is visible here
     * and in the log line. A module becoming optional should be a deliberate edit in two places
     * that a reviewer notices, not a silent consequence of one flag flipping.</p>
     */
    private static final List<String> DISABLED = List.of("teleport", "player-utilities", "moderation");

    private SafeMode() {
        // Static reader only.
    }

    /** @return true when the server was started with safe mode requested */
    public static boolean requested() {
        String raw = System.getProperty(PROPERTY, "");
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return "true".equals(value) || "1".equals(value);
    }

    /** @return the module ids safe mode leaves out */
    public static List<String> disabledModules() {
        return DISABLED;
    }

    /**
     * @return a line for the startup log, stating the mode either way. Silence on the "off" path
     *     would leave an operator who mistyped the flag with nothing to notice
     */
    public static String describe() {
        if (!requested()) {
            return "safe mode OFF (all modules enabled). Start with -D" + PROPERTY + "=true to disable "
                    + DISABLED + ".";
        }
        return "SAFE MODE is ON: " + DISABLED + " are not started. Their commands will refuse with an "
                + "explanation. Remove -D" + PROPERTY + " to start normally.";
    }
}
