package dev.phasecraft.mixin;

import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.phased.Phased;
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

/**
 * Sounds made by a phased block (a button clicking, a plate pressed or
 * released) are also played in the visible world at the same spot, to
 * everyone there, the player who clicked included: they hear their own
 * secret button.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelPhasedSoundMixin {
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"))
    private void phasecraft$echoPhasedSound(Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
            float volume, float pitch, long seed, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (!HiddenDimensions.isHidden(level.dimension())) {
            return;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (level.isLoaded(pos) && Phased.isPhased(level, pos, level.getBlockState(pos))) {
            HiddenLayer.of(level).visibleLevel().playSeededSound(null, x, y, z, sound, source, volume, pitch, seed);
        }
    }
}
