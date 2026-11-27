package dev.phasecraft.shifter;

import dev.phasecraft.PhaseCraftBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** The visible block a phase shifter is holding while it is out of the visible world, if any. */
public class PhaseShifterBlockEntity extends BlockEntity implements HoldsBlocks {
    private Shifting.@Nullable Held held;

    public PhaseShifterBlockEntity(BlockPos pos, BlockState state) {
        super(PhaseCraftBlocks.PHASE_SHIFTER_ENTITY, pos, state);
    }

    @Override
    public int heldCount() {
        return held == null ? 0 : 1;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level instanceof net.minecraft.server.level.ServerLevel server) {
            dev.phasecraft.overlay.OverlaySync.onHoldingChanged(server, worldPosition, heldCount());
        }
    }

    /** The held block, if any. */
    public @Nullable BlockState held() {
        return held == null ? null : held.state();
    }

    Shifting.@Nullable Held holding() {
        return held;
    }

    void setHeld(Shifting.@Nullable Held held) {
        this.held = held;
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        // The two fields as they were saved before the line shifter shared Shifting.Held.
        output.storeNullable("held", BlockState.CODEC, held());
        output.storeNullable("held_data", CompoundTag.CODEC, held == null ? null : held.data());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        held = input.read("held", BlockState.CODEC)
                .map(state -> new Shifting.Held(state, input.read("held_data", CompoundTag.CODEC).orElse(null)))
                .orElse(null);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        PhaseShifterBlock.release(this);
    }
}
