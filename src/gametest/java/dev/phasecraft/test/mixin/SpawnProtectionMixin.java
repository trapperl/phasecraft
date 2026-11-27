package dev.phasecraft.test.mixin;

import dev.phasecraft.test.TestSpawnProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * TEST ONLY (lives in the phasecraft-test mod, never shipped): spawn protection for the spots in
 * {@link TestSpawnProtection}, as DedicatedServer.isUnderSpawnProtection gives near spawn: only in the
 * Overworld (the respawn dimension), never in a hidden dimension.
 */
@Mixin(MinecraftServer.class)
abstract class SpawnProtectionMixin {
    @Inject(method = "isUnderSpawnProtection", at = @At("HEAD"), cancellable = true)
    private void phasecraftTest$protectedSpots(ServerLevel level, BlockPos pos, Player player, CallbackInfoReturnable<Boolean> cir) {
        if (level.dimension() == Level.OVERWORLD && TestSpawnProtection.PROTECTED.contains(pos)) {
            cir.setReturnValue(true);
        }
    }
}
