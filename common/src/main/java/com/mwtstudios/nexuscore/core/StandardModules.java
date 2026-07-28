package com.mwtstudios.nexuscore.core;

import java.util.Set;
import java.util.function.Consumer;

import com.mwtstudios.nexuscore.audit.AuditService;
import com.mwtstudios.nexuscore.command.ConfirmationService;
import com.mwtstudios.nexuscore.command.RateLimiter;
import com.mwtstudios.nexuscore.config.ConfigurationService;
import com.mwtstudios.nexuscore.identity.IdentityService;
import com.mwtstudios.nexuscore.message.MessageService;
import com.mwtstudios.nexuscore.moderation.ModerationService;
import com.mwtstudios.nexuscore.module.ModuleContext;
import com.mwtstudios.nexuscore.module.ModuleManager;
import com.mwtstudios.nexuscore.module.NexusModule;
import com.mwtstudios.nexuscore.permission.PermissionService;
import com.mwtstudios.nexuscore.player.PlayerUtilityService;
import com.mwtstudios.nexuscore.storage.JournalService;
import com.mwtstudios.nexuscore.storage.JsonStore;
import com.mwtstudios.nexuscore.teleport.TeleportService;

/**
 * The modules NexusCore always registers, and the dependency graph between them.
 *
 * <p>This is the single declaration of how services are wired. Before it, the same eleven
 * constructor calls appeared in {@code NexusCore}, {@code NexusCoreFabric} and
 * {@code NexusCoreForge} — identical apart from the flight controller — so the wiring had three
 * copies and no test compared them.</p>
 *
 * <p><b>Core versus optional.</b> Storage, configuration, messages, identity, audit, permissions
 * and the two security controls are core: nothing works without them, and a "safe" mode that
 * dropped permissions or the audit log would be less safe than refusing to start. Teleporting,
 * player utilities and moderation are features, and are what M1's safe mode is for.</p>
 *
 * <p>Clock injection is preserved exactly as it was — {@code System::currentTimeMillis} passed in
 * rather than called inside a service — because that is what makes {@code RateLimiterTest},
 * {@code ConfirmationServiceTest} and {@code ModerationServiceTest} able to test expiry at a
 * boundary without sleeping.</p>
 */
public final class StandardModules {

    private StandardModules() {
        // Utility holder: the module list is data, not state.
    }

    /**
     * Registers every standard module on a manager.
     *
     * @param manager the manager to populate
     * @return the same manager, for chaining
     */
    public static ModuleManager registerAll(ModuleManager manager) {
        return manager
                // Storage recovers before it publishes. replayPending() finishes any transaction
                // that committed but did not land before the last shutdown, and it has to happen
                // here: every module below reads data through this store, and a half-applied
                // multi-file transaction is exactly the state none of them can detect.
                .register(module("storage", true, Set.of(), ctx -> {
                    JsonStore store = new JsonStore(ctx.dataRoot());
                    JournalService journal = new JournalService(store);
                    journal.replayPending();
                    ctx.provide(JsonStore.class, store);
                    ctx.provide(JournalService.class, journal);
                }))

                // Configuration publishes the settings snapshot every later module reads, so it is
                // the one module whose start() does more than construct a service.
                .register(module("configuration", true, Set.of("storage"), ctx -> {
                    ConfigurationService configuration =
                            new ConfigurationService(ctx.service(JsonStore.class), ctx.modVersion());
                    configuration.load();
                    ctx.settingsLoaded(configuration.settings());
                    ctx.provide(ConfigurationService.class, configuration);
                }))

                .register(module("messages", true, Set.of("storage"),
                        ctx -> ctx.provide(MessageService.class, new MessageService(ctx.service(JsonStore.class)))))

                // Identity damps its last-seen writes, so it needs a teardown: without the flush a
                // clean shutdown would discard whatever had not reached disk, trading a write
                // amplification problem for a quiet data-loss one.
                .register(module("identity", true, Set.of("storage"),
                        ctx -> ctx.provide(IdentityService.class, new IdentityService(ctx.service(JsonStore.class))),
                        ctx -> ctx.service(IdentityService.class).flush()))

                .register(module("audit", true, Set.of("storage", "configuration"), ctx -> {
                    AuditService audit = new AuditService(ctx.service(JsonStore.class), ctx.modVersion());
                    audit.setEnabled(ctx.settings().auditEnabled);
                    audit.setMaxSegmentBytes(ctx.settings().auditMaxSegmentBytes);
                    ctx.provide(AuditService.class, audit);
                }))

                .register(module("permissions", true, Set.of("storage", "configuration"),
                        ctx -> ctx.provide(PermissionService.class, new PermissionService(
                                ctx.service(JsonStore.class), ctx.settings().permissionCacheSize))))

                // Rate limiting and confirmation are security controls, not conveniences: a build
                // that dropped them would accept unbounded commands and unconfirmed destructive
                // actions. Core.
                .register(module("rate-limiter", true, Set.of("configuration"),
                        ctx -> ctx.provide(RateLimiter.class, new RateLimiter(
                                ctx.settings().commandsPerMinute, System::currentTimeMillis))))

                .register(module("confirmations", true, Set.of("configuration"),
                        ctx -> ctx.provide(ConfirmationService.class, new ConfirmationService(
                                ctx.settings().confirmationTimeoutSeconds, System::currentTimeMillis))))

                .register(module("teleport", false, Set.of("storage", "configuration"),
                        ctx -> ctx.provide(TeleportService.class, new TeleportService(
                                ctx.service(JsonStore.class), ctx.settings(), System::currentTimeMillis))))

                .register(module("player-utilities", false, Set.of(),
                        ctx -> ctx.provide(PlayerUtilityService.class,
                                new PlayerUtilityService(ctx.flightController()))))

                .register(module("moderation", false, Set.of("storage"),
                        ctx -> ctx.provide(ModerationService.class, new ModerationService(
                                ctx.service(JsonStore.class), System::currentTimeMillis))));
    }

