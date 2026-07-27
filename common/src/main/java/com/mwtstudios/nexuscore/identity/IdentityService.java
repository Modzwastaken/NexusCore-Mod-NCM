package com.mwtstudios.nexuscore.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.mwtstudios.nexuscore.storage.JsonStore;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * UUID-first identity (§9.4).
 *
 * <p>Everything NexusCore persists is keyed by UUID, never by name. Names change; a player
 * who renames must keep their homes, their balance, and their ban. The name index here exists
 * only to answer "who did the operator mean when they typed this name", and it is explicit
 * about the case where the answer is ambiguous.</p>
 *
 * <p>Lookup order for an offline name is deliberate: the live player list first, then
 * NexusCore's own record of players who have joined this server, then the vanilla profile
 * cache. The middle step is what makes {@code /ban SomeoneWhoLeftLastWeek} work.</p>
 */
public final class IdentityService {

    /** File name under the NexusCore data directory. */
    public static final String FILE = "players.json";

    /** Current schema version of the identity document. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final JsonStore store;
    private final Document document;
    /** Lowercased current name to UUID. Rebuilt from the document, never persisted separately. */
    private final Map<String, UUID> nameIndex = new LinkedHashMap<>();

    /**
     * @param store the data store holding {@value #FILE}
     */
    public IdentityService(JsonStore store) {
        this.store = store;
        this.document = store.read(FILE, Document.class, Document::new);
        if (document.players == null) {
            document.players = new LinkedHashMap<>();
        }
        rebuildIndex();
    }

    /**
     * Records that a player is present, updating their name history and last-seen time.
     *
     * @param player the player who just joined
     */
    public void observe(ServerPlayer player) {
        observe(player.getUUID(), player.getGameProfile().getName());
    }

    /**
     * Records a UUID/name pairing.
     *
     * @param uuid the player's UUID
     * @param name their current name
     */
    public void observe(UUID uuid, String name) {
        Profile profile = document.players.computeIfAbsent(uuid.toString(), key -> new Profile());
        if (profile.knownNames == null) {
            profile.knownNames = new ArrayList<>();
        }
        boolean changed = !name.equals(profile.currentName);
        if (changed && profile.currentName != null && !profile.knownNames.contains(profile.currentName)) {
            profile.knownNames.add(profile.currentName);
        }
        profile.currentName = name;
        profile.lastSeenEpochMillis = System.currentTimeMillis();
        if (profile.firstSeenEpochMillis == 0L) {
            profile.firstSeenEpochMillis = profile.lastSeenEpochMillis;
        }
        rebuildIndex();
        save();
    }

    /** Records a player's departure time. */
    public void observeDeparture(UUID uuid) {
        Profile profile = document.players.get(uuid.toString());
        if (profile != null) {
            profile.lastSeenEpochMillis = System.currentTimeMillis();
            save();
        }
    }

    /**
     * @param uuid the player to look up
     * @return their most recently seen name, if NexusCore has ever seen them
     */
    public Optional<String> nameOf(UUID uuid) {
        Profile profile = document.players.get(uuid.toString());
        return Optional.ofNullable(profile == null ? null : profile.currentName);
    }

    /**
     * @param uuid the player to look up
     * @return their last known name, or a shortened UUID when the name is unknown — never
     *     null, so callers can always render something
     */
    public String displayName(UUID uuid) {
        return nameOf(uuid).orElseGet(() -> uuid.toString().substring(0, 8));
    }

