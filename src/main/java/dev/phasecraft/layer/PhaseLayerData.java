package dev.phasecraft.layer;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persistent hidden-layer block ID storage, scoped to each Minecraft dimension.
 *
 * <p>SavedData is attached to the real level, not a second Level instance.
 * Reading or editing this map never places a real block in the overworld.</p>
 */
public final class PhaseLayerData extends SavedData {
    private static final String FILE_NAME = "phasecraft_layer_1";
    private static final String ENTRIES_KEY = "Cells";

    private static final SavedData.Factory<PhaseLayerData> FACTORY =
            new SavedData.Factory<>(PhaseLayerData::new, PhaseLayerData::load,
                    DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final LayerCellStore cells = new LayerCellStore();

    public static PhaseLayerData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
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

    public static PhaseLayerData load(CompoundTag root, HolderLookup.Provider registries) {
        PhaseLayerData layer = new PhaseLayerData();
        ListTag entries = root.getList(ENTRIES_KEY, Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag cell = entries.getCompound(index);
            layer.cells.put(cell.getLong("Pos"), cell.getString("Block"));
        }
        return layer;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        for (Map.Entry<Long, String> cell : cells.snapshot().entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", cell.getKey());
            entry.putString("Block", cell.getValue());
            entries.add(entry);
        }
        root.put(ENTRIES_KEY, entries);
        return root;
    }
}
