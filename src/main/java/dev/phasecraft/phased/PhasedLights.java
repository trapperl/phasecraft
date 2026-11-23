package dev.phasecraft.phased;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.PhaseCraft;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Phased light: a hidden light source that is phased (standing on a phase
 * block, or hanging on its side) lights the visible world at its own spot.
 *
 * <p>The server keeps, per visible dimension, the light each phased hidden
 * block gives off; {@code BlockLightEnginePhasedMixin} adds it to the visible
 * block's own light when vanilla's light engine asks what a position emits,
 * and to a chunk's light sources when a chunk is lit from scratch. The table
 * is filled from hidden block changes and hidden chunk loads; a change
 * re-checks the visible light at that spot. The light engine runs on its own
 * thread, hence concurrent maps.</p>
 *
 * <p>Clients: vanilla sends them the server's light, but a client also
 * recomputes light itself when a visible block near it changes, and must
 * then know where phased lights are (otherwise swapping the visible block at
 * a phased light's spot darkens it on screen, while the server, seeing no
 * change, sends no correction). So every player with PhaseCraft is sent the
 * phased lights of the dimension they are in ({@link Sync}), and changes as
 * they happen; the client keeps them in {@link #CLIENT} and the same mixin
 * uses them there.</p>
 *
 */
public final class PhasedLights {
    private static final Map<ResourceKey<Level>, Map<Long, Integer>> LIGHTS = new ConcurrentHashMap<>();
    /** On a client: the phased lights of the dimension the player is in, as sent by the server. */
    public static final Map<Long, Integer> CLIENT = new ConcurrentHashMap<>();
    /** Hidden chunks loaded since the last tick, per hidden dimension (server thread only). */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> TO_SCAN = new HashMap<>();
    /** The dimension each player was last sent the phased lights of. */
    private static final Map<UUID, ResourceKey<Level>> SENT = new HashMap<>();
    private static final Direction[] DIRECTIONS = Direction.values();

    private PhasedLights() {}

    /** One phased light: its spot and how much light it gives off (0: none any more). */
    public record Light(BlockPos pos, int light) {
        static final StreamCodec<RegistryFriendlyByteBuf, Light> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Light::pos, ByteBufCodecs.VAR_INT, Light::light, Light::new);
    }

    /** Phased lights for a client: all of its dimension's (reset), or changes. */
    public record Sync(boolean reset, List<Light> lights) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "phased_lights"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Sync::reset, Light.STREAM_CODEC.apply(ByteBufCodecs.list()), Sync::lights, Sync::new);

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Sync.TYPE, Sync.STREAM_CODEC);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SENT.remove(handler.player.getUUID()));
        // Scanned on the next tick, not while the chunk is still loading: reading a block of
        // a chunk that is loading waits for that very chunk, and the server hangs at startup.
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            if (HiddenDimensions.isHidden(level.dimension())) {
                TO_SCAN.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet()).add(chunk.getPos().pack());
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(PhasedLights::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            LIGHTS.clear();
            SENT.clear();
            TO_SCAN.clear();
        });
    }

    private static Map<Long, Integer> lightsOf(Level visible) {
        return visible.isClientSide() ? CLIENT : LIGHTS.get(visible.dimension());
    }

    /** Light a phased hidden block gives off at this position of a visible level; 0 if none. */
    public static int emission(Level visible, long pos) {
        Map<Long, Integer> lights = lightsOf(visible);
        if (lights == null || lights.isEmpty()) {
            return 0;
        }
        Integer light = lights.get(pos);
        return light == null ? 0 : light;
    }

    /** Phased lights in a chunk of a visible level, for lighting a chunk from scratch. */
    public static void forEachIn(Level visible, ChunkPos chunk, LongConsumer action) {
        Map<Long, Integer> lights = lightsOf(visible);
        if (lights == null) {
            return;
        }
        for (long pos : lights.keySet()) {
            if (SectionPos.blockToSectionCoord(BlockPos.getX(pos)) == chunk.x()
                    && SectionPos.blockToSectionCoord(BlockPos.getZ(pos)) == chunk.z()) {
                action.accept(pos);
            }
        }
    }

    /** Called for every block change on the server; a hidden change can make the block, or one resting on it, a phased light. */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!HiddenDimensions.isHidden(level.dimension())
                || (oldState.getLightEmission() == 0 && newState.getLightEmission() == 0
                        && !oldState.is(PhaseCraftBlocks.PHASE_BLOCK) && !newState.is(PhaseCraftBlocks.PHASE_BLOCK))) {
            return;
        }
        refresh(level, pos);
        for (Direction direction : DIRECTIONS) {
            refresh(level, pos.relative(direction));
        }
    }

    private static void refresh(ServerLevel hidden, BlockPos pos) {
        if (!hidden.isLoaded(pos)) {
            return;
        }
        BlockState state = hidden.getBlockState(pos);
        // The support may be in a neighbouring chunk; never load one for this.
        int light = state.getLightEmission() > 0 && hidden.isLoaded(Phased.supportOf(pos, state))
                && Phased.isOnPhaseBlock(hidden, pos, state) ? state.getLightEmission() : 0;
        ServerLevel visible = HiddenLayer.of(hidden).visibleLevel();
        Map<Long, Integer> lights = LIGHTS.computeIfAbsent(visible.dimension(), key -> new ConcurrentHashMap<>());
        Integer before = light == 0 ? lights.remove(pos.asLong()) : lights.put(pos.asLong(), light);
        if (before == null ? light != 0 : before != light) {
            recheck(visible.getChunkSource().getLightEngine(), pos, light);
            Sync change = new Sync(false, List.of(new Light(pos.immutable(), light)));
            for (ServerPlayer player : visible.players()) {
                if (visible.dimension().equals(SENT.get(player.getUUID()))) {
                    ServerPlayNetworking.send(player, change);
                }
            }
        }
    }

    /**
     * Has vanilla's light engine look at a spot again. A phased light can sit in
     * a section vanilla thinks is empty (only air in the visible world), where it
     * keeps no light: mark the section as holding something first, as vanilla does
     * when a block is placed into an empty section.
     */
    public static void recheck(LevelLightEngine engine, BlockPos pos, int light) {
        if (light > 0) {
            engine.updateSectionStatus(SectionPos.of(pos), false);
        }
        engine.checkBlock(pos);
        if (engine instanceof ThreadedLevelLightEngine server) {
            // Vanilla only starts queued light work when the server has spare time
            // between ticks; a server with none (a busy one, or GameTests, which run
            // ticks back to back) could leave this check waiting indefinitely.
            server.tryScheduleUpdate();
        }
    }

    private static void tick(MinecraftServer server) {
        if (!TO_SCAN.isEmpty()) {
            for (Map.Entry<ResourceKey<Level>, LongOpenHashSet> entry : TO_SCAN.entrySet()) {
                ServerLevel hidden = server.getLevel(entry.getKey());
                if (hidden != null) {
                    entry.getValue().forEach(packed -> {
                        LevelChunk chunk = hidden.getChunkSource().getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed));
                        if (chunk != null) {
                            scan(hidden, chunk);
                        }
                    });
                }
            }
            TO_SCAN.clear();
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ResourceKey<Level> dimension = player.level().dimension();
            if (!dimension.equals(SENT.get(player.getUUID())) && ServerPlayNetworking.canSend(player, Sync.TYPE)) {
                Map<Long, Integer> lights = LIGHTS.getOrDefault(dimension, Map.of());
                List<Light> all = new ArrayList<>(lights.size());
                lights.forEach((pos, light) -> all.add(new Light(BlockPos.of(pos), light)));
                ServerPlayNetworking.send(player, new Sync(true, all));
                SENT.put(player.getUUID(), dimension);
            }
        }
    }

    private static void scan(ServerLevel level, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.getLightEmission() > 0)) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).getLightEmission() > 0) {
                            refresh(level, new BlockPos(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z));
                        }
                    }
                }
            }
        }
    }
}
