package com.mwtstudios.nexuscore.forge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the metadata differences between Forge and NeoForge, which look interchangeable and
 * are not.
 *
 * <p>The first Forge build was rejected outright by FML with
 * {@code Missing required field mandatory in dependency}, because the dependency blocks were
 * copied from the NeoForge file. Forge requires {@code mandatory = true}; NeoForge replaced
 * that with {@code type = "required"}. The mod simply never loaded, and nothing in the
 * compiler or the unit suite could have said so.</p>
 */
class ForgeMetadataTest {

    /**
     * Returns the file with comment lines stripped. The header comment deliberately explains
     * the Forge/NeoForge difference by quoting both spellings, and an assertion that matched
     * prose rather than declarations would fail on its own documentation.
     */
    private static String modsToml() throws IOException {
        Path file = Path.of("src/main/resources/META-INF/mods.toml");
        assertTrue(Files.isRegularFile(file), "mods.toml not found at " + file.toAbsolutePath());
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("dependency blocks use Forge's 'mandatory', not NeoForge's 'type'")
    void dependenciesUseMandatory() throws IOException {
        String toml = modsToml();

        assertTrue(toml.contains("mandatory = true"),
                "Forge requires 'mandatory' in every dependency block; without it FML rejects the whole jar");
        assertFalse(toml.contains("type = \"required\""),
                "'type = \"required\"' is the NeoForge spelling and makes Forge refuse to load the mod");
    }

    @Test
    @DisplayName("the file is mods.toml, and the NeoForge metadata is not shipped alongside it")
    void correctMetadataFileOnly() {
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/META-INF/mods.toml")),
                "Forge reads META-INF/mods.toml");
        assertFalse(Files.exists(Path.of("src/main/resources/META-INF/neoforge.mods.toml")),
                "the NeoForge metadata must not live in the Forge source tree");
    }

    @Test
    @DisplayName("it declares a forge dependency, not a neoforge one")
    void dependsOnForge() throws IOException {
        String toml = modsToml();

        assertTrue(toml.contains("modId = \"forge\""), "must depend on forge");
        assertFalse(toml.contains("modId = \"neoforge\""), "must not depend on neoforge");
    }
}
