package dev.phasecraft.test.mixin;

import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test clients skip Mojang's blocked-servers list. Vanilla downloads it from
 * sessionserver.mojang.com (a hardcoded address, through the patchy library)
 * before joining any server; test games never talk to Mojang (see
 * NoMojangTrafficGameTests), and only ever join their own local test server.
 */
@Mixin(AddressCheck.class)
interface AddressCheckMixin {
    @Inject(method = "createFromService", at = @At("HEAD"), cancellable = true)
    private static void phasecraftTest$allowAll(CallbackInfoReturnable<AddressCheck> cir) {
        cir.setReturnValue(new AddressCheck() {
            @Override
            public boolean isAllowed(ResolvedServerAddress address) {
                return true;
            }

            @Override
            public boolean isAllowed(ServerAddress address) {
                return true;
            }
        });
    }
}
