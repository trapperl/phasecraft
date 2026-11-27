package dev.phasecraft.client;

import dev.phasecraft.overlay.OverlayPayloads;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The client's copy of one layer's blocks near the player, as sent by the server: the hidden layer's, for the
 * overlay ({@code OverlaySync}), or the visible world's surface, for a player inside the hidden layer
 * ({@code InsideView}). Only blocks other than air are kept, by 16x16x16 section, so that what is drawn can be
 * built, kept and left out section by section. Used on the client thread only.
 */
public final class GhostLayer {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Every face of a block: a bit per {@link Direction}'s ordinal. */
    public static final int ALL_FACES = 0b111111;

    /** Blocks by section ({@link SectionPos#asLong}), then by packed position. */
    private final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<BlockState>> sections = new Long2ObjectOpenHashMap<>();
    /** The sections each chunk (packed ChunkPos) holds blocks in. */
    private final Long2ObjectOpenHashMap<LongOpenHashSet> chunks = new Long2ObjectOpenHashMap<>();
    private int size;
    /** What hidden shifters hold, by packed position, where the server said it is not 0. */
    private final Long2IntOpenHashMap holding = new Long2IntOpenHashMap();
    /** The faces to draw of a block, where the server said so (the inside view); all of them otherwise. */
    private final Long2ByteOpenHashMap faces = new Long2ByteOpenHashMap();
    /**
     * Sections whose drawn ghosts may have changed since {@link #takeChangedSections}: where
     * blocks changed, and the neighbouring section when a change is on the border (faces
     * between ghosts are left out, across section borders too).
     */
    private final LongOpenHashSet changedSections = new LongOpenHashSet();
    private boolean allChanged;

    public GhostLayer() {
        faces.defaultReturnValue((byte) ALL_FACES);
    }

    /** Replaces everything held for one chunk. */
    public void replaceChunk(ChunkPos chunk, List<OverlayPayloads.Ghost> ghosts) {
        forget(chunk);
        apply(ghosts);
    }

    /** Sets or (for air) removes individual blocks. */
    public void apply(List<OverlayPayloads.Ghost> ghosts) {
        for (OverlayPayloads.Ghost ghost : ghosts) {
            set(ghost.pos(), ghost.state(), ALL_FACES);
        }
    }

    /** As {@link #replaceChunk}, for blocks sent with the faces to draw. */
    public void replaceChunkSurface(ChunkPos chunk, List<OverlayPayloads.Surface> blocks) {
        forget(chunk);
        applySurface(blocks);
    }

    /** As {@link #apply}, for blocks sent with the faces to draw. */
    public void applySurface(List<OverlayPayloads.Surface> blocks) {
        for (OverlayPayloads.Surface block : blocks) {
            set(block.pos(), block.state(), block.faces() & ALL_FACES);
        }
    }

    private void set(BlockPos pos, BlockState state, int open) {
        long sectionKey = SectionPos.asLong(pos);
        long posKey = pos.asLong();
        markChanged(pos);
        if (!get(pos).is(state.getBlock())) {
            holding.remove(posKey); // another block now (a new shifter holds nothing yet)
        }
        if (state.isAir()) {
            faces.remove(posKey);
            Long2ObjectOpenHashMap<BlockState> blocks = sections.get(sectionKey);
            if (blocks != null && blocks.remove(posKey) != null) {
                size--;
                if (blocks.isEmpty()) {
                    sections.remove(sectionKey);
                    long chunkKey = ChunkPos.pack(pos);
                    LongOpenHashSet held = chunks.get(chunkKey);
                    if (held != null && held.remove(sectionKey) && held.isEmpty()) {
                        chunks.remove(chunkKey);
                    }
                }
            }
            return;
        }
        Long2ObjectOpenHashMap<BlockState> blocks = sections.get(sectionKey);
        if (blocks == null) {
            blocks = new Long2ObjectOpenHashMap<>();
            sections.put(sectionKey, blocks);
            chunks.computeIfAbsent(ChunkPos.pack(pos), key -> new LongOpenHashSet()).add(sectionKey);
        }
        if (blocks.put(posKey, state) == null) {
            size++;
        }
        if (open == ALL_FACES) {
            faces.remove(posKey);
        } else {
            faces.put(posKey, (byte) open);
        }
    }

