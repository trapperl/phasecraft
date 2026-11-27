package dev.phasecraft.link;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.phasecraft.layer.HiddenDimensions;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

/**
 * Which hidden chunks are loaded and ticked: only those that hold something, each while its visible twin ticks, and
 * as that twin does (blocks, or blocks and entities). An empty hidden chunk is never loaded for its twin.
 *
 * <p>Every visible chunk used to get a ticking hidden twin, though almost all of them are empty: about 1 µs a chunk a
 * tick and 50 to 90 KB of memory each, +0.4 ms a tick and 40 MB for one player's view (measured, src/perf: twins).
 * Now an index per hidden dimension, saved with it, lists the chunks that hold something:</p>
 * <ul>
 *   <li>a chunk goes in when a block other than air is set in it (placed, moved, flowed, built by a structure: every
 *       successful setBlock), when one of the hidden level's entities is in it at the end of a tick, and when a chunk
 *       that holds blocks loads (nothing should get there otherwise; this is a backstop);</li>
 *   <li>a chunk comes out when its twin stops ticking and it is found empty then: only air, no block entities, its
 *       entities loaded and none of them in it;</li>
 *   <li>a world from before the index starts with every hidden chunk on disk (region and entity files) listed; the
 *       empty ones come out as above, the first time each is left.</li>
 * </ul>
 * <p>A chunk that goes in while its twin ticks gets its ticket at the end of the tick; vanilla loads it from the next
 * one (a first hidden block in a new chunk is placed at once, but what it schedules waits those few ticks). The
 * neighbours of a ticking hidden chunk are loaded as vanilla always does, so water, pistons and falling blocks can
 * reach into them, and in doing so put them in the index. Everything that reads the hidden layer from the visible
 * side already treats a chunk that is not loaded as air, which is what an empty one is.</p>
 */
