package dev.phasecraft.layer;

import dev.phasecraft.PhaseCraft;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Moving a player between the visible and the hidden layer, at the same spot: what
 * {@code /phasecraft layer hidden|visible} does, and what the client's key (; by default) asks for with {@link Toggle}.
 *
 * <p>Going in is for Creative and Spectator players only, whoever asks and however: the hidden layer has no ground,
 * and {@link HiddenLayerGuard} would move anyone else straight out again. The key needs no cheats and no operator
 * level: a Creative player may build anywhere already, and what a Spectator could see there the overlay shows them
 * anyway. Where the visible world is protected from a player (a claim, spawn protection), so is the hidden layer
 * they stand in ({@code InsideProtection}). Going out is always allowed.</p>
 */
public final class LayerSwitch {
    private LayerSwitch() {}

    /** The client's key: go to the other layer. The server decides ({@link #toggle}). */
    public record Toggle() implements CustomPacketPayload {
        public static final Type<Toggle> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "layer_toggle"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Toggle> STREAM_CODEC = StreamCodec.unit(new Toggle());

        @Override
        public Type<Toggle> type() {
            return TYPE;
        }
    }

    public enum Result {
        /** Now in the hidden layer. */
        ENTERED,
        /** Now in the visible layer. */
        LEFT,
        /** Already in the layer asked for. */
        ALREADY_THERE,
        /** Not Creative or Spectator: may not go in. */
        WRONG_GAME_MODE,
        /** This dimension has no twin. */
        NO_LAYER
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Toggle.TYPE, Toggle.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Toggle.TYPE, (payload, context) -> toggle(context.player()));
    }

    /** Whether a player may go into the hidden layer: Creative or Spectator, as the command has always asked. */
    public static boolean mayEnter(ServerPlayer player) {
        GameType mode = player.gameMode();
        return mode == GameType.CREATIVE || mode == GameType.SPECTATOR;
    }

    /** The key: to the other layer, with a line above the hotbar saying what happened. */
    public static Result toggle(ServerPlayer player) {
        Result result = move(player, !HiddenDimensions.isHidden(player.level().dimension()));
        // The keys by name as the player has bound them (a keybind component is spelled out on the client).
        player.sendOverlayMessage(Component.translatable(switch (result) {
            case ENTERED -> "phasecraft.layer.entered";
            case LEFT, ALREADY_THERE -> "phasecraft.layer.left";
            case WRONG_GAME_MODE -> "phasecraft.layer.refused";
            case NO_LAYER -> "phasecraft.layer.none";
        }, Component.keybind("key.phasecraft.toggle_layer"), Component.keybind("key.phasecraft.toggle_overlay")));
        return result;
    }

    /** Moves the player to the hidden (or back to the visible) layer at the same spot, looking the same way. */
    public static Result move(ServerPlayer player, boolean toHidden) {
        HiddenLayer layer;
        try {
            layer = HiddenLayer.of(player.level());
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
            return Result.NO_LAYER;
        }
        if (HiddenDimensions.isHidden(player.level().dimension()) == toHidden) {
            return Result.ALREADY_THERE;
        }
        if (toHidden && !mayEnter(player)) {
            return Result.WRONG_GAME_MODE;
        }
        ServerLevel target = toHidden ? layer.level() : layer.visibleLevel();
        player.teleport(new TeleportTransition(target, player.position(), Vec3.ZERO,
                player.getYRot(), player.getXRot(), TeleportTransition.DO_NOTHING));
        if (toHidden && player.getAbilities().mayfly && !player.getAbilities().flying) {
            // No ground in there: a Creative player who walked in would drop into the void.
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }
        return toHidden ? Result.ENTERED : Result.LEFT;
    }
}
