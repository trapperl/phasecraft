package dev.phasecraft.perf;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A contraption layout file (src/gametest/resources/contraptions, format in its README), read from the source
 * tree: only what the measurements need of the GameTests' ContraptionLayout (blocks and the lever).
 */
final class Layout {
    private record Placement(boolean hidden, BlockPos from, BlockPos to, BlockState state) {}

    private final List<Placement> placements = new ArrayList<>();
    BlockPos lever;

    static Layout load(String name) {
        Path file = Path.of(System.getProperty("phasecraft.perf.contraptions", "src/gametest/resources/contraptions"), name + ".txt");
        Layout layout = new Layout();
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.split("#", 2)[0].trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] w = line.split("\\s+");
                switch (w[0]) {
                    case "lever" -> layout.lever = pos(w, 1);
                    case "visible" -> layout.placements.add(new Placement(false, pos(w, 1), pos(w, 1), state(w[4])));
                    case "hidden" -> layout.placements.add(new Placement(true, pos(w, 1), pos(w, 1), state(w[4])));
                    case "fill" -> layout.placements.add(new Placement(w[1].equals("hidden"), pos(w, 2), pos(w, 5), state(w[8])));
                    default -> { }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return layout;
    }

    private static BlockPos pos(String[] w, int at) {
        return new BlockPos(Integer.parseInt(w[at]), Integer.parseInt(w[at + 1]), Integer.parseInt(w[at + 2]));
    }

    private static BlockState state(String text) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, new StringReader(text), false).blockState();
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    /** Builds it in file order with its 0 0 0 at the given offset from the scenario's origin. */
    void build(Ctx ctx, int dx, int dy, int dz) {
        for (Placement p : placements) {
            for (BlockPos rel : BlockPos.betweenClosed(p.from(), p.to())) {
                (p.hidden() ? ctx.hidden : ctx.visible).setBlock(ctx.at(dx + rel.getX(), dy + rel.getY(), dz + rel.getZ()),
                        p.state(), Block.UPDATE_ALL);
            }
        }
    }
}
