package dev.phasecraft.phased;

import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.mixin.BasePressurePlateAccessor;
import dev.phasecraft.mixin.TripWireBlockAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The visible world acting on phased hidden blocks.
 *
 * <ul>
 *   <li><b>Buttons and levers:</b> a right-click on a visible block uses the
 *       phased button or lever hidden at that same spot, for any player who
 *       could click a block there (Survival included: nothing shows where
 *       they are). Sneaking with something in hand places or uses it as
 *       usual, as with a vanilla button.</li>
 *   <li><b>Pressure plates:</b> visible players, mobs and items standing on
 *       a phased plate's spot press it. No entity ever touches a hidden
 *       plate, so hidden plates are tracked and checked every tick while
 *       released; once pressed, the plate's own vanilla ticks keep counting
 *       (and {@code BasePressurePlateMixin} makes them count visible
 *       entities).</li>
 *   <li><b>Tripwire:</b> a hidden string whose line ends at a hook on a
 *       phase block is checked the same way while released; once tripped,
 *       vanilla's rechecks count visible entities ({@code TripWireBlockMixin},
 *       {@link PhasedWatchers}).</li>
 * </ul>
 * Phased blocks make no sound in the visible world (the person, 2026-11-23);
 * their sounds play in the hidden layer only.
 */
public final class PhasedInput {
    /**
     * Every pressure plate and tripwire string in loaded hidden chunks, per hidden dimension and chunk. A chunk's
     * are forgotten when it is unloaded (and found again by {@link #scan} when it loads), and only chunks that
     * tick blocks are looked at: looking at a plate in any other chunk every tick cost time for nothing, and
     * reading its block kept the chunk from ever being unloaded (measured, src/perf: plates_unloaded).
     */
    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<LongOpenHashSet>> PLATES = new HashMap<>();
    /** While {@link #tick} walks the plates: plates placed meanwhile wait here, to be added after. */
    private static final List<Placed> PLACED_WHILE_TICKING = new ArrayList<>();
    /** Likewise chunks unloaded meanwhile (pos: the chunk's packed position), to be forgotten after. */
    private static final List<Placed> UNLOADED_WHILE_TICKING = new ArrayList<>();
    private static boolean ticking;

    private record Placed(ResourceKey<Level> dimension, long pos) {}

    /** Blocks that visible entities can press once phased: pressure plates and tripwire string. */
    private static boolean pressable(Block block) {
        return block instanceof BasePressurePlateBlock || block instanceof TripWireBlock;
    }

    private PhasedInput() {}

