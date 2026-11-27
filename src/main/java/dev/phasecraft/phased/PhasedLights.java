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
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
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
 * change, sends no correction). So every player with PhaseCraft, whatever
 * their game mode (the light is there for everyone), is sent the phased
 * lights of the chunks they are tracking ({@link Sync}): the chunks vanilla
 * sends them anyway, where the light itself already shows. Lights come and
 * go as chunks enter and leave that view, and changes go only to the players
 * tracking the chunk. Never the whole dimension: a modified client would
 * read other people's hidden builds off the list. The client keeps them in
 * {@link #CLIENT} and the same mixin uses them there.</p>
 *
 * <p>The game rule {@code phasecraft:phased_lights} ({@link #RULE}, on by
 * default; the person, 2026-11-27: "make a separate light enable, I wanted
 * builds to be able to have atmospheric light") switches all of this off:
 * the table is still kept, but gives no light and is sent to nobody.
 * Changing the rule while the server runs relights every phased light's
 * spot and tells the clients. It has nothing to do with
 * phasecraft:survival_access.</p>
 */
public final class PhasedLights {
    public static final GameRule<Boolean> RULE = GameRuleBuilder.forBoolean(true)
            .category(GameRuleCategory.MISC)
            .buildAndRegister(Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "phased_lights"));

    /** Every phased hidden light source, per visible dimension, whether the rule is on or off. */
    private static final Map<ResourceKey<Level>, Map<Long, Integer>> LIGHTS = new ConcurrentHashMap<>();
    /** The rule's value as of the last tick (the light thread reads it). */
    private static volatile boolean enabled = true;
    /** On a client: the phased lights of the dimension the player is in, as sent by the server. */
    public static final Map<Long, Integer> CLIENT = new ConcurrentHashMap<>();
    /** Hidden chunks loaded since the last tick, per hidden dimension (server thread only). */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> TO_SCAN = new HashMap<>();
    /** What each player's client holds: the phased lights of this dimension inside this view. */
    private static final Map<UUID, Told> TOLD = new HashMap<>();
    private static final Direction[] DIRECTIONS = Direction.values();

    private PhasedLights() {}

    /** One phased light: its spot and how much light it gives off (0: none any more). */
    public record Light(BlockPos pos, int light) {
        static final StreamCodec<RegistryFriendlyByteBuf, Light> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Light::pos, ByteBufCodecs.VAR_INT, Light::light, Light::new);
    }

    /** The dimension and the chunks (vanilla's own view of what the player is sent) a client holds the lights of. */
    private record Told(ResourceKey<Level> dimension, ChunkTrackingView view) {}

    /** Phased lights for a client: all it is to hold (reset: forget the rest), or changes. */
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
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> TOLD.remove(handler.player.getUUID()));
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
            TOLD.clear();
            TO_SCAN.clear();
            enabled = true;
        });
    }

    /** The lights that shine in a level: on a client what the server sent, on the server none while the rule is off. */
    private static Map<Long, Integer> lightsOf(Level visible) {
        if (visible.isClientSide()) {
            return CLIENT;
        }
        return enabled ? LIGHTS.get(visible.dimension()) : null;
    }

    /**
     * The phased lights a player's client is to hold: those of the chunks the player
     * is tracking, in the dimension they are in; none while the rule is off.
     */
    public static List<Light> lightsFor(ServerPlayer player) {
        return lightsIn(player.level().dimension(), player.getChunkTrackingView());
    }

    private static List<Light> lightsIn(ResourceKey<Level> dimension, ChunkTrackingView view) {
        Map<Long, Integer> lights = enabled ? LIGHTS.get(dimension) : null;
        if (lights == null || lights.isEmpty()) {
            return List.of();
        }
        List<Light> inView = new ArrayList<>();
        lights.forEach((pos, light) -> {
            if (contains(view, pos)) {
                inView.add(new Light(BlockPos.of(pos), light));
            }
        });
        return inView;
    }

    private static boolean contains(ChunkTrackingView view, long pos) {
        return view.contains(SectionPos.blockToSectionCoord(BlockPos.getX(pos)), SectionPos.blockToSectionCoord(BlockPos.getZ(pos)));
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
        if (enabled && (before == null ? light != 0 : before != light)) {
            recheck(visible.getChunkSource().getLightEngine(), pos, light);
            Sync change = new Sync(false, List.of(new Light(pos.immutable(), light)));
            for (ServerPlayer player : visible.players()) {
                Told told = TOLD.get(player.getUUID());
                if (told != null && told.dimension().equals(visible.dimension()) && contains(told.view(), pos.asLong())) {
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
            // ticks back to back) could leave this check waiting indefinitely. The
            // check reaches the queue through another thread, so it may not be there
            // yet: start the engine now, and every tick (see kick).
            server.tryScheduleUpdate();
        }
    }

    /**
     * Every tick, starts the light engine of every level with a hidden twin, as vanilla
     * does whenever the server has spare time (one cheap check when there is no work).
     */
    private static void kick(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (HiddenDimensions.hiddenOf(level.dimension()).isPresent()) {
                level.getChunkSource().getLightEngine().tryScheduleUpdate();
            }
        }
    }

    /** The rule changed: every phased light's spot is lit again or goes dark, and clients forget what they held. */
    private static void setEnabled(MinecraftServer server, boolean now) {
        enabled = now;
        LIGHTS.forEach((dimension, lights) -> {
            ServerLevel visible = server.getLevel(dimension);
            if (visible != null) {
                LevelLightEngine engine = visible.getChunkSource().getLightEngine();
                lights.forEach((pos, light) -> recheck(engine, BlockPos.of(pos), now ? light : 0));
            }
        });
        if (!now) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (TOLD.containsKey(player.getUUID())) {
                    ServerPlayNetworking.send(player, new Sync(true, List.of()));
                }
            }
        }
        TOLD.clear(); // on again: everyone is sent their view afresh, below
    }

    /**
     * Brings one client up to date: everything in view when it holds nothing of this
     * dimension yet, else what the player's view gained and lost since last time.
     */
    private static void tell(ServerPlayer player) {
        ResourceKey<Level> dimension = player.level().dimension();
        ChunkTrackingView view = player.getChunkTrackingView();
        Told told = TOLD.get(player.getUUID());
        if (told == null || !told.dimension().equals(dimension)) {
            if (!ServerPlayNetworking.canSend(player, Sync.TYPE)) {
                return;
            }
            ServerPlayNetworking.send(player, new Sync(true, lightsIn(dimension, view)));
        } else if (told.view().equals(view)) {
            return;
        } else {
            List<Light> changes = new ArrayList<>();
            LIGHTS.getOrDefault(dimension, Map.of()).forEach((pos, light) -> {
                boolean held = contains(told.view(), pos);
                if (held != contains(view, pos)) {
                    changes.add(new Light(BlockPos.of(pos), held ? 0 : light));
                }
            });
            if (!changes.isEmpty()) {
                ServerPlayNetworking.send(player, new Sync(false, changes));
            }
        }
        TOLD.put(player.getUUID(), new Told(dimension, view));
    }

    private static void tick(MinecraftServer server) {
        boolean now = server.getGameRules().get(RULE);
        if (now != enabled) {
            setEnabled(server, now);
        }
        kick(server);
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
        if (enabled) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                tell(player);
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
