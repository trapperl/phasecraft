package dev.phasecraft.test.mixin;

import dev.phasecraft.layer.HiddenDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEST ONLY (lives in the phasecraft-test mod, never shipped).
 *
 * <p>Vanilla clears a test's box before it builds the test there, and between batches (every batch reuses the same
 * grid of places): the visible blocks only. A hidden block a test left behind stays in the hidden layer, where the
 * next test at that place is built, and can power it (an earlier batch's hidden redstone once paired with, and
 * stopped, an area shifter test's corners). This clears the hidden layer in the same box first, with vanilla's own
 * flags (no drops, no block entity side effects: a removed area shifter puts nothing back), and its scheduled
 * ticks.</p>
 */
@Mixin(StructureUtils.class)
abstract class ClearHiddenTestSpaceMixin {
    private static final int CLEAR_FLAGS = 818;     // as StructureUtils.clearBlock

    @Inject(method = "clearSpaceForStructure", at = @At("HEAD"))
    private static void phasecraftTest$clearHiddenToo(BoundingBox box, ServerLevel level, CallbackInfo ci) {
        if (HiddenDimensions.isHidden(level.dimension())) {
            return;
        }
        ServerLevel hidden = HiddenDimensions.hiddenOf(level.dimension())
                .map(key -> level.getServer().getLevel(key)).orElse(null);
        if (hidden == null) {
            return;
        }
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (!hidden.getBlockState(pos).isAir()) {
                hidden.setBlock(pos, Blocks.AIR.defaultBlockState(), CLEAR_FLAGS);
            }
        }
        hidden.getBlockTicks().clearArea(box);
        hidden.getFluidTicks().clearArea(box);
    }
}
