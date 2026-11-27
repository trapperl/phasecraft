package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.layer.PhaseLayerData;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.link.PhasePortBlock;
import dev.phasecraft.shifter.AreaShifterBlock;
import dev.phasecraft.shifter.AreaShifterBlockEntity;
import dev.phasecraft.shifter.LineShifterBlock;
import dev.phasecraft.shifter.LineShifterBlockEntity;
import dev.phasecraft.shifter.PhaseShifterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * A full server restart, split across two separate server processes that
 * share one world folder ({@code ./gradlew restartTest}):
 *
 * <ol>
 *   <li>{@code writeBeforeRestart} places hidden blocks in the hidden
 *       dimensions, and also stores some cells the old way (the per-dimension
 *       block list of earlier builds); the server then shuts down normally.</li>
 *   <li>{@code verifyAfterRestart} runs in a brand-new server process: every
 *       hidden block is still there, and the old-style cells were migrated
 *       into the hidden dimensions at startup.</li>
 * </ol>
 *
 * <p>The class name deliberately does not end in {@code GameTests}, so the
 * ordinary {@code runGameTest} filter skips these phases.</p>
 */
public class PhaseLayerRestartPhases {
    private record Expected(ResourceKey<Level> dimension, BlockPos pos, BlockState block) {}

    /** Where a phased glowstone is stored; after the restart it must light the visible world there. */
    private static final BlockPos PHASED_LIGHT = new BlockPos(10, 70, 30);
    /** A phase shifter holding a visible gold block across the restart. */
    private static final BlockPos SHIFTED = new BlockPos(10, 70, 40);
    /** A phase shifter holding a visible chest with seven diamonds in it across the restart. */
    private static final BlockPos SHIFTED_CHEST = SHIFTED.east(2);
    /**
     * A line shifter (delay 4) facing east into four visible blocks, powered just before the restart: the first is
     * taken at once, the other three are still to go and must go after it; then all four come back.
     */
    private static final BlockPos LINE = SHIFTED.south(3);
    private static final Block[] LINE_BLOCKS = {Blocks.STONE, Blocks.GOLD_BLOCK, Blocks.BRICKS, Blocks.OAK_PLANKS};

    /**
     * A hidden phase port whose saved power is stale: a visible redstone block stands at its spot, but the port was
     * saved unpowered, as when the visible power changed before its hidden chunk had loaded. Loading the chunk after
     * the restart must bring it up to date (issue #17).
     */
    private static final BlockPos STALE_PORT = new BlockPos(20, 70, 30);

    /** Same XYZ in every dimension, plus a cell far outside any loaded chunk. */
    /**
     * An area shifter pair (delay 0: all at once) around a 3 x 2 x 2 box of visible blocks with a chest, taken just
     * before the restart: after it, the leader still holds all twelve, and unpowered puts them back.
     */
    private static final BlockPos AREA = SHIFTED.south(6);
    private static final BlockPos AREA_FAR = AREA.offset(2, 1, 1);

    private static BlockState areaBlock(BlockPos at) {
        int i = (at.getX() - AREA.getX()) + 3 * (at.getY() - AREA.getY()) + 6 * (at.getZ() - AREA.getZ());
        return i == 4 ? Blocks.CHEST.defaultBlockState()
                : (i % 2 == 0 ? Blocks.STONE_BRICKS : Blocks.GOLD_BLOCK).defaultBlockState();
    }

