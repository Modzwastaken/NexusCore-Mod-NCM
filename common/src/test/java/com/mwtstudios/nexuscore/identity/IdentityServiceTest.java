package com.mwtstudios.nexuscore.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

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
    @DisplayName("regression: identity lookup never calls the blocking GameProfileCache.get(String)")
    void identityLookupNeverBlocksOnTheProfileCache() throws IOException {
        String source = readIdentityServiceSource();

        assertFalse(source.contains("getProfileCache().get("),
                "GameProfileCache.get(String) performs a synchronous Mojang HTTP request on the "
                        + "server thread. Any player can reach it with /seen <unknown-name>. Use "
                        + "getAsync and file the result through observe() instead.");
        assertTrue(source.contains("getProfileCache().getAsync("),
                "the off-thread lookup should still exist, so an unknown name resolves on a retry");
    }

    @Test
    @DisplayName("regression: the async result is filed back on the server thread")
    void asyncResultIsFiledOnTheServerThread() throws IOException {
        String source = readIdentityServiceSource();

        assertTrue(source.contains("server.execute("),
                "getAsync completes on a background thread, but the identity document and its "
                        + "name index are not thread-safe — the result must be filed via "
                        + "server.execute() rather than written directly");
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
