package com.mwtstudios.nexuscore.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mwtstudios.nexuscore.storage.JsonStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The M1 exit condition: a corrupted config produces a report naming the file, the key, the
 * invalid value, the expected form, and the chosen fallback — and does not take the server
 * down with it.
 */
class ConfigurationServiceTest {

    @TempDir
    Path directory;

    private ConfigurationService service() {
        return new ConfigurationService(new JsonStore(directory), "0.2.0");
    }

    @Test
    @DisplayName("first run generates a settings file with clean defaults")
    void firstRunGeneratesDefaults() {
        ConfigurationService configuration = service();

        ValidationReport report = configuration.load();

        assertTrue(report.isClean(), report.render());
        assertTrue(Files.isRegularFile(directory.resolve(NexusSettings.FILE)));
        assertEquals("0.2.0", configuration.settings().generatedByVersion);
        assertEquals(NexusSettings.CURRENT_SCHEMA_VERSION, configuration.settings().schemaVersion);
    }

    @Test
    @DisplayName("an out-of-range value is clamped, and the finding names all five required fields")
    void outOfRangeValueIsClampedAndReported() throws IOException {
        Files.writeString(directory.resolve(NexusSettings.FILE),
                "{\"schemaVersion\":1,\"teleportWarmupSeconds\":9999,\"maxHomesPerPlayer\":-5}",
                StandardCharsets.UTF_8);

        ConfigurationService configuration = service();
        ValidationReport report = configuration.load();

        assertFalse(report.isClean());
        assertEquals(3, configuration.settings().teleportWarmupSeconds, "must fall back to the documented default");
        assertEquals(5, configuration.settings().maxHomesPerPlayer);

        ValidationReport.Finding finding = report.findings().stream()
                .filter(f -> f.key().equals("teleportWarmupSeconds"))
                .findFirst().orElseThrow();
        assertEquals(NexusSettings.FILE, finding.file());
        assertEquals("9999", finding.invalidValue());
        assertTrue(finding.expected().contains("between 0 and 60"));
        assertEquals("3", finding.fallback());
    }

    @Test
    @DisplayName("a malformed default group name is replaced with a safe value")
    void malformedGroupNameReplaced() throws IOException {
        Files.writeString(directory.resolve(NexusSettings.FILE),
                "{\"schemaVersion\":1,\"defaultGroup\":\"NOT A VALID NAME!\"}", StandardCharsets.UTF_8);

        ConfigurationService configuration = service();
        configuration.load();

        assertEquals("default", configuration.settings().defaultGroup);
    }

    @Test
    @DisplayName("a wrong schema version is reported and corrected rather than accepted")
    void wrongSchemaVersionReported() throws IOException {
        Files.writeString(directory.resolve(NexusSettings.FILE),
                "{\"schemaVersion\":99}", StandardCharsets.UTF_8);

        ValidationReport report = service().load();

        assertTrue(report.findings().stream().anyMatch(f -> f.key().equals("schemaVersion")));
    }

    @Test
    @DisplayName("an unreadable config falls back to defaults without throwing")
    void unreadableConfigFallsBackSafely() throws IOException {
        Files.writeString(directory.resolve(NexusSettings.FILE), "{ not json at all", StandardCharsets.UTF_8);

        ConfigurationService configuration = service();
        configuration.load();

        assertEquals(3, configuration.settings().teleportWarmupSeconds,
                "the server must keep running on built-in defaults rather than refusing to start");
    }

    @Test
    @DisplayName("a reload that fails leaves the previous settings in force")
    void failedReloadIsTransactional() throws IOException {
        ConfigurationService configuration = service();
        configuration.load();
        configuration.settings().teleportWarmupSeconds = 7;
        Files.writeString(directory.resolve(NexusSettings.FILE), "{ broken", StandardCharsets.UTF_8);

        ConfigurationService.ReloadResult result = configuration.reload();

        assertFalse(result.succeeded());
        assertEquals(7, configuration.settings().teleportWarmupSeconds,
                "a typo in the file must not take the running configuration away from the operator");
    }

    @Test
    @DisplayName("a successful reload replaces the live settings")
    void successfulReloadReplacesSettings() throws IOException {
        ConfigurationService configuration = service();
        configuration.load();
        Files.writeString(directory.resolve(NexusSettings.FILE),
                "{\"schemaVersion\":1,\"teleportWarmupSeconds\":9}", StandardCharsets.UTF_8);

        ConfigurationService.ReloadResult result = configuration.reload();

        assertTrue(result.succeeded());
        assertEquals(9, configuration.settings().teleportWarmupSeconds);
    }

    @Test
    @DisplayName("a key the operator omitted keeps its default rather than becoming null")
    void omittedKeysKeepDefaults() throws IOException {
        Files.writeString(directory.resolve(NexusSettings.FILE),
                "{\"schemaVersion\":1,\"teleportWarmupSeconds\":4}", StandardCharsets.UTF_8);

        ConfigurationService configuration = service();
        configuration.load();

        assertEquals(4, configuration.settings().teleportWarmupSeconds);
        assertEquals("default", configuration.settings().defaultGroup);
        assertEquals(256, configuration.settings().maxReasonLength);
    }

    @Test
    @DisplayName("the rendered report is readable and names every finding")
    void renderedReportIsReadable() {
        NexusSettings settings = new NexusSettings();
        settings.commandsPerMinute = 1;
        settings.adminGuiPageSize = 999;

        String rendered = settings.validate(NexusSettings.FILE).render();

        assertTrue(rendered.contains("commandsPerMinute"));
        assertTrue(rendered.contains("adminGuiPageSize"));
        assertTrue(rendered.contains("expected"));
        assertTrue(rendered.contains("using"));
    }

    @Test
    @DisplayName("a clean report says so rather than being empty")
    void cleanReportSaysSo() {
        assertTrue(new NexusSettings().validate(NexusSettings.FILE).render().contains("no problems found"));
    }
}
