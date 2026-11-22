package dev.phasecraft.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.phasecraft.layer.PhaseLayerData;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;

/**
 * Operator-only developer commands.
 *
 * <p>Use /phasecraft set x y z redstone_wire to store a virtual
 * block without touching the actual overworld block at those coordinates.</p>
 */
public final class PhaseCraftCommands {
    private PhaseCraftCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("phasecraft")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.literal("set")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("block", StringArgumentType.word())
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
        String input = StringArgumentType.getString(context, "block");
        Identifier id = Identifier.tryParse(input);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            source.sendFailure(Component.literal("Unknown block ID: " + input));
            return 0;
        }

        PhaseLayerData.get(source.getLevel()).setBlock(position, id.toString());
        source.sendSuccess(() -> Component.literal(
                "Hidden layer " + position.toShortString() + " = " + id
                + " (overworld unchanged)"), false);
        return 1;
    }

    private static int get(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        BlockPos position = BlockPosArgument.getBlockPos(context, "pos");
        String hidden = PhaseLayerData.get(source.getLevel()).getBlock(position);
        String visible = BuiltInRegistries.BLOCK.getKey(
                source.getLevel().getBlockState(position).getBlock()).toString();
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
