package com.mwtstudios.nexuscore.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the defect that made NexusCore do nothing in singleplayer and on LAN worlds.
 *
 * <p>The mod originally declared a {@code server} entrypoint and implemented
 * {@code DedicatedServerModInitializer}. That combination only fires on a dedicated server, so
 * on a Fabric client the mod loaded, listed itself, and was completely silent — no commands, no
 * config, no log lines. Singleplayer and LAN run an <em>integrated</em> server, which is not a
 * dedicated one.</p>
 *
 * <p>The failure is invisible to a compiler and to every functional test that runs against a
 * dedicated server, which is exactly how it shipped. These assertions are cheap and they fail
 * loudly the moment someone reintroduces it.</p>
 */
class FabricEntrypointTest {

    private static String modJson() throws IOException {
        Path file = Path.of("src/main/resources/fabric.mod.json");
        assertTrue(Files.isRegularFile(file), "fabric.mod.json not found at " + file.toAbsolutePath());
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the entrypoint is 'main', so the mod initialises on clients and integrated servers too")
    void entrypointIsMain() throws IOException {
        String json = modJson();

        assertTrue(json.contains("\"main\""),
                "fabric.mod.json must declare a 'main' entrypoint; anything else skips singleplayer and LAN");
        assertFalse(json.contains("\"server\":"),
                "a 'server' entrypoint fires only on a dedicated server — NexusCore was silent in "
                        + "singleplayer for exactly this reason");
    }

    @Test
    @DisplayName("the entry class implements ModInitializer, not DedicatedServerModInitializer")
    void entryClassImplementsModInitializer() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/mwtstudios/nexuscore/fabric/NexusCoreFabric.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("implements ModInitializer"),
                "NexusCoreFabric must implement ModInitializer");
        // Deliberately matches the declaration and the import, not any mention: the class
        // javadoc explains the original defect by name, and that explanation is worth keeping.
        assertFalse(source.contains("implements DedicatedServerModInitializer"),
                "DedicatedServerModInitializer never runs on a client, so the mod does nothing in singleplayer");
        assertFalse(source.contains("import net.fabricmc.api.DedicatedServerModInitializer;"),
                "the dedicated-server-only entrypoint type must not be imported");
    }

    @Test
    @DisplayName("the death hook is registered, matching the NeoForge build's /back behaviour")
    void deathHookRegistered() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/mwtstudios/nexuscore/fabric/NexusCoreFabric.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("AFTER_DEATH"),
                "without this, /back does not return a player to where they died on Fabric, "
                        + "while it does on NeoForge — a silent behavioural divergence");
    }
}
