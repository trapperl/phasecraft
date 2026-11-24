package dev.phasecraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TripWireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's tripwire check, for phased strings no entity ever touches (see PhasedInput). */
@Mixin(TripWireBlock.class)
public interface TripWireBlockAccessor {
    @Invoker("checkPressed")
    void phasecraft$checkPressed(Level level, BlockPos pos);
}
