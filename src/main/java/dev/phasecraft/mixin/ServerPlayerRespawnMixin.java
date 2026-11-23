package dev.phasecraft.mixin;

import dev.phasecraft.layer.HiddenDimensions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No respawn point in the hidden layer: every later death would respawn you over the void. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerRespawnMixin {
    @Inject(method = "setRespawnPosition", at = @At("HEAD"), cancellable = true)
    private void phasecraft$noHiddenRespawn(ServerPlayer.RespawnConfig config, boolean showMessage, CallbackInfo ci) {
        if (config != null && HiddenDimensions.isHidden(config.respawnData().dimension())) {
            if (showMessage) {
                ((ServerPlayer) (Object) this).sendSystemMessage(Component.translatable("phasecraft.guard.no_respawn"));
            }
            ci.cancel();
        }
    }
}
