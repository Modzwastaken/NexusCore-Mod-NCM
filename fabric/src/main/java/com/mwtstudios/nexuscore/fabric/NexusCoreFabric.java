package com.mwtstudios.nexuscore.fabric;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import com.mojang.logging.LogUtils;
import com.mwtstudios.nexuscore.command.DurationParser;
import com.mwtstudios.nexuscore.command.NexusCommands;
import com.mwtstudios.nexuscore.core.NexusBootstrap;
import com.mwtstudios.nexuscore.core.NexusPlatform;
import com.mwtstudios.nexuscore.core.NexusServices;
import com.mwtstudios.nexuscore.gui.AdminGuiService;
import com.mwtstudios.nexuscore.message.DeathMessages;
import com.mwtstudios.nexuscore.moderation.PunishmentMessages;
import com.mwtstudios.nexuscore.module.ModuleManager;
import com.mwtstudios.nexuscore.platform.FlightController;
import com.mwtstudios.nexuscore.platform.MayflyFlightController;
import com.mwtstudios.nexuscore.teleport.TeleportService;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
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
 * <p><b>Registered as a {@code main} entrypoint, deliberately.</b> An earlier version used
 * {@code DedicatedServerModInitializer} on the reasoning that NexusCore is a server mod with
 * nothing to do on a client. That was wrong, and it made the mod do nothing at all in
 * singleplayer and on an opened LAN world: those run an <em>integrated</em> server, which is
 * not a dedicated one, so the server entrypoint never fired. The mod loaded, listed itself,
 * and was silent.</p>
 *
 * <p>Every hook below keys off the {@link net.minecraft.server.MinecraftServer} lifecycle, and
 * an integrated server fires all of them, so {@code main} is both correct and the only variant
 * that matches how the NeoForge build behaves.</p>
 */
public final class NexusCoreFabric implements ModInitializer {

    /** Directory under the game directory holding every NexusCore data file. */
    public static final String DATA_DIRECTORY = "nexuscore";

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        String version = FabricLoader.getInstance()
                .getModContainer(com.mwtstudios.nexuscore.core.NexusServices.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        String displayName = "NexusCore Administration Framework";

        NexusBootstrap.Started started = NexusBootstrap.start(new FabricPlatform(), version);
        NexusServices services = started.services();
        ModuleManager modules = started.modules();
        AdminGuiService gui = new AdminGuiService(services);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                NexusCommands.register(dispatcher, services, gui, displayName, version));

        // Guarded: safe mode leaves teleport unstarted and this runs every tick.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!services.has("teleport")) {
                return;
            }
            services.teleport().tick(server, (player, outcome) -> {
                if (outcome.moved()) {
                    player.sendSystemMessage(services.messages().render("teleport.done", "label", outcome.detail()));
                } else {
                    player.sendSystemMessage(services.messages().render("teleport.failed", "reason", outcome.detail()));
                }
            });
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            services.identity().observe(player);
            if (services.has("player-utilities")) {
                // Guarded like the handlers around it: throwing here would stop players joining.
                services.players().hideVanishedFrom(server, player);
            }
            // Guarded: throwing here would stop players joining entirely.
            if (!services.has("moderation")) {
                return;
            }
            services.moderation().activeBan(player.getUUID()).ifPresent(ban -> {
                String remaining = DurationParser.describeRemaining(ban.expiresAt(), System.currentTimeMillis());
                player.connection.disconnect(PunishmentMessages.banScreen(
                        services.messages(), services.settings(), ban, System.currentTimeMillis()));
                services.audit().record(null, "SYSTEM", "moderation.ban.enforced", "player", player.getUUID().toString(),
                        "denied", ban.reason(), Map.of("remaining", remaining), UUID.randomUUID().toString());
            });
        });

        // Respawning rebuilds the entity, so vanish has to be put back on it.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (services.has("player-utilities")) {
                services.players().reapplyVanish(newPlayer.server, newPlayer);
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUUID();
            services.identity().observeDeparture(id);
            if (services.has("teleport")) {
                services.teleport().forget(id);
            }
            if (services.has("player-utilities")) {
                services.players().forget(id);
            }
            services.rateLimiter().forget(id);
        });

        // Returning false cancels the message. The muted player is told why rather than
        // having their message silently vanish.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (!services.has("moderation")) {
                return true;
            }
            var mute = services.moderation().activeMute(sender.getUUID());
            if (mute.isEmpty()) {
                return true;
            }
            sender.sendSystemMessage(PunishmentMessages.muteNotice(
                    services.messages(), mute.get(), System.currentTimeMillis()));
            return false;
        });

        // Parity with the NeoForge build: /back returns you to where you died, which is the
        // behaviour players actually expect and the one case where no teleport happened.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof ServerPlayer player) {
                if (services.has("teleport")) {
                    services.teleport().recordReturnPoint(player.getUUID(), TeleportService.Location.of(player));
                }
                DeathMessages.broadcast(player.server, services.messages(), services.settings(), player);
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                DeathMessages.takeOver(server, services.settings()));

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            services.confirmations().clear();
            LOGGER.info("NexusCore stopped: {} audit record(s) written, chain {}",
                    services.audit().count(), services.audit().verify().intact() ? "intact" : "BROKEN");
            modules.stop();
        });
    }

    /** Fabric's half of {@link NexusPlatform}. */
    private record FabricPlatform() implements NexusPlatform {

        @Override
        public String name() {
            return "Fabric";
        }

        @Override
        public Path dataRoot() {
            return FabricLoader.getInstance().getGameDir().resolve(DATA_DIRECTORY);
        }

        @Override
        public FlightController flightController() {
            return new MayflyFlightController();
        }

        @Override
        public String flightDescription() {
            return "Abilities.mayfly (single flag — may conflict with other flight mods)";
        }
    }
}