    /**
     * Builds a module from its declaration.
     *
     * <p>A record rather than eleven near-identical classes. Each module's interesting content is
     * its id, its dependencies and one constructor call; eleven files to express that would bury
     * the dependency graph — which is the thing worth reading — in boilerplate.</p>
     *
     * @param id the module id
     * @param core whether the mod is unusable without it
     * @param dependsOn ids that must start first
     * @param start builds and publishes the service
     * @return the module
     */
    private static NexusModule module(String id, boolean core, Set<String> dependsOn, Consumer<ModuleContext> start) {
        return new Declared(id, core, dependsOn, start, context -> { });
    }

    /**
     * Builds a module that also needs tearing down.
     *
     * @param id the module id
     * @param core whether the mod is unusable without it
     * @param dependsOn ids that must start first
     * @param start builds and publishes the service
     * @param stop runs at shutdown, with the same context the module started from
     * @return the module
     */
    private static NexusModule module(String id, boolean core, Set<String> dependsOn,
            Consumer<ModuleContext> start, Consumer<ModuleContext> stop) {
        return new Declared(id, core, dependsOn, start, stop);
    }

    /**
     * A module declared inline. See {@link #module}.
     *
     * <p>A class rather than a record because {@link NexusModule#stop()} takes no argument, so the
     * context a module started from has to be held until shutdown. That is the only mutable state
     * here, it is written once by {@code start}, and {@link ModuleManager} starts and stops modules
     * on one thread.</p>
     */
    private static final class Declared implements NexusModule {

        private final String id;
        private final boolean core;
        private final Set<String> dependsOn;
        private final Consumer<ModuleContext> starter;
        private final Consumer<ModuleContext> stopper;
        private ModuleContext started;

        Declared(String id, boolean core, Set<String> dependsOn, Consumer<ModuleContext> starter,
                Consumer<ModuleContext> stopper) {
            this.id = id;
            this.core = core;
            this.dependsOn = dependsOn;
            this.starter = starter;
            this.stopper = stopper;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean core() {
            return core;
        }

        @Override
        public Set<String> dependsOn() {
            return dependsOn;
        }

        @Override
        public void start(ModuleContext context) {
            started = context;
            starter.accept(context);
        }

        @Override
        public void stop() {
            // A module that never started has nothing to tear down, and safe mode leaves several
            // in exactly that state.
            if (started != null) {
                stopper.accept(started);
            }
        }
    }
}
