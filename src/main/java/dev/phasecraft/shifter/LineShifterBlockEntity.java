package dev.phasecraft.shifter;

import dev.phasecraft.PhaseCraftBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * What a line shifter holds: the visible blocks it has taken out of its line, by distance (1 is the block right in
 * front of it), and how far the move it is making still has to go.
 */
public class LineShifterBlockEntity extends BlockEntity {
    /** The longest line a piston can push, and so the longest a line shifter takes. */
    public static final int MAX = 12;

    private final Shifting.@Nullable Held[] held = new Shifting.Held[MAX + 1];
    /** While taking: the farthest distance still to take (it takes from there towards itself); 0 when not taking. */
    private int todo;
    /** The game time before which the next block may not move, so a turn around keeps the rhythm. */
    private long nextStep;

    public LineShifterBlockEntity(BlockPos pos, BlockState state) {
        super(PhaseCraftBlocks.LINE_SHIFTER_ENTITY, pos, state);
    }

    /** The block held from the given distance (1 to {@link #MAX}), if any. */
    public @Nullable BlockState held(int distance) {
        Shifting.Held h = held[distance];
        return h == null ? null : h.state();
    }

    /** How many blocks it holds. */
    public int holding() {
        int n = 0;
        for (int d = 1; d <= MAX; d++) {
            if (held[d] != null) {
                n++;
            }
        }
        return n;
    }

    Shifting.@Nullable Held heldAt(int distance) {
        return held[distance];
    }

    void setHeld(int distance, Shifting.@Nullable Held h) {
        held[distance] = h;
        setChanged();
    }

    /** The nearest distance it holds a block from, or 0. */
    int nearestHeld() {
        for (int d = 1; d <= MAX; d++) {
            if (held[d] != null) {
                return d;
            }
        }
        return 0;
    }

    int todo() {
        return todo;
    }

    void setTodo(int todo) {
        this.todo = todo;
        setChanged();
    }

    long nextStep() {
        return nextStep;
    }

    void setNextStep(long nextStep) {
        this.nextStep = nextStep;
        setChanged();
    }

    private record Slot(int distance, Shifting.Held held) {
        static final com.mojang.serialization.Codec<Slot> CODEC =
                com.mojang.serialization.codecs.RecordCodecBuilder.create(i -> i.group(
                        com.mojang.serialization.Codec.INT.fieldOf("distance").forGetter(Slot::distance),
                        Shifting.Held.CODEC.fieldOf("held").forGetter(Slot::held))
                        .apply(i, Slot::new));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        List<Slot> slots = new ArrayList<>();
        for (int d = 1; d <= MAX; d++) {
            if (held[d] != null) {
                slots.add(new Slot(d, held[d]));
            }
        }
        output.store("held", Slot.CODEC.listOf(), slots);
        output.putInt("todo", todo);
        output.putLong("next_step", nextStep);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        java.util.Arrays.fill(held, null);
        for (Slot slot : input.read("held", Slot.CODEC.listOf()).orElse(List.of())) {
            if (slot.distance() >= 1 && slot.distance() <= MAX) {
                held[slot.distance()] = slot.held();
            }
        }
        todo = input.getIntOr("todo", 0);
        nextStep = input.getLongOr("next_step", 0L);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        LineShifterBlock.release(this, state);
    }
}
