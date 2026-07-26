package com.mwtstudios.nexuscore;

import java.nio.file.Path;
import java.util.UUID;

import com.mojang.logging.LogUtils;
import com.mwtstudios.nexuscore.audit.AuditService;
import com.mwtstudios.nexuscore.command.ConfirmationService;
import com.mwtstudios.nexuscore.command.DurationParser;
import com.mwtstudios.nexuscore.command.NexusCommands;
import com.mwtstudios.nexuscore.command.RateLimiter;
import com.mwtstudios.nexuscore.config.ConfigurationService;
import com.mwtstudios.nexuscore.config.NexusSettings;
import com.mwtstudios.nexuscore.core.NexusServices;
import com.mwtstudios.nexuscore.gui.AdminGuiService;
import com.mwtstudios.nexuscore.identity.IdentityService;
import com.mwtstudios.nexuscore.message.MessageService;
import com.mwtstudios.nexuscore.moderation.ModerationService;
import com.mwtstudios.nexuscore.permission.PermissionService;
import com.mwtstudios.nexuscore.player.PlayerUtilityService;
import com.mwtstudios.nexuscore.storage.JsonStore;
import com.mwtstudios.nexuscore.teleport.TeleportService;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import org.slf4j.Logger;

/**
 * Entry point for the NexusCore Administration Framework.
 *
 * <p>Per §7.1 this class owns bootstrap only. It constructs the services once the server
 * exists, wires the event listeners, and does no business logic of its own.</p>
 *
 * <p><b>Why services are built in the constructor.</b> The obvious place is
 * {@code ServerAboutToStartEvent}, since that is where the {@code MinecraftServer} first
 * exists. That is wrong, and it was caught on a real server rather than in review:
 * {@code RegisterCommandsEvent} fires on a datapack worker thread <em>before</em>
 * {@code ServerAboutToStartEvent} fires on the server thread, so the command tree would be
 * built against services that did not exist yet.</p>
 *
 * <p>Nothing NexusCore constructs actually needs the server — only a data directory — and
 * {@code FMLPaths.GAMEDIR} supplies that at mod-construction time, which is before every
 * event. Building here removes the ordering constraint instead of trying to satisfy it.</p>
 */
@Mod(NexusCore.MOD_ID)
public final class NexusCore {

    /** Mod id. Must match {@code mod_id} in gradle.properties and the id in neoforge.mods.toml. */
    public static final String MOD_ID = "nexuscore";

    /** Directory under the server root holding every NexusCore data file. */
    public static final String DATA_DIRECTORY = "nexuscore";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String displayName;
    private final String version;

    private final NexusServices services;
    private final AdminGuiService gui;

    /**
     * Constructed by FML during mod loading. FML supplies the parameters by type.
     *
     * @param modEventBus this mod's event bus, used for registration and lifecycle events
     * @param modContainer this mod's container, the authoritative source of its own metadata
     */
    public NexusCore(IEventBus modEventBus, ModContainer modContainer) {
        this.displayName = modContainer.getModInfo().getDisplayName();
        this.version = modContainer.getModInfo().getVersion().toString();
        this.services = buildServices();
        this.gui = new AdminGuiService(services);

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogout);
        NeoForge.EVENT_BUS.addListener(this::onServerChat);
        NeoForge.EVENT_BUS.addListener(this::onPlayerDeath);

