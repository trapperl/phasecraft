package dev.phasecraft.overlay;

import dev.phasecraft.layer.Access;
import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.layer.HiddenLayerGuard;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * The inside view: the overlay in reverse. A player inside a hidden dimension has the hidden blocks around them
 * for real, and nothing of the visible world; so the server sends that player the visible twin's blocks near them,
 * and the client draws them faint over the hidden layer (a second {@code GhostLayer}), to build against.
 *
 * <ul>
 *   <li><b>Who:</b> a player in a hidden dimension who may be there (Creative, Spectator:
 *       {@link HiddenLayerGuard}) and may see the overlay ({@link Access#maySeeOverlay}), with PhaseCraft on the
 *       client. Anyone else is sent nothing; leaving the hidden layer, or losing the right, resets the client.</li>
 *   <li><b>What:</b> the surface only ({@link #surface}): blocks with a face open to air, to a block that is not a
 *       full opaque cube, or to the sky, each with which faces those are, so that only they are drawn. Solid ground
 *       is hollow in what is sent: a chunk of ordinary terrain is about two thousand blocks instead of thirty
 *       thousand (see docs/ARCHITECTURE.md for measurements).</li>
 *   <li><b>Where:</b> chunks within {@link #RADIUS_CHUNKS} of the player, nearest first, at most
 *       {@link #CHUNKS_PER_TICK} new ones a tick (the scan of a chunk is the costly part, and is done only then);
 *       chunks that leave that square are forgotten.</li>
 *   <li><b>Changes:</b> a visible block change can change whether it and its six neighbours are on the surface, so
 *       those seven spots are looked at again, at the end of the tick, and sent to the players who hold that
 *       chunk.</li>
 *   <li><b>Loading:</b> nobody may be in the visible world there, so each inside player holds a ticket on the visible
 *       level around their spot ({@link #TICKET}, shared and counted when two stand in one chunk): the visible
 *       world near them stays loaded and ticking, as if they stood in it, and their hidden machinery can be tried
 *       against it.</li>
 * </ul>
 */
public final class InsideView {
    /** Chunks around the player whose visible surface is sent: the overlay's square. */
    public static final int RADIUS_CHUNKS = OverlaySync.RADIUS_CHUNKS;
    /** New chunks scanned and sent to one player in a tick. */
    public static final int CHUNKS_PER_TICK = 2;
    /**
     * Keeps the visible world around an inside player loaded and ticking. Centre level 33 - radius: with
     * RADIUS_CHUNKS + 2, the square that is sent ticks entities too, and its neighbours are loaded for the scan.
     */
    public static final TicketType TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath("phasecraft", "inside_view"),
            new TicketType(TicketType.NO_TIMEOUT,
                    TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
    private static final int TICKET_RADIUS = RADIUS_CHUNKS + 2;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final Direction[] DIRECTIONS = Direction.values();

    private static final Map<UUID, Watch> WATCHES = new HashMap<>();
    /** Visible dimensions somebody watches from inside, with how many do. */
    private static final Map<ResourceKey<Level>, Integer> WATCHED = new HashMap<>();
    /**
     * How many inside players hold the ticket at each chunk, per visible dimension. Vanilla keeps one ticket per
     * type, chunk and radius: two players inside in the same chunk share it, and it must stay until both are gone.
     */
    private static final Map<ResourceKey<Level>, Long2IntOpenHashMap> TICKETS = new HashMap<>();
    /** Visible block changes this tick, per visible dimension, by packed position. */
    private static final Map<ResourceKey<Level>, LongLinkedOpenHashSet> PENDING = new HashMap<>();
    private InsideView() {}

    /** What one client has been sent: the visible dimension it mirrors, its chunks, and where its ticket is. */
    private static final class Watch {
        final ResourceKey<Level> visible;
        final LongOpenHashSet chunks = new LongOpenHashSet();
        @Nullable ChunkPos ticket;
        /** Chunks scanned and sent since this watch began. */
        int scans;

        Watch(ResourceKey<Level> visible) {
            this.visible = visible;
        }
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(InsideView::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> drop(server, handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            WATCHES.clear();
            WATCHED.clear();
            TICKETS.clear();
            PENDING.clear();
        });
    }

    /** Called for every block change on the server. Only changes in a visible dimension somebody watches from inside matter. */
    public static void onBlockChanged(ServerLevel level, BlockPos pos) {
        if (WATCHED.isEmpty() || !WATCHED.containsKey(level.dimension())) {
            return;
        }
        PENDING.computeIfAbsent(level.dimension(), key -> new LongLinkedOpenHashSet()).add(pos.asLong());
    }

    /**
     * Whether this player is to be shown the visible world from inside: in a hidden dimension, allowed to be there
     * and to see the overlay. (The client must have PhaseCraft as well; that is checked when sending.)
     */
    public static boolean shows(ServerPlayer player) {
        return HiddenDimensions.isHidden(player.level().dimension())
                && HiddenLayerGuard.mayBeInHiddenLayer(player) && Access.maySeeOverlay(player);
    }

    private static void tick(MinecraftServer server) {
        if (!PENDING.isEmpty()) {
            for (Map.Entry<ResourceKey<Level>, LongLinkedOpenHashSet> entry : PENDING.entrySet()) {
                ServerLevel visible = server.getLevel(entry.getKey());
                if (visible != null) {
                    sendChanges(server, visible, entry.getValue());
                }
            }
            PENDING.clear();
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            update(server, player);
        }
        if (!WATCHES.isEmpty()) {
            // Whoever is gone without a disconnect being told (it is, normally) must not keep a ticket.
            for (UUID id : List.copyOf(WATCHES.keySet())) {
                if (server.getPlayerList().getPlayer(id) == null) {
                    drop(server, id);
                }
            }
        }
    }

    /** Every changed spot and its six neighbours, as they are to be shown now, to each player who holds their chunk. */
    private static void sendChanges(MinecraftServer server, ServerLevel visible, LongLinkedOpenHashSet changed) {
        Long2ObjectLinkedOpenHashMap<OverlayPayloads.Surface> shown = new Long2ObjectLinkedOpenHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LongIterator spots = changed.iterator();
        while (spots.hasNext()) {
            long spot = spots.nextLong();
            if (!shown.containsKey(spot)) {
                shown.put(spot, shownAt(visible, pos.set(spot)));
            }
            for (Direction direction : DIRECTIONS) {
                long next = BlockPos.offset(spot, direction);
                if (!shown.containsKey(next)) {
                    shown.put(next, shownAt(visible, pos.set(next)));
                }
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Watch watch = WATCHES.get(player.getUUID());
            if (watch == null || !watch.visible.equals(visible.dimension())) {
                continue;
            }
            List<OverlayPayloads.Surface> ghosts = new ArrayList<>();
            for (Long2ObjectMap.Entry<OverlayPayloads.Surface> entry : shown.long2ObjectEntrySet()) {
                if (watch.chunks.contains(ChunkPos.pack(entry.getValue().pos()))) {
                    ghosts.add(entry.getValue());
                }
            }
            for (int from = 0; from < ghosts.size(); from += OverlaySync.MAX_GHOSTS_PER_PAYLOAD) {
                List<OverlayPayloads.Surface> part = ghosts.subList(from, Math.min(ghosts.size(), from + OverlaySync.MAX_GHOSTS_PER_PAYLOAD));
                ServerPlayNetworking.send(player, new OverlayPayloads.VisibleBlocks(List.copyOf(part)));
            }
        }
    }

    private static void update(MinecraftServer server, ServerPlayer player) {
        UUID id = player.getUUID();
        Watch watch = WATCHES.get(id);
        ServerLevel visible = shows(player) && ServerPlayNetworking.canSend(player, OverlayPayloads.VisibleChunk.TYPE)
                ? HiddenDimensions.visibleOf(player.level().dimension()).map(server::getLevel).orElse(null) : null;
        if (watch != null && (visible == null || !watch.visible.equals(visible.dimension()))) {
            drop(server, id);
            if (ServerPlayNetworking.canSend(player, OverlayPayloads.VisibleReset.TYPE)) {
                ServerPlayNetworking.send(player, new OverlayPayloads.VisibleReset());
            }
            watch = null;
        }
        if (visible == null) {
            return;
        }
        if (watch == null) {
            watch = new Watch(visible.dimension());
            WATCHES.put(id, watch);
            WATCHED.merge(watch.visible, 1, Integer::sum);
        }

        ChunkPos center = player.chunkPosition();
        if (!center.equals(watch.ticket)) {
            holdTicket(visible, center);
            if (watch.ticket != null) {
                releaseTicket(visible, watch.ticket);
            }
            watch.ticket = center;
        }
        LongIterator held = watch.chunks.iterator();
        while (held.hasNext()) {
            ChunkPos chunk = ChunkPos.unpack(held.nextLong());
            if (Math.abs(chunk.x() - center.x()) > RADIUS_CHUNKS || Math.abs(chunk.z() - center.z()) > RADIUS_CHUNKS) {
                held.remove();
                ServerPlayNetworking.send(player, new OverlayPayloads.VisibleForget(chunk));
            }
        }
        if (watch.chunks.size() == (2 * RADIUS_CHUNKS + 1) * (2 * RADIUS_CHUNKS + 1)) {
            return;
        }
        // Nearest first, ring by ring; a chunk is sent once it and its four neighbours are loaded
        // (whether a block on the chunk's border is on the surface depends on the neighbour).
        int budget = CHUNKS_PER_TICK;
        for (int ring = 0; ring <= RADIUS_CHUNKS && budget > 0; ring++) {
            for (int dx = -ring; dx <= ring && budget > 0; dx++) {
                for (int dz = -ring; dz <= ring && budget > 0; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int x = center.x() + dx;
                    int z = center.z() + dz;
                    if (watch.chunks.contains(ChunkPos.pack(x, z))) {
                        continue;
                    }
                    LevelChunk chunk = visible.getChunkSource().getChunkNow(x, z);
                    if (chunk == null || !neighboursLoaded(visible, x, z)) {
                        continue;
                    }
                    sendChunk(player, visible, chunk);
                    watch.chunks.add(ChunkPos.pack(x, z));
                    watch.scans++;
                    budget--;
                }
            }
        }
    }

    private static boolean neighboursLoaded(ServerLevel level, int x, int z) {
        return level.getChunkSource().getChunkNow(x - 1, z) != null && level.getChunkSource().getChunkNow(x + 1, z) != null
                && level.getChunkSource().getChunkNow(x, z - 1) != null && level.getChunkSource().getChunkNow(x, z + 1) != null;
    }

    /** Ends a player's inside view on the server: its record and its ticket. */
    private static void drop(MinecraftServer server, UUID id) {
        Watch watch = WATCHES.remove(id);
        if (watch == null) {
            return;
        }
        if (WATCHED.merge(watch.visible, -1, Integer::sum) <= 0) {
            WATCHED.remove(watch.visible);
        }
        ServerLevel visible = server.getLevel(watch.visible);
        if (visible != null && watch.ticket != null) {
            releaseTicket(visible, watch.ticket);
        }
    }

    private static void holdTicket(ServerLevel visible, ChunkPos chunk) {
        if (TICKETS.computeIfAbsent(visible.dimension(), key -> new Long2IntOpenHashMap()).addTo(chunk.pack(), 1) == 0) {
            visible.getChunkSource().addTicketWithRadius(TICKET, chunk, TICKET_RADIUS);
        }
    }

    private static void releaseTicket(ServerLevel visible, ChunkPos chunk) {
        Long2IntOpenHashMap held = TICKETS.get(visible.dimension());
        if (held != null && held.addTo(chunk.pack(), -1) <= 1) {
            held.remove(chunk.pack());
            visible.getChunkSource().removeTicketWithRadius(TICKET, chunk, TICKET_RADIUS);
        }
    }

    private static void sendChunk(ServerPlayer player, ServerLevel visible, LevelChunk chunk) {
        List<OverlayPayloads.Surface> ghosts = surface(visible, chunk);
        int first = Math.min(ghosts.size(), OverlaySync.MAX_GHOSTS_PER_PAYLOAD);
        ServerPlayNetworking.send(player, new OverlayPayloads.VisibleChunk(chunk.getPos(), List.copyOf(ghosts.subList(0, first))));
        for (int from = first; from < ghosts.size(); from += OverlaySync.MAX_GHOSTS_PER_PAYLOAD) {
            List<OverlayPayloads.Surface> part = ghosts.subList(from, Math.min(ghosts.size(), from + OverlaySync.MAX_GHOSTS_PER_PAYLOAD));
            ServerPlayNetworking.send(player, new OverlayPayloads.VisibleBlocks(List.copyOf(part)));
        }
    }

    /**
     * Whether a block shows past a neighbour: the neighbour is air, or is not a full opaque cube (glass, leaves, a
     * slab, a torch, water), unless both are the same fluid (the inside of a lake or the sea is not surface).
     */
    public static boolean open(BlockState state, BlockState neighbour) {
        if (neighbour.isAir()) {
            return true;
        }
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && fluid.getType().isSame(neighbour.getFluidState().getType())) {
            return false;
        }
        return !neighbour.isSolidRender();
    }

    /**
     * The surface blocks of one visible chunk: every block other than air with a face that is {@link #open}, and
     * which faces those are. Above the world is open; below it, and a neighbouring chunk that is not loaded, are
     * not. Reads the chunk's sections directly; this is the costly part of the inside view and runs once per chunk
     * sent.
     */
    public static List<OverlayPayloads.Surface> surface(ServerLevel level, LevelChunk chunk) {
        ChunkPos at = chunk.getPos();
        LevelChunkSection[] sections = chunk.getSections();
        LevelChunkSection[][] sides = {
                sectionsOf(level, at.x() - 1, at.z()), sectionsOf(level, at.x() + 1, at.z()),
                sectionsOf(level, at.x(), at.z() - 1), sectionsOf(level, at.x(), at.z() + 1)};
        List<OverlayPayloads.Surface> ghosts = new ArrayList<>();
        int baseX = at.getMinBlockX();
        int baseZ = at.getMinBlockZ();
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
                        if (state.isAir()) {
                            continue;
                        }
                        int faces = 0;
                        if (x > 0 && x < 15 && y > 0 && y < 15 && z > 0 && z < 15) {
                            for (Direction direction : DIRECTIONS) {
                                if (open(state, section.getBlockState(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ()))) {
                                    faces |= 1 << direction.ordinal();
                                }
                            }
                        } else {
                            for (Direction direction : DIRECTIONS) {
                                if (openAt(state, sections, sides, index, x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ())) {
                                    faces |= 1 << direction.ordinal();
                                }
                            }
                        }
                        if (faces != 0) {
                            ghosts.add(new OverlayPayloads.Surface(new BlockPos(baseX + x, baseY + y, baseZ + z), state, (byte) faces));
                        }
                    }
                }
            }
        }
        return ghosts;
    }

    private static LevelChunkSection @Nullable [] sectionsOf(ServerLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
        return chunk == null ? null : chunk.getSections();
    }

    /** {@link #open} toward a spot given in section coordinates, which may be one step outside the section or chunk. */
    private static boolean openAt(BlockState state, LevelChunkSection[] sections, LevelChunkSection[] @Nullable [] sides,
            int index, int x, int y, int z) {
        if (y < 0) {
            index--;
            y = 15;
        } else if (y > 15) {
            index++;
            y = 0;
        }
        if (index < 0) {
            return false;
        }
        if (index >= sections.length) {
            return true;
        }
        LevelChunkSection[] column = sections;
        if (x < 0) {
            column = sides[0];
            x = 15;
        } else if (x > 15) {
            column = sides[1];
            x = 0;
        } else if (z < 0) {
            column = sides[2];
            z = 15;
        } else if (z > 15) {
            column = sides[3];
            z = 0;
        }
        return column != null && open(state, column[index].getBlockState(x, y, z));
    }

    /** What the inside view shows at one visible spot now: its block and open faces if it is on the surface, else air. */
    public static OverlayPayloads.Surface shownAt(ServerLevel level, BlockPos pos) {
        BlockPos at = pos.immutable();
        OverlayPayloads.Surface nothing = new OverlayPayloads.Surface(at, AIR, (byte) 0);
        if (level.isOutsideBuildHeight(at.getY())) {
            return nothing;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(at.getX()), SectionPos.blockToSectionCoord(at.getZ()));
        if (chunk == null) {
            return nothing;
        }
        BlockState state = chunk.getBlockState(at);
        if (state.isAir()) {
            return nothing;
        }
        int faces = 0;
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (Direction direction : DIRECTIONS) {
            next.setWithOffset(at, direction);
            boolean open;
            if (level.isOutsideBuildHeight(next.getY())) {
                open = direction == Direction.UP;
            } else {
                LevelChunk other = SectionPos.blockToSectionCoord(next.getX()) == chunk.getPos().x()
                        && SectionPos.blockToSectionCoord(next.getZ()) == chunk.getPos().z() ? chunk
                        : level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(next.getX()), SectionPos.blockToSectionCoord(next.getZ()));
                open = other != null && open(state, other.getBlockState(next));
            }
            if (open) {
                faces |= 1 << direction.ordinal();
            }
        }
        return faces == 0 ? nothing : new OverlayPayloads.Surface(at, state, (byte) faces);
    }

    /** For tests: how many inside players hold the ticket on the visible level at this chunk. */
    public static int ticketHolders(ServerLevel visible, ChunkPos chunk) {
        Long2IntOpenHashMap held = TICKETS.get(visible.dimension());
        return held == null ? 0 : held.get(chunk.pack());
    }

    /** For tests: how many chunks of the visible world a player inside holds. */
    public static int chunksSentTo(ServerPlayer player) {
        Watch watch = WATCHES.get(player.getUUID());
        return watch == null ? 0 : watch.chunks.size();
    }

    /** For tests: chunks scanned and sent to a player since they went inside (never per tick: only when a chunk is sent). */
    public static int scansFor(ServerPlayer player) {
        Watch watch = WATCHES.get(player.getUUID());
        return watch == null ? 0 : watch.scans;
    }
}
