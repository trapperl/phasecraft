package dev.phasecraft.devtools;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.phasecraft.layer.HiddenDimensions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;

/**
 * Dev-server commands for tools/mcb: a region of both layers to and from vanilla structure files, in one tick.
 *
 * <ul>
 *   <li>{@code /mcb export <from> <to> <name>}: saves the region of the visible world and of its hidden layer as
 *       {@code generated/mcb/structure/<name>/visible.nbt} and {@code .../hidden.nbt} in the world folder.</li>
 *   <li>{@code /mcb place <name> <pos> [strict]}: places those two files (as tools/mcb wrote or changed them) with
 *       their corner at pos, visible first, then hidden, in the same tick; {@code strict} places every block as
 *       written, with no updates (for parts written in their settled state).</li>
 * </ul>
 * Both work from either layer. Loaded only by the dev server (runServer), never shipped.
 */
public final class McbCommands implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("mcb")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.literal("export")
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .then(Commands.argument("name", StringArgumentType.string())
                                                        .executes(McbCommands::export)))))
                        .then(Commands.literal("place")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(context -> place(context, false))
                                                .then(Commands.literal("strict").executes(context -> place(context, true))))))));
    }

    /** The visible and hidden level of the layer pair the command runs in. */
    private static ServerLevel[] levels(CommandSourceStack source) {
        ServerLevel here = source.getLevel();
        ResourceKey<Level> visible = HiddenDimensions.isHidden(here.dimension())
                ? HiddenDimensions.visibleOf(here.dimension()).orElseThrow() : here.dimension();
        ResourceKey<Level> hidden = HiddenDimensions.hiddenOf(visible).orElseThrow();
        return new ServerLevel[] {source.getServer().getLevel(visible), source.getServer().getLevel(hidden)};
    }

    private static Identifier id(String name, String layer) {
        return Identifier.fromNamespaceAndPath("mcb", name + "/" + layer);
    }

    private static int export(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        BlockPos a = BlockPosArgument.getBlockPos(context, "from");
        BlockPos b = BlockPosArgument.getBlockPos(context, "to");
        String name = StringArgumentType.getString(context, "name");
        BlockPos min = BlockPos.min(a, b);
        Vec3i size = BlockPos.max(a, b).subtract(min).offset(1, 1, 1);
        ServerLevel[] levels = levels(source);
        StructureTemplateManager manager = levels[0].getStructureTemplateManager();
        String[] layers = {"visible", "hidden"};
        for (int i = 0; i < 2; i++) {
            Identifier id = id(name, layers[i]);
            StructureTemplate template = manager.getOrCreate(id);
            template.fillFromWorld(levels[i], min, size, false, List.<Block>of(Blocks.STRUCTURE_VOID));
            if (!manager.save(id)) {
                source.sendFailure(Component.literal("could not save " + id));
                return 0;
            }
        }
        source.sendSuccess(() -> Component.literal("exported " + name + " " + size.getX() + "x" + size.getY() + "x" + size.getZ()), false);
        return 1;
    }

    private static int place(CommandContext<CommandSourceStack> context, boolean strict) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        ServerLevel[] levels = levels(source);
        StructureTemplateManager manager = levels[0].getStructureTemplateManager();
        String[] layers = {"visible", "hidden"};
        StructureTemplate[] templates = new StructureTemplate[2];
        for (int i = 0; i < 2; i++) {
            Identifier id = id(name, layers[i]);
            manager.remove(id);   // read the file again: tools/mcb may have rewritten it
            var template = manager.get(id);
            if (template.isEmpty()) {
                source.sendFailure(Component.literal("no structure " + id));
                return 0;
            }
            templates[i] = template.get();
        }
        StructurePlaceSettings settings = new StructurePlaceSettings().setKnownShape(strict);
        int flags = Block.UPDATE_CLIENTS | (strict ? 816 : 0);   // as /place template ... strict
        for (int i = 0; i < 2; i++) {
            templates[i].placeInWorld(levels[i], pos, pos, settings, RandomSource.create(0), flags);
        }
        Vec3i size = templates[0].getSize();
        source.sendSuccess(() -> Component.literal("placed " + name + " " + size.getX() + "x" + size.getY() + "x" + size.getZ()), false);
        return 1;
    }
}