        LOGGER.info("{} {} bootstrapped on the {} event bus", displayName, version, modEventBus.getClass().getSimpleName());
    }

    // ---- lifecycle ---------------------------------------------------------------------

    private NexusServices buildServices() {
        Path dataRoot = FMLPaths.GAMEDIR.get().resolve(DATA_DIRECTORY);
        JsonStore store = new JsonStore(dataRoot);

        ConfigurationService configuration = new ConfigurationService(store, version);
        configuration.load();
        NexusSettings settings = configuration.settings();

        MessageService messages = new MessageService(store);
        IdentityService identity = new IdentityService(store);
        AuditService audit = new AuditService(store, version);
        audit.setEnabled(settings.auditEnabled);
        PermissionService permissions = new PermissionService(store, settings.permissionCacheSize);
        TeleportService teleport = new TeleportService(store, settings, System::currentTimeMillis);
        PlayerUtilityService players = new PlayerUtilityService();
        ModerationService moderation = new ModerationService(store, System::currentTimeMillis);
        RateLimiter rateLimiter = new RateLimiter(settings.commandsPerMinute, System::currentTimeMillis);
        ConfirmationService confirmations =
                new ConfirmationService(settings.confirmationTimeoutSeconds, System::currentTimeMillis);

        LOGGER.info("NexusCore ready: data at {}, {} message(s), {} permission group(s), {} audit record(s)",
                dataRoot, messages.size(), permissions.groupNames().size(), audit.count());
        if (settings.operatorBootstrap) {
            LOGGER.warn("NexusCore operator bootstrap is ENABLED: any level-{} operator has full NexusCore access. "
                    + "Create real groups, then set operatorBootstrap=false in {}.",
                    NexusServices.OPERATOR_LEVEL, dataRoot.resolve(NexusSettings.FILE));
        }

        return new NexusServices(store, configuration, messages, identity, audit, permissions,
                teleport, players, moderation, rateLimiter, confirmations, version);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        NexusCommands.register(event.getDispatcher(), services, gui, displayName, version);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        services.confirmations().clear();
        LOGGER.info("NexusCore stopped: {} audit record(s) written, chain {}",
                services.audit().count(), services.audit().verify().intact() ? "intact" : "BROKEN");
    }

    private void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        services.teleport().tick(server, (player, outcome) -> {
            if (outcome.moved()) {
                player.sendSystemMessage(services.messages().render("teleport.done", "label", outcome.detail()));
            } else {
                player.sendSystemMessage(services.messages().render("teleport.failed", "reason", outcome.detail()));
            }
        });
    }

    // ---- player events -----------------------------------------------------------------

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        services.identity().observe(player);

        // §M5: punishment expiry is re-evaluated at login, not by a background sweep.
        services.moderation().activeBan(player.getUUID()).ifPresent(ban -> {
            String remaining = DurationParser.describeRemaining(ban.expiresAt(), System.currentTimeMillis());
            player.connection.disconnect(services.messages().render(
                    ban.permanent() ? "moderation.ban.disconnect" : "moderation.tempban.disconnect",
                    "reason", ban.reason(),
                    "remaining", remaining,
                    "appeal", services.settings().banAppealMessage));
            services.audit().record(null, "SYSTEM", "moderation.ban.enforced", "player",
                    player.getUUID().toString(), "denied", ban.reason(),
                    java.util.Map.of("remaining", remaining), UUID.randomUUID().toString());
        });
    }

    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID id = player.getUUID();
        services.identity().observeDeparture(id);
        services.teleport().forget(id);
        services.players().forget(id);
        services.rateLimiter().forget(id);
    }

    /**
     * Records where a player died so {@code /back} returns them there.
     *
     * <p>This is the behaviour players actually expect from {@code /back}, and a death is
     * exactly the case where no teleport happened and so no return point would otherwise
     * exist. Recorded on death rather than on respawn, because by respawn time the original
     * position is gone.</p>
     */
    private void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            services.teleport().recordReturnPoint(player.getUUID(), TeleportService.Location.of(player));
        }
    }

    private void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        services.moderation().activeMute(player.getUUID()).ifPresent(mute -> {
            // Cancel rather than silently drop: the muted player is told, so they are not left
            // wondering whether the server ate their message.
            event.setCanceled(true);
            player.sendSystemMessage(services.messages().render("moderation.mute.blocked",
                    "reason", mute.reason(),
                    "remaining", DurationParser.describeRemaining(mute.expiresAt(), System.currentTimeMillis())));
        });
    }
}