public final class HiddenChunks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /** Per visible dimension: the radius its ticking chunks ask of their hidden twins (2 entities tick, 1 blocks). */
    private static final Map<ResourceKey<Level>, Long2IntOpenHashMap> VISIBLE = new HashMap<>();
    /** Per hidden dimension: the radius of the phase ticket held at each chunk. */
    private static final Map<ResourceKey<Level>, Long2IntOpenHashMap> HELD = new HashMap<>();
    /** Per hidden dimension: chunks that went into the index since the end of the last tick. */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> NEW = new HashMap<>();
    private static final Map<ResourceKey<Level>, Index> INDEXES = new HashMap<>();

    private HiddenChunks() {}

    /** The chunks of one hidden dimension that hold something, saved with it. */
    static final class Index extends SavedData {
        private static final Codec<Index> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("indexed", false).forGetter(index -> index.indexed),
                Codec.LONG_STREAM.optionalFieldOf("chunks", LongStream.empty())
                        .forGetter(index -> Arrays.stream(index.chunks.toLongArray()))
        ).apply(instance, Index::new));
        static final SavedDataType<Index> TYPE = new SavedDataType<>(
                Identifier.fromNamespaceAndPath("phasecraft", "hidden_chunks"), Index::new, CODEC, null);

        private final LongOpenHashSet chunks;
        /** False for a world from before the index (or a new one, until its files are looked at). */
        private boolean indexed;

        Index() {
            this(false, LongStream.empty());
        }

        private Index(boolean indexed, LongStream chunks) {
            this.indexed = indexed;
            this.chunks = new LongOpenHashSet(chunks.toArray());
        }
    }

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register(HiddenChunks::onChunkLoad);
        ServerTickEvents.END_SERVER_TICK.register(HiddenChunks::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            VISIBLE.clear();
            HELD.clear();
            NEW.clear();
            INDEXES.clear();
        });
    }

    private static Index index(ServerLevel hidden) {
        Index index = INDEXES.get(hidden.dimension());
        if (index == null) {
            index = hidden.getDataStorage().computeIfAbsent(Index.TYPE);
            if (!index.indexed) {
                int found = fromFiles(hidden, index.chunks);
                index.indexed = true;
                index.setDirty();
                if (found > 0) {
                    LOGGER.info("PhaseCraft: listed {} hidden chunk(s) of {} from its files; empty ones drop out as they are left",
                            found, hidden.dimension().identifier());
                }
            }
            INDEXES.put(hidden.dimension(), index);
        }
        return index;
    }

    /** Adds every chunk a hidden dimension has on disk (block and entity region files); how many were new. */
    private static int fromFiles(ServerLevel hidden, LongOpenHashSet chunks) {
        Path folder = DimensionType.getStorageFolder(hidden.dimension(), hidden.getServer().getWorldPath(LevelResource.ROOT));
        int before = chunks.size();
        for (String kind : new String[] {"region", "entities"}) {
            Path dir = folder.resolve(kind);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "r.*.mca")) {
                for (Path file : files) {
                    Matcher name = REGION_FILE.matcher(file.getFileName().toString());
                    if (name.matches()) {
                        readRegionHeader(file, Integer.parseInt(name.group(1)), Integer.parseInt(name.group(2)), chunks);
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("PhaseCraft: could not list {}", dir, e);
            }
        }
        return chunks.size() - before;
    }

    /** A region file starts with 1024 four-byte locations, one per chunk (x + 32 z); 0: the chunk is not stored. */
    private static void readRegionHeader(Path file, int regionX, int regionZ, LongOpenHashSet chunks) {
        try (InputStream in = Files.newInputStream(file); DataInputStream data = new DataInputStream(in)) {
            for (int i = 0; i < 1024; i++) {
                if (data.readInt() != 0) {
                    chunks.add(ChunkPos.pack(regionX * 32 + (i & 31), regionZ * 32 + (i >> 5)));
                }
            }
        } catch (IOException e) {
            // A short or unreadable file: whatever was read is listed (a damaged region is vanilla's to report).
        }
    }

    /** Whether a hidden chunk may hold something; false means it is empty (whether or not it is loaded). */
    public static boolean mayHold(ServerLevel hidden, int chunkX, int chunkZ) {
        return index(hidden).chunks.contains(ChunkPos.pack(chunkX, chunkZ));
    }

    /** The radius of the phase ticket a hidden chunk has (0: none, so it is not loaded for its twin); for tests. */
    public static int ticketRadius(ServerLevel hidden, int chunkX, int chunkZ) {
        Long2IntOpenHashMap held = HELD.get(hidden.dimension());
        return held == null ? 0 : held.get(ChunkPos.pack(chunkX, chunkZ));
    }

    /** Puts a hidden chunk in the index: it loads and ticks while its visible twin does, from the end of this tick. */
    public static void hold(ServerLevel hidden, ChunkPos pos) {
        hold(hidden, pos.pack());
    }

    private static void hold(ServerLevel hidden, long pos) {
        Index index = index(hidden);
        if (index.chunks.add(pos)) {
            index.setDirty();
            NEW.computeIfAbsent(hidden.dimension(), key -> new LongOpenHashSet()).add(pos);
        }
    }

    /**
     * For tests: forgets a hidden dimension's index, as if the world were from before it, so the next start lists
     * the chunks on disk again.
     */
    public static void forgetIndex(ServerLevel hidden) {
        Index index = index(hidden);
        index.chunks.clear();
        index.indexed = false;
        index.setDirty();
    }

    /** Every successful block change on the server: a hidden chunk that gets a block goes in the index. */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState newState) {
        if (newState.isAir() || !HiddenDimensions.isHidden(level.dimension())) {
            return;
        }
        hold(level, ChunkPos.pack(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())));
    }

    /** A visible chunk's ticking changed (PhaseLink.followTicking): its hidden twin follows, if it holds something. */
    static void onVisibleTicking(ServerLevel visible, ServerLevel hidden, ChunkPos pos, FullChunkStatus status) {
        int radius = ticketRadius(status);
        Long2IntOpenHashMap radii = VISIBLE.computeIfAbsent(visible.dimension(), key -> new Long2IntOpenHashMap());
        if (radius > 0) {
            radii.put(pos.pack(), radius);
        } else {
            radii.remove(pos.pack());
        }
        update(visible, hidden, pos.pack());
    }

    private static int ticketRadius(FullChunkStatus status) {
        // A ticket of radius r puts its centre chunk at level 33 - r: 32 is block ticking, 31 entity ticking.
        if (status.isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
            return 2;
        }
        return status.isOrAfter(FullChunkStatus.BLOCK_TICKING) ? 1 : 0;
    }

    /** Gives the hidden chunk at pos the ticket it should have now: its twin's radius if it holds something, else none. */
    private static void update(ServerLevel visible, ServerLevel hidden, long pos) {
        Index index = index(hidden);
        Long2IntOpenHashMap radii = VISIBLE.get(visible.dimension());
        int twin = radii == null ? 0 : radii.get(pos);
        Long2IntOpenHashMap held = HELD.computeIfAbsent(hidden.dimension(), key -> new Long2IntOpenHashMap());
        int had = held.get(pos);
        if (twin == 0 && had > 0 && isEmpty(hidden, pos)) {
            // Left behind empty (the player took everything away, or a chunk from before the index was empty).
            index.chunks.remove(pos);
            index.setDirty();
        }
        int want = index.chunks.contains(pos) ? twin : 0;
        if (want == had) {
            return;
        }
        ChunkPos chunk = ChunkPos.unpack(pos);
        if (want > 0) {
            hidden.getChunkSource().addTicketWithRadius(PhaseLink.PHASE_TICKET, chunk, want);
            held.put(pos, want);
        } else {
            held.remove(pos);
        }
        if (had > 0) {
            hidden.getChunkSource().removeTicketWithRadius(PhaseLink.PHASE_TICKET, chunk, had);
        }
    }

    /** Whether a loaded hidden chunk is empty: only air, no block entities, its entities loaded and none in it. */
    private static boolean isEmpty(ServerLevel hidden, long pos) {
        LevelChunk chunk = hidden.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos));
        if (chunk == null || !chunk.getBlockEntities().isEmpty() || !hidden.areEntitiesLoaded(pos)) {
            return false;
        }
        for (LevelChunkSection section : chunk.getSections()) {
            if (!section.hasOnlyAir()) {
                return false;
            }
        }
        ChunkPos at = chunk.getPos();
        AABB box = new AABB(at.getMinBlockX(), hidden.getMinY(), at.getMinBlockZ(),
                at.getMaxBlockX() + 1, hidden.getMaxY() + 1, at.getMaxBlockZ() + 1);
        return hidden.getEntities((Entity) null, box, entity -> true).isEmpty();
    }

    /** A backstop: a hidden chunk that loads holding blocks goes in the index (read from the chunk itself, not the level). */
    private static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
        if (!HiddenDimensions.isHidden(level.dimension()) || index(level).chunks.contains(chunk.getPos().pack())) {
            return;
        }
        for (LevelChunkSection section : chunk.getSections()) {
            if (!section.hasOnlyAir()) {
                hold(level, chunk.getPos().pack());
                return;
            }
        }
    }

    private static void tick(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!HiddenDimensions.isHidden(level.dimension())) {
                continue;
            }
            // An entity's chunk holds something (an item thrown into a chunk with no blocks, a minecart rolling on).
            Index index = null;
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Player) {
                    continue;      // players have their own tickets (Creative and Spectator players in the hidden layer)
                }
                if (index == null) {
                    index = index(level);
                }
                long pos = entity.chunkPosition().pack();
                if (!index.chunks.contains(pos)) {
                    hold(level, pos);
                }
            }
        }
        if (NEW.isEmpty()) {
            return;
        }
        for (Map.Entry<ResourceKey<Level>, LongOpenHashSet> entry : NEW.entrySet()) {
            ServerLevel hidden = server.getLevel(entry.getKey());
            ServerLevel visible = hidden == null ? null
                    : HiddenDimensions.visibleOf(entry.getKey()).map(server::getLevel).orElse(null);
            if (visible != null) {
                entry.getValue().forEach(pos -> update(visible, hidden, pos));
            }
        }
        NEW.clear();
    }
}
