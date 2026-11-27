package dev.phasecraft.overlay;

import dev.phasecraft.layer.Access;
import dev.phasecraft.layer.HiddenDimensions;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import dev.phasecraft.shifter.HoldsBlocks;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;

/**
 * Keeps each eligible client's copy of the hidden layer near it up to date,
 * so the client can draw hidden blocks as an overlay on the visible world.
 *
 * <p>Eligible: the client has PhaseCraft, the player may see the hidden
 * layer ({@link Access}: Creative, Spectator, or Survival while the game rule
 * phasecraft:survival_access is on), and the player is in a visible
 * dimension that has a hidden twin. Every tick the server sends the hidden
 * blocks of chunks that came within {@link #RADIUS_CHUNKS} of the player,
 * tells the client to forget chunks that left that range, and passes on hidden
 * block changes in the chunks the client has. Only hidden chunks that are
 * already loaded are sent; nothing is loaded for the overlay.</p>
 */
public final class OverlaySync {
    /** Chunks around the player whose hidden blocks are sent: a 9x9 chunk square. */
    public static final int RADIUS_CHUNKS = 4;
    /** Ghosts per message; a full chunk is split over several, well under the packet size limit. */
    static final int MAX_GHOSTS_PER_PAYLOAD = 4096;

    private static final Map<UUID, Watch> WATCHES = new HashMap<>();
    /** Hidden block changes this tick, per hidden dimension, by packed position. */
    private static final Map<ResourceKey<Level>, Long2ObjectLinkedOpenHashMap<BlockState>> PENDING = new HashMap<>();
    /** What hidden shifters hold, changed this tick, per hidden dimension, by packed position. */
    private static final Map<ResourceKey<Level>, Long2IntLinkedOpenHashMap> PENDING_HOLDING = new HashMap<>();

    private OverlaySync() {}

    /** What one client has been sent: the visible dimension it is in and the chunks it holds. */
    private static final class Watch {
        final ResourceKey<Level> visible;
        final LongOpenHashSet chunks = new LongOpenHashSet();

        Watch(ResourceKey<Level> visible) {
            this.visible = visible;
        }
    }

