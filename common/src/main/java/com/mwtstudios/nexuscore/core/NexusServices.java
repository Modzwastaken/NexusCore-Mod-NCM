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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

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
        if (isSubstituted(source)) {
            // The command is running "as" an entity that did not issue it — /execute as, a command
            // sign, or a lectern book. Refused outright rather than authorised against the
            // impersonated entity, because authorising the impersonation IS the escalation: a
            // vanilla level-2 operator could otherwise run `/execute as <admin> run nexus …` and
            // act with the admin's NexusCore rights, which is exactly what operatorBootstrap=false
            // is supposed to prevent.
            return new PermissionDecision(node, PermissionDecision.Result.DENY, null, "substituted-source",
                    "refused because this command was run as an entity that did not issue it "
                            + "(/execute as, a command sign, or a lectern book). Run it directly.");
        }
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
     * Whether this source's entity is <em>not</em> the thing that issued the command.
     *
     * <p>{@code CommandSourceStack.source} holds the real issuer and is private in vanilla, so it
     * looked unreadable from shared code. It is not: vanilla ships a public method whose contract
     * leaks the field <em>by identity</em>.</p>
     *
     * <pre>
     * public CommandSourceStack withSource(CommandSource source) {
     *     return this.source == source ? this : new CommandSourceStack(...);
     * }
     * </pre>
     *
     * <p>So {@code stack.withSource(x) == stack} is true exactly when {@code stack.source == x}.
     * {@link Entity} implements {@code CommandSource} and {@code Entity.createCommandSourceStack()}
     * passes {@code this} as <b>both</b> the source and the entity, so for a player typing a command
     * the two are the same object and the test returns the stack unchanged. {@code withEntity},
     * which is what {@code /execute as} uses, replaces the entity and <b>preserves the source</b> —
     * so the identities diverge and the substitution is detectable.</p>
     *
     * <p>No access widener, access transformer, mixin or reflection, and no allocation on the
     * ordinary path: {@code withSource} returns {@code this} when the identities match.</p>
     *
     * <p><b>This closes two paths besides {@code /execute}</b>, both confirmed in vanilla source.
     * {@code SignBlockEntity} and {@code LecternBlockEntity} build a stack with
     * {@code CommandSource.NULL} as the source and the <em>clicking player</em> as the entity, at
     * permission level 2. So a {@code run_command} click event on a sign, or in a written book on a
     * lectern, previously authorised and audited as whoever clicked it — an operator could craft one
     * and have a privileged player unwittingly run an administrative command in their own name.</p>
     *
     * @param source the command source
     * @return true when the entity did not issue the command
     */
    private static boolean isSubstituted(CommandSourceStack source) {
        Entity entity = source.getEntity();
        return entity != null && source.withSource(entity) != source;
    }

    /**
     * The player who actually typed the command, seeing through {@code /execute as}.
     *
     * <p>Used for the audit actor and the rate-limit subject, so a refused impersonation is
     * attributed to whoever attempted it rather than to the player they impersonated. The scan of
     * the online player list runs <em>only</em> when a substitution has been detected, which is
     * rare; the ordinary path is the O(1) identity test in {@link #isSubstituted}.</p>
     *
     * @param source the command source
     * @return the issuing player, or null when no online player is the issuer
     */
    private static ServerPlayer issuerOf(CommandSourceStack source) {
        ServerPlayer entityPlayer = actorOf(source);
        if (entityPlayer != null && !isSubstituted(source)) {
            return entityPlayer;
        }
        if (source.getServer() == null) {
            return null;
        }
        for (ServerPlayer candidate : source.getServer().getPlayerList().getPlayers()) {
            if (source.withSource(candidate) == source) {
                return candidate;
            }
        }
        return null;
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
        // Exact rather than heuristic: MinecraftServer implements CommandSource and is its own
        // source, so the withSource identity trick from isSubstituted identifies the console
        // precisely. hasPermission stays as the fallback for RCON, whose RconConsoleSource instance
        // is not reachable from shared code — it authenticates with the server's own password and
        // runs at level 4, so treating it as the console is correct.
        MinecraftServer server = source.getServer();
        if (server != null && source.withSource(server) == source) {
            return true;
        }
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
        return applyBootstrap(permissions.evaluate(player.getUUID(), node), node,
                player.hasPermissions(OPERATOR_LEVEL), "you are");
    }

    /**
     * The same decision {@link #authorise(ServerPlayer, String)} would reach, for a subject who
     * may be offline.
     *
     * <p>This exists because {@code /nexus permission check} — the command whose entire job is
     * explaining a decision — called the evaluator directly, so it could never show the
     * operator-bootstrap grant and under-reported who could actually do what. An explain command
     * that disagrees with enforcement is worse than none.</p>
     *
     * @param server the running server, for the operator list
     * @param subject whose permissions
     * @param name their name, for the profile the operator list is keyed by
     * @param node the permission node
     * @return the decision enforcement would reach
     */
    public PermissionDecision authorise(MinecraftServer server, UUID subject, String name, String node) {
        return applyBootstrap(permissions.evaluate(subject, node), node,
                isOperator(server, subject, name), name + " is");
    }

    /** Whether the subject holds the vanilla operator level bootstrap keys on, online or not. */
    private static boolean isOperator(MinecraftServer server, UUID subject, String name) {
        ServerPlayer online = server.getPlayerList().getPlayer(subject);
        if (online != null) {
            return online.hasPermissions(OPERATOR_LEVEL);
        }
        // Offline: the operator list is on disk and keyed by profile, so the answer is still
        // knowable without a network lookup.
        return server.getPlayerList().isOp(new com.mojang.authlib.GameProfile(subject, name));
    }

    /**
     * Applies the operator-bootstrap grant to a raw evaluation. One implementation, so the
     * explain path and the enforcement path cannot drift apart.
     */
    private PermissionDecision applyBootstrap(PermissionDecision decision, String node,
            boolean operator, String subjectPhrase) {
        if (decision.allowed()) {
            return decision;
        }
        if (decision.result() == PermissionDecision.Result.DENY) {
            // An explicit deny is a deliberate act by an operator. Bootstrap does not override
            // it, or "deny" would not mean anything.
            return decision;
        }
        if (settings().operatorBootstrap && operator) {
            return new PermissionDecision(node, PermissionDecision.Result.ALLOW, "*", "operator-bootstrap",
                    "granted by operator bootstrap, because " + subjectPhrase + " a level-" + OPERATOR_LEVEL
                            + " operator and operatorBootstrap is enabled in config.json — turn it off once "
                            + "real groups exist");
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
        ServerPlayer actor = issuerOf(source);
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
        ServerPlayer actor = issuerOf(source);
        return actor != null ? actor.getUUID() : CONSOLE_SUBJECT;
    }

    /** Re-applies settings to every service that caches them. */
    public void applySettings() {
        NexusSettings settings = settings();
        permissions.applySettings(settings);
        // Re-reads permissions.json, not just the cache. Before 1.1.0 this only called
        // invalidate(), so a hand edit to that file was ignored and then overwritten by the next
        // permission change — silent data loss on the file the documentation tells operators to
        // edit, while /nexus reload reported success.
        permissions.reload();
        // Guarded, and via has() rather than the accessor: this read the raw field, so in safe mode
        // `/nexus reload` threw a plain NullPointerException rather than the ModuleException the
        // pipeline knows how to report. A core command crashed because an optional module was off.
        if (has("teleport")) {
            teleport.applySettings(settings);
        }
        confirmations.setTimeoutSeconds(settings.confirmationTimeoutSeconds);
        audit.setEnabled(settings.auditEnabled);
        // Both of these kept their boot-time value through a reload while /nexus reload reported
        // success. commandsPerMinute is a rate limit — a security control that silently did not
        // apply — so this is the more important of the two.
        rateLimiter.setPermitsPerMinute(settings.commandsPerMinute);
        permissions.setCacheSize(settings.permissionCacheSize);
    }
}