    public static void register() {
        UseBlockCallback.EVENT.register(PhasedInput::use);
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> scan(level, chunk));
        ServerChunkEvents.CHUNK_UNLOAD.register(PhasedInput::forget);
        ServerTickEvents.END_SERVER_TICK.register(PhasedInput::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PLATES.clear();
            PLACED_WHILE_TICKING.clear();
            UNLOADED_WHILE_TICKING.clear();
        });
    }

    private static void remember(ResourceKey<Level> dimension, long pos) {
        if (ticking) {
            // tick is walking these sets (a plate it pressed changed state, or set something off): add after.
            if (!known(dimension, BlockPos.of(pos))) {
                PLACED_WHILE_TICKING.add(new Placed(dimension, pos));
            }
            return;
        }
        Long2ObjectOpenHashMap<LongOpenHashSet> chunks = PLATES.get(dimension);
        if (chunks == null) {
            chunks = new Long2ObjectOpenHashMap<>();
            PLATES.put(dimension, chunks);
        }
        long chunk = ChunkPos.pack(SectionPos.blockToSectionCoord(BlockPos.getX(pos)), SectionPos.blockToSectionCoord(BlockPos.getZ(pos)));
        LongOpenHashSet plates = chunks.get(chunk);
        if (plates == null) {
            plates = new LongOpenHashSet();
            chunks.put(chunk, plates);
        }
        plates.add(pos);
    }

    private static void forget(ServerLevel level, LevelChunk chunk) {
        forget(level.dimension(), chunk.getPos().pack());
    }

    private static void forget(ResourceKey<Level> dimension, long chunk) {
        if (ticking) {
            UNLOADED_WHILE_TICKING.add(new Placed(dimension, chunk));
            return;
        }
        Long2ObjectOpenHashMap<LongOpenHashSet> chunks = PLATES.get(dimension);
        if (chunks != null) {
            chunks.remove(chunk);
        }
    }

    private static InteractionResult use(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide() || hand != InteractionHand.MAIN_HAND || player.isSpectator()
                || !(world instanceof ServerLevel visible) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        Optional<ResourceKey<Level>> hiddenKey = HiddenDimensions.hiddenOf(visible.dimension());
        ServerLevel hidden = hiddenKey.map(visible.getServer()::getLevel).orElse(null);
        if (hidden == null || (player.isSecondaryUseActive()
                && !(player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty()))) {
            return InteractionResult.PASS;
        }
        BlockPos pos = hit.getBlockPos();
        if (!hidden.isLoaded(pos)) {
            return InteractionResult.PASS;
        }
        BlockState state = hidden.getBlockState(pos);
        if (!(state.getBlock() instanceof ButtonBlock || state.getBlock() instanceof LeverBlock)
                || !Phased.isPhased(hidden, pos, state)) {
            return InteractionResult.PASS;
        }
        if (!state.useWithoutItem(hidden, player, hit).consumesAction()) {
            return InteractionResult.PASS;
        }
        serverPlayer.swing(hand, player.getItemInHand(hand).getInteractAnimation(), true);
        // The client may have predicted placing the held block against the visible one: undo that.
        serverPlayer.connection.send(new ClientboundBlockUpdatePacket(visible, pos));
        serverPlayer.connection.send(new ClientboundBlockUpdatePacket(visible, pos.relative(hit.getDirection())));
        serverPlayer.inventoryMenu.sendAllDataToRemote();
        return InteractionResult.SUCCESS;
    }

    /** Called for every block change on the server: keeps the plate list of hidden levels current. */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!HiddenDimensions.isHidden(level.dimension())) {
            return;
        }
        if (pressable(newState.getBlock())) {
            remember(level.dimension(), pos.asLong());
        } else if (pressable(oldState.getBlock()) && !ticking) {
            // (While ticking, tick itself drops what is no longer a plate when it comes to it.)
            Long2ObjectOpenHashMap<LongOpenHashSet> chunks = PLATES.get(level.dimension());
            LongOpenHashSet plates = chunks == null ? null : chunks.get(ChunkPos.pack(pos));
            if (plates != null) {
                plates.remove(pos.asLong());
            }
        }
    }

    private static boolean known(ResourceKey<Level> dimension, BlockPos pos) {
        Long2ObjectOpenHashMap<LongOpenHashSet> chunks = PLATES.get(dimension);
        LongOpenHashSet plates = chunks == null ? null : chunks.get(ChunkPos.pack(pos));
        return plates != null && plates.contains(pos.asLong());
    }

    private static void scan(ServerLevel level, LevelChunk chunk) {
        if (!HiddenDimensions.isHidden(level.dimension())) {
            return;
        }
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(state -> pressable(state.getBlock()))) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (pressable(section.getBlockState(x, y, z).getBlock())) {
                            remember(level.dimension(),
                                    BlockPos.asLong(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z));
                        }
                    }
                }
            }
        }
    }

    private static void tick(MinecraftServer server) {
        ticking = true;
        try {
            for (Map.Entry<ResourceKey<Level>, Long2ObjectOpenHashMap<LongOpenHashSet>> entry : PLATES.entrySet()) {
                ServerLevel hidden = server.getLevel(entry.getKey());
                if (hidden == null || entry.getValue().isEmpty()) {
                    continue;
                }
                ServerLevel visible = HiddenLayer.of(hidden).visibleLevel();
                for (Long2ObjectMap.Entry<LongOpenHashSet> chunk : entry.getValue().long2ObjectEntrySet()) {
                    // A plate reacts where blocks tick (its release is a scheduled tick), as a visible one does.
                    if (hidden.shouldTickBlocksAt(chunk.getLongKey())) {
                        tickChunk(hidden, visible, chunk.getValue());
                    }
                }
            }
        } finally {
            ticking = false;
        }
        if (!UNLOADED_WHILE_TICKING.isEmpty()) {
            for (Placed unloaded : UNLOADED_WHILE_TICKING) {
                forget(unloaded.dimension(), unloaded.pos());
            }
            UNLOADED_WHILE_TICKING.clear();
        }
        if (!PLACED_WHILE_TICKING.isEmpty()) {
            for (Placed placed : PLACED_WHILE_TICKING) {
                remember(placed.dimension(), placed.pos());
            }
            PLACED_WHILE_TICKING.clear();
        }
    }

    private static void tickChunk(ServerLevel hidden, ServerLevel visible, LongOpenHashSet chunkPlates) {
        LongIterator plates = chunkPlates.iterator();
        while (plates.hasNext()) {
            BlockPos pos = BlockPos.of(plates.nextLong());
            BlockState state = hidden.getBlockState(pos);
            if (state.getBlock() instanceof TripWireBlock string) {
                checkString(hidden, visible, pos, state, string);
                continue;
            }
            if (!(state.getBlock() instanceof BasePressurePlateBlock plate)) {
                plates.remove();
                continue;
            }
            BasePressurePlateAccessor access = (BasePressurePlateAccessor) plate;
            if (access.phasecraft$getSignalForState(state) > 0 || !Phased.isPhased(hidden, pos, state)) {
                continue;
            }
            AABB box = BasePressurePlateAccessor.phasecraft$touchAabb().move(pos);
            List<Entity> on = visible.getEntitiesOfClass(Entity.class, box, entity -> !entity.isSpectator());
            if (!on.isEmpty()) {
                access.phasecraft$checkPressed(on.get(0), hidden, pos, state, 0);
            }
        }
    }

    /** A released, attached hidden string with a visible entity at its spot: vanilla's check decides, if the string is phased. */
    private static void checkString(ServerLevel hidden, ServerLevel visible, BlockPos pos, BlockState state, TripWireBlock string) {
        if (state.getValue(BlockStateProperties.POWERED) || !state.getValue(BlockStateProperties.ATTACHED)) {
            return;
        }
        AABB box = state.getShape(hidden, pos).bounds().move(pos);
        if (visible.getEntities((Entity) null, box, entity -> !entity.isIgnoringBlockTriggers()).isEmpty()
                || PhasedWatchers.phasedStringWatches(hidden, pos) == null) {
            return;
        }
        ((TripWireBlockAccessor) string).phasecraft$checkPressed(hidden, pos);
    }
}
