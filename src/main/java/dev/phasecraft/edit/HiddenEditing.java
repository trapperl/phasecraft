package dev.phasecraft.edit;

import dev.phasecraft.PhaseCraft;
import dev.phasecraft.layer.HiddenDimensions;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Editing mode, server side: a Creative player in the visible world places,
 * uses and breaks blocks in the hidden layer at the same coordinates.
 *
 * <p>The client (key J) sends what it aims at:</p>
 * <ul>
 *   <li>{@link Use}: right click. The click is carried out in the hidden
 *       level, by vanilla rules: on a hidden block it uses that block (flips a
 *       lever, sets a repeater) or places against it; aimed at a visible block
 *       with nothing hidden there, the hidden block goes at that same spot
 *       (hidden air is replaceable), overlapping the visible block.</li>
 *   <li>{@link Break}: left click on a hidden block removes it. Visible blocks
 *       are never touched.</li>
 *   <li>{@link Pick}: middle click on a hidden block puts that block in your
 *       hand, as vanilla's pick does for visible blocks.</li>
 * </ul>
 * <p>Requests are checked as vanilla checks clicks: Creative, within reach,
 * hit point on the block, in a visible dimension with a hidden twin.</p>
 */
public final class HiddenEditing {
    private HiddenEditing() {}

    public record Use(InteractionHand hand, BlockHitResult hit) implements CustomPacketPayload {
        public static final Type<Use> TYPE = new Type<>(id("edit_use"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Use> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.idMapper(index -> InteractionHand.values()[index], InteractionHand::ordinal), Use::hand,
                BlockHitResult.STREAM_CODEC.<RegistryFriendlyByteBuf>cast(), Use::hit,
                Use::new);

        @Override
        public Type<Use> type() {
            return TYPE;
        }
    }

    public record Break(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Break> TYPE = new Type<>(id("edit_break"));
        public static final StreamCodec<FriendlyByteBuf, Break> STREAM_CODEC =
                BlockPos.STREAM_CODEC.<FriendlyByteBuf>cast().map(Break::new, Break::pos);

        @Override
        public Type<Break> type() {
            return TYPE;
        }
    }

    /** Middle click. (Block entity data, vanilla's Ctrl+pick, is not copied: hidden chests are picked empty.) */
    public record Pick(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Pick> TYPE = new Type<>(id("edit_pick"));
        public static final StreamCodec<FriendlyByteBuf, Pick> STREAM_CODEC =
                BlockPos.STREAM_CODEC.<FriendlyByteBuf>cast().map(Pick::new, Pick::pos);

        @Override
        public Type<Pick> type() {
            return TYPE;
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, path);
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Use.TYPE, Use.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Break.TYPE, Break.STREAM_CODEC.cast());
        PayloadTypeRegistry.serverboundPlay().register(Pick.TYPE, Pick.STREAM_CODEC.cast());
        ServerPlayNetworking.registerGlobalReceiver(Use.TYPE, (payload, context) -> use(context.player(), payload.hand(), payload.hit()));
        ServerPlayNetworking.registerGlobalReceiver(Break.TYPE, (payload, context) -> breakBlock(context.player(), payload.pos()));
        ServerPlayNetworking.registerGlobalReceiver(Pick.TYPE, (payload, context) -> pick(context.player(), payload.pos()));
    }

    /** The hidden level a player may edit from where they are, or null. */
    private static @Nullable ServerLevel editableHiddenLevel(ServerPlayer player) {
        if (!player.isCreative()) {
            return null;
        }
        Optional<ResourceKey<Level>> hidden = HiddenDimensions.hiddenOf(player.level().dimension());
        return hidden.map(key -> player.level().getServer().getLevel(key)).orElse(null);
    }

