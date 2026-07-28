package com.mwtstudios.nexuscore.gametest;

import java.util.UUID;

import com.mwtstudios.nexuscore.core.NexusBootstrap;
import com.mwtstudios.nexuscore.core.NexusServices;
import com.mwtstudios.nexuscore.moderation.ModerationService;
import com.mwtstudios.nexuscore.permission.PermissionDecision;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;


/**
 * In-server tests for the paths no unit test can reach.
 *
 * <p>Everything in {@code common/src/test} runs against real services in a temporary directory,
 * which is enough for storage, permissions, punishments and parsing. It is <em>not</em> enough for
 * anything needing a {@link ServerPlayer}: those cannot be constructed without a running server, so
 * several refusal paths fixed during 1.1.1 were verified by reading rather than by test. This is
 * the harness that closes them.</p>
 *
 * <p>These run only when {@code neoforge.enabledGameTestNamespaces} names this mod, which the
 * {@code runGameTestServer} task sets and a production server never does.</p>
 */
public final class NexusGameTests {

    /** The live registry the running server is using — not a second copy built for the test. */
    private static NexusServices services() {
        NexusServices services = NexusBootstrap.runningServices();
        if (services == null) {
            throw new IllegalStateException(
                    "NexusCore is not loaded, so these tests would be asserting about nothing");
        }
        return services;
    }

    /**
     * Proves the harness itself runs, before anything depends on it.
     *
     * @param helper the running test
     */
    @GameTest(template = "nexuscore:empty")
    public void harnessRuns(GameTestHelper helper) {
        helper.assertTrue(services() != null, "the running server should expose its service registry");
        helper.succeed();
    }

    // ---- permission gating (M5) ---------------------------------------------------------

    /**
     * A player with no grants is refused, and the refusal explains itself.
     *
     * @param helper the running test
     */
    @GameTest(template = "nexuscore:empty")
    public void permissionGatingRefusesByDefault(GameTestHelper helper) {
        NexusServices services = services();
        UUID stranger = UUID.randomUUID();

        PermissionDecision decision = services.permissions().evaluate(stranger, "nexuscore.command.moderation.ban");

        helper.assertFalse(decision.allowed(),
                "a player with no groups and no grants must not hold a moderation node");
        helper.assertTrue(decision.explanation() != null && !decision.explanation().isBlank(),
                "the decision must explain itself — /nexus permission check renders this");
        helper.succeed();
    }

    /**
     * The operator-bootstrap grant is visible through the explain path, not only through
     * enforcement. This is the 1.1.1 fix: {@code /nexus permission check} called the evaluator
     * directly, so it could never show a grant that {@code authorise()} applies.
     *
     * @param helper the running test
     */
    @GameTest(template = "nexuscore:empty")
    public void explainPathSeesTheSameGrantAsEnforcement(GameTestHelper helper) {
        NexusServices services = services();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        String node = "nexuscore.command.moderation.ban";

        PermissionDecision enforcement = services.authorise(player, node);
        PermissionDecision explain = services.authorise(
                helper.getLevel().getServer(), player.getUUID(), player.getGameProfile().getName(), node);

        helper.assertValueEqual(explain.result(), enforcement.result(),
                "the explain path and the enforcement path must reach the same result — a command "
                        + "whose job is explaining a decision that disagrees with the decision is worse "
                        + "than no explanation");
        // source() is null for a decision no rule matched, so compare null-safely rather than
        // asserting a shape the domain does not promise.
        helper.assertTrue(java.util.Objects.equals(explain.source(), enforcement.source()),
                "and must agree about WHY, or the explanation misleads about which rule applied: "
                        + "explain=" + explain.source() + " enforcement=" + enforcement.source());
        helper.succeed();
    }

    // ---- punishment enforcement (M5) ----------------------------------------------------

    /**
     * A ban is in force for the player it names and nobody else, and lifting it frees exactly
     * that player.
     *
     * @param helper the running test
     */
    @GameTest(template = "nexuscore:empty")
    public void punishmentEnforcementIsPerPlayer(GameTestHelper helper) {
        NexusServices services = services();
        UUID banned = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();

        services.moderation().issue(ModerationService.Type.BAN, banned, "banned-player",
                null, "GameTest", "enforcement test", Long.MAX_VALUE);

        helper.assertTrue(services.moderation().activeBan(banned).isPresent(),
                "the banned player must have a ban in force");
        helper.assertFalse(services.moderation().activeBan(bystander).isPresent(),
                "a ban must not leak onto another player");

        services.moderation().lift(ModerationService.Type.BAN, banned, null, "GameTest");

        helper.assertFalse(services.moderation().activeBan(banned).isPresent(),
                "lifting must actually free the player — /unban once reported success while the "
                        + "player stayed banned");
        helper.assertTrue(services.moderation().history(banned).size() == 1,
                "and the record must survive, because history that can be erased is not history");
        helper.succeed();
    }

    /**
     * A second ban supersedes the first rather than stacking, and one lift is enough. This is the
     * 1.1.1 defect where {@code /unban} reported success while a shadowed ban kept the player out.
     *
     * @param helper the running test
     */
    @GameTest(template = "nexuscore:empty")
    public void oneLiftClearsASupersededBan(GameTestHelper helper) {
        NexusServices services = services();
        UUID target = UUID.randomUUID();

        services.moderation().issue(ModerationService.Type.BAN, target, "repeat-offender",
                null, "GameTest", "first", Long.MAX_VALUE);
        services.moderation().issue(ModerationService.Type.BAN, target, "repeat-offender",
                null, "GameTest", "second", Long.MAX_VALUE);

        helper.assertValueEqual(services.moderation().activeBans().stream()
                        .filter(r -> "repeat-offender".equals(r.targetName()))
                        .count(), 1L,
                "a doubly-banned player must be counted once, whatever order the records sit in");

        services.moderation().lift(ModerationService.Type.BAN, target, null, "GameTest");

        helper.assertFalse(services.moderation().activeBan(target).isPresent(),
                "one /unban must clear every active ban, or the operator is told they freed someone "
                        + "who is still locked out");
        helper.succeed();
    }

    // ---- identity (1.1.1) ----------------------------------------------------------------

    /**
     * Resolving an unknown name never blocks the server thread. The GameTest thread <em>is</em>
     * the server thread, so a blocking Mojang lookup would stall this test rather than fail it —
     * the assertion is that it returns promptly and empty.
     *
     * @param helper the running test
     */
    @GameTest(template = "nexuscore:empty")
    public void unknownNameResolutionDoesNotBlock(GameTestHelper helper) {
        NexusServices services = services();
        long before = System.nanoTime();

        boolean resolved = services.identity()
                .resolve(helper.getLevel().getServer(), "a-name-nobody-here-has-ever-used")
                .isPresent();

        long millis = (System.nanoTime() - before) / 1_000_000L;
        helper.assertFalse(resolved, "nobody by that name is known locally");
        helper.assertTrue(millis < 250L,
                "local resolution must not reach the network: took " + millis + "ms. Any player "
                        + "could trigger this with /seen <unknown-name>");
        helper.succeed();
    }
}
