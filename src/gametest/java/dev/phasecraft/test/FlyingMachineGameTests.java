package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * A flying machine with a hidden engine carrying visible cargo (the person's option A).
 *
 * <p>The engine is vanilla's 6-block two-way "engine A" (Minecraft Wiki, flying
 * machine engine gallery), built entirely in the hidden layer, flying east: two
 * slime blocks, two sticky pistons, two observers whose output powers the slime
 * block under them. A phase piston facing east is stuck to one slime block, so it
 * travels with the engine and is powered once per cycle by that slime block: each
 * cycle it pushes the visible cargo in front of it one block east, keeping pace.
 * The cargo is in the visible layer and the engine in the hidden one, so they
 * never get in each other's way. Hidden obsidian ahead stops the engine, as in
 * vanilla.</p>
 */
public class FlyingMachineGameTests {
    static final String STRUCTURE = "phasecraft-test:empty_11x11x14";
    /** The engine's north-west slime block, test-relative. */
    static final BlockPos ENGINE = new BlockPos(1, 3, 5);
    static final Block CARGO = Blocks.GOLD_BLOCK;

    private static BlockState sticky(Direction facing) {
        return Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, facing);
    }

    /** Engine A in the hidden layer, flying east, with a phase piston on its north-west slime block. */
    static void buildEngine(GameTestHelper helper) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        java.util.function.BiConsumer<BlockPos, BlockState> h = (rel, state) -> hidden.setBlock(helper.absolutePos(rel), state);
        BlockState observer = Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
        h.accept(ENGINE, Blocks.SLIME_BLOCK.defaultBlockState());
        h.accept(ENGINE.east(), sticky(Direction.WEST));
        h.accept(ENGINE.south(), sticky(Direction.EAST));
        h.accept(ENGINE.south().east(), Blocks.SLIME_BLOCK.defaultBlockState());
        h.accept(ENGINE.above(), observer);
        h.accept(ENGINE.south().east().above(), observer);
        h.accept(ENGINE.north(), PhaseCraftBlocks.PHASE_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
    }

    /** Starts the engine: a hidden block appears and goes in front of (above) its first observer. */
    static void start(GameTestHelper helper) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        BlockPos poke = helper.absolutePos(ENGINE.above(2));
        hidden.setBlock(poke, Blocks.STONE.defaultBlockState());
        hidden.removeBlock(poke);
    }

    /** Where (x) a hidden block of this kind is on the engine's rows; -1 if none. */
    private static int hiddenX(GameTestHelper helper, Block block, int y, int z) {
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        for (int x = 0; x <= 10; x++) {
            if (hidden.getBlock(helper.absolutePos(new BlockPos(x, y, z))).is(block)) {
                return x;
            }
        }
        return -1;
    }

    private static int visibleX(GameTestHelper helper, Block block, int y, int z) {
        for (int x = 0; x <= 10; x++) {
            if (helper.getBlockState(new BlockPos(x, y, z)).is(block)) {
                return x;
            }
        }
        return -1;
    }

    /** The hidden engine flies east and its phase piston carries the visible cargo along, until hidden obsidian stops it. */
    @GameTest(structure = STRUCTURE, maxTicks = 400)
    public void hiddenEngineCarriesVisibleCargo(GameTestHelper helper) {
        buildEngine(helper);
        BlockPos cargo = ENGINE.north().east();
        helper.setBlock(cargo, CARGO);
        // The end of the line: hidden obsidian in front of the engine's east side, 6 blocks on.
        HiddenLayer hidden = HiddenLayer.of(helper.getLevel());
        for (int y = 3; y <= 4; y++) {
            for (int z = 4; z <= 6; z++) {
                hidden.setBlock(helper.absolutePos(new BlockPos(9, y, z)), Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        int engineRow = ENGINE.getZ();
        int cargoRow = cargo.getZ();
        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> start(helper))
                .thenWaitUntil(() -> helper.assertTrue(hiddenX(helper, Blocks.SLIME_BLOCK, 3, engineRow) >= 5,
                        "engine did not fly: slime at x=" + hiddenX(helper, Blocks.SLIME_BLOCK, 3, engineRow)))
                .thenExecute(() -> {
                    int engine = hiddenX(helper, Blocks.SLIME_BLOCK, 3, engineRow);
                    int load = visibleX(helper, CARGO, 3, cargoRow);
                    helper.assertTrue(load >= engine, "cargo at x=" + load + " fell behind the engine at x=" + engine);
                    helper.assertTrue(load >= 5, "cargo was not carried: at x=" + load);
                })
                .thenIdle(80)
                .thenExecute(() -> {
                    int engine = hiddenX(helper, Blocks.SLIME_BLOCK, 3, engineRow);
                    helper.assertTrue(engine <= 7, "engine went through the hidden obsidian: slime at x=" + engine);
                    helper.assertTrue(visibleX(helper, CARGO, 3, cargoRow) >= 0, "cargo lost");
                })
                .thenSucceed();
    }

    /**
     * Hidden observers do not see the visible world: a visible block appearing and going in
     * front of each observer in turn (what starts the engine in the hidden layer) leaves the
     * engine where it is.
     */
    @GameTest(structure = STRUCTURE, maxTicks = 200)
    public void visibleChangesDoNotStartTheEngine(GameTestHelper helper) {
        buildEngine(helper);
        int engineRow = ENGINE.getZ();
        var sequence = helper.startSequence().thenIdle(5);
        for (BlockPos front : new BlockPos[] {ENGINE.above(2), ENGINE.south().east().above(2)}) {
            sequence = sequence
                    .thenExecute(() -> helper.setBlock(front, Blocks.STONE))
                    .thenExecute(() -> helper.setBlock(front, Blocks.AIR))
                    .thenIdle(40)
                    .thenExecute(() -> helper.assertValueEqual(hiddenX(helper, Blocks.SLIME_BLOCK, 3, engineRow), ENGINE.getX(),
                            "engine slime x after a visible change in front of the observer at " + front.below()));
        }
        sequence.thenSucceed();
    }
}
