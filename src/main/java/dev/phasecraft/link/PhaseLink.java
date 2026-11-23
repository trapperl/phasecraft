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

    private static boolean signalRelevant(ServerLevel level, BlockPos pos, BlockState state) {
        return state.isSignalSource() || state.isRedstoneConductor(level, pos);
    }

    private static void deliver(MinecraftServer server) {
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
        boolean wasTicking = oldStatus.isOrAfter(FullChunkStatus.BLOCK_TICKING);
        boolean isTicking = newStatus.isOrAfter(FullChunkStatus.BLOCK_TICKING);
        // Radius 1 puts the centre chunk at block-ticking level.
        if (isTicking && !wasTicking) {
            hidden.getChunkSource().addTicketWithRadius(PHASE_TICKET, chunk.getPos(), 1);
        } else if (wasTicking && !isTicking) {
            hidden.getChunkSource().removeTicketWithRadius(PHASE_TICKET, chunk.getPos(), 1);
        }
    }
}
