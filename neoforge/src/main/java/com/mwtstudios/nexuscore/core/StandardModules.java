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
                .register(module("storage", true, Set.of(),
                        ctx -> ctx.provide(JsonStore.class, new JsonStore(ctx.dataRoot()))))

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

                .register(module("identity", true, Set.of("storage"),
                        ctx -> ctx.provide(IdentityService.class, new IdentityService(ctx.service(JsonStore.class)))))

                .register(module("audit", true, Set.of("storage", "configuration"), ctx -> {
                    AuditService audit = new AuditService(ctx.service(JsonStore.class), ctx.modVersion());
                    audit.setEnabled(ctx.settings().auditEnabled);
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
        return new Declared(id, core, dependsOn, start);
    }

    /** A module declared inline. See {@link #module}. */
    private record Declared(String id, boolean core, Set<String> dependsOn, Consumer<ModuleContext> starter)
            implements NexusModule {

        @Override
        public void start(ModuleContext context) {
            starter.accept(context);
        }
    }
}
