package com.mwtstudios.nexuscore.core;

import java.util.Map;
import java.util.UUID;

import com.mwtstudios.nexuscore.audit.AuditService;
import com.mwtstudios.nexuscore.command.ConfirmationService;
import com.mwtstudios.nexuscore.command.RateLimiter;
import com.mwtstudios.nexuscore.config.ConfigurationService;
import com.mwtstudios.nexuscore.config.NexusSettings;
import com.mwtstudios.nexuscore.identity.IdentityService;
import com.mwtstudios.nexuscore.message.MessageService;
import com.mwtstudios.nexuscore.moderation.ModerationService;
import com.mwtstudios.nexuscore.permission.PermissionDecision;
import com.mwtstudios.nexuscore.permission.PermissionService;
import com.mwtstudios.nexuscore.player.PlayerUtilityService;
import com.mwtstudios.nexuscore.storage.JsonStore;
import com.mwtstudios.nexuscore.teleport.TeleportService;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * The explicit service registry (§7.1).
 *
 * <p>Constructor injection, no reflection-based scanning, no global mutable singletons.
 * Everything that needs a service is handed this object, which means the dependency graph is
 * readable in one place rather than inferred from static access.</p>
 *
 * <p>{@link #authorise} is the single authorisation entry point. Commands and the admin GUI
 * both call it, which is how "the GUI and command paths use the same evaluator" (M3 exit) is
 * true by construction — there is no second path to write.</p>
 */
public final class NexusServices {

    /** Vanilla operator level treated as eligible for bootstrap access. */
    public static final int OPERATOR_LEVEL = 4;

    /** Stand-in subject for the console, so rate limiting has a key for it. */
    public static final UUID CONSOLE_SUBJECT = new UUID(0L, 0L);

    private final JsonStore store;
    private final ConfigurationService configuration;
    private final MessageService messages;
    private final IdentityService identity;
    private final AuditService audit;
    private final PermissionService permissions;
    private final TeleportService teleport;
    private final PlayerUtilityService players;
    private final ModerationService moderation;
    private final RateLimiter rateLimiter;
    private final ConfirmationService confirmations;
    private final String modVersion;

    /**
     * @param store the JSON data store
     * @param configuration settings, already loaded
     * @param messages the message catalogue
     * @param identity UUID-first identity
     * @param audit the audit log
     * @param permissions the permission engine
     * @param teleport homes, warps, and teleporting
     * @param players the player utility toolkit
     * @param moderation punishments
     * @param rateLimiter per-subject command rate limiting
     * @param confirmations destructive-action confirmation tokens
     * @param modVersion this build's version
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public NexusServices(JsonStore store, ConfigurationService configuration, MessageService messages,
            IdentityService identity, AuditService audit, PermissionService permissions, TeleportService teleport,
            PlayerUtilityService players, ModerationService moderation, RateLimiter rateLimiter,
            ConfirmationService confirmations, String modVersion) {
        this.store = store;
        this.configuration = configuration;
        this.messages = messages;
        this.identity = identity;
        this.audit = audit;
        this.permissions = permissions;
        this.teleport = teleport;
        this.players = players;
        this.moderation = moderation;
        this.rateLimiter = rateLimiter;
        this.confirmations = confirmations;
        this.modVersion = modVersion;
    }

    /** @return the JSON data store */
    public JsonStore store() {
        return store;
    }

    /** @return the configuration service */
    public ConfigurationService configuration() {
        return configuration;
    }

    /** @return the current settings snapshot */
    public NexusSettings settings() {
        return configuration.settings();
    }

    /** @return the message catalogue */
    public MessageService messages() {
        return messages;
    }

    /** @return the identity service */
    public IdentityService identity() {
        return identity;
    }

    /** @return the audit log */
    public AuditService audit() {
        return audit;
    }

    /** @return the permission engine */
    public PermissionService permissions() {
        return permissions;
    }

    /** @return the teleport service */
    public TeleportService teleport() {
        return teleport;
    }

    /** @return the player utility toolkit */
    public PlayerUtilityService players() {
        return players;
    }

    /** @return the moderation service */
    public ModerationService moderation() {
        return moderation;
    }

    /** @return the command rate limiter */
    public RateLimiter rateLimiter() {
        return rateLimiter;
    }

    /** @return the confirmation token service */
    public ConfirmationService confirmations() {
        return confirmations;
    }

    /** @return this build's version */
    public String modVersion() {
        return modVersion;
    }

    // ---- authorisation -----------------------------------------------------------------

    /**
     * The single authorisation entry point for every command source.
     *
     * <p>The console is root — it already has unrestricted access to the server process, so
     * pretending otherwise would be theatre. A level-{@value #OPERATOR_LEVEL} operator is
     * granted access while {@code operatorBootstrap} is enabled, so a brand-new server is not
     * locked out of its own administration before any group exists. That grant is
     * <em>named in the explain output</em> rather than hidden, and turning it off is a
     * documented step once real groups are configured (§9.1).</p>
     *
     * @param source who is asking
     * @param node the permission node
     * @return the decision, with an explanation
     */
    public PermissionDecision authorise(CommandSourceStack source, String node) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return PermissionDecision.root(node, "the console is root");
        }
        return authorise(player, node);
    }

    /**
     * Authorises a player.
     *
     * @param player who is asking
     * @param node the permission node
     * @return the decision
     */
    public PermissionDecision authorise(ServerPlayer player, String node) {
        PermissionDecision decision = permissions.evaluate(player.getUUID(), node);
        if (decision.allowed()) {
            return decision;
        }
        if (decision.result() == PermissionDecision.Result.DENY) {
            // An explicit deny is a deliberate act by an operator. Bootstrap does not override
            // it, or "deny" would not mean anything.
            return decision;
        }
        if (settings().operatorBootstrap && player.hasPermissions(OPERATOR_LEVEL)) {
            return new PermissionDecision(node, PermissionDecision.Result.ALLOW, "*", "operator-bootstrap",
                    "granted by operator bootstrap, because you are a level-" + OPERATOR_LEVEL + " operator and "
                            + "operatorBootstrap is enabled in config.json — turn it off once real groups exist");
        }
        return decision;
    }

    /**
     * Convenience for call sites that only need a yes or no.
     *
     * @param player who is asking
     * @param node the permission node
     * @return true when allowed
     */
    public boolean allows(ServerPlayer player, String node) {
        return authorise(player, node).allowed();
    }

    /**
     * Writes an audit record attributed to a command source.
     *
     * @param source who acted
     * @param action dotted action name
     * @param targetType what kind of thing was acted on
     * @param targetId which one
     * @param result the outcome
     * @param reason the operator-supplied reason, or null
     * @param parameters normalised parameters
     * @param correlationId ties this to the same request's log lines
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public void audit(CommandSourceStack source, String action, String targetType, String targetId,
            String result, String reason, Map<String, String> parameters, String correlationId) {
        UUID actorUuid = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        String actorName = actorUuid == null ? "CONSOLE" : source.getTextName();
        audit.record(actorUuid, actorName, action, targetType, targetId, result, reason, parameters, correlationId);
    }

    /**
     * @param source a command source
     * @return the subject used for rate limiting
     */
    public static UUID subjectOf(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player.getUUID() : CONSOLE_SUBJECT;
    }

    /** Re-applies settings to every service that caches them. */
    public void applySettings() {
        NexusSettings settings = settings();
        permissions.applySettings(settings);
        permissions.invalidate();
        teleport.applySettings(settings);
        confirmations.setTimeoutSeconds(settings.confirmationTimeoutSeconds);
        audit.setEnabled(settings.auditEnabled);
    }
}