    /**
     * Resolves a name typed by an operator into a UUID, <b>without ever blocking</b>.
     *
     * <p>Only sources that answer instantly are consulted: the online player list and
     * NexusCore's own record of everyone who has joined this server. The vanilla profile cache
     * is deliberately <em>not</em> queried here — {@code GameProfileCache.get(String)} performs
     * a synchronous Mojang HTTP lookup whenever the name is not already cached, and this method
     * runs on the server thread. Any player could stall the entire server for the length of that
     * request by typing {@code /seen <unknown-name>}.</p>
     *
     * <p>When this returns empty the caller should start {@link #resolveAsync}, which performs
     * the same lookup off-thread and files the result here, so a second attempt succeeds
     * locally.</p>
     *
     * @param server the running server, used for the online player list
     * @param name the name as typed
     * @return the resolved UUID, or empty when nobody by that name is known <em>locally</em>
     */
    public Optional<UUID> resolve(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return Optional.of(online.getUUID());
        }
        return resolveLocally(name);
    }

    /**
     * The offline half of {@link #resolve}, with no server dependency so it can be tested
     * directly.
     *
     * @param name the name as typed
     * @return the UUID NexusCore has recorded for that name, if any
     */
    public Optional<UUID> resolveLocally(String name) {
        return Optional.ofNullable(nameIndex.get(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * Resolves a name off the server thread, consulting the vanilla profile cache.
     *
     * <p>Uses {@code GameProfileCache.getAsync}, which runs the lookup on the background
     * executor and de-duplicates concurrent requests for the same name. The result is filed
     * into NexusCore's own index <em>on the server thread</em> — the identity document and its
     * name index are not thread-safe, and this completion arrives on a background thread.</p>
     *
     * @param server the running server
     * @param name the name as typed
     * @return a future completing with the UUID, or empty when the name does not exist
     */
    public CompletableFuture<Optional<UUID>> resolveAsync(MinecraftServer server, String name) {
        Optional<UUID> local = resolve(server, name);
        if (local.isPresent()) {
            return CompletableFuture.completedFuture(local);
        }
        if (server.getProfileCache() == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return server.getProfileCache().getAsync(name).thenApply(profile -> {
            if (profile.isEmpty()) {
                return Optional.<UUID>empty();
            }
            UUID uuid = profile.get().getId();
            String resolved = profile.get().getName();
            // Back to the server thread before touching the document or the index.
            server.execute(() -> observe(uuid, resolved));
            return Optional.of(uuid);
        });
    }

    /**
     * @param uuid the player
     * @return their recorded profile, if NexusCore has ever seen them
     */
    public Optional<KnownPlayer> profileOf(UUID uuid) {
        Profile profile = document.players.get(uuid.toString());
        if (profile == null) {
            return Optional.empty();
        }
        return Optional.of(new KnownPlayer(uuid, profile.currentName,
                profile.firstSeenEpochMillis, profile.lastSeenEpochMillis));
    }

    /**
     * @return every player NexusCore has recorded, newest activity first
     */
    public List<KnownPlayer> knownPlayers() {
        List<KnownPlayer> players = new ArrayList<>();
        for (Map.Entry<String, Profile> entry : document.players.entrySet()) {
            try {
                players.add(new KnownPlayer(UUID.fromString(entry.getKey()),
                        entry.getValue().currentName,
                        entry.getValue().firstSeenEpochMillis,
                        entry.getValue().lastSeenEpochMillis));
            } catch (IllegalArgumentException e) {
                // A malformed key is data corruption, not a crash reason. Skip it; the audit
                // of what was skipped is the document itself, which is left untouched.
                continue;
            }
        }
        players.sort((a, b) -> Long.compare(b.lastSeenEpochMillis(), a.lastSeenEpochMillis()));
        return players;
    }

    private void rebuildIndex() {
        nameIndex.clear();
        for (Map.Entry<String, Profile> entry : document.players.entrySet()) {
            String name = entry.getValue().currentName;
            if (name == null) {
                continue;
            }
            try {
                nameIndex.put(name.toLowerCase(Locale.ROOT), UUID.fromString(entry.getKey()));
            } catch (IllegalArgumentException e) {
                continue;
            }
        }
    }

    private void save() {
        document.schemaVersion = CURRENT_SCHEMA_VERSION;
        store.write(FILE, document);
    }

    /**
     * A player NexusCore has seen.
     *
     * @param uuid the stable identity
     * @param name the most recent name
     * @param firstSeenEpochMillis when they first joined
     * @param lastSeenEpochMillis when they were last seen
     */
    public record KnownPlayer(UUID uuid, String name, long firstSeenEpochMillis, long lastSeenEpochMillis) {
    }

    /** Persisted shape of {@value #FILE}. */
    static final class Document {
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        Map<String, Profile> players = new LinkedHashMap<>();
    }

    /** Persisted shape of one player record. */
    static final class Profile {
        String currentName;
        List<String> knownNames = new ArrayList<>();
        long firstSeenEpochMillis;
        long lastSeenEpochMillis;
    }
}
