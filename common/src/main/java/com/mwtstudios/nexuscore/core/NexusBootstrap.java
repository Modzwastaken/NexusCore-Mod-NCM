package com.mwtstudios.nexuscore.core;

import com.mojang.logging.LogUtils;
import com.mwtstudios.nexuscore.audit.AuditService;
import com.mwtstudios.nexuscore.command.ConfirmationService;
import com.mwtstudios.nexuscore.command.RateLimiter;
import com.mwtstudios.nexuscore.config.ConfigurationService;
import com.mwtstudios.nexuscore.config.NexusSettings;
import com.mwtstudios.nexuscore.identity.IdentityService;
import com.mwtstudios.nexuscore.message.MessageService;
import com.mwtstudios.nexuscore.moderation.ModerationService;
import com.mwtstudios.nexuscore.module.ModuleContext;
import com.mwtstudios.nexuscore.module.ModuleManager;
import com.mwtstudios.nexuscore.permission.PermissionService;
import com.mwtstudios.nexuscore.player.PlayerUtilityService;
import com.mwtstudios.nexuscore.storage.JsonStore;
import com.mwtstudios.nexuscore.teleport.TeleportService;

import org.slf4j.Logger;

/**
 * Builds a fully wired {@link NexusServices} from a {@link NexusPlatform}.
 *
 * <p>The one place service construction happens. Every loader entry point calls
 * {@link #start(NexusPlatform, String)} and gets back the same object graph, which is the point:
 * this replaced an identical wiring block copied into three files, where a change to one copy would
 * change one loader's behaviour and nothing would catch the divergence.</p>
 *
 * <p><b>Nothing here needs a {@code MinecraftServer}.</b> That is deliberate and load-bearing.
 * Because the whole graph builds from a data directory alone, it can be built during mod
 * construction, which precedes every game event — including {@code RegisterCommandsEvent}, which
 * fires on a datapack worker thread before the server exists. The original code built services on
 * {@code ServerAboutToStartEvent} and registered a command tree against services that did not exist
 * yet.</p>
 */
public final class NexusBootstrap {

    /**
     * Set by {@link #start} so the in-server test harness can reach the live services.
     * Volatile because the harness runs on the server thread while start ran on the mod thread.
     */
    private static volatile NexusServices running;

    private static final Logger LOGGER = LogUtils.getLogger();

    private NexusBootstrap() {
        // Static factory only.
    }

    /**
     * Starts every standard module and assembles the service registry.
     *
     * @param platform the loader's data root and flight mechanism
     * @param modVersion this build's version
     * @return the started services, and the manager that owns them
     */
    public static Started start(NexusPlatform platform, String modVersion) {
        ModuleManager manager = StandardModules.registerAll(new ModuleManager());

        boolean safeMode = SafeMode.requested();
        if (safeMode) {
            SafeMode.disabledModules().forEach(manager::disable);
        }
        LOGGER.info("NexusCore {}", SafeMode.describe());

        ModuleContext context = manager.start(
                new ModuleContext(platform.dataRoot(), modVersion, platform.flightController()));

        NexusServices services = new NexusServices(
                context.service(JsonStore.class),
                context.service(ConfigurationService.class),
                context.service(MessageService.class),
                context.service(IdentityService.class),
                context.service(AuditService.class),
                context.service(PermissionService.class),
                optional(context, TeleportService.class, safeMode),
                optional(context, PlayerUtilityService.class, safeMode),
                optional(context, ModerationService.class, safeMode),
                context.service(RateLimiter.class),
                context.service(ConfirmationService.class),
                modVersion);

        report(platform, services);
        running = services;
        return new Started(services, manager);
    }

    /**
     * The services from the most recent successful start, or null before one has happened.
     *
     * <p><b>Why a static exists here at all.</b> GameTest instantiates a test class reflectively
     * with a no-argument constructor and invokes its methods statically — there is no injection
     * point, so a test cannot be handed the services it needs to assert against. NeoForge's
     * entrypoint already carried exactly this accessor for exactly this reason; this is the same
     * seam moved into shared code so Fabric and Forge can use it too, rather than a third pattern.</p>
     *
     * <p>Deliberately <b>not</b> a general-purpose service locator. Production code receives its
     * services through {@link ModuleContext} and the constructor graph, which is what keeps the
     * module contract honest and what makes safe mode's missing services a compile-time shape rather
     * than a runtime surprise. Reaching for this from a service would be a defect.</p>
     *
     * @return the live services, or null before {@link #start} has completed
     */
    public static NexusServices runningServices() {
        return running;
    }

    /**
     * Resolves a service that safe mode may have left unstarted.
     *
     * <p>Returns null rather than throwing when safe mode is on, and still throws when it is off —
     * because outside safe mode an absent optional service means the module graph is broken, and
     * quietly substituting null would hide that.</p>
     */
    private static <T> T optional(ModuleContext context, Class<T> type, boolean safeMode) {
        if (safeMode) {
            return null;
        }
        return context.service(type);
    }

    private static void report(NexusPlatform platform, NexusServices services) {
        NexusSettings settings = services.settings();
        LOGGER.info("NexusCore ready on {}: data at {}, {} message(s), {} permission group(s), {} audit record(s)",
                platform.name(), platform.dataRoot(), services.messages().size(),
                services.permissions().groupNames().size(), services.audit().count());
        LOGGER.info("NexusCore flight mechanism: {}", platform.flightDescription());
        if (settings.operatorBootstrap) {
            LOGGER.warn("NexusCore operator bootstrap is ENABLED: any level-{} operator has full NexusCore access. "
                    + "Create real groups, then set operatorBootstrap=false in {}.",
                    NexusServices.OPERATOR_LEVEL, platform.dataRoot().resolve(NexusSettings.FILE));
        }
    }

    /**
     * What a completed bootstrap hands back.
     *
     * <p>The manager is returned alongside the services because shutdown belongs to it — an entry
     * point that only kept the services would have no way to stop modules in reverse order.</p>
     *
     * @param services the wired service registry
     * @param modules the manager that started them, for shutdown
     */
    public record Started(NexusServices services, ModuleManager modules) {
    }
}
