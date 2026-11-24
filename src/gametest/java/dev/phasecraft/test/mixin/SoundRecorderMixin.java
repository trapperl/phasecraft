package dev.phasecraft.test.mixin;

import dev.phasecraft.test.SoundRecorder;
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

/** Test only: records every sound a server level sends to players (SoundRecorder), at the broadcast, so silenced ones are not counted, so tests can tell where sounds are heard. */
@Mixin(ServerLevel.class)
abstract class SoundRecorderMixin {
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V"))
    private void phasecraftTest$record(Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
            float volume, float pitch, long seed, CallbackInfo ci) {
        SoundRecorder.record((ServerLevel) (Object) this, x, y, z, sound);
    }

    /** Level events (a dispenser's click and smoke, a door's creak...) are heard and seen too: recorded as "level_event:<type>". */
    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/Entity;ILnet/minecraft/core/BlockPos;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V"))
    private void phasecraftTest$recordEvent(Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
        SoundRecorder.recordEvent((ServerLevel) (Object) this, pos, type);
    }
}