    private static final List<Expected> HIDDEN = List.of(
            // Ground first: wire and repeaters need a block under them, by vanilla rules.
            // Kept apart so vanilla does not reshape them (wire next to a repeater connects to it).
            new Expected(Level.OVERWORLD, new BlockPos(10, 63, 10), Blocks.STONE.defaultBlockState()),
            new Expected(Level.OVERWORLD, new BlockPos(14, 63, 10), Blocks.STONE.defaultBlockState()),
            new Expected(Level.OVERWORLD, new BlockPos(10, 64, 10), Blocks.REDSTONE_WIRE.defaultBlockState()),
            new Expected(Level.OVERWORLD, new BlockPos(1_000_000, -40, -1_000_000), Blocks.PISTON.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.DOWN)),
            new Expected(Level.OVERWORLD, new BlockPos(14, 64, 10), Blocks.REPEATER.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                    .setValue(BlockStateProperties.DELAY, 4)),
            new Expected(Level.NETHER, new BlockPos(10, 64, 10), Blocks.STICKY_PISTON.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.EAST)),
            new Expected(Level.END, new BlockPos(10, 64, 10), Blocks.OBSIDIAN.defaultBlockState()),
            // A phased light: loading its hidden chunk once deadlocked the server at startup.
            new Expected(Level.OVERWORLD, PHASED_LIGHT.below(), PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState()),
            new Expected(Level.OVERWORLD, PHASED_LIGHT, Blocks.GLOWSTONE.defaultBlockState()));

    /** Stored the old way before the restart; must be in the hidden dimension after it. */
    private static final List<Expected> LEGACY = List.of(
            new Expected(Level.OVERWORLD, new BlockPos(20, 64, 20), Blocks.COMPARATOR.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)),
            new Expected(Level.NETHER, new BlockPos(20, 64, 20), Blocks.OBSERVER.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, Direction.WEST)));

    @GameTest
    public void writeBeforeRestart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        for (Expected cell : HIDDEN) {
            HiddenLayer layer = HiddenLayer.of(server.getLevel(cell.dimension()));
            helper.assertValueEqual(layer.getBlock(cell.pos()), Blocks.AIR.defaultBlockState(),
                    "hidden cell before write in " + cell.dimension().identifier() + " (stale world folder?)");
            layer.setBlock(cell.pos(), cell.block());
        }
        for (Expected cell : LEGACY) {
            ServerLevel level = server.getLevel(cell.dimension());
            PhaseLayerData.get(level).setBlock(cell.pos(), cell.block());
            level.getDataStorage().saveAndJoin();
        }
        ServerLevel overworld = server.overworld();
        HiddenLayer layer = HiddenLayer.of(overworld);
        overworld.setBlock(SHIFTED, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        layer.setBlock(SHIFTED, PhaseCraftBlocks.PHASE_SHIFTER.defaultBlockState());
        layer.setBlock(SHIFTED.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.assertValueEqual(overworld.getBlockState(SHIFTED).getBlock(), Blocks.AIR, "visible gold taken by the shifter before restart");
        overworld.setBlock(SHIFTED_CHEST, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        ((ChestBlockEntity) overworld.getBlockEntity(SHIFTED_CHEST)).setItem(4, new ItemStack(Items.DIAMOND, 7));
        layer.setBlock(SHIFTED_CHEST, PhaseCraftBlocks.PHASE_SHIFTER.defaultBlockState());
        layer.setBlock(SHIFTED_CHEST.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.assertValueEqual(overworld.getBlockState(SHIFTED_CHEST).getBlock(), Blocks.AIR, "visible chest taken by the shifter before restart");
        for (int d = 1; d <= LINE_BLOCKS.length; d++) {
            overworld.setBlock(LINE.east(d), LINE_BLOCKS[d - 1].defaultBlockState(), Block.UPDATE_ALL);
        }
        layer.setBlock(LINE, PhaseCraftBlocks.LINE_SHIFTER.defaultBlockState()
                .setValue(LineShifterBlock.FACING, Direction.EAST).setValue(LineShifterBlock.DELAY, 4));
        layer.setBlock(LINE.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.assertValueEqual(overworld.getBlockState(LINE.east(4)).getBlock(), Blocks.AIR, "the line's far end taken at once");
        helper.assertValueEqual(overworld.getBlockState(LINE.east(3)).getBlock(), Blocks.BRICKS, "the line's next block not yet taken");
        overworld.getGameRules().set(AreaShifterBlock.RULE, true, server);
        for (BlockPos at : BlockPos.betweenClosed(AREA, AREA_FAR)) {
            overworld.setBlock(at, areaBlock(at), Block.UPDATE_ALL);
        }
        ((ChestBlockEntity) overworld.getBlockEntity(AREA.offset(1, 1, 0))).setItem(4, new ItemStack(Items.EMERALD, 5));
        for (BlockPos corner : List.of(AREA, AREA_FAR)) {
            layer.setBlock(corner, PhaseCraftBlocks.AREA_SHIFTER.defaultBlockState().setValue(AreaShifterBlock.DELAY, 0));
        }
        layer.setBlock(AREA.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        layer.setBlock(AREA_FAR.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        for (BlockPos at : BlockPos.betweenClosed(AREA, AREA_FAR)) {
            helper.assertTrue(overworld.getBlockState(at).isAir(), "the area box taken before restart at " + at.toShortString());
        }
        helper.succeed();
    }

    @GameTest
    public void writeAStalePort(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        ServerLevel hidden = HiddenLayer.of(overworld).level();
        helper.startSequence()
                .thenExecute(() -> overworld.setBlock(STALE_PORT, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL))
                .thenIdle(2)
                // Unpowered, with no updates and no onPlace: nothing tells the port about the power at its spot.
                .thenExecute(() -> hidden.setBlock(STALE_PORT, PhaseCraftBlocks.PHASE_PORT.defaultBlockState(),
                        Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ON_PLACE))
                .thenIdle(5)
                .thenExecute(() -> helper.assertValueEqual(hidden.getBlockState(STALE_PORT).getValue(PhasePortBlock.POWER), 0,
                        "the stale port's power before the restart"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void verifyTheStalePortCaughtUp(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        ServerLevel hidden = HiddenLayer.of(overworld).level();
        int chunkX = SectionPos.blockToSectionCoord(STALE_PORT.getX());
        int chunkZ = SectionPos.blockToSectionCoord(STALE_PORT.getZ());
        // As in play: the visible chunk ticks (here forced), and its hidden twin loads after it. Reading the port
        // before then would load the hidden chunk on its own.
        overworld.setChunkForced(chunkX, chunkZ, true);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(hidden.getChunkSource().getChunkNow(chunkX, chunkZ) != null,
                        "the port's hidden chunk loaded after its visible twin"))
                .thenWaitUntil(() -> helper.assertValueEqual(hidden.getBlockState(STALE_PORT).getValue(PhasePortBlock.POWER), 15,
                        "the port's power after its chunk loaded (the visible redstone block at its spot)"))
                .thenExecute(() -> overworld.setChunkForced(chunkX, chunkZ, false))
                .thenSucceed();
    }

    @GameTest(maxTicks = 2400)
    public void verifyAfterRestart(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        for (Expected cell : HIDDEN) {
            helper.assertValueEqual(HiddenLayer.of(server.getLevel(cell.dimension())).getBlock(cell.pos()), cell.block(),
                    "hidden cell " + cell.pos().toShortString() + " in " + cell.dimension().identifier() + " after restart");
        }
        for (Expected cell : LEGACY) {
            ServerLevel level = server.getLevel(cell.dimension());
            helper.assertValueEqual(HiddenLayer.of(level).getBlock(cell.pos()), cell.block(),
                    "old-style cell " + cell.pos().toShortString() + " in " + cell.dimension().identifier()
                    + " after restart (not migrated?)");
            helper.assertValueEqual(PhaseLayerData.get(level).size(), 0,
                    "old-style cells left in " + cell.dimension().identifier());
        }
        ServerLevel overworld = server.overworld();
        HiddenLayer layer = HiddenLayer.of(overworld);
        helper.assertValueEqual(overworld.getBlockState(SHIFTED).getBlock(), Blocks.AIR, "visible spot of the shifted gold after restart");
        helper.assertTrue(layer.level().getBlockEntity(SHIFTED) instanceof PhaseShifterBlockEntity entity
                && entity.held() != null && entity.held().is(Blocks.GOLD_BLOCK), "the shifter still holds the gold after restart");
        layer.removeBlock(SHIFTED.above());
        helper.assertValueEqual(overworld.getBlockState(SHIFTED_CHEST).getBlock(), Blocks.AIR, "visible spot of the shifted chest after restart");
        layer.removeBlock(SHIFTED_CHEST.above());
        LineShifterBlockEntity line = (LineShifterBlockEntity) layer.level().getBlockEntity(LINE);
        // What the line shifter saved: the far block it took, and the three still to take. (That the move goes on
        // after loading is LineShifterGameTests.aMoveGoesOnWhateverTheGameTime: the step itself is a vanilla block
        // tick, saved with the chunk, and runs once the chunk ticks, which a test server here does not promise.)
        helper.assertValueEqual(line.held(LINE_BLOCKS.length), LINE_BLOCKS[LINE_BLOCKS.length - 1].defaultBlockState(),
                "the block the line shifter held before restart");
        helper.assertValueEqual(line.holding() + line.saveWithoutMetadata(layer.level().registryAccess()).getIntOr("todo", -1),
                LINE_BLOCKS.length, "blocks the line shifter held and still had to take, after restart");
        AreaShifterBlockEntity area = (AreaShifterBlockEntity) layer.level().getBlockEntity(AREA);
        helper.assertValueEqual(area.holding(), 12, "blocks the area shifter held after restart");
        helper.assertValueEqual(area.held(AREA.offset(1, 1, 0)), Blocks.CHEST.defaultBlockState(), "the area's chest held after restart");
        layer.removeBlock(AREA.west());
        for (BlockPos at : BlockPos.betweenClosed(AREA, AREA_FAR)) {
            helper.assertValueEqual(overworld.getBlockState(at).getBlock(), areaBlock(at).getBlock(),
                    "the area's block at " + at.toShortString() + " put back after restart");
        }
        helper.assertTrue(overworld.getBlockEntity(AREA.offset(1, 1, 0)) instanceof ChestBlockEntity chest
                && chest.getItem(4).is(Items.EMERALD) && chest.getItem(4).getCount() == 5,
                "the area's chest put back after restart, with its five emeralds");
        // Light is computed on its own thread: wait for it (in real time; see PhasedLightGameTests).
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(overworld.getBlockState(SHIFTED).getBlock(), Blocks.GOLD_BLOCK,
                        "the gold put back after restart"))
                .thenWaitUntil(() -> helper.assertTrue(overworld.getBlockEntity(SHIFTED_CHEST) instanceof ChestBlockEntity chest
                        && chest.getItem(4).is(Items.DIAMOND) && chest.getItem(4).getCount() == 7,
                        "the chest put back after restart, with its seven diamonds"))
                .thenExecute(() -> {
                    for (int d = 1; d < LINE_BLOCKS.length; d++) {
                        helper.assertTrue(line.held(d) != null || overworld.getBlockState(LINE.east(d)).is(LINE_BLOCKS[d - 1]),
                                "the line's block " + d + " neither held nor there after restart");
                    }
                    layer.removeBlock(LINE.west());
                })
                .thenWaitUntil(() -> {
                    for (int d = 1; d <= LINE_BLOCKS.length; d++) {
                        helper.assertValueEqual(overworld.getBlockState(LINE.east(d)).getBlock(), LINE_BLOCKS[d - 1],
                                "the line's block " + d + " put back after restart");
                    }
                })
                .thenWaitUntil(() -> helper.assertValueEqual(
                        server.overworld().getBrightness(LightLayer.BLOCK, PHASED_LIGHT), 15,
                        "visible block light at the phased glowstone after restart"))
                .thenSucceed();
    }
}
