package dev.phasecraft.mixin;

import dev.phasecraft.link.PhaseLink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A hidden phase port's up direction: every signal read in a server level also sees the power a
 * hidden port at that position puts up (its UP), as if an invisible redstone block stood there.
 * Vanilla's SignalGetter.getSignal is an interface default, so ServerLevel gets an override with
 * the same body plus the port. Hidden levels never have anything put up into them.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPhaseBridgeMixin implements SignalGetter {
    @Override
    public int getSignal(BlockPos pos, Direction direction) {
        BlockState state = getBlockState(pos);
        int signal = state.getSignal(this, pos, direction);
        if (state.isRedstoneConductor(this, pos)) {
            signal = Math.max(signal, getDirectSignalTo(pos));
        }
        return Math.max(signal, PhaseLink.upAt((ServerLevel) (Object) this, pos));
    }
}
