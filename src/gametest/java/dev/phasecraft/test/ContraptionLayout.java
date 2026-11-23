package dev.phasecraft.test;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.phasecraft.layer.HiddenLayer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A contraption described in a layout file under src/gametest/resources/contraptions
 * (format: contraptions/README.md). The same file is built by GameTests (here),
 * by tools/examples.py on the dev server, and checked by tools/redstone_check.py,
 * so the three never drift apart.
 */
public final class ContraptionLayout {
    private record Placement(boolean hidden, BlockPos from, BlockPos to, BlockState state) {}

    /** A visible block a player clicks to operate the contraption, and the face clicked. */
    public record Click(BlockPos pos, Direction face) {}

    private final List<Placement> placements = new ArrayList<>();
    private BlockPos lever;
    private final List<Click> clicks = new ArrayList<>();
    private Block door;

    private ContraptionLayout() {}

    public static ContraptionLayout load(String name) {
        ContraptionLayout layout = new ContraptionLayout();
        String path = "/contraptions/" + name + ".txt";
        try (InputStream in = ContraptionLayout.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("no layout " + path);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String raw;
            int number = 0;
            while ((raw = reader.readLine()) != null) {
                number++;
                String line = raw.split("#", 2)[0].trim();
                if (!line.isEmpty()) {
                    layout.read(line.split("\\s+"), path + ":" + number);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return layout;
    }

    private void read(String[] w, String where) {
        switch (w[0]) {
            case "size", "allow" -> { }
            case "lever" -> lever = pos(w, 1);
            case "click" -> clicks.add(new Click(pos(w, 1), Direction.byName(w[4])));
            case "door" -> door = state(w[1], where).getBlock();
            case "visible" -> placements.add(new Placement(false, pos(w, 1), pos(w, 1), state(w[4], where)));
            case "hidden" -> placements.add(new Placement(true, pos(w, 1), pos(w, 1), state(w[4], where)));
            case "fill" -> placements.add(new Placement(w[1].equals("hidden"), pos(w, 2), pos(w, 5), state(w[8], where)));
            default -> throw new IllegalArgumentException(where + ": unknown line " + String.join(" ", w));
        }
    }

    private static BlockPos pos(String[] w, int at) {
        return new BlockPos(Integer.parseInt(w[at]), Integer.parseInt(w[at + 1]), Integer.parseInt(w[at + 2]));
    }

    private static BlockState state(String text, String where) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, new StringReader(text), false).blockState();
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException(where + ": " + e.getMessage(), e);
        }
    }

    /** Builds everything in file order, relative to the helper's test area. */
    public void build(GameTestHelper helper) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        for (Placement p : placements) {
            for (BlockPos rel : BlockPos.betweenClosed(p.from(), p.to())) {
                if (p.hidden()) {
                    hidden.setBlock(helper.absolutePos(rel), p.state());
                } else {
                    helper.setBlock(rel.immutable(), p.state());
                }
            }
        }
    }

    public BlockPos lever() {
        return lever;
    }

    public List<Click> clicks() {
        return clicks;
    }

    public Block door() {
        return door;
    }
}
