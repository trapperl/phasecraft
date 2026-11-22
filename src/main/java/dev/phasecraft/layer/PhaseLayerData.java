package dev.phasecraft.layer;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * Persistent, per-dimension hidden-layer block states.
 *
 * <p>Each cell stores a full BlockState (block plus properties such as facing
 * or delay); block-entity data is not stored yet. This is data only. It deliberately does not call ServerLevel.setBlock,
 * schedule block ticks, or replace the visible world.</p>
 */
public final class PhaseLayerData extends SavedData {
    private record Cell(long pos, BlockState state) {
        /** Current format: {pos, state}, state as Minecraft writes block states. */
        private static final Codec<Cell> STATE_CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.LONG.fieldOf("pos").forGetter(Cell::pos),
                        BlockState.CODEC.fieldOf("state").forGetter(Cell::state)
                ).apply(instance, Cell::new));

        /** First prototype format: {pos, block} with a block ID only; read as the default state. */
        private static final Codec<Cell> LEGACY_BLOCK_ID_CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.LONG.fieldOf("pos").forGetter(Cell::pos),
                        BuiltInRegistries.BLOCK.byNameCodec().fieldOf("block").forGetter(cell -> cell.state().getBlock())
                ).apply(instance, (pos, block) -> new Cell(pos, block.defaultBlockState())));

        /** Writes the current format; reads either. */
        private static final Codec<Cell> CODEC = Codec.withAlternative(STATE_CODEC, LEGACY_BLOCK_ID_CODEC);
    }

    // Modern Minecraft SavedData uses codecs rather than overriding NBT save().
    private static final Codec<PhaseLayerData> CODEC = Cell.CODEC.listOf().xmap(
            PhaseLayerData::new, PhaseLayerData::storedCells);

    private static final SavedDataType<PhaseLayerData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("phasecraft", "layer_1"),
            PhaseLayerData::new,
            CODEC,
            null
    );

    private final LayerCellStore<BlockState> cells = new LayerCellStore<>(Blocks.AIR.defaultBlockState());

    public PhaseLayerData() {
    }

    private PhaseLayerData(List<Cell> entries) {
        for (Cell cell : entries) {
            cells.put(cell.pos(), cell.state());
        }
    }

    private List<Cell> storedCells() {
        return cells.snapshot().entrySet().stream()
                .map(entry -> new Cell(entry.getKey(), entry.getValue()))
                .toList();
    }

    public static PhaseLayerData get(ServerLevel level) {
        return get(level.getDataStorage());
    }

    /** Loads (or creates) the hidden layer held by a specific storage, e.g. a fresh one in tests. */
    public static PhaseLayerData get(SavedDataStorage storage) {
        return storage.computeIfAbsent(TYPE);
    }

    /** The hidden block state at a position; air if nothing is stored there. */
    public BlockState getBlock(BlockPos position) {
        return cells.get(position.asLong());
    }

    /** Stores a hidden block state; storing air clears the cell. */
    public void setBlock(BlockPos position, BlockState state) {
        cells.put(position.asLong(), state);
        setDirty();
    }

    public void removeBlock(BlockPos position) {
        cells.remove(position.asLong());
        setDirty();
    }

    public int size() {
        return cells.size();
    }
}
