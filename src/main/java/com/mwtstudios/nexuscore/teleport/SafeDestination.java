package com.mwtstudios.nexuscore.teleport;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import net.minecraft.server.level.ServerLevel;

/**
 * Destination safety, implementing the §19.1 algorithm.
 *
 * <p>The contract is narrow and absolute: this either returns a position a player can stand
 * in without being hurt, or it returns a <em>specific reason</em> why it could not find one.
 * It never falls back to "close enough". §19.1 step 7 is explicit — never place the player in
 * an unsafe fallback silently — because the failure mode of a silent fallback is a player
 * teleported into lava, which is exactly the bug an administration mod must not have.</p>
 */
public final class SafeDestination {

    /** Blocks that hurt a player standing in or on them. */
    private static final Set<Block> HARMFUL = Set.of(
            Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS,
            Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE,
            Blocks.POWDER_SNOW, Blocks.END_PORTAL, Blocks.NETHER_PORTAL);

    private SafeDestination() {
        // Utility holder.
    }

    /**
     * Finds a safe standing position at or near the requested coordinates.
     *
     * <p>Searches the requested Y first, then alternates downward and upward within
     * {@code searchHeight}. Downward is tried first at each step because a home saved on a
     * block that has since been mined is far more common than one saved inside terrain.</p>
     *
     * @param level the destination world
     * @param x requested X
     * @param y requested Y
     * @param z requested Z
     * @param searchHeight how many blocks up and down may be searched
     * @return a result carrying either a position or the reason none was found
     */
    public static Result find(ServerLevel level, double x, double y, double z, int searchHeight) {
        BlockPos requested = BlockPos.containing(x, y, z);

        if (!level.getWorldBorder().isWithinBounds(requested)) {
            return Result.unsafe("the destination is outside this world's border");
        }
        if (requested.getY() < level.getMinBuildHeight() || requested.getY() > level.getMaxBuildHeight()) {
            return Result.unsafe("the destination is outside this world's build height");
        }

        // §19.1 step 2: bring exactly one chunk in, never a cascade. If it is already
        // resident this is free; if it is not, the cost is bounded to that single chunk.
        LevelChunk chunk = level.getChunk(requested.getX() >> 4, requested.getZ() >> 4);
        if (chunk == null) {
            return Result.unsafe("the destination chunk could not be loaded");
        }

        if (isSafeAt(level, requested)) {
            return Result.safe(centre(requested));
        }
        for (int offset = 1; offset <= searchHeight; offset++) {
            BlockPos below = requested.below(offset);
            if (below.getY() >= level.getMinBuildHeight() && isSafeAt(level, below)) {
                return Result.safe(centre(below));
            }
            BlockPos above = requested.above(offset);
            if (above.getY() <= level.getMaxBuildHeight() - 2 && isSafeAt(level, above)) {
                return Result.safe(centre(above));
            }
        }
        return Result.unsafe("no safe ground was found within " + searchHeight + " blocks of the destination");
    }

    /**
     * Tests one candidate position.
     *
     * @param level the world
     * @param feet the block the player's feet would occupy
     * @return true when the player can stand there unharmed
     */
    public static boolean isSafeAt(ServerLevel level, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos ground = feet.below();

        if (ground.getY() < level.getMinBuildHeight()) {
            return false;
        }

        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(head);
        BlockState groundState = level.getBlockState(ground);

        // Feet and head must be open, and neither may suffocate the player. The collision
        // shape is used rather than the deprecated blocksMotion(), and it is also the more
        // accurate question: a carpet or a pressure plate has no collision and is fine to
        // stand in, while blocksMotion() reasons about the block as a whole.
        if (!isPassable(level, feet, feetState) || !isPassable(level, head, headState)) {
            return false;
        }
        if (feetState.isSuffocating(level, feet) || headState.isSuffocating(level, head)) {
            return false;
        }
        // Standing in any fluid — lava obviously, but water too, since dropping a player into
        // deep water is a drowning risk, not a courtesy.
        if (!feetState.getFluidState().isEmpty() || !headState.getFluidState().isEmpty()) {
            return false;
        }
        // There must be something to stand on: no void drop, no falling through.
        if (isPassable(level, ground, groundState)) {
            return false;
        }
        return !isHarmful(feetState) && !isHarmful(headState) && !isHarmful(groundState);
    }

    private static boolean isPassable(ServerLevel level, BlockPos pos, BlockState state) {
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isHarmful(BlockState state) {
        return HARMFUL.contains(state.getBlock()) || !state.getFluidState().isEmpty();
    }

    private static Vec3 centre(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    /**
     * The outcome of a safety search.
     *
     * @param safe true when a position was found
     * @param position the safe position, or null
     * @param reason why no position was found, or null when one was
     */
    public record Result(boolean safe, Vec3 position, String reason) {

        static Result safe(Vec3 position) {
            return new Result(true, position, null);
        }

        static Result unsafe(String reason) {
            return new Result(false, null, reason);
        }
    }
}
