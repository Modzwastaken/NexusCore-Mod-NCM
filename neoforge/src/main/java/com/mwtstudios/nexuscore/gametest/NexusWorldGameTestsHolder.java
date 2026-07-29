package com.mwtstudios.nexuscore.gametest;

import com.mwtstudios.nexuscore.core.NexusServices;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * NeoForge's declared holder for the shared world-and-player tests.
 *
 * <p>Bodies live in {@link NexusWorldGameTests} in {@code common/}. See
 * {@link NexusGameTestsHolder} for why this class exists at all — the same three discovery rules
 * apply, and none of them can be satisfied from shared source.</p>
 */
@GameTestHolder(NexusServices.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NexusWorldGameTestsHolder {

    private final NexusWorldGameTests shared = new NexusWorldGameTests();

    /** @param helper the running test */
    @GameTest(template = "empty")
    public void teleportRefusesASuffocatingDestination(GameTestHelper helper) {
        shared.teleportRefusesASuffocatingDestination(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "empty")
    public void teleportAcceptsSolidGroundWithHeadroom(GameTestHelper helper) {
        shared.teleportAcceptsSolidGroundWithHeadroom(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "empty")
    public void teleportRefusesLava(GameTestHelper helper) {
        shared.teleportRefusesLava(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "empty")
    public void homesPersistWithTheirCoordinates(GameTestHelper helper) {
        shared.homesPersistWithTheirCoordinates(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "empty")
    public void homeLimitIsEnforcedAndDeletingFreesASlot(GameTestHelper helper) {
        shared.homeLimitIsEnforcedAndDeletingFreesASlot(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "empty")
    public void adminMenuRefusesEveryClick(GameTestHelper helper) {
        shared.adminMenuRefusesEveryClick(helper);
    }
}
