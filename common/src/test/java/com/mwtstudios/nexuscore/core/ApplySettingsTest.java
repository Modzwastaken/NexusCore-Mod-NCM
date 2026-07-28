package com.mwtstudios.nexuscore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.mwtstudios.nexuscore.audit.AuditService;
import com.mwtstudios.nexuscore.command.ConfirmationService;
import com.mwtstudios.nexuscore.command.RateLimiter;
import com.mwtstudios.nexuscore.config.ConfigurationService;
import com.mwtstudios.nexuscore.config.NexusSettings;
import com.mwtstudios.nexuscore.identity.IdentityService;
import com.mwtstudios.nexuscore.message.MessageService;
import com.mwtstudios.nexuscore.permission.PermissionService;
import com.mwtstudios.nexuscore.storage.JsonStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /nexus reload} re-applying settings to services that cached them.
 *
 * <p>The defect: {@code commandsPerMinute} and {@code permissionCacheSize} kept their boot-time
 * values through a reload while the command reported success. The first is a rate limit, so a
 * security control silently did not apply — an operator lowering it after abuse would have been
 * told it worked.</p>
 *
 * <p>This is the first test to drive {@link NexusServices#applySettings()} itself rather than the
 * individual setters. Every service in the graph is constructible without a running server; the
 * optional ones the reload path guards behind {@code has()} are passed as null, which is exactly
 * the safe-mode shape and also proves the guard holds.</p>
 */
class ApplySettingsTest {

    @TempDir
    Path directory;

    private JsonStore store;
    private ConfigurationService configuration;
    private RateLimiter rateLimiter;
    private PermissionService permissions;
    private NexusServices services;
    private final AtomicLong clock = new AtomicLong(0L);

    @BeforeEach
    void setUp() {
        store = new JsonStore(directory);
        configuration = new ConfigurationService(store, "test");
        permissions = new PermissionService(store, 4096);
        rateLimiter = new RateLimiter(60, clock::get);
        services = new NexusServices(
                store,
                configuration,
                new MessageService(store),
                new IdentityService(store),
                new AuditService(store, "test"),
                permissions,
                null,
                null,
                null,
                rateLimiter,
                new ConfirmationService(30, clock::get),
                "test");
    }

    /** Writes a settings document and reloads it through the real configuration service. */
    private void reloadWith(int commandsPerMinute, int permissionCacheSize) {
        String json = """
                {"schemaVersion":1,"commandsPerMinute":%d,"permissionCacheSize":%d}
                """.formatted(commandsPerMinute, permissionCacheSize);
        try {
            Files.writeString(directory.resolve(NexusSettings.FILE), json, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not seed the settings document", e);
        }
        assertTrue(configuration.reload().failure() == null, "the reload itself should succeed");
        services.applySettings();
    }

    @Test
    @DisplayName("regression: a reloaded commandsPerMinute applies to the live rate limiter")
    void reloadAppliesCommandsPerMinute() {
        UUID subject = UUID.randomUUID();
        for (int i = 0; i < 60; i++) {
            assertTrue(rateLimiter.tryAcquire(subject), "permit " + i + " is within the boot capacity");
        }
        assertFalse(rateLimiter.tryAcquire(subject), "the boot-time bucket is spent");

        reloadWith(600, 4096);
        clock.addAndGet(60_000L);

        int granted = 0;
        while (rateLimiter.tryAcquire(subject)) {
            granted++;
        }
        assertEquals(600, granted,
                "the reloaded rate limit must govern — this is a security control that reported "
                        + "success while keeping its boot-time value");
    }

    @Test
    @DisplayName("regression: a reloaded permissionCacheSize applies to the live permission cache")
    void reloadAppliesPermissionCacheSize() {
        permissions.evaluate(UUID.randomUUID(), "nexuscore.command.player.seen");
        assertTrue(permissions.cacheSize() > 0, "the evaluation is cached");

        reloadWith(120, 64);

        assertEquals(64, permissions.cacheBound(), "the reloaded bound must be the one in force");
        assertEquals(0, permissions.cacheSize(),
                "shrinking clears, because an LRU only evicts on insertion");
    }

    @Test
    @DisplayName("the reload path holds in safe mode, where the optional services are absent")
    void reloadSurvivesAbsentOptionalModules() {
        // teleport, players and moderation are null here, exactly as in safe mode. applySettings
        // guards teleport behind has(); a regression there previously threw a plain
        // NullPointerException out of a core command.
        reloadWith(300, 128);
        assertEquals(128, permissions.cacheBound());
    }
}