    public static void register() {
        OverlayPayloads.register();
        ServerTickEvents.END_SERVER_TICK.register(OverlaySync::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> WATCHES.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            WATCHES.clear();
            PENDING.clear();
            PENDING_HOLDING.clear();
        });
    }

    /** Called for every block change on the server. Only hidden-layer changes matter here. */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState newState) {
        if (WATCHES.isEmpty() || !HiddenDimensions.isHidden(level.dimension())) {
            return;
        }
        PENDING.computeIfAbsent(level.dimension(), key -> new Long2ObjectLinkedOpenHashMap<>())
                .put(pos.asLong(), newState);
    }

    /** A hidden shifter's held count changed (its block entity's setChanged). */
    public static void onHoldingChanged(ServerLevel level, BlockPos pos, int count) {
        if (WATCHES.isEmpty() || !HiddenDimensions.isHidden(level.dimension())) {
            return;
        }
        PENDING_HOLDING.computeIfAbsent(level.dimension(), key -> new Long2IntLinkedOpenHashMap()).put(pos.asLong(), count);
    }

    private static void tick(MinecraftServer server) {
        // Changes first, to chunks clients already hold; chunks sent below are
        // read now, so they already include this tick's changes.
        if (!PENDING.isEmpty()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Watch watch = WATCHES.get(player.getUUID());
                if (watch != null) {
                    sendChanges(player, watch);
                }
            }
            PENDING.clear();
        }
        if (!PENDING_HOLDING.isEmpty()) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Watch watch = WATCHES.get(player.getUUID());
                Long2IntLinkedOpenHashMap changes = watch == null ? null
                        : PENDING_HOLDING.get(HiddenDimensions.hiddenOf(watch.visible).orElseThrow());
                if (changes == null) {
                    continue;
                }
                List<OverlayPayloads.Count> counts = new ArrayList<>();
                for (Long2IntMap.Entry change : changes.long2IntEntrySet()) {
                    BlockPos pos = BlockPos.of(change.getLongKey());
                    if (watch.chunks.contains(ChunkPos.pack(pos))) {
                        counts.add(new OverlayPayloads.Count(pos, change.getIntValue()));
                    }
                }
                if (!counts.isEmpty()) {
                    ServerPlayNetworking.send(player, new OverlayPayloads.Holding(counts));
                }
            }
            PENDING_HOLDING.clear();
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            update(server, player);
        }
    }

    private static void sendChanges(ServerPlayer player, Watch watch) {
        Long2ObjectLinkedOpenHashMap<BlockState> changes = PENDING.get(HiddenDimensions.hiddenOf(watch.visible).orElseThrow());
        if (changes == null) {
            return;
        }
        List<OverlayPayloads.Ghost> ghosts = new ArrayList<>();
        for (Long2ObjectMap.Entry<BlockState> change : changes.long2ObjectEntrySet()) {
            BlockPos pos = BlockPos.of(change.getLongKey());
            if (watch.chunks.contains(ChunkPos.pack(pos))) {
                ghosts.add(new OverlayPayloads.Ghost(pos, change.getValue()));
            }
        }
        for (int from = 0; from < ghosts.size(); from += MAX_GHOSTS_PER_PAYLOAD) {
            List<OverlayPayloads.Ghost> part = ghosts.subList(from, Math.min(ghosts.size(), from + MAX_GHOSTS_PER_PAYLOAD));
            ServerPlayNetworking.send(player, new OverlayPayloads.Blocks(List.copyOf(part)));
        }
    }

    private static void update(MinecraftServer server, ServerPlayer player) {
        UUID id = player.getUUID();
        Watch watch = WATCHES.get(id);
        ServerLevel hidden = eligibleHiddenLevel(server, player);
        if (hidden == null) {
            if (watch != null) {
                WATCHES.remove(id);
                ServerPlayNetworking.send(player, new OverlayPayloads.Reset());
            }
            return;
        }
        ResourceKey<Level> visible = player.level().dimension();
        if (watch == null || !watch.visible.equals(visible)) {
            if (watch != null) {
                ServerPlayNetworking.send(player, new OverlayPayloads.Reset());
            }
            watch = new Watch(visible);
            WATCHES.put(id, watch);
        }

        ChunkPos center = player.chunkPosition();
        LongIterator held = watch.chunks.iterator();
        while (held.hasNext()) {
            ChunkPos chunk = ChunkPos.unpack(held.nextLong());
            if (Math.abs(chunk.x() - center.x()) > RADIUS_CHUNKS || Math.abs(chunk.z() - center.z()) > RADIUS_CHUNKS) {
                held.remove();
                ServerPlayNetworking.send(player, new OverlayPayloads.Forget(chunk));
            }
        }
        for (int x = center.x() - RADIUS_CHUNKS; x <= center.x() + RADIUS_CHUNKS; x++) {
            for (int z = center.z() - RADIUS_CHUNKS; z <= center.z() + RADIUS_CHUNKS; z++) {
                long key = ChunkPos.pack(x, z);
                if (watch.chunks.contains(key)) {
                    continue;
                }
                LevelChunk chunk = hidden.getChunkSource().getChunkNow(x, z);
                if (chunk != null) {
                    sendChunk(player, chunk);
                    watch.chunks.add(key);
                }
            }
        }
    }

    /** The hidden level to mirror for this player, or null if the player should not see the overlay. */
    private static @Nullable ServerLevel eligibleHiddenLevel(MinecraftServer server, ServerPlayer player) {
        if (!Access.maySeeOverlay(player) || !ServerPlayNetworking.canSend(player, OverlayPayloads.Chunk.TYPE)) {
            return null;
        }
        Optional<ResourceKey<Level>> hiddenKey = HiddenDimensions.hiddenOf(player.level().dimension());
        return hiddenKey.map(server::getLevel).orElse(null);
    }

    /** Sends every non-air block of a hidden chunk: one {@link OverlayPayloads.Chunk}, then more if needed. */
    private static void sendChunk(ServerPlayer player, LevelChunk chunk) {
        List<OverlayPayloads.Ghost> ghosts = ghosts(chunk);
        int first = Math.min(ghosts.size(), MAX_GHOSTS_PER_PAYLOAD);
        ServerPlayNetworking.send(player, new OverlayPayloads.Chunk(chunk.getPos(), List.copyOf(ghosts.subList(0, first))));
        for (int from = first; from < ghosts.size(); from += MAX_GHOSTS_PER_PAYLOAD) {
            List<OverlayPayloads.Ghost> part = ghosts.subList(from, Math.min(ghosts.size(), from + MAX_GHOSTS_PER_PAYLOAD));
            ServerPlayNetworking.send(player, new OverlayPayloads.Blocks(List.copyOf(part)));
        }
        List<OverlayPayloads.Count> counts = new ArrayList<>();
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
            if (entity instanceof HoldsBlocks shifter && shifter.heldCount() > 0) {
                counts.add(new OverlayPayloads.Count(entity.getBlockPos(), shifter.heldCount()));
            }
        }
        if (!counts.isEmpty()) {
            ServerPlayNetworking.send(player, new OverlayPayloads.Holding(counts));
        }
    }

    static List<OverlayPayloads.Ghost> ghosts(LevelChunk chunk) {
        List<OverlayPayloads.Ghost> ghosts = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir()) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!state.isAir()) {
                            ghosts.add(new OverlayPayloads.Ghost(new BlockPos(baseX + x, baseY + y, baseZ + z), state));
                        }
                    }
                }
            }
        }
        return ghosts;
    }

    /** For tests and /phasecraft link: how many chunks a player's client holds. */
    public static int chunksSentTo(ServerPlayer player) {
        Watch watch = WATCHES.get(player.getUUID());
        return watch == null ? 0 : watch.chunks.size();
    }
}
