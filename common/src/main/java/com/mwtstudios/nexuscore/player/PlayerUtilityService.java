package com.mwtstudios.nexuscore.player;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.mwtstudios.nexuscore.platform.FlightController;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The staff quality-of-life toolkit: heal, feed, fly, god, speed, vanish, and player info.
 *
 * <p>Each of these mutates live entity state, so every method here must be called on the
 * server thread (§16.3). Commands already run there; nothing in this class may be reached
 * from a background task without hopping back first.</p>
 */
public final class PlayerUtilityService {

    /** Minimum walk/fly speed multiplier accepted. */
    public static final float MIN_SPEED = 0.1f;
    /** Maximum walk/fly speed multiplier accepted. Above this, players fall out of loaded chunks. */
    public static final float MAX_SPEED = 10.0f;

    /** Vanilla defaults, restored by {@code /speed reset}. */
    private static final float DEFAULT_WALK_SPEED = 0.1f;
    private static final float DEFAULT_FLY_SPEED = 0.05f;

    /** Vanished players. Session-only: vanish does not survive a restart, by design. */
    private final Set<UUID> vanished = new LinkedHashSet<>();

    /** Supplied by the platform, because flight is the one thing loaders genuinely differ on. */
    private final FlightController flight;

    /**
     * @param flight the platform's flight mechanism
     */
    public PlayerUtilityService(FlightController flight) {
        this.flight = flight;
    }

    /** @return how this platform grants flight, for diagnostics */
    public String flightMechanism() {
        return flight.describe();
    }

