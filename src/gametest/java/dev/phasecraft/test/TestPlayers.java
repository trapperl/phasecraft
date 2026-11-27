package dev.phasecraft.test;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;

/** Real, connected test players. (GameTestHelper's makeMockServerPlayerInLevel always reports Creative.) */
final class TestPlayers {
    /** Each test player's end of the connection: what the server sends it piles up there. */
    private static final Map<UUID, EmbeddedChannel> CHANNELS = new ConcurrentHashMap<>();

    private TestPlayers() {}

    static ServerPlayer join(GameTestHelper helper, GameType mode) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "phasecraft-test"), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        CHANNELS.put(player.getUUID(), new EmbeddedChannel(connection));
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(mode);
        return player;
    }

    static void leave(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().getServer().getPlayerList().remove(player);
        EmbeddedChannel channel = CHANNELS.remove(player.getUUID());
        if (channel != null) {
            channel.releaseOutbound();
        }
    }

    /**
     * A test player whose client says it has PhaseCraft: it announces the given payload types, as a real client
     * with the mod does when it joins (Fabric's minecraft:register), so the server will send them to it.
     */
    static ServerPlayer joinWithMod(GameTestHelper helper, GameType mode, List<CustomPacketPayload.Type<?>> types) {
        ServerPlayer player = join(helper, mode);
        List<Identifier> ids = types.stream().map(CustomPacketPayload.Type::id).toList();
        player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new RegistrationPayload(RegistrationPayload.REGISTER, ids)));
        return player;
    }

    /** What a client would do with this payload arriving at the server: through the server's real receivers. */
    static void sendToServer(ServerPlayer player, CustomPacketPayload payload) {
        player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(payload));
    }

    /** The custom payloads the server has sent this test player since the last call, in order. */
    static List<CustomPacketPayload> sent(ServerPlayer player) {
        EmbeddedChannel channel = CHANNELS.get(player.getUUID());
        List<CustomPacketPayload> payloads = new ArrayList<>();
        if (channel == null) {
            return payloads;
        }
        channel.flushOutbound();
        for (Object message = channel.readOutbound(); message != null; message = channel.readOutbound()) {
            if (message instanceof ClientboundCustomPayloadPacket(CustomPacketPayload payload)) {
                payloads.add(payload);
            }
        }
        return payloads;
    }
}
