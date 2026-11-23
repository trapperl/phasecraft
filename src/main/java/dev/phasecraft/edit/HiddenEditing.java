package dev.phasecraft.edit;

import dev.phasecraft.PhaseCraft;
import dev.phasecraft.layer.Access;
import dev.phasecraft.layer.HiddenDimensions;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Editing mode, server side: a player in the visible world places, uses and
 * breaks blocks in the hidden layer at the same coordinates. Who may: see
 * {@link Access} (Creative; Survival while the game rule is on). Survival
 * plays by Survival rules: placing uses up the item, breaking takes the
 * block's mining time ({@link StartBreak}, then {@link Break}) and drops it in
 * the visible world, middle click only selects what is already carried.
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
 * <p>Requests are checked as vanilla checks clicks: allowed to edit, within reach,
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

    /** Survival: the player started mining a hidden block (left button down on it). */
    public record StartBreak(BlockPos pos) implements CustomPacketPayload {
        public static final Type<StartBreak> TYPE = new Type<>(id("edit_start_break"));
        public static final StreamCodec<FriendlyByteBuf, StartBreak> STREAM_CODEC =
                BlockPos.STREAM_CODEC.<FriendlyByteBuf>cast().map(StartBreak::new, StartBreak::pos);

        @Override
        public Type<StartBreak> type() {
            return TYPE;
        }
    }

    /** Where and when each Survival player started mining a hidden block. */
    private record Mining(BlockPos pos, int startTick) {}

    private static final Map<UUID, Mining> MINING = new HashMap<>();

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(PhaseCraft.MOD_ID, path);
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Use.TYPE, Use.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Break.TYPE, Break.STREAM_CODEC.cast());
        PayloadTypeRegistry.serverboundPlay().register(Pick.TYPE, Pick.STREAM_CODEC.cast());
        PayloadTypeRegistry.serverboundPlay().register(StartBreak.TYPE, StartBreak.STREAM_CODEC.cast());
        ServerPlayNetworking.registerGlobalReceiver(StartBreak.TYPE, (payload, context) -> startBreaking(context.player(), payload.pos()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> MINING.remove(handler.player.getUUID()));
        ServerPlayNetworking.registerGlobalReceiver(Use.TYPE, (payload, context) -> use(context.player(), payload.hand(), payload.hit()));
        ServerPlayNetworking.registerGlobalReceiver(Break.TYPE, (payload, context) -> breakBlock(context.player(), payload.pos()));
        ServerPlayNetworking.registerGlobalReceiver(Pick.TYPE, (payload, context) -> pick(context.player(), payload.pos()));
    }

    /** The hidden level a player may edit from where they are, or null. */
    private static @Nullable ServerLevel editableHiddenLevel(ServerPlayer player) {
        if (!Access.mayEdit(player)) {
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
        if (stack.getItem() instanceof BucketItem bucket) {
            return useBucket(player, hand, hidden, bucket, stack, pos, hit);
        }
        int count = stack.getCount();
        InteractionResult result = stack.useOn(new UseOnContext(hidden, player, hand, stack, hit));
        if (player.hasInfiniteMaterials()) {
            stack.setCount(count); // Creative: infinite materials; Survival uses the item up
        }
        if (result.consumesAction()) {
            // Placed at the clicked spot if that changed (it was hidden air), else next to it.
            BlockPos placed = hidden.getBlockState(pos) != state ? pos : pos.relative(hit.getDirection());
            playPlaceSound(player, hidden.getBlockState(placed), placed);
        }
        return result;
    }

    /**
     * A bucket in editing mode, as vanilla's BucketItem.use but at the editing target
     * instead of along the player's view in their own level. Empty: scoops up hidden
     * fluid (or a waterlogged hidden block's water). Full: pours into the clicked spot
     * when nothing is hidden there (as blocks are placed), into a waterloggable hidden
     * block, or next to the hidden block clicked. Survival swaps buckets as vanilla
     * does; Creative keeps the bucket. Fish buckets are refused: the fish would be
     * let out in the hidden layer.
     */
    private static InteractionResult useBucket(ServerPlayer player, InteractionHand hand, ServerLevel hidden, BucketItem bucket,
            ItemStack stack, BlockPos pos, BlockHitResult hit) {
        if (bucket instanceof MobBucketItem) {
            return InteractionResult.PASS;
        }
        BlockState state = hidden.getBlockState(pos);
        if (bucket.getContent() == Fluids.EMPTY) {
            if (state.getBlock() instanceof BucketPickup pickup) {
                ItemStack filled = pickup.pickupBlock(player, hidden, pos, state);
                if (!filled.isEmpty()) {
                    player.awardStat(Stats.ITEM_USED.get(bucket));
                    pickup.getPickupSound().ifPresent(sound -> player.level().playSound(null, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F));
                    player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, filled));
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.FAIL;
        }
        boolean intoBlock = state.getBlock() instanceof LiquidBlockContainer && bucket.getContent() == Fluids.WATER;
        BlockPos target = state.isAir() || intoBlock ? pos : pos.relative(hit.getDirection());
        if (!bucket.emptyContents(player, hidden, target, hit)) {
            return InteractionResult.FAIL;
        }
        player.awardStat(Stats.ITEM_USED.get(bucket));
        player.level().playSound(null, target, bucket.getContent().is(FluidTags.LAVA) ? SoundEvents.BUCKET_EMPTY_LAVA : SoundEvents.BUCKET_EMPTY,
                SoundSource.BLOCKS, 1.0F, 1.0F);
        player.setItemInHand(hand, BucketItem.getEmptySuccessItem(stack, player));
        return InteractionResult.SUCCESS;
    }

    /** Survival: the player started mining a hidden block; breaking it is allowed once its mining time has passed. */
    public static void startBreaking(ServerPlayer player, BlockPos pos) {
        ServerLevel hidden = editableHiddenLevel(player);
        if (hidden != null && player.isWithinBlockInteractionRange(pos, 1.0)) {
            MINING.put(player.getUUID(), new Mining(pos.immutable(), player.level().getServer().getTickCount()));
        }
    }

    /**
     * Whether a Survival player has mined long enough: vanilla's own measure
     * (the block's destroy progress per tick for this player and tool), with
     * vanilla's server-side allowance of 70% for lag.
     */
    private static boolean minedLongEnough(ServerPlayer player, ServerLevel hidden, BlockPos pos, BlockState state) {
        Mining mining = MINING.get(player.getUUID());
        if (mining == null || !mining.pos().equals(pos)) {
            return false;
        }
        float perTick = state.getDestroyProgress(player, hidden, pos);
        int ticks = player.level().getServer().getTickCount() - mining.startTick() + 1;
        return perTick > 0 && perTick * ticks >= 0.7F;
    }

    /**
     * Left click on a hidden block in editing mode. Creative: removed at once.
     * Survival: only after the block's mining time ({@link #startBreaking}); it
     * drops (in the visible world, where the player can pick it up) if the tool
     * is right for it, and the tool wears, as in vanilla. Returns whether a
     * hidden block was removed.
     */
    public static boolean breakBlock(ServerPlayer player, BlockPos pos) {
        ServerLevel hidden = editableHiddenLevel(player);
        if (hidden == null || !player.isWithinBlockInteractionRange(pos, 1.0)) {
            return false;
        }
        BlockState state = hidden.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        boolean survival = !player.hasInfiniteMaterials();
        if (survival && !minedLongEnough(player, hidden, pos, state)) {
            return false;
        }
        MINING.remove(player.getUUID());
        player.resetLastActionTime();
        BlockEntity blockEntity = hidden.getBlockEntity(pos);
        ItemStack tool = player.getMainHandItem();
        ItemStack toolBefore = tool.copy();
        boolean harvest = survival && player.hasCorrectToolForDrops(state);
        state.getBlock().playerWillDestroy(hidden, pos, state, player);
        boolean removed = hidden.removeBlock(pos, false);
        if (removed) {
            state.getBlock().destroy(hidden, pos, state);
            if (survival) {
                tool.mineBlock(hidden, state, pos, player);
                player.awardStat(Stats.BLOCK_MINED.get(state.getBlock()));
                player.causeFoodExhaustion(0.005F);
                if (harvest) {
                    for (ItemStack drop : Block.getDrops(state, hidden, pos, blockEntity, player, toolBefore)) {
                        Block.popResource(player.level(), pos, drop);
                    }
                }
            }
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
        } else if (player.hasInfiniteMaterials()) {
            inventory.addAndPickItem(stack);
        } else {
            return false; // Survival: only what is already carried
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
