package dev.phasecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phasecraft.phased.PhasedItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A phased dropper drops into the visible container in front of it, or out into the visible world, silently there (see PhasedItems). */
@Mixin(DropperBlock.class)
abstract class DropperBlockMixin {
    @WrapOperation(method = "dispenseFrom", at = @At(value = "NEW", target = "net/minecraft/core/dispenser/BlockSource"))
    private BlockSource phasecraft$visibleSource(ServerLevel level, BlockPos pos, BlockState state, DispenserBlockEntity entity,
            Operation<BlockSource> original) {
        ServerLevel visible = PhasedItems.visibleFor(level, pos, state);
        return original.call(visible != null ? visible : level, pos, state, entity);
    }

    @WrapOperation(method = "dispenseFrom", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;getContainerAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/Container;"))
    private Container phasecraft$visibleContainer(Level level, BlockPos front, Operation<Container> original,
            ServerLevel hidden, BlockState state, BlockPos pos) {
        ServerLevel visible = PhasedItems.visibleFor(hidden, pos, state);
        return original.call(visible != null ? visible : level, front);
    }

    @WrapOperation(method = "dispenseFrom", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/dispenser/DispenseItemBehavior;dispense(Lnet/minecraft/core/dispenser/BlockSource;Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack phasecraft$silently(DispenseItemBehavior behavior, BlockSource source, ItemStack stack, Operation<ItemStack> original) {
        if (source.level() != source.blockEntity().getLevel()) {
            return PhasedItems.silently(source.level(), () -> original.call(behavior, source, stack));
        }
        return original.call(behavior, source, stack);
    }
}
