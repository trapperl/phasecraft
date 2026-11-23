package dev.phasecraft.layer;

import dev.phasecraft.PhaseCraft;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

/**
 * Who may see the hidden layer (the overlay) and edit it (editing mode).
 *
 * <ul>
 *   <li>Creative: always. Spectator: may see, not edit.</li>
 *   <li>Survival: while the game rule {@code phasecraft:survival_access} is
 *       on (the default), by Survival rules (see HiddenEditing). A server can
 *       turn it off: on a public server, Survival editing lets players build
 *       inside other people's walls, past claim mods that only watch the
 *       visible world.</li>
 *   <li>Adventure: never.</li>
 * </ul>
 * Going into the hidden layer itself stays Creative/Spectator only
 * ({@link HiddenLayerGuard}). Each client is told whether it may edit
 * ({@link Allowed}), so it can say so when J is pressed.
 */
public final class Access {
    public static final GameRule<Boolean> SURVIVAL_ACCESS = GameRuleBuilder.forBoolean(true)
            .category(GameRuleCategory.PLAYER)
            .buildAndRegister(Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "survival_access"));

    /** What each player's client was last told. */
    private static final Map<UUID, Boolean> TOLD = new HashMap<>();

    private Access() {}

    /** Whether this player's client may use editing mode. */
    public record Allowed(boolean edit) implements CustomPacketPayload {
        public static final Type<Allowed> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "access"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Allowed> STREAM_CODEC =
                ByteBufCodecs.BOOL.<RegistryFriendlyByteBuf>cast().map(Allowed::new, Allowed::edit);

        @Override
        public Type<Allowed> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Allowed.TYPE, Allowed.STREAM_CODEC);
        ServerTickEvents.END_SERVER_TICK.register(Access::tell);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> TOLD.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> TOLD.clear());
    }

    private static boolean survivalAllowed(ServerPlayer player) {
        return ((ServerLevel) player.level()).getGameRules().get(SURVIVAL_ACCESS);
    }

    /** Whether the player may edit the hidden layer (editing mode). */
    public static boolean mayEdit(ServerPlayer player) {
        return player.isCreative() || (player.gameMode() == GameType.SURVIVAL && survivalAllowed(player));
    }

    /** Whether the player may see the hidden layer (the overlay). */
    public static boolean maySeeOverlay(ServerPlayer player) {
        return player.isSpectator() || mayEdit(player);
    }

    private static void tell(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean edit = mayEdit(player);
            if (!Boolean.valueOf(edit).equals(TOLD.get(player.getUUID())) && ServerPlayNetworking.canSend(player, Allowed.TYPE)) {
                ServerPlayNetworking.send(player, new Allowed(edit));
                TOLD.put(player.getUUID(), edit);
            }
        }
    }
}
