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
import com.mwtstudios.nexuscore.module.ModuleException;
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

    /** Mod id, shared by every platform entry point. */
    public static final String MOD_ID = "nexuscore";

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

    /**
     * @return the teleport service
     * @throws ModuleException in safe mode, where the {@code teleport} module is not started
     */
    public TeleportService teleport() {
        return required(teleport, "teleport");
    }

    /**
     * @return the player utility toolkit
     * @throws ModuleException in safe mode, where the {@code player-utilities} module is not started
     */
    public PlayerUtilityService players() {
        return required(players, "player-utilities");
    }

    /**
     * @return the moderation service
     * @throws ModuleException in safe mode, where the {@code moderation} module is not started
     */
    public ModerationService moderation() {
        return required(moderation, "moderation");
    }

    /**
     * Whether an optional module started.
     *
     * <p>For callers that must not throw — event handlers, mainly. A login handler that blew up
     * because moderation is disabled would stop players joining, which is the opposite of what safe
     * mode is for.</p>
     *
     * @param module the module id
     * @return true when that module's service is available
     */
    public boolean has(String module) {
        return switch (module) {
            case "teleport" -> teleport != null;
            case "player-utilities" -> players != null;
            case "moderation" -> moderation != null;
            default -> true;
        };
    }

    /**
     * Optional services are null in safe mode. Throwing a named {@link ModuleException} rather than
     * letting a null escape is the whole reason the disabled path is safe: the command pipeline
     * catches it and tells the player the feature is off, instead of a null dereference surfacing
     * somewhere unrelated with no indication why.
     */
    private static <T> T required(T service, String module) {
        if (service == null) {
            throw new ModuleException("the '" + module + "' module is not started, so this command is "
                    + "unavailable. The server is running in safe mode (-D" + SafeMode.PROPERTY + ").");
        }
        return service;
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
        ServerPlayer actor = actorOf(source);
        if (actor != null) {
            return authorise(actor, node);
        }
        if (source.getEntity() != null) {
            // An entity that is not a player. This is the /execute as bypass: the previous
            // implementation treated every non-player as the console and granted ROOT, so
            // `/execute as @e[type=armor_stand] run nexus …` ran with full privileges and was
            // audited as CONSOLE. It defeated the permission engine outright — an operator could
            // prefix any refused command with /execute to run it, and operatorBootstrap=false,
            // the documented way to stop operators having blanket access, constrained nobody.
            return new PermissionDecision(node, PermissionDecision.Result.DENY, null, "non-player-entity",
                    "refused because the command was run as a non-player entity, which is never "
                            + "privileged — run it directly rather than through /execute as");
        }
        if (isConsole(source)) {
            return PermissionDecision.root(node, "the console is root");
        }
        // No entity and below operator level: a command block or a datapack function. Default
        // deny (§9.1) rather than root, since anything that can power a block would otherwise
        // inherit administration.
        return new PermissionDecision(node, PermissionDecision.Result.DENY, null, "unprivileged-source",
                "refused because command blocks and datapack functions are not privileged");
    }

    /**
     * The player behind a command source, or null.
     *
     * @param source the command source
     * @return the issuing player, or null when the source is not a player
     */
    private static ServerPlayer actorOf(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player : null;
    }

    /**
     * Whether a source with no player entity is the console rather than a command block.
     *
     * <p>The obvious implementation reads {@code CommandSourceStack.source}, which holds the
     *真 issuer and is unaffected by {@code /execute as}. It cannot be used: that field is
     * <b>private in vanilla</b> and only public under NeoForge's access transformer, so shared
     * code that touches it compiles on NeoForge and Forge and fails on Fabric. {@code getPlayer()}
     * and {@code isPlayer()} are no help either — both read {@code entity}, not {@code source}.
     *
     * <p>Permission level is the portable discriminator. The console and an authenticated RCON
     * connection run at level 4; command blocks and datapack functions run at level 2. So a
     * source with no entity and level 4 is the console, and one with no entity and less is not.
     *
     * @param source the command source
     * @return true when this is the server console or RCON
     */
    private static boolean isConsole(CommandSourceStack source) {
        return source.hasPermission(OPERATOR_LEVEL);
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
        // Attribution follows the same rule as authorisation: the player who issued the command,
        // not whatever /execute as last impersonated. Reading getEntity() here recorded an
        // /execute'd action as CONSOLE, which made it unattributable.
        ServerPlayer actor = actorOf(source);
        UUID actorUuid = actor == null ? null : actor.getUUID();
        String actorName = actor != null ? actor.getGameProfile().getName()
                : (isConsole(source) ? "CONSOLE" : source.getTextName());
        audit.record(actorUuid, actorName, action, targetType, targetId, result, reason, parameters, correlationId);
    }

    /**
     * @param source a command source
     * @return the subject used for rate limiting
     */
    public static UUID subjectOf(CommandSourceStack source) {
        // Same rule as authorisation and attribution, for the same reason: keying the rate limit
        // on getEntity() let a player shed their own bucket by wrapping the command in
        // /execute as, since every impersonated entity resolved to the shared CONSOLE_SUBJECT.
        ServerPlayer actor = actorOf(source);
        return actor != null ? actor.getUUID() : CONSOLE_SUBJECT;
    }

    /** Re-applies settings to every service that caches them. */
    public void applySettings() {
        NexusSettings settings = settings();
        permissions.applySettings(settings);
        permissions.invalidate();
        // Guarded, and via has() rather than the accessor: this read the raw field, so in safe mode
        // `/nexus reload` threw a plain NullPointerException rather than the ModuleException the
        // pipeline knows how to report. A core command crashed because an optional module was off.
        if (has("teleport")) {
            teleport.applySettings(settings);
        }
        confirmations.setTimeoutSeconds(settings.confirmationTimeoutSeconds);
        audit.setEnabled(settings.auditEnabled);
    }
}
