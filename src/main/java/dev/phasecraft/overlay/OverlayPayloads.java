package dev.phasecraft.overlay;

import dev.phasecraft.PhaseCraft;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-to-client messages that mirror the hidden layer near a player, so the
 * client can draw it as an overlay. Only clients with PhaseCraft receive them.
 */
public final class OverlayPayloads {
    private OverlayPayloads() {}

    /** One hidden block. Air means "nothing hidden here (any more)". */
    public record Ghost(BlockPos pos, BlockState state) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Ghost> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Ghost::pos,
                ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), Ghost::state,
                Ghost::new);
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Ghost>> GHOSTS =
            Ghost.STREAM_CODEC.apply(ByteBufCodecs.list());

    /** The hidden blocks of one chunk, replacing whatever the client had for it. More may follow in {@link Blocks}. */
    public record Chunk(ChunkPos chunk, List<Ghost> ghosts) implements CustomPacketPayload {
        public static final Type<Chunk> TYPE = new Type<>(id("hidden_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Chunk> STREAM_CODEC = StreamCodec.composite(
                ChunkPos.STREAM_CODEC, Chunk::chunk, GHOSTS, Chunk::ghosts, Chunk::new);

        @Override
        public Type<Chunk> type() {
            return TYPE;
        }
    }

    /** Hidden blocks that changed (air: removed), in chunks the client already has. */
    public record Blocks(List<Ghost> ghosts) implements CustomPacketPayload {
        public static final Type<Blocks> TYPE = new Type<>(id("hidden_blocks"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Blocks> STREAM_CODEC =
                GHOSTS.map(Blocks::new, Blocks::ghosts);

        @Override
        public Type<Blocks> type() {
            return TYPE;
        }
    }

    /** How many visible blocks a hidden shifter holds (for the editing readout). */
    public record Count(BlockPos pos, int count) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Count> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Count::pos, ByteBufCodecs.VAR_INT, Count::count, Count::new);
    }

    /** What hidden shifters hold, in chunks the client has: sent with a chunk, then whenever it changes. */
    public record Holding(List<Count> counts) implements CustomPacketPayload {
        public static final Type<Holding> TYPE = new Type<>(id("hidden_holding"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Holding> STREAM_CODEC =
                Count.STREAM_CODEC.apply(ByteBufCodecs.list()).map(Holding::new, Holding::counts);

        @Override
        public Type<Holding> type() {
            return TYPE;
        }
    }

    /** The client should drop one chunk: it is now out of range. */
    public record Forget(ChunkPos chunk) implements CustomPacketPayload {
        public static final Type<Forget> TYPE = new Type<>(id("hidden_forget"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Forget> STREAM_CODEC =
                ChunkPos.STREAM_CODEC.<RegistryFriendlyByteBuf>cast().map(Forget::new, Forget::chunk);

        @Override
        public Type<Forget> type() {
            return TYPE;
        }
    }

    /** The client should drop everything (changed dimension, or may no longer see the hidden layer). */
    public record Reset() implements CustomPacketPayload {
        public static final Type<Reset> TYPE = new Type<>(id("hidden_reset"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Reset> STREAM_CODEC = StreamCodec.unit(new Reset());

        @Override
        public Type<Reset> type() {
            return TYPE;
        }
    }

    /**
     * One visible block on the surface, for the inside view, and which of its faces are open (a bit per
     * {@link net.minecraft.core.Direction}'s ordinal): only those are drawn. Air means "nothing to show here (any more)".
     */
    public record Surface(BlockPos pos, BlockState state, byte faces) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Surface> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Surface::pos,
                ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY), Surface::state,
                ByteBufCodecs.BYTE, Surface::faces,
                Surface::new);
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Surface>> SURFACES =
            Surface.STREAM_CODEC.apply(ByteBufCodecs.list());

    /**
     * The inside view ({@link InsideView}), for a player in a hidden dimension: the visible twin's surface blocks
     * of one chunk, replacing whatever the client had for it. More may follow in {@link VisibleBlocks}.
     *
     * <p>A chunk of ordinary terrain is some two thousand blocks, and 81 chunks are sent on going in, so each
     * block is written short: its place in the chunk (a byte for x and z, a short for y) instead of a whole
     * position, 5 to 6 bytes a block instead of 11. Every block must be in {@code chunk}.</p>
     */
    public record VisibleChunk(ChunkPos chunk, List<Surface> blocks) implements CustomPacketPayload {
        public static final Type<VisibleChunk> TYPE = new Type<>(id("visible_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, VisibleChunk> STREAM_CODEC =
                StreamCodec.of(VisibleChunk::write, VisibleChunk::read);

        private static void write(RegistryFriendlyByteBuf buffer, VisibleChunk payload) {
            ChunkPos.STREAM_CODEC.encode(buffer, payload.chunk);
            buffer.writeVarInt(payload.blocks.size());
            for (Surface block : payload.blocks) {
                buffer.writeByte((block.pos.getX() & 15) << 4 | (block.pos.getZ() & 15));
                buffer.writeShort(block.pos.getY());
                buffer.writeVarInt(Block.getId(block.state));
                buffer.writeByte(block.faces);
            }
        }

        private static VisibleChunk read(RegistryFriendlyByteBuf buffer) {
            ChunkPos chunk = ChunkPos.STREAM_CODEC.decode(buffer);
            int count = buffer.readVarInt();
            List<Surface> blocks = new java.util.ArrayList<>(Math.min(count, 4096));
            for (int i = 0; i < count; i++) {
                int xz = buffer.readUnsignedByte();
                int y = buffer.readShort();
                BlockState state = Block.stateById(buffer.readVarInt());
                blocks.add(new Surface(new BlockPos(chunk.getMinBlockX() + (xz >> 4), y, chunk.getMinBlockZ() + (xz & 15)), state, buffer.readByte()));
            }
            return new VisibleChunk(chunk, blocks);
        }

        @Override
        public Type<VisibleChunk> type() {
            return TYPE;
        }
    }

    /** Inside view: visible surface blocks that changed (air: no longer there, or no longer on the surface). */
    public record VisibleBlocks(List<Surface> blocks) implements CustomPacketPayload {
        public static final Type<VisibleBlocks> TYPE = new Type<>(id("visible_blocks"));
        public static final StreamCodec<RegistryFriendlyByteBuf, VisibleBlocks> STREAM_CODEC =
                SURFACES.map(VisibleBlocks::new, VisibleBlocks::blocks);

        @Override
        public Type<VisibleBlocks> type() {
            return TYPE;
        }
    }

    /** Inside view: the client should drop one chunk of visible blocks: it is now out of range. */
    public record VisibleForget(ChunkPos chunk) implements CustomPacketPayload {
        public static final Type<VisibleForget> TYPE = new Type<>(id("visible_forget"));
        public static final StreamCodec<RegistryFriendlyByteBuf, VisibleForget> STREAM_CODEC =
                ChunkPos.STREAM_CODEC.<RegistryFriendlyByteBuf>cast().map(VisibleForget::new, VisibleForget::chunk);

        @Override
        public Type<VisibleForget> type() {
            return TYPE;
        }
    }

    /** Inside view: the client should drop every visible block it holds (left the hidden layer, or may no longer be there). */
    public record VisibleReset() implements CustomPacketPayload {
        public static final Type<VisibleReset> TYPE = new Type<>(id("visible_reset"));
        public static final StreamCodec<RegistryFriendlyByteBuf, VisibleReset> STREAM_CODEC = StreamCodec.unit(new VisibleReset());

        @Override
        public Type<VisibleReset> type() {
            return TYPE;
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, path);
    }

    static void register() {
        PayloadTypeRegistry<RegistryFriendlyByteBuf> clientbound = PayloadTypeRegistry.clientboundPlay();
        clientbound.register(Chunk.TYPE, Chunk.STREAM_CODEC);
        clientbound.register(Blocks.TYPE, Blocks.STREAM_CODEC);
        clientbound.register(Forget.TYPE, Forget.STREAM_CODEC);
        clientbound.register(Reset.TYPE, Reset.STREAM_CODEC);
        clientbound.register(Holding.TYPE, Holding.STREAM_CODEC);
        clientbound.register(VisibleChunk.TYPE, VisibleChunk.STREAM_CODEC);
        clientbound.register(VisibleBlocks.TYPE, VisibleBlocks.STREAM_CODEC);
        clientbound.register(VisibleForget.TYPE, VisibleForget.STREAM_CODEC);
        clientbound.register(VisibleReset.TYPE, VisibleReset.STREAM_CODEC);
    }
}
