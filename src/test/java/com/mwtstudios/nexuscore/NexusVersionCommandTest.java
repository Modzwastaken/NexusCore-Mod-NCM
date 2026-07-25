package com.mwtstudios.nexuscore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M0 tests. These exist to prove the test harness runs and to hold the three places that
 * describe NexusCore's identity — the Java constants, the language file, and the generated
 * mod metadata — from drifting apart.
 */
class NexusVersionCommandTest {

    private static final String LANG_RESOURCE = "/assets/nexuscore/lang/en_us.json";
    private static final String MODS_TOML_RESOURCE = "/META-INF/neoforge.mods.toml";

    @Test
    @DisplayName("the command format string is the value stored under its translation key")
    void formatStringMatchesLanguageFile() throws IOException {
        JsonObject lang = readLanguageFile();

        assertTrue(lang.has(NexusVersionCommand.VERSION_MESSAGE_KEY),
                LANG_RESOURCE + " is missing key " + NexusVersionCommand.VERSION_MESSAGE_KEY);
        assertEquals(NexusVersionCommand.VERSION_MESSAGE_FORMAT,
                lang.get(NexusVersionCommand.VERSION_MESSAGE_KEY).getAsString(),
                "NexusVersionCommand.VERSION_MESSAGE_FORMAT has drifted from " + LANG_RESOURCE);
    }

    @Test
    @DisplayName("formatVersion renders display name and version in that order")
    void formatVersionRendersNameThenVersion() {
        assertEquals("NexusCore Administration Framework version 0.1.0",
                NexusVersionCommand.formatVersion("NexusCore Administration Framework", "0.1.0"));
    }

    @Test
    @DisplayName("the mod id constant matches the id in the generated mod metadata")
    void modIdMatchesGeneratedMetadata() throws IOException {
        String metadata = readResource(MODS_TOML_RESOURCE);

        assertTrue(metadata.contains("modId = \"" + NexusCore.MOD_ID + "\""),
                MODS_TOML_RESOURCE + " does not declare modId \"" + NexusCore.MOD_ID + "\"");
    }

    @Test
    @DisplayName("the generated mod metadata carries no unexpanded gradle tokens")
    void generatedMetadataHasNoUnexpandedTokens() throws IOException {
        String metadata = readResource(MODS_TOML_RESOURCE);

        assertTrue(!metadata.contains("${"),
                MODS_TOML_RESOURCE + " still contains an unexpanded ${...} token");
    }

    private JsonObject readLanguageFile() throws IOException {
        try (InputStream stream = open(LANG_RESOURCE);
                Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private String readResource(String path) throws IOException {
        try (InputStream stream = open(path)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private InputStream open(String path) {
        InputStream stream = NexusVersionCommandTest.class.getResourceAsStream(path);
        assertNotNull(stream, "resource not found on the test classpath: " + path);
        return stream;
    }
}
