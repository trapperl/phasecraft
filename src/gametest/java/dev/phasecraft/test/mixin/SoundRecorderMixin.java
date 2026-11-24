package dev.phasecraft.test.mixin;

import dev.phasecraft.test.SoundRecorder;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test only: records every sound a server level plays (SoundRecorder), so tests can tell where sounds are heard. */
@Mixin(ServerLevel.class)
abstract class SoundRecorderMixin {
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"))
    private void phasecraftTest$record(Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
            float volume, float pitch, long seed, CallbackInfo ci) {
        SoundRecorder.record((ServerLevel) (Object) this, x, y, z, sound);
    }
}
