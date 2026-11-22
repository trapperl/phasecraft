package dev.phasecraft.layer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Mapping of world-space block coordinates (BlockPos.asLong()) to cell values.
 *
 * <p>Deliberately independent of Minecraft classes so it can be tested without
 * starting a game; in the mod the values are full BlockStates. A cell holding
 * the {@code empty} value (air) is not stored at all.</p>
 *
 * @param <T> cell value type
 */
public final class LayerCellStore<T> {
    private final T empty;
    private final Map<Long, T> cells = new HashMap<>();

    public LayerCellStore(T empty) {
        this.empty = Objects.requireNonNull(empty, "empty");
    }

    public T get(long position) {
        return cells.getOrDefault(position, empty);
    }

    public void put(long position, T value) {
        Objects.requireNonNull(value, "value");
        if (empty.equals(value)) {
            cells.remove(position);
        } else {
            cells.put(position, value);
        }
    }

    public void remove(long position) {
        cells.remove(position);
    }

    public int size() {
        return cells.size();
    }

    public Map<Long, T> snapshot() {
        return Map.copyOf(cells);
    }
}
