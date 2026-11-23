package dev.phasecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.phasecraft.edit.HiddenEditing;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Editing mode, client side (key J, Creative only): clicks go to the hidden
 * layer instead of the visible world.
 *
 * <p>What you aim at: the nearest hidden block along your view within reach,
 * even inside a wall (as the overlay shows it); if there is none, the visible
 * block you look at, meaning "put a hidden block at this spot". It is
 * outlined in purple, and vanilla's own targeting is switched off so nothing
 * visible is broken or used. The server does the actual work
 * ({@link HiddenEditing}).</p>
 */
public final class ClientEditing {
    /** Outline colour of the edit target: PhaseCraft purple. */
    static final int OUTLINE = 0xFFB04CFF;

    private static boolean editing;
    private static @Nullable Target target;
    /** Whether the server lets this player edit (Creative; Survival while its game rule is on). */
    private static boolean allowed;
    /** Survival: the hidden block being mined, and how far (1 = mined). */
    private static @Nullable BlockPos mining;
    private static float progress;

    private ClientEditing() {}

    /** What a click would act on. {@code hidden}: a hidden block; otherwise a visible block's spot. */
    public record Target(BlockHitResult hit, boolean hidden) {}

    public static boolean editing() {
        return editing;
    }

    public static @Nullable Target target() {
        return target;
    }

    /** Editing needs a player the server lets edit; losing that (game mode, game rule) ends it. */
    public static boolean active(Minecraft minecraft) {
        return editing && minecraft.player != null && mayEdit(minecraft.player);
    }

    private static boolean mayEdit(LocalPlayer player) {
        return allowed || player.isCreative();
    }

    static void setAllowed(boolean value) {
        allowed = value;
    }

    static void toggle(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        if (!editing && !mayEdit(player)) {
            player.sendOverlayMessage(Component.translatable("phasecraft.edit.not_allowed"));
            return;
        }
        editing = !editing;
        target = null;
        player.sendOverlayMessage(Component.translatable(editing ? "phasecraft.edit.on" : "phasecraft.edit.off"));
    }

    static void reset() {
        editing = false;
        target = null;
        allowed = false;
        mining = null;
        progress = 0;
    }

    /** After vanilla picks what the crosshair is on: pick in the hidden layer instead, and hide vanilla's pick. */
    public static void afterPick(Minecraft minecraft, float partialTicks) {
        if (!active(minecraft)) {
            target = null;
            return;
        }
        LocalPlayer player = minecraft.player;
        Vec3 from = player.getEyePosition(partialTicks);
        Vec3 to = from.add(player.getViewVector(partialTicks).scale(player.blockInteractionRange()));
        target = pick(minecraft, from, to);
        minecraft.hitResult = BlockHitResult.miss(to, Direction.getApproximateNearest(from.subtract(to)), BlockPos.containing(to));
        minecraft.crosshairPickEntity = null;
    }

    static @Nullable Target pick(Minecraft minecraft, Vec3 from, Vec3 to) {
        GhostGetter ghosts = new GhostGetter(PhaseCraftClient.layer(), minecraft.level);
        BlockHitResult hidden = BlockGetter.traverseBlocks(from, to, ghosts, (getter, pos) -> {
            BlockState state = getter.getBlockState(pos);
            if (state.isAir()) {
                return null;
            }
            VoxelShape shape = state.getShape(getter, pos);
            if (shape.isEmpty()) {
                shape = Shapes.block(); // shapeless hidden blocks can still be picked
            }
            return getter.clipWithInteractionOverride(from, to, pos.immutable(), shape, state);
        }, getter -> null);
        if (hidden != null) {
            return new Target(hidden, true);
        }
        BlockHitResult visible = minecraft.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
        return visible.getType() == HitResult.Type.BLOCK ? new Target(visible, false) : null;
    }

    /** Left click while editing: break the hidden block aimed at. */
    public static void attack(Minecraft minecraft) {
        if (target != null && target.hidden()) {
            BlockPos pos = target.hit().getBlockPos();
            if (minecraft.player.hasInfiniteMaterials()) {
                ClientPlayNetworking.send(new HiddenEditing.Break(pos));
            } else {
                startMining(minecraft, pos);
            }
            swing(minecraft);
        }
    }

