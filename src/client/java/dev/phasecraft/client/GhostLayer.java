package dev.phasecraft.client;

import dev.phasecraft.overlay.OverlayPayloads;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The client's copy of the hidden layer near the player, as sent by the
 * server ({@code OverlaySync}). Only non-air hidden blocks are kept, by chunk.
 * Used on the client thread only.
 */
public final class GhostLayer {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<BlockState>> chunks = new Long2ObjectOpenHashMap<>();
    private int size;

    /** Replaces everything held for one chunk. */
    public void replaceChunk(ChunkPos chunk, List<OverlayPayloads.Ghost> ghosts) {
        forget(chunk);
        apply(ghosts);
    }

    /** Sets or (for air) removes individual hidden blocks. */
    public void apply(List<OverlayPayloads.Ghost> ghosts) {
        for (OverlayPayloads.Ghost ghost : ghosts) {
            long chunkKey = ChunkPos.pack(ghost.pos());
            long posKey = ghost.pos().asLong();
            if (ghost.state().isAir()) {
                Long2ObjectOpenHashMap<BlockState> blocks = chunks.get(chunkKey);
                if (blocks != null && blocks.remove(posKey) != null) {
                    size--;
                    if (blocks.isEmpty()) {
                        chunks.remove(chunkKey);
                    }
                }
            } else if (chunks.computeIfAbsent(chunkKey, key -> new Long2ObjectOpenHashMap<>()).put(posKey, ghost.state()) == null) {
                size++;
            }
        }
    }

    public void forget(ChunkPos chunk) {
        Long2ObjectOpenHashMap<BlockState> blocks = chunks.remove(chunk.pack());
        if (blocks != null) {
            size -= blocks.size();
        }
    }

    public void clear() {
        chunks.clear();
        size = 0;
    }

    /** The hidden block at a position, or air if none is known there. */
    public BlockState get(BlockPos pos) {
        Long2ObjectOpenHashMap<BlockState> blocks = chunks.get(ChunkPos.pack(pos));
        return blocks == null ? AIR : blocks.getOrDefault(pos.asLong(), AIR);
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Calls the visitor for every held hidden block, with its packed position. */
    public void forEach(Visitor visitor) {
        for (Long2ObjectOpenHashMap<BlockState> blocks : chunks.values()) {
            for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
                visitor.visit(entry.getLongKey(), entry.getValue());
            }
        }
    }

    @FunctionalInterface
    public interface Visitor {
        void visit(long pos, BlockState state);
    }
}
