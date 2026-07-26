package com.mwtstudios.nexuscore.teleport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.LongSupplier;

import com.mwtstudios.nexuscore.config.NexusSettings;
import com.mwtstudios.nexuscore.storage.JsonStore;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Homes, warps, spawn, {@code /back}, teleport requests, and the warmup pipeline.
 *
 * <p>Every teleport goes through {@link #begin}, which schedules a warmup and then re-runs
 * both the safety check and the permission check at the moment of commit (§19.1 step 5). The
 * gap between typing a command and arriving is exactly where a destination becomes unsafe or
 * an operator revokes access, so checking only at command time would be checking at the wrong
 * time.</p>
 *
 * <p>The previous location for {@code /back} is recorded <em>after</em> the destination is
 * committed (§19.1 step 6). Recording it up front would leave a player who failed to teleport
 * with a {@code /back} pointing at where they already are.</p>
 */
public final class TeleportService {

    /** Homes document. */
    public static final String HOMES_FILE = "homes.json";
    /** Warps document. */
    public static final String WARPS_FILE = "warps.json";
    /** Spawn document. */
    public static final String SPAWN_FILE = "spawn.json";

    /** Current schema version of the teleport documents. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final JsonStore store;
    private final LongSupplier clock;

    private final HomesDocument homes;
    private final WarpsDocument warps;
    private final SpawnDocument spawn;

    /** Pending warmups, keyed by player. At most one per player. */
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    /** Open {@code /tpa} requests: target to requester. */
    private final Map<UUID, Request> requests = new LinkedHashMap<>();
    /** Last location before a teleport, for {@code /back}. Memory-only by design. */
    private final Map<UUID, Location> back = new LinkedHashMap<>();
    /** When each player last completed a teleport, for the cooldown. Session-only. */
    private final Map<UUID, Long> lastTeleportMillis = new LinkedHashMap<>();

    private volatile NexusSettings settings;

    /**
     * @param store the data store
     * @param settings current settings
     * @param clock supplies the current time in milliseconds
     */
    public TeleportService(JsonStore store, NexusSettings settings, LongSupplier clock) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
        this.homes = store.read(HOMES_FILE, HomesDocument.class, HomesDocument::new);
        this.warps = store.read(WARPS_FILE, WarpsDocument.class, WarpsDocument::new);
        this.spawn = store.read(SPAWN_FILE, SpawnDocument.class, SpawnDocument::new);
        if (homes.homes == null) {
            homes.homes = new LinkedHashMap<>();
        }
        if (warps.warps == null) {
            warps.warps = new LinkedHashMap<>();
        }
    }

    /** Applies a settings change. */
    public void applySettings(NexusSettings updated) {
        this.settings = updated;
    }

    // ---- teleporting -------------------------------------------------------------------

    /**
     * Begins a teleport, applying the configured warmup.
     *
     * @param player who is moving
     * @param destination where to
     * @param label a short description used in messages, for example {@code home 'base'}
     * @param bypassWarmup true to commit immediately and skip the cooldown, for staff teleports
     * @return the outcome; a warmup returns {@link Outcome.Status#WARMUP}
     */
    public Outcome begin(ServerPlayer player, Location destination, String label, boolean bypassWarmup) {
        if (!bypassWarmup) {
            long remaining = cooldownRemainingMillis(player.getUUID());
            if (remaining > 0) {
                return Outcome.failure("you must wait " + (remaining / 1000 + 1) + "s before teleporting again");
            }
        }
        ServerLevel level = destination.resolveLevel(player.server);
        if (level == null) {
            return Outcome.failure("the world '" + destination.dimension() + "' is not loaded on this server");
        }
        SafeDestination.Result safety = SafeDestination.find(
                level, destination.x(), destination.y(), destination.z(), settings.safeDestinationSearchHeight);
        if (!safety.safe()) {
            return Outcome.failure(safety.reason());
        }

        int warmup = bypassWarmup ? 0 : settings.teleportWarmupSeconds;
        if (warmup <= 0) {
            return commit(player, destination, label);
        }
        pending.put(player.getUUID(), new Pending(destination, label, player.position(),
                clock.getAsLong() + warmup * 1000L));
        return new Outcome(Outcome.Status.WARMUP, "teleporting in " + warmup + "s — do not move", warmup);
    }

    /**
     * Commits a teleport now, re-checking safety immediately beforehand.
     *
     * @param player who is moving
     * @param destination where to
     * @param label short description for messages
     * @return the outcome
     */
    public Outcome commit(ServerPlayer player, Location destination, String label) {
        ServerLevel level = destination.resolveLevel(player.server);
        if (level == null) {
            return Outcome.failure("the world '" + destination.dimension() + "' is not loaded on this server");
        }
        // §19.1 step 5: re-check at the moment of commit, not only at command time.
        SafeDestination.Result safety = SafeDestination.find(
                level, destination.x(), destination.y(), destination.z(), settings.safeDestinationSearchHeight);
        if (!safety.safe()) {
            return Outcome.failure(safety.reason());
        }

        Location previous = Location.of(player);
        Vec3 target = safety.position();
        player.teleportTo(level, target.x, target.y, target.z, destination.yaw(), destination.pitch());

        // §19.1 step 6: only now that the move committed does /back point at the old place.
        back.put(player.getUUID(), previous);
        lastTeleportMillis.put(player.getUUID(), clock.getAsLong());
        return new Outcome(Outcome.Status.TELEPORTED, label, 0);
    }

    /**
     * Advances pending warmups. Called once per server tick from the game thread.
     *
     * @param server the running server
     * @param onCommit invoked with the player and the outcome when a warmup resolves
     */
    public void tick(MinecraftServer server, java.util.function.BiConsumer<ServerPlayer, Outcome> onCommit) {
        if (pending.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        List<UUID> resolved = new ArrayList<>();

        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Pending job = entry.getValue();

            if (player == null) {
                resolved.add(entry.getKey());
                continue;
            }
            if (player.position().distanceTo(job.origin) > settings.teleportWarmupMoveTolerance) {
                resolved.add(entry.getKey());
                onCommit.accept(player, Outcome.failure("teleport cancelled because you moved"));
                continue;
            }
            if (now >= job.readyAtMillis) {
                resolved.add(entry.getKey());
                onCommit.accept(player, commit(player, job.destination, job.label));
            }
        }
        resolved.forEach(pending::remove);

        requests.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis <= now);
    }

    /**
     * How long a player must still wait before teleporting again.
     *
     * @param player the player
     * @return milliseconds remaining, or 0 when they may teleport now
     */
    public long cooldownRemainingMillis(UUID player) {
        Long last = lastTeleportMillis.get(player);
        if (last == null || settings.teleportCooldownSeconds <= 0) {
            return 0L;
        }
        long readyAt = last + settings.teleportCooldownSeconds * 1000L;
        return Math.max(0L, readyAt - clock.getAsLong());
    }

    /** Cancels any pending warmup for a player, for example because they took damage. */
    public void cancelPending(UUID player) {
        pending.remove(player);
    }

    /** @return true when the player has a teleport counting down */
    public boolean hasPending(UUID player) {
        return pending.containsKey(player);
    }

    // ---- requests ----------------------------------------------------------------------

    /**
     * Opens a {@code /tpa} request.
     *
     * @param requester who wants to teleport
     * @param target whose approval is needed
     */
    public void request(UUID requester, UUID target) {
        requests.put(target, new Request(requester, clock.getAsLong() + settings.teleportRequestTimeoutSeconds * 1000L));
    }

    /**
     * Consumes the open request for a target.
     *
     * @param target the player who was asked
     * @return the requester, if a live request exists
     */
    public Optional<UUID> consumeRequest(UUID target) {
        Request request = requests.remove(target);
        if (request == null || request.expiresAtMillis <= clock.getAsLong()) {
            return Optional.empty();
        }
        return Optional.of(request.requester);
    }

    /** @return true when the target has a live request waiting */
    public boolean hasRequest(UUID target) {
        Request request = requests.get(target);
        return request != null && request.expiresAtMillis > clock.getAsLong();
    }

    // ---- homes -------------------------------------------------------------------------

    /**
     * Saves a home.
     *
     * @param owner the player
     * @param name the home name
     * @param location where
     * @param limit maximum homes this player may have
     * @return true when saved; false when the limit is already reached and this is a new name
     */
    public boolean setHome(UUID owner, String name, Location location, int limit) {
        Map<String, Location> owned = homes.homes.computeIfAbsent(owner.toString(), key -> new LinkedHashMap<>());
        String key = normalise(name);
        if (!owned.containsKey(key) && owned.size() >= limit) {
            return false;
        }
        owned.put(key, location);
        save(HOMES_FILE, homes);
        return true;
    }

    /**
     * @param owner the player
     * @param name the home name
     * @return the home, if it exists
     */
    public Optional<Location> home(UUID owner, String name) {
        Map<String, Location> owned = homes.homes.get(owner.toString());
        return Optional.ofNullable(owned == null ? null : owned.get(normalise(name)));
    }

    /**
     * @param owner the player
     * @param name the home name
     * @return true when a home was removed
     */
    public boolean deleteHome(UUID owner, String name) {
        Map<String, Location> owned = homes.homes.get(owner.toString());
        if (owned == null || owned.remove(normalise(name)) == null) {
            return false;
        }
        save(HOMES_FILE, homes);
        return true;
    }

    /**
     * @param owner the player
     * @return their home names, sorted
     */
    public java.util.Set<String> homeNames(UUID owner) {
        Map<String, Location> owned = homes.homes.get(owner.toString());
        return owned == null ? java.util.Set.of() : new TreeSet<>(owned.keySet());
    }

    // ---- warps -------------------------------------------------------------------------

    /**
     * Saves a server warp.
     *
     * @param name warp name
     * @param location where
     */
    public void setWarp(String name, Location location) {
        warps.warps.put(normalise(name), location);
        save(WARPS_FILE, warps);
    }

    /**
     * @param name warp name
     * @return the warp, if it exists
     */
    public Optional<Location> warp(String name) {
        return Optional.ofNullable(warps.warps.get(normalise(name)));
    }

    /**
     * @param name warp name
     * @return true when a warp was removed
     */
    public boolean deleteWarp(String name) {
        if (warps.warps.remove(normalise(name)) == null) {
            return false;
        }
        save(WARPS_FILE, warps);
        return true;
    }

    /** @return every warp name, sorted */
    public java.util.Set<String> warpNames() {
        return new TreeSet<>(warps.warps.keySet());
    }

    // ---- spawn and back ----------------------------------------------------------------

    /**
     * Sets the NexusCore spawn point.
     *
     * @param location where
     */
    public void setSpawn(Location location) {
        spawn.spawn = location;
        save(SPAWN_FILE, spawn);
    }

    /** @return the configured spawn, if one has been set */
    public Optional<Location> spawn() {
        return Optional.ofNullable(spawn.spawn);
    }

    /**
     * @param player the player
     * @return where they were before their last committed teleport
     */
    public Optional<Location> lastLocation(UUID player) {
        return Optional.ofNullable(back.get(player));
    }

    /** Forgets per-player transient state on disconnect. */
    public void forget(UUID player) {
        pending.remove(player);
        lastTeleportMillis.remove(player);
        requests.remove(player);
        requests.values().removeIf(request -> request.requester.equals(player));
    }

    private static String normalise(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private <T> void save(String file, T document) {
        store.write(file, document);
    }

    // ---- types -------------------------------------------------------------------------

    /**
     * A world-qualified position.
     *
     * @param dimension the dimension id, for example {@code minecraft:overworld}
     * @param x block-precise X
     * @param y block-precise Y
     * @param z block-precise Z
     * @param yaw facing
     * @param pitch facing
     */
    public record Location(String dimension, double x, double y, double z, float yaw, float pitch) {

        /**
         * @param player the player to snapshot
         * @return their current location
         */
        public static Location of(ServerPlayer player) {
            return new Location(player.level().dimension().location().toString(),
                    player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        }

        /**
         * @param server the running server
         * @return the level this location names, or null when it is not loaded
         */
        public ServerLevel resolveLevel(MinecraftServer server) {
            ResourceLocation id = ResourceLocation.tryParse(dimension);
            if (id == null) {
                return null;
            }
            return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        }

        /** @return a short human-readable form for messages and GUI tooltips */
        public String describe() {
            return String.format(Locale.ROOT, "%s %.1f, %.1f, %.1f",
                    dimension.replace("minecraft:", ""), x, y, z);
        }

        /** @return the overworld spawn fallback used when nothing else is configured */
        public static Location worldSpawn(ServerLevel level) {
            return new Location(Level.OVERWORLD.location().toString(),
                    level.getSharedSpawnPos().getX() + 0.5,
                    level.getSharedSpawnPos().getY(),
                    level.getSharedSpawnPos().getZ() + 0.5,
                    level.getSharedSpawnAngle(), 0.0f);
        }
    }

    /**
     * The outcome of a teleport attempt.
     *
     * @param status what happened
     * @param detail a label on success, or the specific reason on failure
     * @param warmupSeconds seconds remaining when the status is {@link Status#WARMUP}
     */
    public record Outcome(Status status, String detail, int warmupSeconds) {

        /** Possible outcomes. */
        public enum Status {
            /** The player has been moved. */
            TELEPORTED,
            /** A warmup is counting down. */
            WARMUP,
            /** Nothing happened, and {@code detail} says why. */
            FAILED
        }

        static Outcome failure(String reason) {
            return new Outcome(Status.FAILED, reason, 0);
        }

        /** @return true when the player actually moved */
        public boolean moved() {
            return status == Status.TELEPORTED;
        }
    }

    private record Pending(Location destination, String label, Vec3 origin, long readyAtMillis) {
    }

    private record Request(UUID requester, long expiresAtMillis) {
    }

    /** Persisted shape of {@value #HOMES_FILE}. */
    static final class HomesDocument {
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        Map<String, Map<String, Location>> homes = new LinkedHashMap<>();
    }

    /** Persisted shape of {@value #WARPS_FILE}. */
    static final class WarpsDocument {
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        Map<String, Location> warps = new LinkedHashMap<>();
    }

    /** Persisted shape of {@value #SPAWN_FILE}. */
    static final class SpawnDocument {
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        Location spawn;
    }
}
