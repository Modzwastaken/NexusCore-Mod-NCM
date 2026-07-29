package com.mwtstudios.nexuscore.gametest;

import com.mwtstudios.nexuscore.core.NexusServices;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;

/**
 * Forge's declared holder for the shared service tests.
 *
 * <p>Bodies live in {@link NexusGameTests} in {@code common/}. Forge's discovery rules differ from
 * both NeoForge's and Fabric's, and each detail here answers one of them — read from
 * {@code ForgeGameTestHooks} in {@code forge-1.21.1-52.1.16-sources.jar}, not assumed:</p>
 *
 * <ul>
 *   <li><b>{@code forge.enabledGameTestNamespaces} filters TEST NAMES, not template namespaces.</b>
 *       {@code ForgeGameTestHooks.addTest} keeps a test iff the filter equals the test's name or
 *       the name starts with {@code "<filter>."}. The name prefix comes from
 *       {@code @GameTestHolder.value()} (falling back to the lowercased class name), so without
 *       this annotation the shared classes produce names like
 *       {@code nexusgametests.harnessruns}, the {@code nexuscore} filter matches none of them, and
 *       the server refuses to start with "No test functions were given!" — observed on this
 *       loader, then traced through the bytecode of the patched
 *       {@code GameTestRegistry.register(Method, Set)}.</li>
 *   <li><b>The template keeps its full path because it contains a colon.</b>
 *       {@code ForgeGameTestHooks.getTestTemplate} uses a template with {@code ':'} verbatim, so
 *       {@code nexuscore:empty} resolves to the shared structure directly. (NeoForge has no such
 *       verbatim rule, which is why its holder writes {@code template = "empty"} instead — the
 *       loaders genuinely disagree here.)</li>
 *   <li>Vanilla enumerates {@code getDeclaredMethods()}, so every method is declared here and
 *       delegates rather than inherited.</li>
 * </ul>
 */
@GameTestHolder(NexusServices.MOD_ID)
public final class NexusGameTestsHolder {

    private final NexusGameTests shared = new NexusGameTests();

    /** @param helper the running test */
    @GameTest(template = "nexuscore:empty")
    public void harnessRuns(GameTestHelper helper) {
        shared.harnessRuns(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "nexuscore:empty")
    public void permissionGatingRefusesByDefault(GameTestHelper helper) {
        shared.permissionGatingRefusesByDefault(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "nexuscore:empty")
    public void explainPathSeesTheSameGrantAsEnforcement(GameTestHelper helper) {
        shared.explainPathSeesTheSameGrantAsEnforcement(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "nexuscore:empty")
    public void punishmentEnforcementIsPerPlayer(GameTestHelper helper) {
        shared.punishmentEnforcementIsPerPlayer(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "nexuscore:empty")
    public void oneLiftClearsASupersededBan(GameTestHelper helper) {
        shared.oneLiftClearsASupersededBan(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "nexuscore:empty")
    public void unknownNameResolutionDoesNotBlock(GameTestHelper helper) {
        shared.unknownNameResolutionDoesNotBlock(helper);
    }
}
