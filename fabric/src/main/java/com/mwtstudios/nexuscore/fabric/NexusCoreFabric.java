package com.mwtstudios.nexuscore.fabric;

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
import com.mwtstudios.nexuscore.message.MessageService;
import com.mwtstudios.nexuscore.moderation.ModerationService;
import com.mwtstudios.nexuscore.permission.PermissionService;
import com.mwtstudios.nexuscore.player.PlayerUtilityService;
import com.mwtstudios.nexuscore.storage.JsonStore;
import com.mwtstudios.nexuscore.teleport.TeleportService;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;

/**
 * Fabric entry point for the NexusCore Administration Framework.
 *
 * <p>This is the <em>only</em> file that differs between the NeoForge and Fabric builds
 * beyond the flight controller. Every service, command, permission rule, and GUI screen is
 * compiled from the same shared sources, so a fix lands on both loaders at once.</p>
 *
 * <p>Registered as a {@code server} entrypoint: NexusCore is a dedicated-server
 * administration mod and has nothing to initialise on a client.</p>
 */
public final class NexusCoreFabric implements DedicatedServerModInitializer {

    /** Directory under the game directory holding every NexusCore data file. */
    public static final String DATA_DIRECTORY = "nexuscore";

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitializeServer() {
        String version = FabricLoader.getInstance()
                .getModContainer(com.mwtstudios.nexuscore.core.NexusServices.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        String displayName = "NexusCore Administration Framework";

        Path dataRoot = FabricLoader.getInstance().getGameDir().resolve(DATA_DIRECTORY);
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
        PlayerUtilityService players = new PlayerUtilityService(new FabricFlightController());
        ModerationService moderation = new ModerationService(store, System::currentTimeMillis);
        RateLimiter rateLimiter = new RateLimiter(settings.commandsPerMinute, System::currentTimeMillis);
        ConfirmationService confirmations =
                new ConfirmationService(settings.confirmationTimeoutSeconds, System::currentTimeMillis);

        NexusServices services = new NexusServices(store, configuration, messages, identity, audit, permissions,
                teleport, players, moderation, rateLimiter, confirmations, version);
        AdminGuiService gui = new AdminGuiService(services);

        LOGGER.info("NexusCore ready on Fabric: data at {}, {} message(s), {} permission group(s), {} audit record(s)",
                dataRoot, messages.size(), permissions.groupNames().size(), audit.count());
        LOGGER.info("NexusCore flight mechanism: {}", players.flightMechanism());
        if (settings.operatorBootstrap) {
            LOGGER.warn("NexusCore operator bootstrap is ENABLED: any level-{} operator has full NexusCore access. "
                    + "Create real groups, then set operatorBootstrap=false in {}.",
                    NexusServices.OPERATOR_LEVEL, dataRoot.resolve(NexusSettings.FILE));
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                NexusCommands.register(dispatcher, services, gui, displayName, version));

        ServerTickEvents.END_SERVER_TICK.register(server -> teleport.tick(server, (player, outcome) -> {
            if (outcome.moved()) {
                player.sendSystemMessage(messages.render("teleport.done", "label", outcome.detail()));
            } else {
                player.sendSystemMessage(messages.render("teleport.failed", "reason", outcome.detail()));
            }
        }));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            identity.observe(player);
            moderation.activeBan(player.getUUID()).ifPresent(ban -> {
                String remaining = DurationParser.describeRemaining(ban.expiresAt(), System.currentTimeMillis());
                player.connection.disconnect(messages.render(
                        ban.permanent() ? "moderation.ban.disconnect" : "moderation.tempban.disconnect",
                        "reason", ban.reason(), "remaining", remaining, "appeal", settings.banAppealMessage));
                audit.record(null, "SYSTEM", "moderation.ban.enforced", "player", player.getUUID().toString(),
                        "denied", ban.reason(), Map.of("remaining", remaining), UUID.randomUUID().toString());
            });
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUUID();
            identity.observeDeparture(id);
            teleport.forget(id);
            players.forget(id);
            rateLimiter.forget(id);
        });

        // Returning false cancels the message. The muted player is told why rather than
        // having their message silently vanish.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            var mute = moderation.activeMute(sender.getUUID());
            if (mute.isEmpty()) {
                return true;
            }
            sender.sendSystemMessage(messages.render("moderation.mute.blocked",
                    "reason", mute.get().reason(),
                    "remaining", DurationParser.describeRemaining(mute.get().expiresAt(), System.currentTimeMillis())));
            return false;
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            confirmations.clear();
            LOGGER.info("NexusCore stopped: {} audit record(s) written, chain {}",
                    audit.count(), audit.verify().intact() ? "intact" : "BROKEN");
        });
    }
}
