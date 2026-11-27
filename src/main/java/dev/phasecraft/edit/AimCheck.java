package dev.phasecraft.edit;

import dev.phasecraft.PhaseCraft;
import dev.phasecraft.layer.Access;
import eu.pb4.common.protection.api.CommonProtection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Editing mode shows a protected spot before it is clicked (the person, 2026-11-27: "is there something better than
 * the protector message?"; the agent's proposal, "Yeah, let's do that"). The client asks about the spot it aims at
 * when its aim moves ({@link Aim}); the server answers why it would refuse an edit there ({@link Answer}): a claim
 * (asked through the Common Protection API, as an edit is: breaking a hidden block aimed at, placing at a visible
 * spot aimed at), spawn protection, or the world border. The client then outlines the spot in red and says why in
 * its readout. Asking only asks: no message, nothing changes.
 *
 * <p>A refused edit itself still says why in the action bar, shortly, and the player alone hears a dispenser's
 * failing click ({@link #tellRefused}).</p>
 *
 * <p>The client is not trusted: only players who may edit are answered, about spots within their reach, and at most
 * once a tick each (the latest question).</p>
 */
public final class AimCheck {
    /** Nothing in the way. */
    public static final int FREE = 0;
    public static final int CLAIM = 1;
    public static final int SPAWN = 2;
    public static final int BORDER = 3;

    /** The latest question of each player since the last tick. */
    private static final Map<UUID, Aim> ASKED = new HashMap<>();

    private AimCheck() {}

    /** Client to server: what would stop an edit at this spot? {@code hidden}: a hidden block is aimed at. */
    public record Aim(BlockPos pos, boolean hidden) implements CustomPacketPayload {
        public static final Type<Aim> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "edit_aim"));
        public static final StreamCodec<FriendlyByteBuf, Aim> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Aim::pos, ByteBufCodecs.BOOL, Aim::hidden, Aim::new);

        @Override
        public Type<Aim> type() {
            return TYPE;
        }
    }

    /** Server to client: the answer for a spot ({@link #FREE}, {@link #CLAIM}, {@link #SPAWN} or {@link #BORDER}). */
    public record Answer(BlockPos pos, boolean hidden, int why) implements CustomPacketPayload {
        public static final Type<Answer> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "edit_aim_answer"));
        public static final StreamCodec<FriendlyByteBuf, Answer> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Answer::pos, ByteBufCodecs.BOOL, Answer::hidden, ByteBufCodecs.VAR_INT, Answer::why,
                Answer::new);

        @Override
        public Type<Answer> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Aim.TYPE, Aim.STREAM_CODEC.cast());
        PayloadTypeRegistry.clientboundPlay().register(Answer.TYPE, Answer.STREAM_CODEC.cast());
        ServerPlayNetworking.registerGlobalReceiver(Aim.TYPE, (payload, context) -> ASKED.put(context.player().getUUID(), payload));
        ServerTickEvents.END_SERVER_TICK.register(AimCheck::answer);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ASKED.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ASKED.clear());
    }

    private static void answer(MinecraftServer server) {
        if (ASKED.isEmpty()) {
            return;
        }
        ASKED.forEach((id, aim) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && Access.mayEdit(player) && player.isWithinBlockInteractionRange(aim.pos(), 2.0)) {
                ServerPlayNetworking.send(player, new Answer(aim.pos(), aim.hidden(), why(player, aim.pos(), aim.hidden())));
            }
        });
        ASKED.clear();
    }

    /**
     * What would refuse an edit at this spot of the player's (visible) level: the world border, spawn protection, a
     * claim (breaking what is hidden there, or placing there if nothing hidden is aimed at), or nothing.
     */
    public static int why(ServerPlayer player, BlockPos pos, boolean hidden) {
        ServerLevel level = player.level();
        if (!level.getWorldBorder().isWithinBounds(pos)) {
            return BORDER;
        }
        if (level.getServer().isUnderSpawnProtection(level, pos, player)) {
            return SPAWN;
        }
        boolean allowed = hidden
                ? CommonProtection.canBreakBlock(level, pos, player.nameAndId(), player)
                : CommonProtection.canPlaceBlock(level, pos, player.nameAndId(), player);
        return allowed ? FREE : CLAIM;
    }

    /** The short reason, as the readout and the action bar say it ("protected by a claim"...). */
    public static Component reason(int why) {
        return Component.translatable(switch (why) {
            case CLAIM -> "phasecraft.edit.protected.claim";
            case SPAWN -> "phasecraft.edit.protected.spawn";
            default -> "phasecraft.edit.protected.border";
        });
    }

    /** A refused edit: the reason in the action bar, and a dispenser's failing click that only this player hears. */
    public static void tellRefused(ServerPlayer player, int why) {
        player.sendSystemMessage(reason(why).copy().withStyle(ChatFormatting.RED), true);
        player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.DISPENSER_FAIL),
                SoundSource.BLOCKS, player.getX(), player.getEyeY(), player.getZ(), 0.6F, 1.2F, player.getRandom().nextLong()));
    }
}
