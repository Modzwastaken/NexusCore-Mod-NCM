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

    // ---- multiple active punishments of the same kind -----------------------------------
    //
    // Three faults in one mechanism, all from a second punishment not retiring the first:
    // /unban lifted one and reported success while the player stayed banned, activeBans()
    // counted the player once per active row, and activeRecord() returned the last-issued
    // match rather than the strictest — so a short ban issued after a long one silently
    // shortened it. ModerationService:108,150,205.

    @Test
    @DisplayName("regression: a second ban supersedes the first rather than stacking")
    void secondBanSupersedesTheFirst() {
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "first", Long.MAX_VALUE);
        now.addAndGet(1_000L);
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "second", Long.MAX_VALUE);

        assertEquals(1, countActive(ModerationService.Type.BAN),
                "two bans must not both be in force");
        assertEquals("second", moderation.activeBan(target).orElseThrow().reason());
        assertEquals(2, moderation.history(target).size(), "the superseded record is kept");
    }

    @Test
    @DisplayName("regression: one /unban fully unbans a player carrying several active bans")
    void oneUnbanClearsEveryActiveBan() {
        writeTwoActiveBans();
        ModerationService reopened = new ModerationService(new JsonStore(directory), now::get);
        assertTrue(reopened.activeBan(target).isPresent());

        assertTrue(reopened.lift(ModerationService.Type.BAN, target, actor, "Admin").isPresent());

        assertTrue(reopened.activeBan(target).isEmpty(),
                "/unban reported success while the player stayed banned — the original defect");
    }

    @Test
    @DisplayName("regression: activeBans() lists a doubly-banned player once, not twice")
    void activeBansDoesNotDoubleCount() {
        writeTwoActiveBans();
        ModerationService reopened = new ModerationService(new JsonStore(directory), now::get);

        assertEquals(1, reopened.activeBans().size(),
                "/banlist and the admin panel counted one player per active row");
    }

    @Test
    @DisplayName("a deliberate re-ban replaces the earlier terms, even when shorter")
    void deliberateRebanReplacesEarlierTerms() {
        long farFuture = now.get() + 30L * 24 * 60 * 60 * 1000;
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "long", farFuture);
        now.addAndGet(1_000L);
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "short", now.get() + 60_000L);

        // An operator typing a new /ban means the new terms. The old record stays in history.
        assertEquals("short", moderation.activeBan(target).orElseThrow().reason());

        now.addAndGet(120_000L);
        assertTrue(moderation.activeBan(target).isEmpty(),
                "the replacement's expiry governs, so the player is free once it lapses");
    }

    @Test
    @DisplayName("regression: strictest wins when a legacy document holds several active bans")
    void strictestWinsAcrossLegacyActiveRecords() {
        // There is no issue order to honour here: these rows come from a build that let bans
        // stack, so the safe reconciliation is the strictest, not whichever sits last in the file.
        writeTwoActiveBans(Long.MAX_VALUE, now.get() + 60_000L);
        ModerationService reopened = new ModerationService(new JsonStore(directory), now::get);

        assertTrue(reopened.activeBan(target).orElseThrow().permanent(),
                "the permanent ban must survive reconciliation, not the timed one");
        assertEquals(1, reopened.activeBans().size());
    }

    @Test
    @DisplayName("mutes and bans supersede independently of each other")
    void mutesAndBansSupersedeIndependently() {
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "ban", Long.MAX_VALUE);
        moderation.issue(ModerationService.Type.MUTE, target, "alice", actor, "Mod", "mute", Long.MAX_VALUE);

        assertTrue(moderation.activeBan(target).isPresent());
        assertTrue(moderation.activeMute(target).isPresent());
        assertEquals(1, countActive(ModerationService.Type.BAN));
        assertEquals(1, countActive(ModerationService.Type.MUTE));
    }

    private long countActive(ModerationService.Type type) {
        return moderation.history(target).stream()
                .filter(r -> r.active() && r.type() == type)
                .count();
    }

    /**
     * Writes a punishments document holding two simultaneously-active bans — the shape produced
     * by builds before {@code issue()} superseded. Written directly rather than through
     * {@code issue()}, which now prevents it, so the healing path is what is under test.
     */
    private void writeTwoActiveBans() {
        writeTwoActiveBans(Long.MAX_VALUE, Long.MAX_VALUE);
    }

    private void writeTwoActiveBans(long firstExpiry, long secondExpiry) {
        String json = """
                {"schemaVersion":1,"records":[
                 {"id":"a","type":"BAN","targetUuid":"%s","targetName":"alice","actorName":"Mod",
                  "reason":"first","issuedAtEpochMillis":1,"expiresAtEpochMillis":%d,
                  "active":true},
                 {"id":"b","type":"BAN","targetUuid":"%s","targetName":"alice","actorName":"Mod",
                  "reason":"second","issuedAtEpochMillis":2,"expiresAtEpochMillis":%d,
                  "active":true}]}
                """.formatted(target, firstExpiry, target, secondExpiry);
        try {
            java.nio.file.Files.writeString(directory.resolve(ModerationService.FILE), json,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not seed the legacy punishments document", e);
        }
    }
}
