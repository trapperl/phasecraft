package dev.phasecraft.layer;

import dev.phasecraft.PhaseCraft;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Moves hidden blocks stored by earlier builds (a block list per dimension,
 * {@link PhaseLayerData}) into the hidden dimensions, once, at server start.
 *
 * <p>Blocks are placed without neighbour updates or placement logic, so a cell is copied exactly
 * even where vanilla would not let it stand (a wire with nothing under it);
 * the next nearby change applies vanilla rules to it as usual. The legacy list
 * is emptied afterwards, so running again moves nothing. Cells outside the
 * world's height range (the old list accepted any Y) cannot be placed: they
 * stay in the old list and a warning is logged.</p>
 */
public final class LegacyLayerMigration {
    private LegacyLayerMigration() {}

    /** Migrates every visible dimension that has a hidden layer. */
    public static void migrateAll(MinecraftServer server) {
        for (ResourceKey<Level> key : HiddenDimensions.visibleDimensions()) {
            ServerLevel level = server.getLevel(key);
            if (level != null) {
                migrate(level);
            }
        }
    }

    /** Migrates one visible dimension's legacy cells; returns how many moved. */
    public static int migrate(ServerLevel visible) {
        PhaseLayerData legacy = PhaseLayerData.existing(visible.getDataStorage()).orElse(null);
        if (legacy == null || legacy.size() == 0) {
            return 0;
        }
        HiddenLayer layer = HiddenLayer.of(visible);
        ServerLevel hidden = layer.level();
        int moved = 0;
        for (Map.Entry<BlockPos, BlockState> cell : legacy.cells().entrySet()) {
            BlockPos pos = cell.getKey();
            if (!layer.contains(pos)) {
                PhaseCraft.LOGGER.warn("Kept hidden block {} at {} in {}: outside the world height, cannot be placed",
                        cell.getValue(), pos.toShortString(), visible.dimension().identifier());
                continue;
            }
            hidden.setBlock(pos, cell.getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE);
            legacy.removeBlock(pos);
            moved++;
        }
        PhaseCraft.LOGGER.info("Moved {} hidden blocks in {} into {}",
                moved, visible.dimension().identifier(), hidden.dimension().identifier());
        return moved;
    }
}
