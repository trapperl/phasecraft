package dev.phasecraft.command;

import com.mojang.brigadier.context.CommandContext;
import dev.phasecraft.layer.PhaseLayerData;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Operator-only developer commands.
 *
 * <p>Use {@code /phasecraft set x y z minecraft:repeater[facing=east,delay=3]}
 * (block syntax as in /setblock) to store a hidden block state without
 * touching the visible block at those coordinates. Block-entity data in
 * {@code {...}} is not stored.</p>
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
                                        .executes(PhaseCraftCommands::clear)))));
    }

    private static int set(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        BlockPos position = BlockPosArgument.getBlockPos(context, "pos");
        BlockState state = BlockStateArgument.getBlock(context, "block").getState();

        PhaseLayerData.get(source.getLevel()).setBlock(position, state);
        source.sendSuccess(() -> Component.literal(
                "Hidden layer " + position.toShortString() + " = " + BlockStateParser.serialize(state)
                + " (overworld unchanged)"), false);
        return 1;
    }

    private static int get(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        BlockPos position = BlockPosArgument.getBlockPos(context, "pos");
        String hidden = BlockStateParser.serialize(PhaseLayerData.get(source.getLevel()).getBlock(position));
        String visible = BlockStateParser.serialize(source.getLevel().getBlockState(position));
        source.sendSuccess(() -> Component.literal("At " + position.toShortString()
                + " | visible: " + visible + " | hidden: " + hidden), false);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        BlockPos position = BlockPosArgument.getBlockPos(context, "pos");
        PhaseLayerData.get(source.getLevel()).removeBlock(position);
        source.sendSuccess(() -> Component.literal(
                "Cleared hidden layer at " + position.toShortString()), false);
        return 1;
    }
}
