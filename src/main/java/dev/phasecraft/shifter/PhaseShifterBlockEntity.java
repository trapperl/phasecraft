package dev.phasecraft.shifter;

import dev.phasecraft.PhaseCraftBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** The visible block a phase shifter is holding while it is out of the visible world, if any. */
public class PhaseShifterBlockEntity extends BlockEntity {
    private @Nullable BlockState held;

    public PhaseShifterBlockEntity(BlockPos pos, BlockState state) {
        super(PhaseCraftBlocks.PHASE_SHIFTER_ENTITY, pos, state);
    }

    public @Nullable BlockState held() {
        return held;
    }

    void setHeld(@Nullable BlockState state) {
        held = state;
        setChanged();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("held", BlockState.CODEC, held);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        held = input.read("held", BlockState.CODEC).orElse(null);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        PhaseShifterBlock.release(this);
    }
}
