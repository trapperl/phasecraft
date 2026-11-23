package dev.phasecraft.phased;

import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.mixin.BasePressurePlateAccessor;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
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
 * </ul>
 * Sounds of phased blocks are heard in the visible world
 * ({@code ServerLevelPhasedSoundMixin}).
 */
public final class PhasedInput {
    /** Every pressure plate in loaded hidden chunks, per hidden dimension. */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> PLATES = new HashMap<>();

    private PhasedInput() {}

    public static void register() {
        UseBlockCallback.EVENT.register(PhasedInput::use);
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> scan(level, chunk));
        ServerTickEvents.END_SERVER_TICK.register(PhasedInput::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PLATES.clear());
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
        if (newState.getBlock() instanceof BasePressurePlateBlock) {
            PLATES.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet()).add(pos.asLong());
        } else if (oldState.getBlock() instanceof BasePressurePlateBlock) {
            LongOpenHashSet plates = PLATES.get(level.dimension());
            if (plates != null) {
                plates.remove(pos.asLong());
            }
        }
    }

    private static void scan(ServerLevel level, LevelChunk chunk) {
        if (!HiddenDimensions.isHidden(level.dimension())) {
            return;
        }
        LevelChunkSection[] sections = chunk.getSections();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.getBlock() instanceof BasePressurePlateBlock)) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).getBlock() instanceof BasePressurePlateBlock) {
                            PLATES.computeIfAbsent(level.dimension(), key -> new LongOpenHashSet()).add(
                                    BlockPos.asLong(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z));
                        }
                    }
                }
            }
        }
    }

    private static void tick(MinecraftServer server) {
        for (Map.Entry<ResourceKey<Level>, LongOpenHashSet> entry : PLATES.entrySet()) {
            ServerLevel hidden = server.getLevel(entry.getKey());
            if (hidden == null) {
                continue;
            }
            ServerLevel visible = HiddenLayer.of(hidden).visibleLevel();
            LongIterator plates = entry.getValue().iterator();
            while (plates.hasNext()) {
                BlockPos pos = BlockPos.of(plates.nextLong());
                if (!hidden.isLoaded(pos)) {
                    continue;
                }
                BlockState state = hidden.getBlockState(pos);
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
    }
}
