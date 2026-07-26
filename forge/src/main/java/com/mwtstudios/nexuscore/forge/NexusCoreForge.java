package com.mwtstudios.nexuscore.forge;

import java.nio.file.Path;
import java.util.Map;
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
import com.mwtstudios.nexuscore.message.DeathMessages;
import com.mwtstudios.nexuscore.message.MessageService;
import com.mwtstudios.nexuscore.moderation.ModerationService;
import com.mwtstudios.nexuscore.moderation.PunishmentMessages;
import com.mwtstudios.nexuscore.permission.PermissionService;
import com.mwtstudios.nexuscore.platform.MayflyFlightController;
import com.mwtstudios.nexuscore.player.PlayerUtilityService;
import com.mwtstudios.nexuscore.storage.JsonStore;
import com.mwtstudios.nexuscore.teleport.TeleportService;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import org.slf4j.Logger;

/**
 * MinecraftForge entry point for the NexusCore Administration Framework.
 *
 * <p>One of only two files that differ from the NeoForge build (the other being the flight
 * controller). Every service, command, permission rule, and GUI screen is compiled from the
 * same shared sources, so a fix lands on all three loaders at once.</p>
 *
 * <p><b>Services are built here, in the constructor, rather than on a server-start event.</b>
 * {@code RegisterCommandsEvent} fires on a worker thread while the server's resources are
 * being assembled, which is <em>before</em> the server lifecycle events. Building services on
 * {@code ServerAboutToStartEvent} therefore leaves the command tree with nothing to register
 * against — a mistake already made once on NeoForge and caught by a real server run. The
 * services need only a data directory, and {@link FMLPaths#GAMEDIR} supplies that
 * immediately, so there is no ordering hazard to get wrong.</p>
 */
@Mod(NexusServices.MOD_ID)
public final class NexusCoreForge {

    /** Directory under the game directory holding every NexusCore data file. */
    public static final String DATA_DIRECTORY = "nexuscore";

    private static final Logger LOGGER = LogUtils.getLogger();

    private final NexusServices services;
    private final AdminGuiService gui;
    private final String displayName = "NexusCore Administration Framework";
    private final String version;

    /** Constructed by FML during mod loading. */
    public NexusCoreForge() {
        this.version = ModList.get().getModContainerById(NexusServices.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");

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
        PlayerUtilityService players = new PlayerUtilityService(new MayflyFlightController());
        ModerationService moderation = new ModerationService(store, System::currentTimeMillis);
        RateLimiter rateLimiter = new RateLimiter(settings.commandsPerMinute, System::currentTimeMillis);
        ConfirmationService confirmations =
                new ConfirmationService(settings.confirmationTimeoutSeconds, System::currentTimeMillis);

        this.services = new NexusServices(store, configuration, messages, identity, audit, permissions,
                teleport, players, moderation, rateLimiter, confirmations, version);
        this.gui = new AdminGuiService(services);

        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLogin);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerLogout);
        MinecraftForge.EVENT_BUS.addListener(this::onServerChat);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerDeath);

        LOGGER.info("NexusCore ready on Forge: data at {}, {} message(s), {} permission group(s), {} audit record(s)",
                dataRoot, messages.size(), permissions.groupNames().size(), audit.count());
        LOGGER.info("NexusCore flight mechanism: {}", players.flightMechanism());
        if (settings.operatorBootstrap) {
            LOGGER.warn("NexusCore operator bootstrap is ENABLED: any level-{} operator has full NexusCore access. "
                    + "Create real groups, then set operatorBootstrap=false in {}.",
                    NexusServices.OPERATOR_LEVEL, dataRoot.resolve(NexusSettings.FILE));
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        NexusCommands.register(event.getDispatcher(), services, gui, displayName, version);
    }

    private void onServerStarted(ServerStartedEvent event) {
        DeathMessages.takeOver(event.getServer(), services.settings());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        services.confirmations().clear();
        LOGGER.info("NexusCore stopped: {} audit record(s) written, chain {}",
                services.audit().count(), services.audit().verify().intact() ? "intact" : "BROKEN");
    }

    private void onServerTick(TickEvent.ServerTickEvent.Post event) {
        services.teleport().tick(event.getServer(), (player, outcome) -> {
            if (outcome.moved()) {
                player.sendSystemMessage(services.messages().render("teleport.done", "label", outcome.detail()));
            } else {
                player.sendSystemMessage(services.messages().render("teleport.failed", "reason", outcome.detail()));
            }
        });
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        services.identity().observe(player);
        services.moderation().activeBan(player.getUUID()).ifPresent(ban -> {
            String remaining = DurationParser.describeRemaining(ban.expiresAt(), System.currentTimeMillis());
            player.connection.disconnect(PunishmentMessages.banScreen(
                    services.messages(), services.settings(), ban, System.currentTimeMillis()));
            services.audit().record(null, "SYSTEM", "moderation.ban.enforced", "player",
                    player.getUUID().toString(), "denied", ban.reason(),
                    Map.of("remaining", remaining), UUID.randomUUID().toString());
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

    private void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        services.moderation().activeMute(player.getUUID()).ifPresent(mute -> {
            event.setCanceled(true);
            player.sendSystemMessage(PunishmentMessages.muteNotice(
                    services.messages(), mute, System.currentTimeMillis()));
        });
    }

    /** Records where a player died so {@code /back} returns them there, as on the other loaders. */
    private void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            services.teleport().recordReturnPoint(player.getUUID(), TeleportService.Location.of(player));
            DeathMessages.broadcast(player.server, services.messages(), services.settings(), player);
        }
    }
}
