package dev.phasecraft.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LayerCellStoreTest {
    private static final String AIR = "minecraft:air";

    @Test
    void differentCoordinatesHaveIndependentBlockIds() {
        LayerCellStore<String> layer = new LayerCellStore<>(AIR);
        layer.put(100L, "minecraft:redstone_wire");
        layer.put(200L, "minecraft:piston");

        assertEquals("minecraft:redstone_wire", layer.get(100L));
        assertEquals("minecraft:piston", layer.get(200L));
        assertEquals(AIR, layer.get(300L));
    }

    @Test
    void clearingOneCellDoesNotAffectOthers() {
        LayerCellStore<String> layer = new LayerCellStore<>(AIR);
        layer.put(100L, "minecraft:redstone_wire");
        layer.put(200L, "minecraft:piston");
        layer.remove(100L);

        assertEquals(AIR, layer.get(100L));
        assertEquals("minecraft:piston", layer.get(200L));
        assertEquals(1, layer.size());
    }

    @Test
    void airDoesNotOccupyStorage() {
        LayerCellStore<String> layer = new LayerCellStore<>(AIR);
        layer.put(100L, "minecraft:stone");
        layer.put(100L, AIR);
        assertEquals(0, layer.size());
    }

    @Test
    void snapshotsAreImmutableAndIsolated() {
        LayerCellStore<String> layer = new LayerCellStore<>(AIR);
        layer.put(100L, "minecraft:stone");
        var snapshot = layer.snapshot();
        layer.put(100L, "minecraft:piston");

        assertEquals("minecraft:stone", snapshot.get(100L));
        assertFalse(snapshot.isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put(200L, "minecraft:dirt"));
    }
}
