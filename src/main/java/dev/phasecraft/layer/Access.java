package dev.phasecraft.layer;

import com.mojang.serialization.Codec;
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
import net.minecraft.gametest.framework.GameTestServer;
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
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * Who may see the hidden layer (the overlay) and edit it (editing mode).
 *
 * <ul>
 *   <li>Creative: always. Spectator: may see, not edit.</li>
 *   <li>Survival: while the game rule {@code phasecraft:survival_access} is
 *       on, by Survival rules (see HiddenEditing). On in single player and
 *       on LAN; a dedicated server starts with it off
 *       ({@link #applyServerDefault}): on a public server it lets every
 *       Survival player see every hidden build near them, open other
 *       people's hidden chests and build inside their walls, past claim
 *       mods that only watch the visible world.</li>
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

    /**
     * Kept with the world (data/phasecraft/server_defaults.dat): whether a dedicated
     * server has already been started on it with survival_access switched off.
     */
    public static final class ServerDefaults extends SavedData {
        private static final Codec<ServerDefaults> CODEC = Codec.BOOL.fieldOf("survival_access_off").codec()
                .xmap(ServerDefaults::new, defaults -> defaults.survivalAccessOff);
        private static final SavedDataType<ServerDefaults> TYPE = new SavedDataType<>(
                Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, "server_defaults"), ServerDefaults::new, CODEC, null);

        private boolean survivalAccessOff;

        public ServerDefaults() {}

        private ServerDefaults(boolean survivalAccessOff) {
            this.survivalAccessOff = survivalAccessOff;
        }
    }

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
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                applyServerDefault(server, isDedicatedForPlayers(server), server.overworld().getDataStorage()));
    }

    /**
     * Whether this is a dedicated server people play on: not single player or LAN,
     * and not the GameTest server either, which is no place to play and which
     * Fabric's GameTest API makes report itself as dedicated (the tests are to run
     * as single player does, with the rule at its default).
     */
    public static boolean isDedicatedForPlayers(MinecraftServer server) {
        return server.isDedicatedServer() && !(server instanceof GameTestServer);
    }

    /**
     * A dedicated server starts with survival_access off (the person, 2026-11-27).
     * A game rule has one default for every kind of server, and that one is on, for
     * single player and LAN; so the first time a dedicated server starts a world
     * with this version, the rule is set to false, once, the world remembers that
     * ({@link ServerDefaults}), and the log says how to turn it on. After that the
     * setting is the admin's. A world already run on a dedicated server by an
     * earlier version is switched off once as well: a rule left at its default
     * cannot be told from one set on purpose. Returns whether the rule was set.
     *
     * @param dedicated {@link #isDedicatedForPlayers} (tests pass it: the GameTest server does not count)
     * @param storage the overworld's saved data, where the world remembers
     */
    public static boolean applyServerDefault(MinecraftServer server, boolean dedicated, SavedDataStorage storage) {
        if (!dedicated) {
            return false;
        }
        ServerDefaults defaults = storage.computeIfAbsent(ServerDefaults.TYPE);
        if (defaults.survivalAccessOff) {
            return false;
        }
        server.getGameRules().set(SURVIVAL_ACCESS, false, server);
        defaults.survivalAccessOff = true;
        defaults.setDirty();
        PhaseCraft.LOGGER.info("Dedicated server: the game rule phasecraft:survival_access was set to false, once for this "
                + "world, so Survival players cannot see or edit the hidden layer (each other's hidden builds and chests). "
                + "To let them: /gamerule phasecraft:survival_access true");
        return true;
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