    /**
     * Restores health, hunger, saturation, and extinguishes the player.
     *
     * @param player the target
     */
    public void heal(ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0f);
        player.clearFire();
        player.removeAllEffects();
    }

    /**
     * Restores hunger and saturation only.
     *
     * @param player the target
     */
    public void feed(ServerPlayer player) {
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0f);
    }

    /**
     * Toggles the ability to fly.
     *
     * @param player the target
     * @param enabled true to allow flight
     */
    public void setFlight(ServerPlayer player, boolean enabled) {
        flight.setFlight(player, enabled);
    }

    /**
     * @param player the target
     * @return true when the player may currently fly, from any source
     */
    public boolean canFly(ServerPlayer player) {
        return flight.canFly(player);
    }

    /**
     * @param player the target
     * @return true when NexusCore specifically is what is granting flight
     */
    public boolean hasNexusFlight(ServerPlayer player) {
        return flight.hasNexusFlight(player);
    }

    /**
     * Toggles damage immunity.
     *
     * @param player the target
     * @param enabled true to make them invulnerable
     */
    public void setGodMode(ServerPlayer player, boolean enabled) {
        player.getAbilities().invulnerable = enabled;
        player.onUpdateAbilities();
    }

    /**
     * @param player the target
     * @return true when the player is currently invulnerable
     */
    public boolean isGodMode(ServerPlayer player) {
        return player.getAbilities().invulnerable;
    }

    /**
     * Sets movement speed as a multiplier of the vanilla default.
     *
     * @param player the target
     * @param multiplier between {@value #MIN_SPEED} and {@value #MAX_SPEED}
     * @param flying true to change fly speed, false for walk speed
     * @throws IllegalArgumentException when the multiplier is out of range
     */
    public void setSpeed(ServerPlayer player, float multiplier, boolean flying) {
        if (multiplier < MIN_SPEED || multiplier > MAX_SPEED) {
            throw new IllegalArgumentException("speed must be between " + MIN_SPEED + " and " + MAX_SPEED);
        }
        if (flying) {
            player.getAbilities().setFlyingSpeed(DEFAULT_FLY_SPEED * multiplier);
        } else {
            player.getAbilities().setWalkingSpeed(DEFAULT_WALK_SPEED * multiplier);
        }
        player.onUpdateAbilities();
    }

    /**
     * Restores vanilla movement speeds.
     *
     * @param player the target
     */
    public void resetSpeed(ServerPlayer player) {
        player.getAbilities().setFlyingSpeed(DEFAULT_FLY_SPEED);
        player.getAbilities().setWalkingSpeed(DEFAULT_WALK_SPEED);
        player.onUpdateAbilities();
    }

    // ---- vanish ------------------------------------------------------------------------

    /**
     * Hides or reveals a staff member.
     *
     * <p><b>What this does and does not do.</b> It makes the player invisible and removes
     * them from the player list every other client renders. It does not make them
     * undetectable: a player standing in a doorway still blocks it, and a client mod that
     * tracks entity positions will still see them. Vanish is a staff convenience, not a
     * cloaking device, and the admin documentation says so rather than implying otherwise.</p>
     *
     * @param server the running server
     * @param player the staff member
     * @param hidden true to vanish
     */
    public void setVanished(MinecraftServer server, ServerPlayer player, boolean hidden) {
        if (hidden) {
            vanished.add(player.getUUID());
            player.setInvisible(true);
            broadcastExcept(server, player, new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
        } else {
            vanished.remove(player.getUUID());
            player.setInvisible(false);
            broadcastExcept(server, player,
                    ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
        }
    }

    /**
     * Hides everyone currently vanished from a player who has just joined.
     *
     * <p>Vanilla sends a new client the whole player list, so a staff member who vanished
     * before that client connected appeared in their list anyway. Vanish held only for the
     * clients that were already connected when it was switched on, which made it look like it
     * randomly stopped working.</p>
     *
     * @param server the running server
     * @param joiner the player who has just connected
     */
    public void hideVanishedFrom(MinecraftServer server, ServerPlayer joiner) {
        List<UUID> hidden = vanished.stream().filter(uuid -> !uuid.equals(joiner.getUUID())).toList();
        if (hidden.isEmpty()) {
            return;
        }
        joiner.connection.send(new ClientboundPlayerInfoRemovePacket(hidden));
        for (UUID uuid : hidden) {
            ServerPlayer hiddenPlayer = server.getPlayerList().getPlayer(uuid);
            if (hiddenPlayer != null) {
                hiddenPlayer.setInvisible(true);
            }
        }
    }

    /**
     * Re-applies vanish to a player whose entity has just been rebuilt.
     *
     * <p>Respawning replaces the {@code ServerPlayer}, so the invisibility flag was lost while
     * the vanished set still held the UUID. NexusCore then believed the staff member was hidden
     * — {@code /list} and {@code /near} filtered them out — while every client could see them
     * standing there. The two halves of the state disagreed, and the visible half was the wrong
     * one.</p>
     *
     * @param server the running server
     * @param player the player after respawn
     */
    public void reapplyVanish(MinecraftServer server, ServerPlayer player) {
        if (!isVanished(player.getUUID())) {
            return;
        }
        player.setInvisible(true);
        broadcastExcept(server, player, new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
    }

    /**
     * @param player the player's UUID
     * @return true when they are currently vanished
     */
    public boolean isVanished(UUID player) {
        return vanished.contains(player);
    }

    /** @return every vanished player's UUID */
    public Set<UUID> vanishedPlayers() {
        return Set.copyOf(vanished);
    }

    /** Clears vanish state for a player who disconnected. */
    public void forget(UUID player) {
        vanished.remove(player);
    }

    private static void broadcastExcept(MinecraftServer server, ServerPlayer excluded,
            net.minecraft.network.protocol.Packet<?> packet) {
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (!other.getUUID().equals(excluded.getUUID())) {
                other.connection.send(packet);
            }
        }
    }

    // ---- info --------------------------------------------------------------------------

    /**
     * Collects a diagnostic snapshot of a player.
     *
     * @param player the target
     * @return the snapshot
     */
    public Snapshot describe(ServerPlayer player) {
        return new Snapshot(
                player.getGameProfile().getName(),
                player.getUUID(),
                player.gameMode.getGameModeForPlayer().getName(),
                player.getHealth(),
                player.getMaxHealth(),
                player.getFoodData().getFoodLevel(),
                player.level().dimension().location().toString(),
                String.format(Locale.ROOT, "%.1f, %.1f, %.1f", player.getX(), player.getY(), player.getZ()),
                player.connection.latency(),
                flight.canFly(player),
                player.getAbilities().invulnerable,
                isVanished(player.getUUID()));
    }

    /**
     * A point-in-time view of a player, safe to hand to a GUI or a command.
     *
     * @param name current name
     * @param uuid stable identity
     * @param gameMode current game mode
     * @param health current health
     * @param maxHealth maximum health
     * @param foodLevel current hunger
     * @param dimension current world
     * @param position formatted coordinates
     * @param latencyMillis measured ping
     * @param canFly whether flight is enabled
     * @param godMode whether they are invulnerable
     * @param vanished whether they are hidden
     */
    public record Snapshot(String name, UUID uuid, String gameMode, float health, float maxHealth, int foodLevel,
            String dimension, String position, int latencyMillis, boolean canFly, boolean godMode, boolean vanished) {
    }
}
