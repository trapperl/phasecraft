package dev.phasecraft.layer;

import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Persistent, per-dimension hidden-layer block IDs.
 *
 * <p>This is data only. It deliberately does not call ServerLevel.setBlock,
 * schedule block ticks, or replace the visible world.</p>
 */
public final class PhaseLayerData extends SavedData {
    private record Cell(long pos, String block) {
        private static final Codec<Cell> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.LONG.fieldOf("pos").forGetter(Cell::pos),
                        Codec.STRING.fieldOf("block").forGetter(Cell::block)
                ).apply(instance, Cell::new));
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

    private final LayerCellStore cells = new LayerCellStore();

    public PhaseLayerData() {
    }

    private PhaseLayerData(List<Cell> entries) {
        for (Cell cell : entries) {
            cells.put(cell.pos(), cell.block());
        }
    }

    private List<Cell> storedCells() {
        return cells.snapshot().entrySet().stream()
                .map(entry -> new Cell(entry.getKey(), entry.getValue()))
                .toList();
    }

    public static PhaseLayerData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public String getBlock(BlockPos position) {
        return cells.get(position.asLong());
    }

    public void setBlock(BlockPos position, String blockId) {
        cells.put(position.asLong(), blockId);
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
