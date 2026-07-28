package com.mwtstudios.nexuscore.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import com.mwtstudios.nexuscore.storage.JsonStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Identity lookup, and the guard for the defect that made it stall the whole server.
 *
 * <p>{@code resolve()} used to fall back to {@code GameProfileCache.get(String)}, which performs
 * a synchronous Mojang HTTP request whenever the name is not already cached. It runs on the
 * server thread and was reachable by any ordinary player through {@code /seen <unknown-name>},
 * so one slow or unreachable Mojang response parked the entire server for its duration.</p>
 */
class IdentityServiceTest {

    @TempDir
    Path directory;

    private IdentityService identity;

    @BeforeEach
    void setUp() {
        identity = new IdentityService(new JsonStore(directory));
    }

    // ---- behaviour ---------------------------------------------------------------------

    @Test
    @DisplayName("a player NexusCore has seen resolves locally, with no server or network")
    void observedPlayerResolvesLocally() {
        UUID uuid = UUID.randomUUID();
        identity.observe(uuid, "Deathcember");

        assertEquals(uuid, identity.resolveLocally("Deathcember").orElseThrow());
    }

    @Test
    @DisplayName("local resolution is case-insensitive, as operators type names inconsistently")
    void localResolutionIsCaseInsensitive() {
        UUID uuid = UUID.randomUUID();
        identity.observe(uuid, "Deathcember");

        assertEquals(uuid, identity.resolveLocally("deathcember").orElseThrow());
        assertEquals(uuid, identity.resolveLocally("DEATHCEMBER").orElseThrow());
    }

    @Test
    @DisplayName("an unknown name resolves to empty rather than reaching for the network")
    void unknownNameResolvesEmpty() {
        assertTrue(identity.resolveLocally("SomeoneWhoNeverJoined").isEmpty());
    }

    @Test
    @DisplayName("a renamed player still resolves under their current name")
    void renamedPlayerResolvesUnderCurrentName() {
        UUID uuid = UUID.randomUUID();
        identity.observe(uuid, "OldName");
        identity.observe(uuid, "NewName");

        assertEquals(uuid, identity.resolveLocally("NewName").orElseThrow());
        assertTrue(identity.resolveLocally("OldName").isEmpty(),
                "the index tracks the current name; history is kept on the profile, not the index");
    }

    @Test
    @DisplayName("the local index survives a restart")
    void localIndexSurvivesRestart() {
        UUID uuid = UUID.randomUUID();
        identity.observe(uuid, "Deathcember");

        IdentityService reopened = new IdentityService(new JsonStore(directory));

        assertEquals(uuid, reopened.resolveLocally("Deathcember").orElseThrow());
    }

    // ---- the regression guard ------------------------------------------------------------

    /**
     * The defect cannot be reproduced in a unit test: triggering it needs a live
     * {@code MinecraftServer} and an unreachable Mojang API, and the symptom is a stalled server
     * thread rather than a wrong value. What <em>can</em> be pinned is the call that caused it.
     *
     * <p>{@code GameProfileCache.get(String)} is the blocking form. {@code getAsync(String)} runs
     * the same lookup on the background executor. This asserts the blocking form is absent and
     * the asynchronous one is present, so re-introducing the stall fails the build rather than
     * waiting to be discovered on a live server.
     */
    @Test
    @DisplayName("regression: no main source calls the blocking GameProfileCache.get(String)")
    void identityLookupNeverBlocksOnTheProfileCache() throws IOException {
        // The whole main tree, not one file: the original defect surface was the COMMAND layer,
        // and a scan of IdentityService.java alone would pass with the blocking call reinstated
        // there. This is a spelling tripwire, second line behind the behavioural tests above —
        // it pins the obvious reintroduction, not the property.
        String source = readMainSources();

        // Whitespace-collapsed, so a line break or an extra space between the accessor and
        // the call cannot slip the blocking form past this.
        String collapsed = source.replaceAll("\\s+", "");
        assertFalse(collapsed.contains("getProfileCache().get("),
                "GameProfileCache.get(String) performs a synchronous Mojang HTTP request on the "
                        + "server thread. Any player can reach it with /seen <unknown-name>. Use "
                        + "the async seam and file the result on the server thread instead.");
        assertTrue(collapsed.contains(".getAsync("),
                "the off-thread lookup should still exist somewhere, so an unknown name resolves "
                        + "on a retry (the accessor may be held in a local, so only the call is pinned)");
    }

    @Test
    @DisplayName("regression: the async result is filed through the server-thread executor, not inline")
    void asyncResultIsFiledOnTheServerThread() {
        LatchedLookup lookup = new LatchedLookup();
        UUID found = UUID.randomUUID();
        List<Runnable> queued = new ArrayList<>();

        CompletableFuture<Optional<UUID>> future =
                identity.resolveAsync(lookup, queued::add, () -> false, "Queued");
        lookup.pending.complete(Optional.of(new IdentityService.ResolvedName(found, "Queued")));

        // The lookup has completed on its own thread. The document and the name index are not
        // thread-safe, so nothing may have been written yet — the work must be waiting for the
        // server thread to run it.
        assertEquals(Optional.of(found), future.join());
        assertTrue(identity.profileOf(found).isEmpty(),
                "the document was mutated on the completing thread instead of the server thread");
        assertEquals(1, queued.size(), "exactly one task should be handed to the server thread");

        queued.get(0).run();
        assertEquals(Optional.of(found), identity.resolveLocally("Queued"),
                "and once the server thread runs it, the name resolves locally");
    }