    public void forget(ChunkPos chunk) {
        holding.keySet().removeIf(pos -> ChunkPos.pack(BlockPos.of(pos)) == chunk.pack());
        LongOpenHashSet held = chunks.remove(chunk.pack());
        if (held == null) {
            return;
        }
        for (long sectionKey : held) {
            Long2ObjectOpenHashMap<BlockState> blocks = sections.remove(sectionKey);
            if (blocks == null) {
                continue;
            }
            size -= blocks.size();
            if (!faces.isEmpty()) {
                for (long pos : blocks.keySet()) {
                    faces.remove(pos);
                }
            }
            changedSections.add(sectionKey);
            changedSections.add(SectionPos.offset(sectionKey, -1, 0, 0));
            changedSections.add(SectionPos.offset(sectionKey, 1, 0, 0));
            changedSections.add(SectionPos.offset(sectionKey, 0, 0, -1));
            changedSections.add(SectionPos.offset(sectionKey, 0, 0, 1));
        }
    }

    public void clear() {
        sections.clear();
        chunks.clear();
        holding.clear();
        faces.clear();
        size = 0;
        changedSections.clear();
        allChanged = true;
    }

    private void markChanged(BlockPos pos) {
        long section = SectionPos.asLong(pos);
        changedSections.add(section);
        int inX = pos.getX() & 15;
        int inY = pos.getY() & 15;
        int inZ = pos.getZ() & 15;
        if (inX == 0) {
            changedSections.add(SectionPos.offset(section, -1, 0, 0));
        } else if (inX == 15) {
            changedSections.add(SectionPos.offset(section, 1, 0, 0));
        }
        if (inY == 0) {
            changedSections.add(SectionPos.offset(section, 0, -1, 0));
        } else if (inY == 15) {
            changedSections.add(SectionPos.offset(section, 0, 1, 0));
        }
        if (inZ == 0) {
            changedSections.add(SectionPos.offset(section, 0, 0, -1));
        } else if (inZ == 15) {
            changedSections.add(SectionPos.offset(section, 0, 0, 1));
        }
    }

    /**
     * Hands over what changed since the last call: the sections, or null if everything did
     * (after {@link #clear}). The caller (the renderer's cache) rebuilds those.
     */
    public @Nullable LongOpenHashSet takeChangedSections() {
        if (allChanged) {
            allChanged = false;
            changedSections.clear();
            return null;
        }
        LongOpenHashSet changed = new LongOpenHashSet(changedSections);
        changedSections.clear();
        return changed;
    }

    /** Sections holding blocks, as {@link SectionPos#asLong}. */
    public LongSet sectionKeys() {
        return sections.keySet();
    }

    /** Calls the visitor for every held block in one section. */
    public void forEachInSection(long sectionKey, Visitor visitor) {
        Long2ObjectOpenHashMap<BlockState> blocks = sections.get(sectionKey);
        if (blocks != null) {
            for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
                visitor.visit(entry.getLongKey(), entry.getValue());
            }
        }
    }

    /** The block held at a position, or air if none is known there. */
    public BlockState get(BlockPos pos) {
        Long2ObjectOpenHashMap<BlockState> blocks = sections.get(SectionPos.asLong(pos));
        return blocks == null ? AIR : blocks.getOrDefault(pos.asLong(), AIR);
    }

    /** Whether the face of the block at pos toward a direction is to be drawn, as far as the server said. */
    public boolean faceOpen(BlockPos pos, Direction direction) {
        return faces.isEmpty() || (faces.get(pos.asLong()) & (1 << direction.ordinal())) != 0;
    }

    /** The faces to draw of the block at pos (a bit per Direction ordinal): all, unless the server said otherwise. */
    public int faces(BlockPos pos) {
        return faces.isEmpty() ? ALL_FACES : faces.get(pos.asLong()) & ALL_FACES;
    }

    /** A hidden shifter's held count, as the server sent it. */
    public void setHolding(BlockPos pos, int count) {
        if (count == 0) {
            holding.remove(pos.asLong());
        } else {
            holding.put(pos.asLong(), count);
        }
    }

    /** How many visible blocks the hidden shifter at pos holds (0 if the server said nothing). */
    public int holding(BlockPos pos) {
        return holding.get(pos.asLong());
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Calls the visitor for every held block, with its packed position. */
    public void forEach(Visitor visitor) {
        for (Long2ObjectOpenHashMap<BlockState> blocks : sections.values()) {
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