    /**
     * Left button held while editing. Survival: mine the hidden block aimed at,
     * at vanilla's speed for this player and tool, showing vanilla's cracks; the
     * server checks the time again before it lets the block go.
     */
    public static void continueAttack(Minecraft minecraft, boolean down) {
        LocalPlayer player = minecraft.player;
        if (!down || player.hasInfiniteMaterials() || target == null || !target.hidden()) {
            stopMining(minecraft);
            return;
        }
        BlockPos pos = target.hit().getBlockPos();
        if (!pos.equals(mining)) {
            startMining(minecraft, pos);
        }
        GhostGetter ghosts = new GhostGetter(PhaseCraftClient.layer(), minecraft.level);
        progress += ghosts.getBlockState(pos).getDestroyProgress(player, ghosts, pos);
        swing(minecraft);
        if (progress >= 1.0F) {
            ClientPlayNetworking.send(new HiddenEditing.Break(pos));
            stopMining(minecraft);
        } else {
            minecraft.level.destroyBlockProgress(player.getId(), pos, (int) (progress * 10.0F) - 1);
        }
    }

    private static void startMining(Minecraft minecraft, BlockPos pos) {
        stopMining(minecraft);
        mining = pos.immutable();
        progress = 0;
        ClientPlayNetworking.send(new HiddenEditing.StartBreak(mining));
    }

    private static void stopMining(Minecraft minecraft) {
        if (mining != null && minecraft.level != null) {
            minecraft.level.destroyBlockProgress(minecraft.player.getId(), mining, -1);
        }
        mining = null;
        progress = 0;
    }

    private static void swing(Minecraft minecraft) {
        minecraft.player.swing(InteractionHand.MAIN_HAND,
                minecraft.player.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(), false);
    }

    /** Right click while editing: use or place in the hidden layer. */
    public static void use(Minecraft minecraft) {
        if (target != null) {
            ClientPlayNetworking.send(new HiddenEditing.Use(InteractionHand.MAIN_HAND, target.hit()));
            minecraft.player.swing(InteractionHand.MAIN_HAND,
                    minecraft.player.getItemInHand(InteractionHand.MAIN_HAND).getInteractAnimation(), false);
        }
    }

    /**
     * Middle click while editing: a hidden block is picked by the server; a
     * visible block (where a new hidden block would go) by vanilla's pick.
     */
    public static void pick(Minecraft minecraft, boolean includeData) {
        if (target == null) {
            return;
        }
        BlockPos pos = target.hit().getBlockPos();
        if (target.hidden()) {
            ClientPlayNetworking.send(new HiddenEditing.Pick(pos));
        } else {
            minecraft.gameMode.handlePickItemFromBlock(pos, includeData);
        }
    }

    /** Purple outline around the target: the hidden block, or the spot a new hidden block would go. */
    static void submitOutline(LevelRenderContext context) {
        Target current = target;
        Minecraft minecraft = Minecraft.getInstance();
        if (current == null || !active(minecraft)) {
            return;
        }
        BlockPos pos = current.hit().getBlockPos();
        VoxelShape shape = Shapes.block();
        if (current.hidden()) {
            BlockState state = PhaseCraftClient.layer().get(pos);
            VoxelShape own = state.getShape(new GhostGetter(PhaseCraftClient.layer(), minecraft.level), pos);
            if (!own.isEmpty()) {
                shape = own;
            }
        }
        Vec3 camera = context.levelState().cameraRenderState.pos;
        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        context.submitNodeCollector().submitShapeOutline(poseStack, shape, RenderTypes.linesTranslucent(), OUTLINE, 3.0F, true);
        poseStack.popPose();
    }

    /** "Editing: hidden layer" in the top left while editing. */
    static void drawHud(GuiGraphicsExtractor graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!active(minecraft)) {
            return;
        }
        Component label = Component.translatable("phasecraft.edit.hud");
        int width = minecraft.font.width(label);
        graphics.fill(2, 2, 8 + width, 14, 0x80000000);
        graphics.text(minecraft.font, label, 5, 4, OUTLINE, false);
    }

    /** The hidden blocks the client holds, as a BlockGetter (for shapes and raycasts). */
    public record GhostGetter(GhostLayer layer, net.minecraft.world.level.LevelHeightAccessor height) implements BlockGetter {
        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return layer.get(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return layer.get(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return height.getHeight();
        }

        @Override
        public int getMinY() {
            return height.getMinY();
        }
    }
}