    /* ---------- the async path, driven directly through the seam ----------
     *
     * The source scans above pin a spelling. These pin the behaviour: a latched lookup lets the
     * test decide when — and on which thread — the answer arrives.
     */

    /** A lookup that answers only when the test says so. */
    private static final class LatchedLookup implements IdentityService.ProfileLookup {
        private final CompletableFuture<Optional<IdentityService.ResolvedName>> pending =
                new CompletableFuture<>();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletableFuture<Optional<IdentityService.ResolvedName>> lookup(String name) {
            calls.incrementAndGet();
            return pending;
        }
    }

    @Test
    @DisplayName("regression: an unknown name does not block the caller waiting for the lookup")
    void serverThreadNeverBlocksOnUnknownName() {
        LatchedLookup lookup = new LatchedLookup();
        CompletableFuture<Optional<UUID>> future =
                identity.resolveAsync(lookup, Runnable::run, () -> false, "Nobody");

        // The lookup has been dispatched and has NOT answered. If resolution blocked on it, this
        // assertion could not be reached at all — which is the whole defect.
        assertEquals(1, lookup.calls.get(), "the lookup should have been dispatched");
        assertFalse(future.isDone(), "resolution must not wait for the lookup to answer");
    }

    @Test
    @DisplayName("a successful lookup is filed under the name, without inventing a visit")
    void successfulLookupFilesTheNameButNotASighting() {
        LatchedLookup lookup = new LatchedLookup();
        UUID found = UUID.randomUUID();
        CompletableFuture<Optional<UUID>> future =
                identity.resolveAsync(lookup, Runnable::run, () -> false, "Stranger");

        lookup.pending.complete(Optional.of(new IdentityService.ResolvedName(found, "Stranger")));

        assertEquals(Optional.of(found), future.join());
        assertEquals(Optional.of(found), identity.resolveLocally("Stranger"),
                "the retry must resolve locally, which is the point of filing the result");
        assertEquals(0L, identity.profileOf(found).orElseThrow().lastSeenEpochMillis(),
                "a lookup is not a visit — /seen reported players who never joined as seen just now");
        assertEquals(0L, identity.profileOf(found).orElseThrow().firstSeenEpochMillis(),
                "a lookup must not claim a first join either");
    }

    @Test
    @DisplayName("regression: a name that does not exist stops promising a lookup is under way")
    void aMissingNameBecomesADefiniteAnswer() {
        LatchedLookup lookup = new LatchedLookup();
        assertFalse(identity.recentlyMissed("Ghost"), "nothing is known about this name yet");

        identity.resolveAsync(lookup, Runnable::run, () -> false, "Ghost");
        lookup.pending.complete(Optional.empty());

        assertTrue(identity.recentlyMissed("Ghost"),
                "vanilla caches no negative result, so without this the operator is told to "
                        + "retry forever and can never distinguish 'does not exist' from 'in flight'");
        assertTrue(identity.recentlyMissed("gHoSt"), "names are matched case-insensitively");
    }

    @Test
    @DisplayName("regression: a lookup completing during shutdown is dropped, not written off-thread")
    void aLookupCompletingDuringShutdownIsDropped() {
        LatchedLookup lookup = new LatchedLookup();
        AtomicInteger executed = new AtomicInteger();
        UUID found = UUID.randomUUID();

        // MinecraftServer.execute runs a task INLINE on the calling thread once isStopped() is
        // true, so a completion arriving in that window would rewrite players.json from the
        // background thread while the server thread is saving it.
        Executor countingInline = task -> {
            executed.incrementAndGet();
            task.run();
        };
        CompletableFuture<Optional<UUID>> future =
                identity.resolveAsync(lookup, countingInline, () -> true, "Late");
        lookup.pending.complete(Optional.of(new IdentityService.ResolvedName(found, "Late")));

        assertEquals(Optional.of(found), future.join(), "the caller still gets its answer");
        assertEquals(0, executed.get(), "nothing may be scheduled once the server is stopping");
        assertTrue(identity.profileOf(found).isEmpty(), "and nothing may be written");
    }

    private static String readMainSources() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("common/src/main/java"))) {
            root = root.getParent();
        }
        assertTrue(root != null, "could not find common/src/main/java above " + Path.of("").toAbsolutePath());
        Path main = root.resolve("common/src/main/java");
        StringBuilder all = new StringBuilder();
        try (var paths = Files.walk(main)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return all.toString();
    }

    private static String readIdentityServiceSource() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("common/src/main/java"))) {
            root = root.getParent();
        }
        assertTrue(root != null, "could not find common/src/main/java above " + Path.of("").toAbsolutePath());
        Path file = root.resolve("common/src/main/java/com/mwtstudios/nexuscore/identity/IdentityService.java");
        assertTrue(Files.isRegularFile(file), "IdentityService.java not found at " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
