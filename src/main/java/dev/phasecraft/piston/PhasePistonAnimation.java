package dev.phasecraft.piston;

import dev.phasecraft.PhaseCraft;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lets players' games show blocks moved by a phase piston sliding, as vanilla
 * shows blocks moved by its own pistons. A vanilla piston tells each client to
 * run the move itself (a block event at the piston); a phase piston is in the
 * hidden layer, so the client could not, and saw the moved blocks vanish and
 * reappear two ticks later. Instead the server sends the moved blocks
 * ({@link Moved}); the client places the same moving-block entities vanilla's
 * client would (PhasePistonAnimationClient), and the server's own block
 * updates then settle the result.
 */
public final class PhasePistonAnimation {
    /** One block moving into pos, from pos minus the push direction. */
    public record Moving(BlockPos pos, BlockState state, Direction direction, boolean extending) {
        static final StreamCodec<RegistryFriendlyByteBuf, Moving> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Moving::pos, ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), Moving::state,
                Direction.STREAM_CODEC, Moving::direction, ByteBufCodecs.BOOL, Moving::extending, Moving::new);

        /** Where the block comes from. */
        public BlockPos from() {
            return pos.relative(extending ? direction.getOpposite() : direction);
        }
    }

    public record Moved(List<Moving> blocks) implements CustomPacketPayload {
        public static final Type<Moved> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "phase_piston_moved"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Moved> STREAM_CODEC = StreamCodec.composite(
                Moving.STREAM_CODEC.apply(ByteBufCodecs.list()), Moved::blocks, Moved::new);

        @Override
        public Type<Moved> type() {
            return TYPE;
        }
    }

    private PhasePistonAnimation() {}

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Moved.TYPE, Moved.STREAM_CODEC);
    }

    /** Tells every player who can see the blocks (and has PhaseCraft) that they started moving. */
    static void send(ServerLevel visible, List<Moving> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        Moved payload = new Moved(List.copyOf(blocks));
        for (ServerPlayer player : PlayerLookup.tracking(visible, blocks.getFirst().pos())) {
            if (ServerPlayNetworking.canSend(player, Moved.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }
}
