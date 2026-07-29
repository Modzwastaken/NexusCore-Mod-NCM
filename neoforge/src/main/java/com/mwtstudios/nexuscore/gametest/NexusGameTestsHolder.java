package com.mwtstudios.nexuscore.gametest;

import com.mwtstudios.nexuscore.core.NexusServices;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * NeoForge's declared holder for the shared service tests.
 *
 * <p>The test bodies live in {@link NexusGameTests} in {@code common/}, so all three loaders run
 * the same assertions. This class exists because discovery is the part the loaders genuinely do
 * not share, and each one's rules were read from its sources rather than assumed:</p>
 *
 * <ul>
 *   <li>Vanilla's {@code GameTestRegistry.register(Class)} enumerates {@code getDeclaredMethods()}
 *       — <em>declared</em>, not inherited — so every method is declared here and delegates,
 *       rather than subclassing a shared holder and registering nothing.</li>
 *   <li>NeoForge derives a test's namespace from {@code @GameTest.templateNamespace()}, then the
 *       class's {@code @GameTestHolder}, then falls back to {@code "minecraft"}
 *       ({@code GameTestHooks.getTemplateNamespace}). The shared class can carry neither
 *       annotation — {@code templateNamespace} is a NeoForge patch to the vanilla annotation and
 *       does not compile on Fabric or Forge — so without this holder all twelve tests are
 *       namespaced {@code minecraft} and {@code enabledGameTestNamespaces=nexuscore} silently
 *       drops every one. That is the zero-test green run 1.1.2 exists to prevent.</li>
 *   <li>{@code @PrefixGameTestTemplate(false)} is load-bearing: with the default prefixing the
 *       structure resolves to {@code nexuscore:nexusgametestsholder.empty}, which does not exist
 *       ({@code IllegalStateException: Missing test structure} — observed, not theoretical).</li>
 * </ul>
 */
@GameTestHolder(NexusServices.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NexusGameTestsHolder {

    private final NexusGameTests shared = new NexusGameTests();

    /** @param helper the running test */
    @GameTest(template = "empty")
    public void harnessRuns(GameTestHelper helper) {
        shared.harnessRuns(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "empty")
    public void permissionGatingRefusesByDefault(GameTestHelper helper) {
        shared.permissionGatingRefusesByDefault(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "empty")
    public void explainPathSeesTheSameGrantAsEnforcement(GameTestHelper helper) {
        shared.explainPathSeesTheSameGrantAsEnforcement(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "empty")
    public void punishmentEnforcementIsPerPlayer(GameTestHelper helper) {
        shared.punishmentEnforcementIsPerPlayer(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "empty")
    public void oneLiftClearsASupersededBan(GameTestHelper helper) {
        shared.oneLiftClearsASupersededBan(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "empty")
    public void unknownNameResolutionDoesNotBlock(GameTestHelper helper) {
        shared.unknownNameResolutionDoesNotBlock(helper);
    }
}
