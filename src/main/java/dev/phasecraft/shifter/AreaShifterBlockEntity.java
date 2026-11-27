package dev.phasecraft.shifter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.phasecraft.PhaseCraftBlocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * An area shifter corner's state. The leader of a pair (see {@link AreaShifterBlock}) holds the box's blocks while
 * they are out of the visible world, with the other corner and how far down a move has taken; the other corner only
 * remembers its leader, so that removing it tells the leader.
 */
public class AreaShifterBlockEntity extends BlockEntity implements HoldsBlocks {
    /** The box's blocks out of the visible world, by position (ordered, so a layer goes back in a fixed order). */
    private final Map<BlockPos, Shifting.Held> held = new TreeMap<>();
    /** Leader: the other corner while a box is taken or moving; null when nothing is held. */
    private @Nullable BlockPos partner;
    /** Not a leader: the leader that holds this corner's box, if any. */
    private @Nullable BlockPos leader;
    /** While taking: the highest layer (y) still to look at; below the box when done. */
    private int cursor = Integer.MIN_VALUE;
    /** The game time before which the next layer may not move (not saved: see LineShifterBlockEntity). */
    private long nextStep;

    public AreaShifterBlockEntity(BlockPos pos, BlockState state) {
        super(PhaseCraftBlocks.AREA_SHIFTER_ENTITY, pos, state);
    }

    @Override
    public int heldCount() {
        return holding();
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level instanceof net.minecraft.server.level.ServerLevel server) {
            dev.phasecraft.overlay.OverlaySync.onHoldingChanged(server, worldPosition, heldCount());
        }
    }

    /** How many blocks of its box it holds. */
    public int holding() {
        return held.size();
    }

    /** The block held from that position, if any. */
    public @Nullable BlockState held(BlockPos pos) {
        Shifting.Held h = held.get(pos);
        return h == null ? null : h.state();
    }

    Map<BlockPos, Shifting.Held> heldMap() {
        return held;
    }

    void hold(BlockPos pos, Shifting.@Nullable Held h) {
        if (h == null) {
            held.remove(pos);
        } else {
            held.put(pos.immutable(), h);
        }
        setChanged();
    }

    @Nullable BlockPos partner() {
        return partner;
    }

    void setPartner(@Nullable BlockPos partner) {
        this.partner = partner;
        setChanged();
    }

    @Nullable BlockPos leader() {
        return leader;
    }

    void setLeader(@Nullable BlockPos leader) {
        this.leader = leader;
        setChanged();
    }

    int cursor() {
        return cursor;
    }

    void setCursor(int cursor) {
        this.cursor = cursor;
        setChanged();
    }

    long nextStep() {
        return nextStep;
    }

    void setNextStep(long nextStep) {
        this.nextStep = nextStep;
    }

    private record Slot(BlockPos offset, Shifting.Held held) {
        static final Codec<Slot> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("offset").forGetter(Slot::offset),
                Shifting.Held.CODEC.fieldOf("held").forGetter(Slot::held))
                .apply(i, Slot::new));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        List<Slot> slots = new ArrayList<>();
        held.forEach((pos, h) -> slots.add(new Slot(pos.subtract(worldPosition), h)));
        output.store("held", Slot.CODEC.listOf(), slots);
        output.storeNullable("partner", BlockPos.CODEC, partner == null ? null : partner.subtract(worldPosition));
        output.storeNullable("leader", BlockPos.CODEC, leader == null ? null : leader.subtract(worldPosition));
        output.putInt("cursor", cursor);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        held.clear();
        for (Slot slot : input.read("held", Slot.CODEC.listOf()).orElse(List.of())) {
            held.put(worldPosition.offset(slot.offset()), slot.held());
        }
        partner = input.read("partner", BlockPos.CODEC).map(worldPosition::offset).orElse(null);
        leader = input.read("leader", BlockPos.CODEC).map(worldPosition::offset).orElse(null);
        cursor = input.getIntOr("cursor", Integer.MIN_VALUE);
        nextStep = 0L;
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        AreaShifterBlock.removed(this);
    }
}
