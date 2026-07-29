package com.mwtstudios.nexuscore.gametest;

import com.mwtstudios.nexuscore.core.NexusServices;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;

/**
 * Forge's declared holder for the shared world-and-player tests.
 *
 * <p>Bodies live in {@link NexusWorldGameTests} in {@code common/}. See
 * {@link NexusGameTestsHolder} for Forge's three discovery rules and where each was read from —
 * the name-prefix filter is the one that silently drops unannotated shared classes.</p>
 */
@GameTestHolder(NexusServices.MOD_ID)
public final class NexusWorldGameTestsHolder {

    private final NexusWorldGameTests shared = new NexusWorldGameTests();

    /** @param helper the running test */
    @GameTest(template = "nexuscore:empty")
    public void teleportRefusesASuffocatingDestination(GameTestHelper helper) {
        shared.teleportRefusesASuffocatingDestination(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "nexuscore:empty")
    public void teleportAcceptsSolidGroundWithHeadroom(GameTestHelper helper) {
        shared.teleportAcceptsSolidGroundWithHeadroom(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "nexuscore:empty")
    public void teleportRefusesLava(GameTestHelper helper) {
        shared.teleportRefusesLava(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "nexuscore:empty")
    public void homesPersistWithTheirCoordinates(GameTestHelper helper) {
        shared.homesPersistWithTheirCoordinates(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "nexuscore:empty")
    public void homeLimitIsEnforcedAndDeletingFreesASlot(GameTestHelper helper) {
        shared.homeLimitIsEnforcedAndDeletingFreesASlot(helper);
    }

    /** @param helper the running test */
    @GameTest(template = "nexuscore:empty")
    public void adminMenuRefusesEveryClick(GameTestHelper helper) {
        shared.adminMenuRefusesEveryClick(helper);
    }
}
