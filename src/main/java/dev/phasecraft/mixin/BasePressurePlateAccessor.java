package dev.phasecraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla pressure-plate internals that phased plates need (see PhasedInput). */
@Mixin(BasePressurePlateBlock.class)
public interface BasePressurePlateAccessor {
    @Accessor("TOUCH_AABB")
    static AABB phasecraft$touchAabb() {
        throw new AssertionError();
    }

    @Invoker("getEntityCount")
    static int phasecraft$getEntityCount(Level level, AABB box, Class<? extends Entity> type) {
        throw new AssertionError();
    }

    @Invoker("checkPressed")
    void phasecraft$checkPressed(Entity entity, Level level, BlockPos pos, BlockState state, int signal);

    @Invoker("getSignalForState")
    int phasecraft$getSignalForState(BlockState state);
}
