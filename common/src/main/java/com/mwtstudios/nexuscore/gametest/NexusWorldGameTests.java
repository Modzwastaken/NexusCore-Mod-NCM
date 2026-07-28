package com.mwtstudios.nexuscore.gametest;

import java.util.UUID;

import com.mwtstudios.nexuscore.core.NexusBootstrap;
import com.mwtstudios.nexuscore.core.NexusServices;
import com.mwtstudios.nexuscore.gui.AdminMenu;
import com.mwtstudios.nexuscore.teleport.SafeDestination;
import com.mwtstudios.nexuscore.teleport.TeleportService;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;


/**
 * In-server tests that need a real world or a real player.
 *
 * <p>Separate from {@link NexusGameTests}, which asserts about services alone. Everything here
 * places blocks, moves a player, or opens a container — the things a unit test cannot do at all,
 * and the reason several 1.1.1 fixes were correct in code but verified only by reading.</p>
 */
public final class NexusWorldGameTests {

    private static NexusServices services() {
        NexusServices services = NexusBootstrap.runningServices();
        if (services == null) {
            throw new IllegalStateException("NexusCore is not loaded");
        }
        return services;
    }

    // ---- teleport safety (M5) -----------------------------------------------------------

    /**
     * A destination inside solid rock is refused, with a reason.
     *
     * @param helper the running test
     */
    @GameTest(template = "nexuscore:empty")
    public void teleportRefusesASuffocatingDestination(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1));

        // A solid column: feet and head both inside stone, with stone above so the upward search
        // finds no pocket either.
        for (int y = 0; y < 6; y++) {
            level.setBlockAndUpdate(base.above(y), Blocks.STONE.defaultBlockState());
        }

        SafeDestination.Result result = SafeDestination.find(
                level, base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 4);

        helper.assertFalse(result.safe(),
                "a destination buried in stone must be refused — teleporting into rock is the "
                        + "failure this check exists to prevent");
        helper.assertTrue(result.reason() != null && !result.reason().isBlank(),
                "and the refusal must say why, because the operator sees this text");
        helper.succeed();
    }

    /**
     * A destination standing on solid ground with air above it is accepted.
     *
     * @param helper the running test
     */
    @GameTest(template = "nexuscore:empty")
    public void teleportAcceptsSolidGroundWithHeadroom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        for (int y = 1; y <= 3; y++) {
            level.setBlockAndUpdate(floor.above(y), Blocks.AIR.defaultBlockState());
        }

        SafeDestination.Result result = SafeDestination.find(
                level, floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5, 4);

        helper.assertTrue(result.safe(),
                "solid footing with headroom is the ordinary safe case and must not be refused: "
                        + result.reason());
        helper.succeed();
    }

    /**
     * Lava is refused rather than treated as passable space.
     *
     * @param helper the running test
     */
    @GameTest(template = "nexuscore:empty")
    public void teleportRefusesLava(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos floor = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(floor, Blocks.LAVA.defaultBlockState());
        level.setBlockAndUpdate(floor.above(), Blocks.LAVA.defaultBlockState());

        SafeDestination.Result result = SafeDestination.find(
                level, floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5, 2);

        helper.assertFalse(result.safe(), "standing in lava is not a safe destination");
        helper.succeed();
    }

    // ---- home persistence (M5) ----------------------------------------------------------

    /**
     * A home survives a round trip through storage, with its coordinates intact.
     *
     * @param helper the running test
     */
    @GameTest(template = "nexuscore:empty")
    public void homesPersistWithTheirCoordinates(GameTestHelper helper) {
        NexusServices services = services();
        UUID owner = UUID.randomUUID();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        TeleportService.Location where = TeleportService.Location.of(player);

        helper.assertTrue(services.teleport().setHome(owner, "base", where, 3),
                "setting a first home within the limit should succeed");

        TeleportService.Location read = services.teleport().home(owner, "base")
                .orElseThrow(() -> new AssertionError("the home was not readable after being set"));

        helper.assertValueEqual(read.dimension(), where.dimension(), "the world must survive the round trip");
        helper.assertTrue(Math.abs(read.x() - where.x()) < 1.0e-6, "x must survive exactly");
        helper.assertTrue(Math.abs(read.y() - where.y()) < 1.0e-6, "y must survive exactly");
        helper.assertTrue(Math.abs(read.z() - where.z()) < 1.0e-6, "z must survive exactly");
        helper.assertTrue(services.teleport().homeNames(owner).contains("base"),
                "and the home must be listed under its own name");
        helper.succeed();
    }

    /**
     * The home limit is enforced, and deleting frees a slot.
     *
     * @param helper the running test
     */
    @GameTest(template = "nexuscore:empty")
    public void homeLimitIsEnforcedAndDeletingFreesASlot(GameTestHelper helper) {
        NexusServices services = services();
        UUID owner = UUID.randomUUID();
        TeleportService.Location where = TeleportService.Location.of(helper.makeMockServerPlayerInLevel());

        helper.assertTrue(services.teleport().setHome(owner, "one", where, 2), "first is within the limit");
        helper.assertTrue(services.teleport().setHome(owner, "two", where, 2), "second reaches the limit");
        helper.assertFalse(services.teleport().setHome(owner, "three", where, 2),
                "a third must be refused, or the limit is decoration");

        helper.assertTrue(services.teleport().deleteHome(owner, "one"), "deleting an existing home succeeds");
        helper.assertTrue(services.teleport().setHome(owner, "three", where, 2),
                "and the freed slot is usable, so the limit counts what exists rather than what "
                        + "was ever created");
        helper.succeed();
    }

    // ---- the admin panel is a read-only container (M5) -----------------------------------

    /**
     * Items cannot be taken out of the admin panel, or put into it.
     *
     * <p>The panel is a real chest menu on an unmodified client, so the only thing standing between
     * a decorative icon and a duplicated item is that this container refuses every click. Named in
     * the 1.1.2 rung as the missing test, and it needs a real player with a real inventory.</p>
     *
     * @param helper the running test
     */
    @GameTest(template = "nexuscore:empty")
    public void adminMenuRefusesEveryClick(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        SimpleContainer contents = new SimpleContainer(27);
        contents.setItem(0, new ItemStack(Items.DIAMOND, 5));

        Inventory inventory = player.getInventory();
        inventory.clearContent();
        inventory.setItem(0, new ItemStack(Items.STONE, 3));

        AdminMenu menu = new AdminMenu(1, inventory, contents, 3, java.util.Map.of());

        // Take from the panel.
        menu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertValueEqual(contents.getItem(0).getCount(), 5,
                "the panel's own contents must be untouched by a click");
        helper.assertTrue(menu.getCarried().isEmpty(),
                "and nothing may end up on the cursor — that is how an icon becomes an item");

        // Shift-click out of the panel.
        helper.assertTrue(menu.quickMoveStack(player, 0).isEmpty(),
                "quickMoveStack must always return empty, or shift-click moves icons into a "
                        + "player's inventory");

        // Put something in from the player's inventory.
        int before = inventory.getItem(0).getCount();
        menu.clicked(27, 0, ClickType.PICKUP, player);
        helper.assertValueEqual(inventory.getItem(0).getCount(), before,
                "a click on the player's own row must not move items into the panel either");
        helper.succeed();
    }
}
