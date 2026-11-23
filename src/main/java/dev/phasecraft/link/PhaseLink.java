package dev.phasecraft.link;

import dev.phasecraft.layer.HiddenDimensions;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
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
     * The power a hidden port at pos puts up into this visible level (its UP), or 0: given out at
     * pos like an invisible redstone block (ServerLevelPhaseBridgeMixin).
     */
    public static int upAt(ServerLevel visible, BlockPos pos) {
        if (ignoreBridge > 0) {
            return 0;
        }
        ResourceKey<Level> hiddenKey = HiddenDimensions.hiddenOf(visible.dimension()).orElse(null);
        ServerLevel hidden = hiddenKey == null ? null : visible.getServer().getLevel(hiddenKey);
        if (hidden == null || !hidden.isLoaded(pos)) {
            return 0;
        }
        BlockState state = hidden.getBlockState(pos);
        return state.getBlock() instanceof PhasePortBlock ? state.getValue(PhasePortBlock.UP) : 0;
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
