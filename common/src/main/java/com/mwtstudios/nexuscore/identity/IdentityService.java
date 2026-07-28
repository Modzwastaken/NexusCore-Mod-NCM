package com.mwtstudios.nexuscore.identity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

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
 * <p>Lookup order for an offline name is deliberate, and it stops at sources that answer
 * <em>instantly</em>: the live player list first, then NexusCore's own record of players who have
 * joined this server. The middle step is what makes {@code /ban SomeoneWhoLeftLastWeek} work. The
 * vanilla profile cache is <b>not</b> consulted by {@link #resolve} — it performs a synchronous
 * Mojang HTTP request on a cache miss, on the server thread. It is reached only through
 * {@link #resolveAsync}, off-thread.</p>
 */
public final class IdentityService {

    /** File name under the NexusCore data directory. */
    public static final String FILE = "players.json";

    /** Current schema version of the identity document. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final JsonStore store;
    private final LongSupplier clock;
    private final Document document;

    /** Set when the document has changed since the last write; cleared by every write. */
    private boolean dirty;

    /** When the document was last written, on the injected clock. */
    private long lastWriteAt;

    /** How long a damped change may wait before it is written anyway. */
    private long flushIntervalMillis = DEFAULT_FLUSH_INTERVAL_MILLIS;
    /** Lowercased current name to UUID. Rebuilt from the document, never persisted separately. */
    private final Map<String, UUID> nameIndex = new LinkedHashMap<>();

    /**
     * Lowercased names a lookup reported as non-existent, and when. Deliberately in memory only
     * and bounded: it exists so a retry can be told "no such account" instead of "still looking",
     * not to be a durable record of every typo an operator has ever made.
     */
    private final Map<String, Long> recentMisses = new LinkedHashMap<>();

    private static final long MISS_MEMORY_MILLIS = 10L * 60L * 1000L;
    private static final int MAX_REMEMBERED_MISSES = 256;

    /**
     * How long a last-seen update may sit unwritten. A rejoining player only moves a timestamp, and
     * paying a full document rewrite plus a backup copy and an fsync for that — on every join AND
     * every leave — is the write amplification this bounds.
     */
    private static final long DEFAULT_FLUSH_INTERVAL_MILLIS = 30_000L;

    /**
     * @param store the data store holding {@value #FILE}
     */
    public IdentityService(JsonStore store) {
        this(store, System::currentTimeMillis);
    }

    /**
     * @param store the data store holding {@value #FILE}
     * @param clock supplies epoch milliseconds, injected so a test can drive the damping window
     *     without sleeping — the same reason {@code RateLimiter} and {@code ModerationService} take one
     */
    public IdentityService(JsonStore store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
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
        boolean isNew = profile.firstSeenEpochMillis == 0L;
        profile.currentName = name;
        profile.lastSeenEpochMillis = clock.getAsLong();
        if (isNew) {
            profile.firstSeenEpochMillis = profile.lastSeenEpochMillis;
        }
        rebuildIndex();
        if (isNew || changed) {
            // A player this document has never held, or a name the index now resolves by. Facts,
            // not timestamps — a crash must not lose them.
            saveNow();
        } else {
            saveDamped();
        }
    }

    /**
     * Records a UUID/name pairing learned from a <em>lookup</em> rather than from a visit.
     *
     * <p>Deliberately does not touch first- or last-seen. {@link #observe} means "this player was
     * here just now"; a lookup means only "this account exists and this is its name". Filing a
     * pre-ban lookup through {@code observe} made {@code /seen} report a player who has never
     * connected as first joining today and last seen moments ago.</p>
     *
     * @param uuid the player's UUID
     * @param name their current name
     */
    public void observeLookup(UUID uuid, String name) {
        Profile profile = document.players.computeIfAbsent(uuid.toString(), key -> new Profile());
        if (profile.knownNames == null) {
            profile.knownNames = new ArrayList<>();
        }
        boolean renamed = !name.equals(profile.currentName);
        if (renamed && profile.currentName != null && !profile.knownNames.contains(profile.currentName)) {
            profile.knownNames.add(profile.currentName);
        }
        boolean isNew = profile.firstSeenEpochMillis == 0L && profile.knownNames.isEmpty();
        profile.currentName = name;
        recentMisses.remove(name.toLowerCase(Locale.ROOT));
        rebuildIndex();
        if (isNew || renamed) {
            saveNow();
        } else {
            saveDamped();
        }
    }

    /**
     * Records that a lookup found no such account, so the next attempt can say so instead of
     * promising again that one is under way.
     */
    private void recordMiss(String name) {
        if (recentMisses.size() >= MAX_REMEMBERED_MISSES) {
            var oldest = recentMisses.keySet().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        recentMisses.put(name.toLowerCase(Locale.ROOT), clock.getAsLong());
    }

    /**
     * @param name the name as typed
     * @return true when a recent lookup established that no account by this name exists
     */
    public boolean recentlyMissed(String name) {
        Long at = recentMisses.get(name.toLowerCase(Locale.ROOT));
        if (at == null) {
            return false;
        }
        if (clock.getAsLong() - at > MISS_MEMORY_MILLIS) {
            recentMisses.remove(name.toLowerCase(Locale.ROOT));
            return false;
        }
        return true;
    }

    /** Records a player's departure time. */
    public void observeDeparture(UUID uuid) {
        Profile profile = document.players.get(uuid.toString());
        if (profile != null) {
            profile.lastSeenEpochMillis = clock.getAsLong();
            // Purely a timestamp. The flush on module stop is what makes damping this safe.
            saveDamped();
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
        return resolveAsync(vanillaLookup(server), server, server::isStopped, name);
    }

    /**
     * The testable core of {@link #resolveAsync}: every dependency on a running server is a
     * parameter, so a test can complete a lookup on demand and observe what gets filed.
     *
     * @param lookup where an unknown name is looked up, off-thread
     * @param onServerThread runs a task on the server thread
     * @param stopped whether the server has begun shutting down
     * @param name the name as typed
     * @return a future completing with the UUID, or empty when the name does not exist
     */
    CompletableFuture<Optional<UUID>> resolveAsync(ProfileLookup lookup, Executor onServerThread,
            BooleanSupplier stopped, String name) {
        return lookup.lookup(name).thenApply(resolved -> {
            if (resolved.isEmpty()) {
                // Vanilla caches only positive results, so without this the operator gets the
                // same "looking it up" answer forever and can never tell a name that does not
                // exist from one still in flight. Recorded on the server thread like any other
                // mutation.
                fileOnServerThread(onServerThread, stopped, () -> recordMiss(name));
                return Optional.<UUID>empty();
            }
            UUID uuid = resolved.get().uuid();
            String canonical = resolved.get().name();
            fileOnServerThread(onServerThread, stopped, () -> observeLookup(uuid, canonical));
            return Optional.of(uuid);
        });
    }

    /**
     * Runs a document mutation on the server thread — unless the server is stopping, in which
     * case it is dropped.
     *
     * <p>{@code MinecraftServer.execute} does <b>not</b> guarantee the server thread: the
     * underlying event loop runs a task inline on the calling thread once {@code isStopped()}
     * is true, and {@code stopped} is set before the shutdown save. A lookup completing in that
     * window would therefore rewrite {@value #FILE} from a background thread while the server
     * thread is saving the same file. Nothing is lost by dropping it: the record only caches a
     * name lookup for a player who has not joined.</p>
     */
    private static void fileOnServerThread(Executor onServerThread, BooleanSupplier stopped, Runnable work) {
        if (stopped.getAsBoolean()) {
            return;
        }
        onServerThread.execute(() -> {
            if (!stopped.getAsBoolean()) {
                work.run();
            }
        });
    }

    /** Adapts the vanilla profile cache to {@link ProfileLookup}. */
    private static ProfileLookup vanillaLookup(MinecraftServer server) {
        var cache = server.getProfileCache();
        if (cache == null) {
            return name -> CompletableFuture.completedFuture(Optional.empty());
        }
        return name -> cache.getAsync(name)
                .thenApply(profile -> profile.map(p -> new ResolvedName(p.getId(), p.getName())));
    }

    /** A name looked up successfully: the UUID, and the canonical spelling the source returned. */
    public record ResolvedName(UUID uuid, String name) { }

    /**
     * Where a name NexusCore has never recorded is looked up. Production is the vanilla profile
     * cache; a test supplies its own so the async path can be driven without a server.
     */
    @FunctionalInterface
    public interface ProfileLookup {
        /**
         * @param name the name as typed
         * @return a future completing with the resolved identity, or empty when no such account
         */
        CompletableFuture<Optional<ResolvedName>> lookup(String name);
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

    /** Writes immediately. For changes that must survive the very next crash. */
    private void saveNow() {
        document.schemaVersion = CURRENT_SCHEMA_VERSION;
        store.write(FILE, document);
        dirty = false;
        lastWriteAt = clock.getAsLong();
    }

    /**
     * Records a change, writing only if the damping window has elapsed.
     *
     * <p>Used for updates that are pure observation — a last-seen timestamp moving. Losing the last
     * few of those to a crash costs an approximate answer from {@code /seen}; writing every one of
     * them costs a full document rewrite, a backup copy and an fsync per join and per leave, for
     * every player, forever. <b>Anything that changes what the document *means* — a player it has
     * never recorded, or a name change that the name index resolves by — goes through
     * {@link #saveNow} instead</b>, so damping never costs a fact, only a timestamp's precision.</p>
     */
    private void saveDamped() {
        dirty = true;
        if (clock.getAsLong() - lastWriteAt >= flushIntervalMillis) {
            saveNow();
        }
    }

    /**
     * Writes any damped change immediately.
     *
     * <p>Called when the identity module stops. Without it, damping would trade a real write
     * amplification problem for a quiet data-loss one: every clean shutdown would discard whatever
     * had not yet reached disk, which is worse than the defect being fixed.</p>
     */
    public void flush() {
        if (dirty) {
            saveNow();
        }
    }

    /**
     * Sets how long a damped change may wait.
     *
     * @param millis the window; zero writes every change immediately, as before 1.1.4
     */
    public void setFlushIntervalMillis(long millis) {
        this.flushIntervalMillis = millis;
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
