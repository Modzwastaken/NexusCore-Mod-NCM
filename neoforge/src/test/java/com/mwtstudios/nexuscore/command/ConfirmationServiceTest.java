package com.mwtstudios.nexuscore.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §15.1: a confirmation for one destructive action can never authorise another.
 *
 * <p>The clock is injected, so nothing here sleeps and nothing is timing-dependent (§21.4).</p>
 */
class ConfirmationServiceTest {

    private final AtomicLong now = new AtomicLong(1_000L);
    private ConfirmationService confirmations;
    private UUID actor;
    private UUID otherActor;

    @BeforeEach
    void setUp() {
        confirmations = new ConfirmationService(30, now::get);
        actor = UUID.randomUUID();
        otherActor = UUID.randomUUID();
    }

    @Test
    @DisplayName("a token confirms the exact action it was issued for")
    void tokenConfirmsItsOwnAction() {
        String token = confirmations.issue(actor, "moderation.ban", "alice", "spam");

        assertEquals(ConfirmationService.Outcome.CONFIRMED,
                confirmations.consume(token, actor, "moderation.ban", "alice", "spam"));
    }

    @Test
    @DisplayName("a token is single-use")
    void tokenIsSingleUse() {
        String token = confirmations.issue(actor, "moderation.ban", "alice", "spam");
        confirmations.consume(token, actor, "moderation.ban", "alice", "spam");

        assertEquals(ConfirmationService.Outcome.UNKNOWN,
                confirmations.consume(token, actor, "moderation.ban", "alice", "spam"),
                "replaying a spent token must not authorise the action a second time");
    }

    @Test
    @DisplayName("a token for one target cannot ban a different target")
    void tokenIsBoundToTarget() {
        String token = confirmations.issue(actor, "moderation.ban", "alice", "spam");

        assertEquals(ConfirmationService.Outcome.MISMATCH,
                confirmations.consume(token, actor, "moderation.ban", "bob", "spam"));
    }

    @Test
    @DisplayName("a token for one reason cannot ban with a different reason")
    void tokenIsBoundToParameters() {
        String token = confirmations.issue(actor, "moderation.ban", "alice", "spam");

        assertEquals(ConfirmationService.Outcome.MISMATCH,
                confirmations.consume(token, actor, "moderation.ban", "alice", "griefing"));
    }

    @Test
    @DisplayName("a token for one action cannot authorise a different action")
    void tokenIsBoundToAction() {
        String token = confirmations.issue(actor, "moderation.ban", "alice", "spam");

        assertEquals(ConfirmationService.Outcome.MISMATCH,
                confirmations.consume(token, actor, "system.reset", "alice", "spam"));
    }

    @Test
    @DisplayName("one actor cannot spend another actor's token")
    void tokenIsBoundToActor() {
        String token = confirmations.issue(actor, "moderation.ban", "alice", "spam");

        assertEquals(ConfirmationService.Outcome.MISMATCH,
                confirmations.consume(token, otherActor, "moderation.ban", "alice", "spam"));
    }

    @Test
    @DisplayName("a mismatched attempt does not spend the token")
    void mismatchDoesNotSpendToken() {
        String token = confirmations.issue(actor, "moderation.ban", "alice", "spam");
        confirmations.consume(token, otherActor, "moderation.ban", "alice", "spam");

        assertEquals(ConfirmationService.Outcome.CONFIRMED,
                confirmations.consume(token, actor, "moderation.ban", "alice", "spam"),
                "a wrong guess must not let an attacker cancel someone else's pending confirmation");
    }

    @Test
    @DisplayName("a token expires exactly at its deadline")
    void tokenExpiresAtDeadline() {
        String token = confirmations.issue(actor, "moderation.ban", "alice", "spam");

        now.set(1_000L + 30_000L - 1);
        assertEquals(ConfirmationService.Outcome.CONFIRMED,
                confirmations.consume(token, actor, "moderation.ban", "alice", "spam"));

        String second = confirmations.issue(actor, "moderation.ban", "bob", "spam");
        now.addAndGet(30_000L);
        assertEquals(ConfirmationService.Outcome.UNKNOWN,
                confirmations.consume(second, actor, "moderation.ban", "bob", "spam"),
                "an expired token is purged and reads as unknown");
    }

    @Test
    @DisplayName("two tokens issued for the same action are still distinct")
    void tokensAreUnique() {
        assertNotEquals(confirmations.issue(actor, "moderation.ban", "alice", "spam"),
                confirmations.issue(actor, "moderation.ban", "alice", "spam"));
    }

    // ---- stored-action tokens ----------------------------------------------------------

    @Test
    @DisplayName("a stored action runs exactly once, and only for its issuer")
    void storedActionRunsOnceForIssuer() {
        AtomicBoolean ran = new AtomicBoolean(false);
        String token = confirmations.issue(actor, "moderation.ban", "alice", "spam",
                "permanently ban alice", () -> ran.set(true));

        assertEquals(ConfirmationService.Outcome.MISMATCH, confirmations.take(token, otherActor).outcome(),
                "another player must not be able to spend this token");
        assertFalse(ran.get());

        ConfirmationService.Taken taken = confirmations.take(token, actor);
        assertEquals(ConfirmationService.Outcome.CONFIRMED, taken.outcome());
        assertEquals("permanently ban alice", taken.description());
        taken.body().run();
        assertTrue(ran.get());

        assertEquals(ConfirmationService.Outcome.UNKNOWN, confirmations.take(token, actor).outcome(),
                "the token must be spent");
    }

    @Test
    @DisplayName("a token with no stored action is refused by take rather than confirming nothing")
    void tokenWithoutActionIsRefusedByTake() {
        String token = confirmations.issue(actor, "moderation.ban", "alice", "spam");

        assertEquals(ConfirmationService.Outcome.MISMATCH, confirmations.take(token, actor).outcome());
    }

    @Test
    @DisplayName("an unknown token is reported as unknown")
    void unknownToken() {
        assertEquals(ConfirmationService.Outcome.UNKNOWN, confirmations.take("deadbeef", actor).outcome());
        assertEquals(ConfirmationService.Outcome.UNKNOWN,
                confirmations.consume("deadbeef", actor, "moderation.ban", "alice", "spam"));
    }

    @Test
    @DisplayName("expired tokens are purged so the pending map stays bounded")
    void expiredTokensArePurged() {
        for (int i = 0; i < 10; i++) {
            confirmations.issue(actor, "moderation.ban", "target" + i, "spam");
        }
        assertEquals(10, confirmations.pendingCount());

        now.addAndGet(31_000L);

        assertEquals(0, confirmations.pendingCount());
    }
}
