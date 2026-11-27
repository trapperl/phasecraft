package dev.phasecraft.perf;

import com.mojang.authlib.GameProfile;
import dev.phasecraft.overlay.OverlayPayloads;
import dev.phasecraft.phased.PhasedLights;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;

/**
 * Players without a client: a real ServerPlayer on a connection that goes nowhere (as the GameTests' TestPlayers),
 * told to have PhaseCraft's channels, so the overlay sync treats them as PhaseCraft clients. What the server sends
 * them piles up in the channel; {@link Fake#drain} counts it, and encodes PhaseCraft's messages as the network
 * thread of a real server would, to know their size and what encoding them costs.
 */
final class FakePlayers {
    private FakePlayers() {}

    static final class Fake {
        final ServerPlayer player;
        final EmbeddedChannel channel;

        Fake(ServerPlayer player, EmbeddedChannel channel) {
            this.player = player;
            this.channel = channel;
        }

        /** Empties what was sent; adds counts to the scenario's numbers. Outside the measured part of a tick. */
        void drain(Ctx ctx) {
            RegistryFriendlyByteBuf buffer = null;
            Object message;
            while ((message = channel.readOutbound()) != null) {
                ctx.add("packets", 1);
                if (!(message instanceof ClientboundCustomPayloadPacket(CustomPacketPayload payload))) {
                    continue;
                }
                if (buffer == null) {
                    buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), ctx.server.registryAccess());
                }
                long start = System.nanoTime();
                switch (payload) {
                    case OverlayPayloads.Chunk chunk -> {
                        OverlayPayloads.Chunk.STREAM_CODEC.encode(buffer, chunk);
                        ctx.add("overlayChunkMsgs", 1);
                        ctx.add("overlayGhosts", chunk.ghosts().size());
                    }
                    case OverlayPayloads.Blocks blocks -> {
                        OverlayPayloads.Blocks.STREAM_CODEC.encode(buffer, blocks);
                        ctx.add("overlayBlocksMsgs", 1);
                        ctx.add("overlayGhosts", blocks.ghosts().size());
                    }
                    case OverlayPayloads.Forget forget -> {
                        OverlayPayloads.Forget.STREAM_CODEC.encode(buffer, forget);
                        ctx.add("overlayForgetMsgs", 1);
                    }
                    case PhasedLights.Sync sync -> {
                        PhasedLights.Sync.STREAM_CODEC.encode(buffer, sync);
                        ctx.add("lightMsgs", 1);
                    }
                    default -> { }
                }
                ctx.add("phasecraftEncodeMs", (System.nanoTime() - start) / 1e6);
                ctx.add("phasecraftBytes", buffer.writerIndex());
                buffer.clear();
            }
        }
    }

    static Fake join(MinecraftServer server, ServerLevel level, double x, double y, double z, int number) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "perf" + number), false);
        ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.CREATIVE);
        player.teleportTo(x, y, z);
        level.getChunkSource().move(player);
        hasPhaseCraft(player);
        return new Fake(player, channel);
    }

    static void move(Fake fake, double x, double y, double z) {
        fake.player.teleportTo(x, y, z);
        ((ServerLevel) fake.player.level()).getChunkSource().move(fake.player);
    }

    /** As if the client had announced PhaseCraft's channels (a real one does when it joins). */
    @SuppressWarnings("unchecked")
    private static void hasPhaseCraft(ServerPlayer player) {
        try {
            Object addon = ServerNetworkingImpl.getAddon(player.connection);
            Field field = addon.getClass().getSuperclass().getDeclaredField("sendableChannels");
            field.setAccessible(true);
            Set<Identifier> channels = (Set<Identifier>) field.get(addon);
            channels.add(OverlayPayloads.Chunk.TYPE.id());
            channels.add(OverlayPayloads.Blocks.TYPE.id());
            channels.add(OverlayPayloads.Forget.TYPE.id());
            channels.add(OverlayPayloads.Reset.TYPE.id());
            channels.add(PhasedLights.Sync.TYPE.id());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot give a fake player PhaseCraft's channels", e);
        }
    }
}
