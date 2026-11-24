package dev.phasecraft.client;

import dev.phasecraft.piston.PhasePistonAnimation;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Shows blocks moved by a phase piston sliding: places the moving-block entities
 * vanilla's client places for its own pistons, and clears the cells the blocks
 * left (see PhasePistonAnimation). The server's block updates then settle the result.
 */
final class PhasePistonAnimationClient {
    private PhasePistonAnimationClient() {}

    static void apply(PhasePistonAnimation.Moved moved) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Set<BlockPos> targets = new HashSet<>();
        for (PhasePistonAnimation.Moving moving : moved.blocks()) {
            targets.add(moving.pos());
        }
        for (PhasePistonAnimation.Moving moving : moved.blocks()) {
            BlockState movingPiston = Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, moving.direction());
            level.setBlock(moving.pos(), movingPiston, 324);
            level.setBlockEntity(MovingPistonBlock.newMovingBlockEntity(moving.pos(), movingPiston, moving.state(),
                    moving.direction(), moving.extending(), false));
        }
        for (PhasePistonAnimation.Moving moving : moved.blocks()) {
            if (!targets.contains(moving.from()) && level.getBlockState(moving.from()).equals(moving.state())) {
                level.setBlock(moving.from(), Blocks.AIR.defaultBlockState(), 82);
            }
        }
    }
}
