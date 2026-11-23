package dev.phasecraft.client;

import dev.phasecraft.overlay.OverlayPayloads;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The client's copy of the hidden layer near the player, as sent by the
 * server ({@code OverlaySync}). Only non-air hidden blocks are kept, by chunk.
 * Used on the client thread only.
 */
public final class GhostLayer {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<BlockState>> chunks = new Long2ObjectOpenHashMap<>();
    private int size;
    /**
     * Chunks whose drawn ghosts may have changed since {@link #takeChangedChunks}: where
     * blocks changed, and the neighbouring chunk when a change is on the border (faces
     * between ghosts are left out, across chunk borders too).
     */
    private final LongOpenHashSet changedChunks = new LongOpenHashSet();
    private boolean allChanged;

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
            markChanged(ghost.pos());
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
            changedChunks.add(chunk.pack());
            changedChunks.add(ChunkPos.pack(chunk.x() - 1, chunk.z()));
            changedChunks.add(ChunkPos.pack(chunk.x() + 1, chunk.z()));
            changedChunks.add(ChunkPos.pack(chunk.x(), chunk.z() - 1));
            changedChunks.add(ChunkPos.pack(chunk.x(), chunk.z() + 1));
        }
    }

    public void clear() {
        chunks.clear();
        size = 0;
        changedChunks.clear();
        allChanged = true;
    }

    private void markChanged(BlockPos pos) {
        int x = SectionPos.blockToSectionCoord(pos.getX());
        int z = SectionPos.blockToSectionCoord(pos.getZ());
        changedChunks.add(ChunkPos.pack(x, z));
        int inX = pos.getX() & 15;
        int inZ = pos.getZ() & 15;
        if (inX == 0) {
            changedChunks.add(ChunkPos.pack(x - 1, z));
        } else if (inX == 15) {
            changedChunks.add(ChunkPos.pack(x + 1, z));
        }
        if (inZ == 0) {
            changedChunks.add(ChunkPos.pack(x, z - 1));
        } else if (inZ == 15) {
            changedChunks.add(ChunkPos.pack(x, z + 1));
        }
    }

    /**
     * Hands over what changed since the last call: the chunks, or null if everything did
     * (after {@link #clear}). The caller (the renderer's cache) rebuilds those.
     */
    public @Nullable LongOpenHashSet takeChangedChunks() {
        if (allChanged) {
            allChanged = false;
            changedChunks.clear();
            return null;
        }
        LongOpenHashSet changed = new LongOpenHashSet(changedChunks);
        changedChunks.clear();
        return changed;
    }

    /** Chunks holding hidden blocks, as packed ChunkPos. */
    public LongSet chunkKeys() {
        return chunks.keySet();
    }

    /** Calls the visitor for every held hidden block in one chunk. */
    public void forEachInChunk(long chunkKey, Visitor visitor) {
        Long2ObjectOpenHashMap<BlockState> blocks = chunks.get(chunkKey);
        if (blocks != null) {
            for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
                visitor.visit(entry.getLongKey(), entry.getValue());
            }
        }
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
