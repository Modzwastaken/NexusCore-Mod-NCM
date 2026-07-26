package com.mwtstudios.nexuscore.config;

/**
 * The NexusCore server settings document.
 *
 * <p>Plain mutable fields so Gson can populate it from a partially-specified file: a key the
 * operator did not write keeps its default rather than becoming {@code null}. Unknown keys
 * the operator <em>did</em> write are preserved on disk by the read-modify-write cycle in
 * {@link ConfigurationService} only insofar as they are re-serialised from this class, so
 * anything genuinely unknown is reported rather than silently wiped.</p>
 *
 * <p>{@link #validate(String)} clamps every field into its supported range and reports what it
 * changed. Nothing else in the codebase may assume a value is sane without having called it.</p>
 */
public final class NexusSettings {

    /** Current schema version of this document. Bumping it requires a migration (§11.4). */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** File name under the NexusCore data directory. */
    public static final String FILE = "config.json";

    // ---- document metadata -------------------------------------------------------------

    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public String generatedByVersion = "";

    // ---- permissions -------------------------------------------------------------------

    /** Group assigned to a player with no explicit membership. */
    public String defaultGroup = "default";

    /**
     * When true, a server operator (vanilla op level 4) is granted {@code nexuscore.bootstrap.*}
     * so they can create the first real group. §9.1 requires this be switchable off, and it
     * is not a permanent hidden bypass — turn it off once real groups exist.
     */
    public boolean operatorBootstrap = true;

    /** Maximum cached permission decisions. Bounded, per §16 ("no unbounded UUID or name maps"). */
    public int permissionCacheSize = 4096;

    // ---- teleport ----------------------------------------------------------------------

    /** Seconds a player must stand still before a teleport commits. 0 disables the warmup. */
    public int teleportWarmupSeconds = 3;

    /** Seconds before the same player may teleport again. */
    public int teleportCooldownSeconds = 5;

    /** Seconds a {@code /tpa} request stays open before it expires. */
    public int teleportRequestTimeoutSeconds = 120;

    /** Cancel a pending warmup if the player moves further than this many blocks. */
    public double teleportWarmupMoveTolerance = 0.5;

    /** Maximum homes per player, before per-group overrides. */
    public int maxHomesPerPlayer = 5;

    /** Vertical search range when looking for safe ground at a destination. */
    public int safeDestinationSearchHeight = 8;

    // ---- moderation --------------------------------------------------------------------

    /** Broadcast bans, kicks, and mutes to everyone rather than only to staff. */
    public boolean broadcastPunishments = true;

    /** Message shown to a banned player at the point of disconnection. */
    public String banAppealMessage = "If you believe this is a mistake, contact the server staff.";

    /** Maximum length accepted for an operator-supplied reason. */
    public int maxReasonLength = 256;

    // ---- commands ----------------------------------------------------------------------

    /** Register short top-level aliases such as /heal, where nothing else owns the name. */
    public boolean registerAliases = true;

    /** Seconds a destructive-action confirmation token stays valid. */
    public int confirmationTimeoutSeconds = 30;

    /** Commands allowed per player per minute before rate limiting kicks in. */
    public int commandsPerMinute = 120;

    // ---- gui ---------------------------------------------------------------------------

    /** Enable the vanilla-container admin panel (works on unmodified clients). */
    public boolean adminGuiEnabled = true;

    /** Players shown per page in the admin panel's player list. */
    public int adminGuiPageSize = 28;

    // ---- audit -------------------------------------------------------------------------

    /** Write an audit record for every privileged action. Disabling this is not recommended. */
    public boolean auditEnabled = true;

    /**
     * Clamps every field into its supported range.
     *
     * @param file the file name to attribute findings to
     * @return a report naming every value that was rejected and what replaced it
     */
    public ValidationReport validate(String file) {
        ValidationReport report = new ValidationReport(file);

        defaultGroup = requireIdentifier(report, "defaultGroup", defaultGroup, "default");
        permissionCacheSize = clamp(report, "permissionCacheSize", permissionCacheSize, 64, 65_536, 4096);

        teleportWarmupSeconds = clamp(report, "teleportWarmupSeconds", teleportWarmupSeconds, 0, 60, 3);
        teleportCooldownSeconds = clamp(report, "teleportCooldownSeconds", teleportCooldownSeconds, 0, 3600, 5);
        teleportRequestTimeoutSeconds =
                clamp(report, "teleportRequestTimeoutSeconds", teleportRequestTimeoutSeconds, 5, 3600, 120);
        maxHomesPerPlayer = clamp(report, "maxHomesPerPlayer", maxHomesPerPlayer, 0, 1000, 5);
        safeDestinationSearchHeight = clamp(report, "safeDestinationSearchHeight", safeDestinationSearchHeight, 1, 64, 8);

        if (!(teleportWarmupMoveTolerance > 0.0) || teleportWarmupMoveTolerance > 16.0) {
            report.reject("teleportWarmupMoveTolerance", teleportWarmupMoveTolerance,
                    "a number greater than 0 and at most 16", 0.5);
            teleportWarmupMoveTolerance = 0.5;
        }

        maxReasonLength = clamp(report, "maxReasonLength", maxReasonLength, 16, 1024, 256);
        confirmationTimeoutSeconds = clamp(report, "confirmationTimeoutSeconds", confirmationTimeoutSeconds, 5, 600, 30);
        commandsPerMinute = clamp(report, "commandsPerMinute", commandsPerMinute, 10, 6000, 120);
        adminGuiPageSize = clamp(report, "adminGuiPageSize", adminGuiPageSize, 7, 45, 28);

        if (banAppealMessage == null) {
            report.reject("banAppealMessage", null, "a string", "\"\"");
            banAppealMessage = "";
        } else if (banAppealMessage.length() > 512) {
            report.reject("banAppealMessage", "a string of " + banAppealMessage.length() + " characters",
                    "at most 512 characters", "the first 512 characters");
            banAppealMessage = banAppealMessage.substring(0, 512);
        }

        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            report.reject("schemaVersion", schemaVersion,
                    "exactly " + CURRENT_SCHEMA_VERSION + " for this NexusCore version", CURRENT_SCHEMA_VERSION);
            schemaVersion = CURRENT_SCHEMA_VERSION;
        }

        return report;
    }

    private static int clamp(ValidationReport report, String key, int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            report.reject(key, value, "a whole number between " + min + " and " + max, fallback);
            return fallback;
        }
        return value;
    }

    private static String requireIdentifier(ValidationReport report, String key, String value, String fallback) {
        if (value == null || !value.matches("[a-z0-9_-]{1,32}")) {
            report.reject(key, value, "1-32 characters of a-z, 0-9, underscore or hyphen", fallback);
            return fallback;
        }
        return value;
    }
}
