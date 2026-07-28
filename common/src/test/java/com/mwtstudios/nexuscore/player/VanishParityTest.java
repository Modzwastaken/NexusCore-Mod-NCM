package com.mwtstudios.nexuscore.player;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards two of the four vanish faults, and guards them on <em>every</em> loader.
 *
 * <p>Vanish is wired in each loader's entry point, which is the one file the three builds do not
 * share. That is precisely where a fix lands on one loader and quietly misses the others — the
 * defect table already carries a row of exactly this shape, where styled death messages lose their
 * cause on Fabric alone. A behavioural test cannot reach these paths without a running server, so
 * what is mechanically checkable is that all three entry points wire the same hooks.</p>
 *
 * <p>The two faults covered here:</p>
 * <ul>
 *   <li><b>Vanish was not applied to players who joined later.</b> Vanilla sends a new client the
 *       whole player list, so a staff member who vanished before that client connected showed up
 *       in their list regardless. Fixed by {@code hideVanishedFrom} on join.</li>
 *   <li><b>The vanished set survived death while the invisibility flag did not.</b> Respawning
 *       replaces the {@code ServerPlayer}, so NexusCore believed the staff member was hidden —
 *       {@code /list} and {@code /near} filtered them out — while every client could see them.
 *       Fixed by {@code reapplyVanish} on respawn.</li>
 * </ul>
 *
 * <p>The remaining two faults — the player-list desync that renders staff chat as a red
 * chat-validation error, and un-vanish not restoring the entity for clients that received
 * {@code AddEntity} while vanished — are client-rendering behaviour. They are not fixed here and
 * are not claimed to be; they are recorded in {@code IMPLEMENTATION_STATUS.md} as awaiting the
 * scheduled 1.1.3 two-real-players sweep.</p>
 */
class VanishParityTest {

    /** Each loader's entry point, relative to the repository root. */
    private static final Map<String, String> ENTRY_POINTS = new LinkedHashMap<>(Map.of(
            "neoforge", "neoforge/src/main/java/com/mwtstudios/nexuscore/NexusCore.java",
            "fabric", "fabric/src/main/java/com/mwtstudios/nexuscore/fabric/NexusCoreFabric.java",
            "forge", "forge/src/main/java/com/mwtstudios/nexuscore/forge/NexusCoreForge.java"));

    private static Path repositoryRoot() {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.isDirectory(here.resolve("common/src/main/java"))) {
            here = here.getParent();
        }
        assertTrue(here != null, "could not find common/src/main/java above " + Path.of("").toAbsolutePath());
        return here;
    }

    private static String entryPointSource(String loader) throws IOException {
        Path file = repositoryRoot().resolve(ENTRY_POINTS.get(loader));
        assertTrue(Files.isRegularFile(file), loader + " entry point not found at " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("regression: every loader hides already-vanished staff from a joining player")
    void everyLoaderHidesVanishedFromJoiners() throws IOException {
        for (String loader : ENTRY_POINTS.keySet()) {
            assertTrue(entryPointSource(loader).contains("hideVanishedFrom("),
                    loader + " does not hide vanished players from someone joining, so vanish "
                            + "silently stops applying to every client that connects afterwards");
        }
    }

    @Test
    @DisplayName("regression: every loader re-applies vanish after a respawn")
    void everyLoaderReappliesVanishAfterRespawn() throws IOException {
        for (String loader : ENTRY_POINTS.keySet()) {
            assertTrue(entryPointSource(loader).contains("reapplyVanish("),
                    loader + " does not re-apply vanish after respawn, so a vanished staff member "
                            + "who dies becomes visible while NexusCore still reports them hidden");
        }
    }

    @Test
    @DisplayName("both vanish hooks are guarded, because throwing in a login handler stops joins")
    void bothHooksAreGuardedByTheModuleCheck() throws IOException {
        for (String loader : ENTRY_POINTS.keySet()) {
            String source = entryPointSource(loader);
            assertTrue(source.contains("has(\"player-utilities\")"),
                    loader + " calls the vanish hooks without checking the player-utilities module "
                            + "is enabled; in safe mode that throws inside a login handler and "
                            + "stops players joining entirely");
        }
    }
}