    /** Right click in editing mode. Returns the result of the hidden click (PASS if refused). */
    public static InteractionResult use(ServerPlayer player, InteractionHand hand, BlockHitResult hit) {
        ServerLevel hidden = editableHiddenLevel(player);
        BlockPos pos = hit.getBlockPos();
        if (hidden == null || !player.isWithinBlockInteractionRange(pos, 1.0) || !hidden.isInWorldBounds(pos)) {
            return InteractionResult.PASS;
        }
        Vec3 offset = hit.getLocation().subtract(Vec3.atCenterOf(pos));
        if (Math.abs(offset.x()) >= 1.0000001 || Math.abs(offset.y()) >= 1.0000001 || Math.abs(offset.z()) >= 1.0000001) {
            return InteractionResult.PASS;
        }
        player.resetLastActionTime();

        // Vanilla's ServerPlayerGameMode.useItemOn, but with the hidden level
        // (vanilla's UseOnContext would take the player's own, visible level).
        ItemStack stack = player.getItemInHand(hand);
        BlockState state = hidden.getBlockState(pos);
        boolean holdingSomething = !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty();
        if (!(player.isSecondaryUseActive() && holdingSomething)) {
            InteractionResult itemOnBlock = state.useItemOn(stack, hidden, player, hand, hit);
            if (itemOnBlock.consumesAction()) {
                return itemOnBlock;
            }
            if (itemOnBlock instanceof InteractionResult.TryEmptyHandInteraction && hand == InteractionHand.MAIN_HAND) {
                InteractionResult emptyHand = state.useWithoutItem(hidden, player, hit);
                if (emptyHand.consumesAction()) {
                    return emptyHand;
                }
            }
        }
        if (stack.isEmpty() || player.getCooldowns().isOnCooldown(stack)) {
            return InteractionResult.PASS;
        }
        int count = stack.getCount();
        InteractionResult result = stack.useOn(new UseOnContext(hidden, player, hand, stack, hit));
        stack.setCount(count); // Creative: infinite materials
        if (result.consumesAction()) {
            // Placed at the clicked spot if that changed (it was hidden air), else next to it.
            BlockPos placed = hidden.getBlockState(pos) != state ? pos : pos.relative(hit.getDirection());
            playPlaceSound(player, hidden.getBlockState(placed), placed);
        }
        return result;
    }

    /** Left click on a hidden block in editing mode. Returns whether a hidden block was removed. */
    public static boolean breakBlock(ServerPlayer player, BlockPos pos) {
        ServerLevel hidden = editableHiddenLevel(player);
        if (hidden == null || !player.isWithinBlockInteractionRange(pos, 1.0)) {
            return false;
        }
        BlockState state = hidden.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        player.resetLastActionTime();
        state.getBlock().playerWillDestroy(hidden, pos, state, player);
        boolean removed = hidden.removeBlock(pos, false);
        if (removed) {
            state.getBlock().destroy(hidden, pos, state);
            SoundType sound = state.getSoundType();
            // The hidden level has no listeners; play it where the player is.
            player.level().playSound(null, pos, sound.getBreakSound(), SoundSource.BLOCKS,
                    (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        }
        return removed;
    }

    /**
     * Middle click on a hidden block: that block's item into the hand, as
     * vanilla's pick (ServerGamePacketListenerImpl.tryPickItem). Returns
     * whether something was picked.
     */
    public static boolean pick(ServerPlayer player, BlockPos pos) {
        ServerLevel hidden = editableHiddenLevel(player);
        if (hidden == null || !player.isWithinBlockInteractionRange(pos, 1.0) || !hidden.isLoaded(pos)) {
            return false;
        }
        ItemStack stack = hidden.getBlockState(pos).getCloneItemStack(hidden, pos, false);
        if (stack.isEmpty() || !stack.isItemEnabled(hidden.enabledFeatures())) {
            return false;
        }
        Inventory inventory = player.getInventory();
        int existing = inventory.findSlotMatchingItem(stack);
        if (existing != -1) {
            if (Inventory.isHotbarSlot(existing)) {
                inventory.setSelectedSlot(existing);
            } else {
                inventory.pickSlot(existing);
            }
        } else {
            inventory.addAndPickItem(stack);
        }
        player.connection.send(new ClientboundSetHeldSlotPacket(inventory.getSelectedSlot()));
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    private static void playPlaceSound(ServerPlayer player, BlockState state, BlockPos pos) {
        if (state.isAir()) {
            return;
        }
        SoundType sound = state.getSoundType();
        // The hidden level has no listeners; play it where the player is.
        player.level().playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
    }
}
