package dev.phasecraft.layer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps people from ending up in the hidden layer by accident.
 *
 * <ul>
 *   <li>Only Creative and Spectator players may be in the hidden layer (it has
 *       no ground). Any other player found there, however they got there
 *       (game mode changed while inside, a forced game mode on login, a
 *       teleport), is moved to the visible layer at the same spot, or the
 *       first free spot above it.</li>
 *   <li>No respawn point can be set in the hidden layer (a bed or respawn
 *       anchor used there); see {@code ServerPlayerRespawnMixin}.</li>
 *   <li>Portals in the hidden layer do nothing (they would lead into the real
 *       Nether or End and build a visible portal there); see
 *       {@code EntityPortalMixin}.</li>
 *   <li>No mob is born in the hidden layer ({@link #mayBeBorn}; see
 *       {@code EntityTypeHiddenSpawnMixin}): no spawners, trial spawners,
 *       spawn eggs, dispensers, breeding, buckets, raids, golems built from
 *       blocks. A hidden mob would be invisible from the visible world, keep
 *       its hidden chunk loaded and ticking, and could stand on hidden
 *       pressure plates. (Natural spawning finds nothing to spawn anyway:
 *       the hidden dimensions use the void biome.)</li>
 * </ul>
 */
public final class HiddenLayerGuard {
    /** How far up to look for a free spot when moving a player out. */
    private static final int MAX_LIFT = 32;

    private HiddenLayerGuard() {}

    /**
     * Whether a new entity may come into being: anything but a mob, anywhere; a mob in the hidden layer only when
     * loaded from a save, put there by a command (/summon, or a mod's code that says so), changed from another mob
     * already there (a zombie drowning), or brought by dimension travel.
     */
    public static boolean mayBeBorn(Level level, EntitySpawnReason reason, @Nullable Entity entity) {
        if (!(entity instanceof Mob) || !(level instanceof ServerLevel) || !HiddenDimensions.isHidden(level.dimension())) {
            return true;
        }
        return switch (reason) {
            case LOAD, COMMAND, CONVERSION, DIMENSION_TRAVEL -> true;
            default -> false;
        };
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(HiddenLayerGuard::tick);
    }

    private static void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!mayBeInHiddenLayer(player) && HiddenDimensions.isHidden(player.level().dimension())) {
                moveOut(player);
            }
        }
    }

    public static boolean mayBeInHiddenLayer(ServerPlayer player) {
        return player.isCreative() || player.isSpectator();
    }

    /** True if an entity is in a hidden layer, where portals do nothing. */
    public static boolean inHiddenLayer(Entity entity) {
        return entity.level() instanceof ServerLevel level && HiddenDimensions.isHidden(level.dimension());
    }

    private static void moveOut(ServerPlayer player) {
        ServerLevel visible = HiddenLayer.of(player.level()).visibleLevel();
        Vec3 spot = freeSpotAbove(visible, player, player.position());
        player.teleport(new TeleportTransition(visible, spot, Vec3.ZERO, player.getYRot(), player.getXRot(),
                TeleportTransition.DO_NOTHING));
        player.resetFallDistance();
        player.sendSystemMessage(Component.translatable("phasecraft.guard.moved_out"));
    }

    /** The spot itself if the player fits there in the visible level, else the first whole-block height above that does. */
    static Vec3 freeSpotAbove(ServerLevel visible, ServerPlayer player, Vec3 spot) {
        for (int lift = 0; lift <= MAX_LIFT; lift++) {
            Vec3 candidate = lift == 0 ? spot : new Vec3(spot.x, Math.floor(spot.y) + lift, spot.z);
            if (candidate.y > visible.getMaxY()) {
                break;
            }
            if (visible.noCollision(player, player.getDimensions(player.getPose()).makeBoundingBox(candidate))) {
                return candidate;
            }
        }
        return spot;
    }
}
