package dev.phasecraft.edit;

import dev.phasecraft.layer.HiddenDimensions;
import eu.pb4.common.protection.api.CommonProtection;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Claims and spawn protection, for a player standing inside the hidden layer. The key K lets every Creative
 * player in, not only operators, and in there they place and break as vanilla does, in a dimension no claim mod
 * knows: so what editing mode asks before a hidden edit ({@link Protection}, {@code HiddenEditing.mayInteract}) is
 * asked here too, for the visible twin of the level and the same spot. A refused click does nothing, and the
 * player is told why ({@link AimCheck#tellRefused}).
 *
 * <ul>
 *   <li>Breaking a hidden block: {@code canBreakBlock} at its spot.</li>
 *   <li>Clicking a hidden block: {@code canInteractBlock} at its spot, and with something in hand
 *       {@code canPlaceBlock} at that spot and the one beside it (where a block would go), as editing mode asks
 *       for any item.</li>
 *   <li>A bucket: {@code canBreakBlock} and {@code canPlaceBlock} at the spot aimed at and the one beside it.</li>
 * </ul>
 * These are the player's own clicks in their own level, heard through Fabric's events for exactly those; nothing
 * is fired for a block nobody clicked. Spectators click nothing. With no protection mod, away from spawn and
 * inside the border, every answer is yes.
 */
public final class InsideProtection {
    private InsideProtection() {}

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            ServerPlayer inside = inside(player, level);
            return inside == null || allowed(inside, pos, true, false, false);
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            ServerPlayer inside = inside(player, level);
            if (inside == null || player.isSpectator()) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hit.getBlockPos();
            boolean holding = !player.getItemInHand(hand).isEmpty();
            boolean allowed = allowed(inside, pos, false, holding, true)
                    && (!holding || allowed(inside, pos.relative(hit.getDirection()), false, true, false));
            return allowed ? InteractionResult.PASS : InteractionResult.FAIL;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ServerPlayer inside = inside(player, level);
            if (inside == null || !(player.getItemInHand(hand).getItem() instanceof BucketItem)) {
                return InteractionResult.PASS;
            }
            // Where the bucket would scoop or pour: what vanilla's own ray finds, fluids included.
            Vec3 from = player.getEyePosition();
            Vec3 to = from.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
            BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
            if (hit.getType() != HitResult.Type.BLOCK) {
                return InteractionResult.PASS;
            }
            boolean allowed = allowed(inside, hit.getBlockPos(), true, true, false)
                    && allowed(inside, hit.getBlockPos().relative(hit.getDirection()), false, true, false);
            return allowed ? InteractionResult.PASS : InteractionResult.FAIL;
        });
    }

    /** The player, if they are on the server and standing in this hidden level; else null. */
    private static @Nullable ServerPlayer inside(Player player, Level level) {
        return player instanceof ServerPlayer server && server.level() == level && HiddenDimensions.isHidden(level.dimension())
                ? server : null;
    }

    /** Whether the visible world's protection at this spot lets the player break, place or use there; if not, tells them. */
    private static boolean allowed(ServerPlayer player, BlockPos pos, boolean breaking, boolean placing, boolean using) {
        ServerLevel visible = HiddenDimensions.visibleOf(player.level().dimension()).map(player.level().getServer()::getLevel).orElse(null);
        if (visible == null) {
            return true;
        }
        int why = AimCheck.FREE;
        if (!visible.getWorldBorder().isWithinBounds(pos)) {
            why = AimCheck.BORDER;
        } else if (visible.getServer().isUnderSpawnProtection(visible, pos, player)) {
            why = AimCheck.SPAWN;
        } else if ((breaking && !CommonProtection.canBreakBlock(visible, pos, player.nameAndId(), player))
                || (placing && !CommonProtection.canPlaceBlock(visible, pos, player.nameAndId(), player))
                || (using && !CommonProtection.canInteractBlock(visible, pos, player.nameAndId(), player))) {
            why = AimCheck.CLAIM;
        }
        if (why != AimCheck.FREE) {
            AimCheck.tellRefused(player, why);
        }
        return why == AimCheck.FREE;
    }
}
