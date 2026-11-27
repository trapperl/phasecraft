package dev.phasecraft.perf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** What a scenario builds in: the two layers and its own corner of the world (a fresh one for every measurement). */
final class Ctx {
    /** Bulk placing: no neighbour or shape updates. */
    static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    final MinecraftServer server;
    final ServerLevel visible;
    final ServerLevel hidden;
    /** The middle of the area: the north-west bottom corner of its centre chunk, y = 64 (air over a flat world). */
    final BlockPos origin;
    final List<FakePlayers.Fake> players = new ArrayList<>();
    /** Numbers a scenario wants in the results beside the tick times. */
    final Map<String, Double> extra = new LinkedHashMap<>();

    Ctx(MinecraftServer server, ServerLevel visible, ServerLevel hidden, BlockPos origin) {
        this.server = server;
        this.visible = visible;
        this.hidden = hidden;
        this.origin = origin;
    }

    BlockPos at(int dx, int dy, int dz) {
        return origin.offset(dx, dy, dz);
    }

    /** Places a block as /setblock does (neighbours react). */
    void set(ServerLevel level, int dx, int dy, int dz, BlockState state) {
        level.setBlock(at(dx, dy, dz), state, Block.UPDATE_ALL);
    }

    void quiet(ServerLevel level, int dx, int dy, int dz, BlockState state) {
        level.setBlock(at(dx, dy, dz), state, QUIET);
    }

    void fillQuiet(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(at(x1, y1, z1), at(x2, y2, z2))) {
            level.setBlock(pos, state, QUIET);
        }
    }

    void add(String key, double value) {
        extra.merge(key, value, Double::sum);
    }
}
