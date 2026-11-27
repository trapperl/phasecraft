package dev.phasecraft.mixin;

import dev.phasecraft.layer.HiddenLayerGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No mob is born in the hidden layer (HiddenLayerGuard.mayBeBorn). Every new entity the game makes goes through this
 * method with the reason it is made: spawners, trial spawners, natural spawning, spawn eggs, dispensers, breeding,
 * buckets, raids and patrols, golems built from blocks; and so do the ones loaded from a save, which are let through.
 */
@Mixin(EntityType.class)
abstract class EntityTypeHiddenSpawnMixin<T extends Entity> {
    @Inject(method = "create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnRequest;)Lnet/minecraft/world/entity/Entity;",
            at = @At("RETURN"), cancellable = true)
    private void phasecraft$noMobsBornInHiddenLayer(Level level, EntitySpawnRequest request, CallbackInfoReturnable<T> cir) {
        if (!HiddenLayerGuard.mayBeBorn(level, request.reason(), cir.getReturnValue())) {
            cir.setReturnValue(null);
        }
    }
}
