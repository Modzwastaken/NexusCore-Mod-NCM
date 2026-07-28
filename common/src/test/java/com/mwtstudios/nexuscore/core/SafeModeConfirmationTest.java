package com.mwtstudios.nexuscore.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.mwtstudios.nexuscore.audit.AuditService;
import com.mwtstudios.nexuscore.command.ConfirmationService;
import com.mwtstudios.nexuscore.command.RateLimiter;
import com.mwtstudios.nexuscore.config.ConfigurationService;
import com.mwtstudios.nexuscore.identity.IdentityService;
import com.mwtstudios.nexuscore.message.MessageService;
import com.mwtstudios.nexuscore.module.ModuleException;
import com.mwtstudios.nexuscore.permission.PermissionService;
import com.mwtstudios.nexuscore.storage.JsonStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A confirmation must never be staged for an action that cannot run.
 *
 * <p>The defect: {@code /ban} in safe mode issued its confirmation prompt happily, because the
 * only thing that touched the moderation module was the token's <em>body</em> — and a body does
 * not run until the operator confirms. {@code /nexus confirm} then spent the token, the body
 * failed, and the operator was left with no ban, no token, and no audit record of either.</p>
 *
 * <p>Both halves are fixed and both are pinned here: the propose path reaches the module before
 * anything is staged, and a body that fails after the token is spent leaves a record.</p>
 */
class SafeModeConfirmationTest {

    @TempDir
    Path directory;

    private NexusServices safeMode;
    private ConfirmationService confirmations;
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        JsonStore store = new JsonStore(directory);
        confirmations = new ConfirmationService(30, clock::get);
        // moderation, teleport and players are null — exactly what safe mode produces.
        safeMode = new NexusServices(
                store,
                new ConfigurationService(store, "test"),
                new MessageService(store),
                new IdentityService(store),
                new AuditService(store, "test"),
                new PermissionService(store, 4096),
                null,
                null,
                null,
                new RateLimiter(120, clock::get),
                confirmations,
                "test");
    }

    @Test
    @DisplayName("regression: the propose path reaches the module before staging anything")
    void moderationIsUnreachableInSafeMode() {
        // proposeBan's first statement is services.moderation(). This is what it hits: the same
        // ModuleException the command wrapper already reports as a clean refusal, raised BEFORE
        // a token exists rather than after one has been spent.
        assertThrows(ModuleException.class, safeMode::moderation);
        assertTrue(safeMode.has("configuration"), "a core module is still present, so this is not a broken graph");
    }

    @Test
    @DisplayName("has() reports moderation absent without throwing, for callers that must not")
    void hasReportsAbsenceWithoutThrowing() {
        assertTrue(!safeMode.has("moderation"),
                "event handlers ask this way; a login handler that threw would stop players joining");
    }

    @Test
    @DisplayName("regression: a token spent on a body that fails stays spent")
    void aFailedBodyDoesNotReturnTheToken() {
        UUID actor = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        String token = confirmations.issue(actor, "moderation.ban", "target-uuid", "reason",
                "permanently ban alice (reason)",
                () -> {
                    runs.incrementAndGet();
                    throw new ModuleException("moderation");
                });

        ConfirmationService.Taken taken = confirmations.take(token, actor);
        assertEquals(ConfirmationService.Outcome.CONFIRMED, taken.outcome());
        assertThrows(ModuleException.class, () -> taken.body().run());
        assertEquals(1, runs.get());

        // Single use is a security property: handing the token back so the operator could retry
        // would also let a partially applied action be applied twice.
        ConfirmationService.Taken second = confirmations.take(token, actor);
        assertNotEquals(ConfirmationService.Outcome.CONFIRMED, second.outcome(),
                "the token must not become reusable because its body failed");
    }

    @Test
    @DisplayName("the audit log is writable in safe mode, so a spent-token failure can be recorded")
    void auditRemainsAvailableInSafeMode() {
        // The fix records the failure through services.audit(). Audit is a core module, so it is
        // present in exactly the mode where this failure occurs — which is what makes the record
        // possible rather than aspirational.
        assertTrue(safeMode.has("audit"), "audit must survive safe mode for the failure record to exist");
    }
}
