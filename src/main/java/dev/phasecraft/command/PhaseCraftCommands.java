package dev.phasecraft.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.phasecraft.layer.HiddenDimensions;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Operator-only developer commands. They work from either layer.
 *
 * <ul>
 *   <li>{@code /phasecraft set x y z <block>}: place a hidden block (syntax as
 *       in /setblock, e.g. {@code minecraft:repeater[facing=east,delay=3]})
 *       without touching the visible block there.</li>
 *   <li>{@code /phasecraft get x y z} and {@code clear x y z}.</li>
 *   <li>{@code /phasecraft layer hidden|visible}: move yourself to the other
 *       layer at the same spot (Creative or Spectator only: the hidden layer
 *       has no ground).</li>
 * </ul>
 */
public final class PhaseCraftCommands {
    private PhaseCraftCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("phasecraft")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.literal("set")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("block", BlockStateArgument.block(registryAccess))
                                                .executes(PhaseCraftCommands::set))))
                        .then(Commands.literal("get")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(PhaseCraftCommands::get)))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(PhaseCraftCommands::clear)))
                        .then(Commands.literal("layer")
                                .then(Commands.literal("hidden").executes(context -> switchLayer(context, true)))
                                .then(Commands.literal("visible").executes(context -> switchLayer(context, false))))));
    }

    /** The layer pair for where the command runs, or null after reporting why there is none. */
    private static HiddenLayer layer(CommandSourceStack source) {
        try {
            return HiddenLayer.of(source.getLevel());
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
            source.sendFailure(Component.literal("No hidden layer here: " + e.getMessage()));
            return null;
        }
    }

    private static int set(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        HiddenLayer layer = layer(source);
        if (layer == null) {
            return 0;
        }
        BlockPos position = BlockPosArgument.getBlockPos(context, "pos");
        BlockState state = BlockStateArgument.getBlock(context, "block").getState();
        if (!layer.contains(position)) {
            source.sendFailure(Component.literal(position.toShortString() + " is outside the world height ("
                    + layer.level().getMinY() + " to " + layer.level().getMaxY() + ")"));
            return 0;
        }

        layer.setBlock(position, state);
        source.sendSuccess(() -> Component.literal(
                "Hidden layer " + position.toShortString() + " = " + BlockStateParser.serialize(state)
                + " (visible block unchanged)"), false);
        return 1;
    }

    private static int get(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        HiddenLayer layer = layer(source);
        if (layer == null) {
            return 0;
        }
        BlockPos position = BlockPosArgument.getBlockPos(context, "pos");
        String hidden = BlockStateParser.serialize(layer.getBlock(position));
        String visible = BlockStateParser.serialize(layer.visibleLevel().getBlockState(position));
        source.sendSuccess(() -> Component.literal("At " + position.toShortString()
                + " | visible: " + visible + " | hidden: " + hidden), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        HiddenLayer layer = layer(source);
        if (layer == null) {
            return 0;
        }
        BlockPos position = BlockPosArgument.getBlockPos(context, "pos");
        if (!layer.contains(position)) {
            source.sendFailure(Component.literal(position.toShortString() + " is outside the world height"));
            return 0;
        }
        layer.removeBlock(position);
        source.sendSuccess(() -> Component.literal(
                "Cleared hidden layer at " + position.toShortString()), false);
        return 1;
    }

    private static int switchLayer(CommandContext<CommandSourceStack> context, boolean toHidden)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        HiddenLayer layer = layer(source);
        if (layer == null) {
            return 0;
        }
        boolean inHidden = HiddenDimensions.isHidden(player.level().dimension());
        if (inHidden == toHidden) {
            source.sendFailure(Component.literal("You are already in the " + (toHidden ? "hidden" : "visible") + " layer"));
            return 0;
        }
        GameType mode = player.gameMode();
        if (toHidden && mode != GameType.CREATIVE && mode != GameType.SPECTATOR) {
            source.sendFailure(Component.literal(
                    "Switch to Creative or Spectator first: the hidden layer has no ground to stand on"));
            return 0;
        }
        ServerLevel target = toHidden ? layer.level() : layer.visibleLevel();
        Vec3 position = player.position();
        player.teleport(new TeleportTransition(target, position, Vec3.ZERO,
                player.getYRot(), player.getXRot(), TeleportTransition.DO_NOTHING));
        source.sendSuccess(() -> Component.literal((toHidden ? "Now in the hidden layer" : "Back in the visible layer")
                + " at " + BlockPos.containing(position).toShortString()
                + " (" + target.dimension().identifier() + ")"), false);
        return 1;
    }
}
