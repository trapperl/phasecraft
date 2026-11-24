package dev.phasecraft.mixin;

import dev.phasecraft.phased.PhasedItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While a phased dropper or dispenser acts on a visible level, nothing it does is heard there (see PhasedItems). */
@Mixin(ServerLevel.class)
abstract class ServerLevelSilenceMixin {
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"), cancellable = true)
    private void phasecraft$silenceAt(Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
            float volume, float pitch, long seed, CallbackInfo ci) {
        if (PhasedItems.isSilenced((ServerLevel) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"), cancellable = true)
    private void phasecraft$silenceFrom(Entity except, Entity sourceEntity, Holder<SoundEvent> sound, SoundSource source,
            float volume, float pitch, long seed, CallbackInfo ci) {
        if (PhasedItems.isSilenced((ServerLevel) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/Entity;ILnet/minecraft/core/BlockPos;I)V", at = @At("HEAD"), cancellable = true)
    private void phasecraft$silenceEvent(Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
        if (PhasedItems.isSilenced((ServerLevel) (Object) this)) {
            ci.cancel();
        }
    }
}
