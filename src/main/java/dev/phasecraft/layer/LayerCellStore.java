package dev.phasecraft.layer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Mapping of world-space block coordinates (BlockPos.asLong()) to block IDs.
 *
 * <p>This is deliberately independent of Minecraft classes so it can be tested
 * without starting a game. It does not currently simulate blocks, ticks,
 * redstone, block entities, or block-state properties.</p>
 */
public final class LayerCellStore {
    public static final String AIR = "minecraft:air";

    private final Map<Long, String> cells = new HashMap<>();

    public String get(long position) {
        return cells.getOrDefault(position, AIR);
    }

    public void put(long position, String blockId) {
        Objects.requireNonNull(blockId, "blockId");
        if (blockId.isBlank() || AIR.equals(blockId)) {
            cells.remove(position);
        } else {
            cells.put(position, blockId);
        }
    }

    public void remove(long position) {
        cells.remove(position);
    }

    public int size() {
        return cells.size();
    }

    public Map<Long, String> snapshot() {
        return Map.copyOf(cells);
    }
}
