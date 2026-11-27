package dev.phasecraft.mixin;

import dev.phasecraft.edit.MachineOwners;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A hidden machine belongs to the player who placed it (see MachineOwners): every block a player places, from
 * editing mode or standing in the hidden layer, goes through here.
 */
@Mixin(BlockItem.class)
abstract class BlockItemMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void phasecraft$rememberPlacer(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (cir.getReturnValue().consumesAction() && context.getLevel() instanceof ServerLevel level
                && context.getPlayer() instanceof ServerPlayer player) {
            MachineOwners.onPlaced(level, context.getClickedPos(), player.nameAndId());
        }
    }
}
