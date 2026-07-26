package com.mwtstudios.nexuscore.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.mwtstudios.nexuscore.storage.JsonStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Punishment lifecycle: expiry at the boundary, lifting that preserves history, durability. */
class ModerationServiceTest {

    @TempDir
    Path directory;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private ModerationService moderation;
    private UUID target;
    private UUID actor;

    @BeforeEach
    void setUp() {
        moderation = new ModerationService(new JsonStore(directory), now::get);
        target = UUID.randomUUID();
        actor = UUID.randomUUID();
    }

    @Test
    @DisplayName("a permanent ban is active and never expires")
    void permanentBanIsActive() {
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "griefing", Long.MAX_VALUE);

        assertTrue(moderation.activeBan(target).isPresent());
        assertTrue(moderation.activeBan(target).orElseThrow().permanent());

        now.addAndGet(1_000L * 60 * 60 * 24 * 365 * 20);
        assertTrue(moderation.activeBan(target).isPresent(), "a permanent ban must not lapse with time");
    }

    @Test
    @DisplayName("a temporary ban lapses exactly at its expiry, not before")
    void temporaryBanExpiresAtBoundary() {
        long expiresAt = now.get() + 60_000L;
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "spam", expiresAt);

        now.set(expiresAt - 1);
        assertTrue(moderation.activeBan(target).isPresent(), "must still be active one millisecond before expiry");

        now.set(expiresAt);
        assertTrue(moderation.activeBan(target).isEmpty(), "must be over at the expiry instant itself");
    }

    @Test
    @DisplayName("an expired ban is deactivated and persisted, so the file converges without a sweep task")
    void expiredBanIsPersistedAsInactive() {
        long expiresAt = now.get() + 60_000L;
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "spam", expiresAt);
        now.set(expiresAt + 1);
        moderation.activeBan(target);

        ModerationService reopened = new ModerationService(new JsonStore(directory), now::get);

        assertTrue(reopened.activeBan(target).isEmpty());
        assertEquals(1, reopened.history(target).size(), "the record itself must survive");
        assertEquals("expiry", reopened.history(target).get(0).liftedByName());
    }

    @Test
    @DisplayName("lifting a ban keeps the record and stamps who lifted it")
    void liftingKeepsHistory() {
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "griefing", Long.MAX_VALUE);

        assertTrue(moderation.lift(ModerationService.Type.BAN, target, actor, "Admin").isPresent());

        assertTrue(moderation.activeBan(target).isEmpty());
        assertEquals(1, moderation.history(target).size(), "history that can be erased is not history");
        assertEquals("Admin", moderation.history(target).get(0).liftedByName());
        assertFalse(moderation.history(target).get(0).active());
    }

    @Test
    @DisplayName("lifting a punishment that is not active reports nothing to lift")
    void liftingNothingReportsNothing() {
        assertTrue(moderation.lift(ModerationService.Type.BAN, target, actor, "Admin").isEmpty());
    }

    @Test
    @DisplayName("a kick is recorded but never active, so it cannot block a rejoin")
    void kickIsRecordedButNotActive() {
        moderation.issue(ModerationService.Type.KICK, target, "alice", actor, "Mod", "language", Long.MAX_VALUE);

        assertTrue(moderation.activeRecord(ModerationService.Type.KICK, target).isEmpty());
        assertEquals(1, moderation.history(target).size());
    }

    @Test
    @DisplayName("a warning is recorded but never active")
    void warningIsRecordedButNotActive() {
        moderation.issue(ModerationService.Type.WARNING, target, "alice", actor, "Mod", "first warning", Long.MAX_VALUE);
        moderation.issue(ModerationService.Type.WARNING, target, "alice", actor, "Mod", "second warning", Long.MAX_VALUE);

        assertEquals(2, moderation.warnings(target).size());
        assertTrue(moderation.activeRecord(ModerationService.Type.WARNING, target).isEmpty());
    }

    @Test
    @DisplayName("a ban and a mute are independent")
    void banAndMuteAreIndependent() {
        moderation.issue(ModerationService.Type.MUTE, target, "alice", actor, "Mod", "spam", Long.MAX_VALUE);

        assertTrue(moderation.activeMute(target).isPresent());
        assertTrue(moderation.activeBan(target).isEmpty());

        moderation.lift(ModerationService.Type.MUTE, target, actor, "Admin");
        assertTrue(moderation.activeMute(target).isEmpty());
    }

    @Test
    @DisplayName("punishments survive a restart")
    void punishmentsSurviveRestart() {
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "griefing", Long.MAX_VALUE);

        ModerationService reopened = new ModerationService(new JsonStore(directory), now::get);

        assertTrue(reopened.activeBan(target).isPresent());
        assertEquals("griefing", reopened.activeBan(target).orElseThrow().reason());
        assertEquals("alice", reopened.activeBan(target).orElseThrow().targetName());
    }

    @Test
    @DisplayName("activeBans lists only bans that are still in force")
    void activeBansExcludesExpired() {
        UUID other = UUID.randomUUID();
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "permanent", Long.MAX_VALUE);
        moderation.issue(ModerationService.Type.BAN, other, "bob", actor, "Mod", "temporary", now.get() + 60_000L);

        assertEquals(2, moderation.activeBans().size());

        now.addAndGet(60_001L);

        assertEquals(1, moderation.activeBans().size());
        assertEquals("alice", moderation.activeBans().get(0).targetName());
    }

    @Test
    @DisplayName("history is newest first")
    void historyIsNewestFirst() {
        moderation.issue(ModerationService.Type.WARNING, target, "alice", actor, "Mod", "first", Long.MAX_VALUE);
        now.addAndGet(1_000L);
        moderation.issue(ModerationService.Type.WARNING, target, "alice", actor, "Mod", "second", Long.MAX_VALUE);

        assertEquals("second", moderation.history(target).get(0).reason());
    }

    // ---- reason sanitising -------------------------------------------------------------

    @Test
    @DisplayName("a blank reason becomes an explicit placeholder rather than an empty string")
    void blankReasonBecomesPlaceholder() {
        assertEquals("No reason given", ModerationService.sanitiseReason(null, 256));
        assertEquals("No reason given", ModerationService.sanitiseReason("   ", 256));
    }

    @Test
    @DisplayName("a reason is flattened to one line and bounded in length")
    void reasonIsFlattenedAndBounded() {
        assertEquals("a b", ModerationService.sanitiseReason("a\nb", 256));
        assertEquals("a b", ModerationService.sanitiseReason("a\r\n\tb", 256));
        assertEquals("a b", ModerationService.sanitiseReason("a     b", 256));
        assertEquals(16, ModerationService.sanitiseReason("x".repeat(500), 16).length());
    }
}
