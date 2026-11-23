package dev.phasecraft.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * TEST ONLY (lives in the phasecraft-test mod, never shipped).
 *
 * <p>Vanilla's GameTest server builds its world from the flat preset and
 * deliberately passes an empty registry instead of the data-pack dimensions,
 * so PhaseCraft's hidden dimensions (data/phasecraft/dimension) would not
 * exist in tests. A normal server includes them. This passes the loaded
 * data-pack dimensions through, as a normal server does.</p>
 */
@Mixin(GameTestServer.class)
abstract class GameTestServerDimensionsMixin {
    @WrapOperation(
            method = "lambda$create$1",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/WorldDimensions;bake(Lnet/minecraft/core/Registry;)Lnet/minecraft/world/level/levelgen/WorldDimensions$Complete;"))
    private static WorldDimensions.Complete phasecraftTest$includeDatapackDimensions(
            WorldDimensions dimensions, Registry<LevelStem> noDatapackDimensions,
            Operation<WorldDimensions.Complete> original,
            @Local(argsOnly = true) WorldLoader.DataLoadContext context) {
        return original.call(dimensions, context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM));
    }
}
