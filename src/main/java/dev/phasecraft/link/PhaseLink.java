package dev.phasecraft.link;

import dev.phasecraft.layer.HiddenDimensions;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The link between a visible dimension and its hidden layer.
 *
 * <ul>
 *   <li><b>Power crosses only at phase ports</b> ({@link PhasePortBlock}).
 *       Everywhere else the layers are independent.</li>
 *   <li><b>Ports hear the other layer with up to one tick of delay:</b> a
 *       change to a signal source or conductor near a position queues a
 *       refresh of any port at that position in the other layer, delivered at
 *       the end of the tick through a {@link PhaseUpdateQueue}. Loops through
 *       both layers therefore advance one hop per tick instead of recursing.</li>
 *   <li><b>Hidden chunks tick where the visible world ticks:</b> a chunk ticket
 *       in the hidden layer follows each visible chunk's ticking status.</li>
 *   <li><b>Ports catch up when a chunk starts ticking:</b> a refresh for a
 *       port whose chunk is not loaded is dropped (a hidden chunk loads a few
 *       ticks after its visible twin, so the visible power at a port can
 *       change before then), and a port reads 0 from the other layer where
 *       that is not loaded. So when a chunk starts ticking, the ports in it,
 *       and in the other layer's chunk at the same place, are refreshed
 *       (issue #17).</li>
 * </ul>
 */
public final class PhaseLink {
    /** Loads and ticks a hidden chunk (and keeps its dimension active) while its visible twin ticks. */
    public static final TicketType PHASE_TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath("phasecraft", "phase_link"),
            new TicketType(TicketType.NO_TIMEOUT,
                    TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    private record Refresh(ResourceKey<Level> dimension, BlockPos pos) {}

    private static final PhaseUpdateQueue<Refresh> QUEUE = new PhaseUpdateQueue<>();
    /** Visible positions whose neighbours must hear that a hidden port's UP changed. */
    private static final PhaseUpdateQueue<Refresh> VISIBLE_UPDATES = new PhaseUpdateQueue<>();
    /** While above 0, {@link #upAt} reports nothing: reads across the layers ignore what ports put up. */
    private static int ignoreBridge;
    private static final Direction[] DIRECTIONS = Direction.values();

    private static long queued;
    private static long delivered;
    private static long portsRefreshed;
    private static long skippedUnloaded;

    private PhaseLink() {}

    /** Counters since server start, for /phasecraft link. */
    public static String stats() {
        return "pending=" + QUEUE.size() + " queued=" + queued + " delivered=" + delivered
                + " portsRefreshed=" + portsRefreshed + " skippedUnloaded=" + skippedUnloaded;
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(PhaseLink::deliver);
        ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.register(PhaseLink::followTicking);
        ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.register(PhaseLink::refreshPortsWhenTicking);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> lastTwin = null);
    }

    /**
     * A chunk started ticking: refresh, at the end of the tick, the ports in it and in the other layer's chunk at the
     * same place. (Not on load: a chunk kept in memory comes back without loading again, and a hidden chunk can load
     * on its own before its visible twin, when something reads it.) A hidden chunk ticks only once its visible twin
     * does, so by then both are there to read.
     */
    private static void refreshPortsWhenTicking(ServerLevel level, LevelChunk chunk, FullChunkStatus oldStatus,
            FullChunkStatus newStatus) {
        if (oldStatus.isOrAfter(FullChunkStatus.BLOCK_TICKING) || !newStatus.isOrAfter(FullChunkStatus.BLOCK_TICKING)) {
            return;
        }
        ResourceKey<Level> otherKey = otherLayer(level.dimension()).orElse(null);
        if (otherKey == null) {
            return;
        }
        queuePorts(level.dimension(), chunk);
        ServerLevel other = level.getServer().getLevel(otherKey);
        LevelChunk twin = other == null ? null : other.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z());
        if (twin != null) {
            queuePorts(otherKey, twin);
        }
    }

    private static void queuePorts(ResourceKey<Level> dimension, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(PhaseLink::isPort)) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (isPort(section.getBlockState(x, y, z))) {
                            QUEUE.add(new Refresh(dimension, new BlockPos(chunk.getPos().getMinBlockX() + x, baseY + y,
                                    chunk.getPos().getMinBlockZ() + z)));
                            queued++;
                        }
                    }
                }
            }
        }
    }

    private static Optional<ResourceKey<Level>> otherLayer(ResourceKey<Level> dimension) {
        return HiddenDimensions.isHidden(dimension)
                ? HiddenDimensions.visibleOf(dimension)
                : HiddenDimensions.hiddenOf(dimension);
    }

    private static boolean isPort(BlockState state) {
        return state.getBlock() instanceof PhasePortBlock;
    }

    /**
     * What a port at this position receives: the power reaching the same
     * position in the other layer, from a source standing there or from its
     * neighbours there. Ports there are ignored. 0 if that chunk is not loaded.
     */
    public static int portInput(ServerLevel level, BlockPos pos) {
        ResourceKey<Level> otherKey = otherLayer(level.dimension()).orElse(null);
        ServerLevel other = otherKey == null ? null : level.getServer().getLevel(otherKey);
        if (other == null || !other.isLoaded(pos)) {
            return 0;
        }
        ignoreBridge++;
        try {
            return otherLayerPower(other, pos);
        } finally {
            ignoreBridge--;
        }
    }

    private static int otherLayerPower(ServerLevel other, BlockPos pos) {
        int best = 0;
        BlockState there = other.getBlockState(pos);
        if (there.isSignalSource() && !isPort(there)) {
            for (Direction direction : DIRECTIONS) {
                best = Math.max(best, there.getSignal(other, pos, direction));
            }
        }
        for (Direction direction : DIRECTIONS) {
            if (best >= 15) {
                return 15;
            }
            BlockPos neighbour = pos.relative(direction);
            if (!isPort(other.getBlockState(neighbour))) {
                best = Math.max(best, other.getSignal(neighbour, direction));
            }
        }
        return Math.min(best, 15);
    }

    /**
     * Called for every block change on the server. A change to a signal source
     * or conductor can change the power reaching positions up to two blocks
     * away (directly, or through a powered conductor), so ports at those
     * positions in the other layer are queued for a refresh.
     */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!signalRelevant(level, pos, oldState) && !signalRelevant(level, pos, newState)) {
            return;
        }
        ResourceKey<Level> other = otherLayer(level.dimension()).orElse(null);
        if (other == null) {
            return;
        }
        // Almost every change has no port anywhere near it in the other layer: nothing to queue (measured on a
        // busy wire network: queueing and delivering 25 refreshes per change was about a tenth of the tick).
        ServerLevel otherLevel = level.getServer().getLevel(other);
        if (otherLevel == null || !mayHavePortNear(otherLevel, pos)) {
            return;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 2) {
                        QUEUE.add(new Refresh(other, pos.offset(dx, dy, dz)));
                        queued++;
                    }
                }
            }
        }
    }

    /**
     * Whether a port could stand within two blocks of pos in this level, so that a refresh queued for it could do
     * anything: false only if no chunk section the positions lie in can hold one. A section's palette answers
     * (it may still list a port that has been removed: then this says true, and the refreshes find nothing, as
     * before). A chunk that is loaded but not there yet counts as "could": the delivery at the end of the tick
     * decides, as before.
     */
    private static boolean mayHavePortNear(ServerLevel level, BlockPos pos) {
        int minSection = Math.max(SectionPos.blockToSectionCoord(pos.getY() - 2), level.getMinSectionY());
        int maxSection = Math.min(SectionPos.blockToSectionCoord(pos.getY() + 2), level.getMaxSectionY());
        int minChunkX = SectionPos.blockToSectionCoord(pos.getX() - 2);
        int maxChunkX = SectionPos.blockToSectionCoord(pos.getX() + 2);
        int minChunkZ = SectionPos.blockToSectionCoord(pos.getZ() - 2);
        int maxChunkZ = SectionPos.blockToSectionCoord(pos.getZ() + 2);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    if (level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                        return true;
                    }
                    continue;
                }
                for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
                    if (!section.hasOnlyAir() && section.maybeHas(PhaseLink::isPort)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The power a hidden port at pos puts up into this visible level (its UP), or 0: given out at
     * pos like an invisible redstone block (ServerLevelPhaseBridgeMixin).
     */
    public static int upAt(ServerLevel visible, BlockPos pos) {
        if (ignoreBridge > 0) {
            return 0;
        }
        ServerLevel hidden = hiddenOf(visible);
        if (hidden == null) {
            return 0;
        }
        // Every signal read in a visible level comes through here (measured on a busy wire network: an eighth of
        // the tick), and almost none has a port at its spot. A hidden chunk that is there answers that with one
        // look-up, its empty sections without reading a block.
        LevelChunk chunk = hidden.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        if (chunk != null && !portIn(chunk, pos)) {
            return 0;
        }
        // A port, or a chunk that is not there (yet): as before.
        if (!hidden.isLoaded(pos)) {
            return 0;
        }
        BlockState state = hidden.getBlockState(pos);
        return state.getBlock() instanceof PhasePortBlock ? state.getValue(PhasePortBlock.UP) : 0;
    }

    /** Whether the block at pos in this chunk is a port, read from its section (LevelChunk.getBlockState does more per call). */
    private static boolean portIn(LevelChunk chunk, BlockPos pos) {
        int index = chunk.getSectionIndex(pos.getY());
        LevelChunkSection[] sections = chunk.getSections();
        if (index < 0 || index >= sections.length || sections[index].hasOnlyAir()) {
            return false;
        }
        return isPort(sections[index].getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15));
    }

    /**
     * The hidden level last asked for, by its visible level (null: that level has none, as a hidden level itself,
     * whose signal reads come through upAt too): saves upAt two map look-ups on every signal read.
     */
    private record Twin(ServerLevel visible, ServerLevel hidden) {}

    private static Twin lastTwin;

    private static ServerLevel hiddenOf(ServerLevel visible) {
        Twin twin = lastTwin;
        if (twin != null && twin.visible() == visible) {
            return twin.hidden();
        }
        ResourceKey<Level> hiddenKey = HiddenDimensions.hiddenOf(visible.dimension()).orElse(null);
        ServerLevel hidden = hiddenKey == null ? null : visible.getServer().getLevel(hiddenKey);
        if (hidden != null || hiddenKey == null) {
            lastTwin = new Twin(visible, hidden);
        }
        return hidden;
    }

    /** A hidden port's UP changed: the visible neighbours of its spot hear about it at the end of the tick. */
    public static void queueVisibleUpdate(ServerLevel hidden, BlockPos pos) {
        HiddenDimensions.visibleOf(hidden.dimension()).ifPresent(visible -> VISIBLE_UPDATES.add(new Refresh(visible, pos.immutable())));
    }

    private static boolean signalRelevant(ServerLevel level, BlockPos pos, BlockState state) {
        return state.isSignalSource() || state.isRedstoneConductor(level, pos);
    }

    private static void deliver(MinecraftServer server) {
        VISIBLE_UPDATES.drain(update -> {
            ServerLevel visible = server.getLevel(update.dimension());
            if (visible != null && visible.isLoaded(update.pos())) {
                visible.updateNeighborsAt(update.pos(), visible.getBlockState(update.pos()).getBlock());
            }
        });
        delivered += QUEUE.drain(refresh -> {
            ServerLevel target = server.getLevel(refresh.dimension());
            if (target != null && target.isLoaded(refresh.pos())) {
                BlockState state = target.getBlockState(refresh.pos());
                if (isPort(state)) {
                    portsRefreshed++;
                    PhasePortBlock.refresh(target, refresh.pos(), state);
                }
            } else {
                skippedUnloaded++;
            }
        });
    }

    private static void followTicking(ServerLevel level, LevelChunk chunk, FullChunkStatus oldStatus, FullChunkStatus newStatus) {
        ResourceKey<Level> hiddenKey = HiddenDimensions.hiddenOf(level.dimension()).orElse(null);
        ServerLevel hidden = hiddenKey == null ? null : level.getServer().getLevel(hiddenKey);
        if (hidden == null) {
            return;
        }
        // The hidden chunk ticks exactly as the visible one does: blocks where it
        // ticks blocks, entities too (falling sand, items, minecarts) where it
        // ticks entities. A ticket of radius r puts its centre chunk at level
        // 33 - r: 32 is block ticking, 31 entity ticking.
        int oldRadius = ticketRadius(oldStatus);
        int newRadius = ticketRadius(newStatus);
        if (oldRadius == newRadius) {
            return;
        }
        if (newRadius > 0) {
            hidden.getChunkSource().addTicketWithRadius(PHASE_TICKET, chunk.getPos(), newRadius);
        }
        if (oldRadius > 0) {
            hidden.getChunkSource().removeTicketWithRadius(PHASE_TICKET, chunk.getPos(), oldRadius);
        }
    }

    private static int ticketRadius(FullChunkStatus status) {
        if (status.isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
            return 2;
        }
        return status.isOrAfter(FullChunkStatus.BLOCK_TICKING) ? 1 : 0;
    }
}
