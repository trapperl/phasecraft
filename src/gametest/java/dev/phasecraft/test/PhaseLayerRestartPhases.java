package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.edit.MachineOwners;
import dev.phasecraft.layer.Access;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.link.HiddenChunks;
import dev.phasecraft.layer.PhaseLayerData;
import java.util.List;
import java.util.UUID;
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
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.ChunkPos;
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
    /** Whose that shifter is (see MachineOwners): still theirs after the restart. */
    private static final NameAndId SHIFTER_OWNER = new NameAndId(UUID.fromString("5a1f0c1e-7b1d-4c58-9a57-0d3c2b6e9f41"), "shifter-owner");
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
    /** In the Nether's hidden layer, far from the rest. */
    private static final BlockPos OLD_WORLD = new BlockPos(600, 70, 600);
    /** In the overworld's hidden layer, far from the rest. */
    private static final BlockPos FAR = new BlockPos(-700, 70, 900);

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
        // As a dedicated server's first start on this world: survival_access is switched off, once, and the world
        // remembers. Then the admin turns it on; that must hold after the restart.
        helper.assertTrue(Access.applyServerDefault(server, true, overworld.getDataStorage()),
                "a dedicated server's first start did not switch survival_access off");
        helper.assertFalse(server.getGameRules().get(Access.SURVIVAL_ACCESS), "survival_access after a dedicated server's first start");
        server.getGameRules().set(Access.SURVIVAL_ACCESS, true, server);
        HiddenLayer layer = HiddenLayer.of(overworld);
        overworld.setBlock(SHIFTED, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        layer.setBlock(SHIFTED, PhaseCraftBlocks.PHASE_SHIFTER.defaultBlockState());
        layer.setBlock(SHIFTED.above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        helper.assertValueEqual(overworld.getBlockState(SHIFTED).getBlock(), Blocks.AIR, "visible gold taken by the shifter before restart");
        MachineOwners.setOwner(layer.level(), SHIFTED, SHIFTER_OWNER);
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

    /**
     * A hidden block in a world from before the hidden chunk index (HiddenChunks): here the Nether's hidden layer
     * forgets its index after the block is placed. After the restart the index is listed again from the files on
     * disk, and the block's chunk loads and ticks under its ticking twin. (The overworld keeps its index: the stale
     * port above checks that a saved index brings its chunk back.)
     */
    @GameTest
    public void writeAnOldWorldsHiddenBlock(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        HiddenLayer layer = HiddenLayer.of(nether);
        layer.setBlock(OLD_WORLD, Blocks.STONE.defaultBlockState());
        HiddenChunks.forgetIndex(layer.level());
        helper.assertFalse(HiddenChunks.mayHold(layer.level(), SectionPos.blockToSectionCoord(OLD_WORLD.getX()),
                SectionPos.blockToSectionCoord(OLD_WORLD.getZ())), "the forgotten index still lists the block's chunk");
        helper.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void verifyAnOldWorldsHiddenBlockTicks(GameTestHelper helper) {
        verifyFarHiddenBlockTicks(helper, helper.getLevel().getServer().getLevel(Level.NETHER), OLD_WORLD,
                "the old world's hidden block");
    }

    /**
     * A hidden block far from everything else, which nothing reads after the restart: its chunk comes back only
     * because the saved index lists it (reading a hidden chunk loads it, and one that loads holding blocks is
     * listed again, which would hide a lost index).
     */
    @GameTest
    public void writeAFarHiddenBlock(GameTestHelper helper) {
        HiddenLayer.of(helper.getLevel().getServer().overworld()).setBlock(FAR, Blocks.STONE.defaultBlockState());
        helper.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void verifyAFarHiddenBlockTicks(GameTestHelper helper) {
        verifyFarHiddenBlockTicks(helper, helper.getLevel().getServer().overworld(), FAR, "the far hidden block");
    }

    /**
     * Keeps the visible chunk at pos ticking and waits for its hidden twin to tick, then checks the hidden stone is
     * there. The visible chunks around, and the hidden ones around but not the twin itself, are loaded first, so
     * only the twin's own ticket is waited for (a far chunk generated or loaded by tickets alone can take longer
     * than the test's ticks, which run back to back when few tests do).
     */
    private static void verifyFarHiddenBlockTicks(GameTestHelper helper, ServerLevel visible, BlockPos pos, String what) {
        ServerLevel hidden = HiddenLayer.of(visible).level();
        int chunkX = SectionPos.blockToSectionCoord(pos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                visible.getChunk(chunkX + dx, chunkZ + dz);
                if (dx != 0 || dz != 0) {
                    hidden.getChunk(chunkX + dx, chunkZ + dz);
                }
            }
        }
        visible.setChunkForced(chunkX, chunkZ, true);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(hidden.isPositionEntityTicking(pos), what + "'s chunk ticks under its ticking twin"))
                .thenExecute(() -> {
                    helper.assertValueEqual(hidden.getBlockState(pos).getBlock(), Blocks.STONE, what);
                    visible.setChunkForced(chunkX, chunkZ, false);
                })
                .thenSucceed();
    }

    /**
     * Machines stopped mid-move by the restart, in a far chunk of their own that the write phase keeps ticking until
     * it lets go of it, all at once, mid-move (the server then stops whenever it does: nothing there ticks any more).
     * After the restart each goes on by itself where it left off, once its chunk ticks again:
     * a line shifter taking its line, another putting its line back, an area shifter taking its box, a sticky phase
     * piston whose block is sliding out, a phase shifter waiting to put its block back (bedrock is in the spot), and
     * the flying machine in flight with its cargo.
     */
    private static final ChunkPos MID_MOVE = new ChunkPos(-3000, 2500);
    private static final BlockPos MM_TAKING = midMove(1, 1);
    private static final BlockPos MM_BACK = midMove(1, 3);
    private static final BlockPos MM_AREA = midMove(1, 5);
    private static final BlockPos MM_AREA_FAR = MM_AREA.offset(2, 2, 2);
    private static final BlockPos MM_SHIFTER = midMove(10, 1);
    private static final BlockPos MM_PISTON = midMove(10, 3);
    private static final BlockPos MM_ENGINE = midMove(1, 12);
    private static final int MM_WALL = 12;
    private static final Block[] MM_LINE = {Blocks.STONE, Blocks.GOLD_BLOCK, Blocks.BRICKS, Blocks.OAK_PLANKS,
            Blocks.COBBLESTONE, Blocks.IRON_BLOCK};

    private static BlockPos midMove(int dx, int dz) {
        return new BlockPos(MID_MOVE.getMinBlockX() + dx, 80, MID_MOVE.getMinBlockZ() + dz);
    }

    private static void loadAround(ServerLevel level, ChunkPos chunk) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.getChunk(chunk.x() + dx, chunk.z() + dz);
            }
        }
    }

    private static LineShifterBlockEntity lineAt(ServerLevel hidden, BlockPos at) {
        return (LineShifterBlockEntity) hidden.getBlockEntity(at);
    }

    /** Every block of a line is held or in the visible world, never both, never neither; how many are held. */
    private static int lineWhole(GameTestHelper helper, ServerLevel visible, ServerLevel hidden, BlockPos at, String what) {
        LineShifterBlockEntity entity = lineAt(hidden, at);
        int held = 0;
        for (int d = 1; d <= MM_LINE.length; d++) {
            boolean there = visible.getBlockState(at.east(d)).is(MM_LINE[d - 1]);
            boolean holds = entity.held(d) != null && entity.held(d).is(MM_LINE[d - 1]);
            helper.assertTrue(there != holds, what + ": block " + d + (there ? " both in the world and held" : " neither in the world nor held"));
            held += holds ? 1 : 0;
        }
        helper.assertValueEqual(entity.holding(), held, what + ": blocks held");
        return held;
    }

    /** Every block of the box is held or in the visible world, never both, never neither; how many are held. */
    private static int areaWhole(GameTestHelper helper, ServerLevel visible, ServerLevel hidden, String what) {
        AreaShifterBlockEntity leader = (AreaShifterBlockEntity) hidden.getBlockEntity(MM_AREA);
        int held = 0;
        for (BlockPos at : BlockPos.betweenClosed(MM_AREA, MM_AREA_FAR)) {
            boolean there = visible.getBlockState(at).is(Blocks.STONE_BRICKS);
            boolean holds = leader.held(at) != null && leader.held(at).is(Blocks.STONE_BRICKS);
            helper.assertTrue(there != holds, what + ": the block at " + at.toShortString()
                    + (there ? " both in the world and held" : " neither in the world nor held"));
            held += holds ? 1 : 0;
        }
        helper.assertValueEqual(leader.holding(), held, what + ": blocks held");
        return held;
    }

    private static int count(ServerLevel level, BlockPos from, BlockPos to, Block block) {
        int n = 0;
        for (BlockPos at : BlockPos.betweenClosed(from, to)) {
            n += level.getBlockState(at).is(block) ? 1 : 0;
        }
        return n;
    }

    /** How far east of where it started the flying machine's phase piston is; -99 if it is gone. */
    private static int engineAhead(ServerLevel hidden) {
        for (int dx = -1; dx <= MM_WALL; dx++) {
            if (hidden.getBlockState(MM_ENGINE.north().east(dx)).is(PhaseCraftBlocks.PHASE_PISTON)) {
                return dx;
            }
        }
        return -99;
    }

    @GameTest(maxTicks = 2400)
    public void writeMachinesMidMove(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel visible = server.overworld();
        HiddenLayer layer = HiddenLayer.of(visible);
        ServerLevel hidden = layer.level();
        BlockState redstone = Blocks.REDSTONE_BLOCK.defaultBlockState();
        loadAround(visible, MID_MOVE);
        visible.setChunkForced(MID_MOVE.x(), MID_MOVE.z(), true);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(visible.isPositionEntityTicking(MM_TAKING), "the visible chunk ticks"))
                .thenExecute(() -> {
                    loadAround(hidden, MID_MOVE);
                    visible.getGameRules().set(AreaShifterBlock.RULE, true, server);
                    for (BlockPos at : List.of(MM_TAKING, MM_BACK)) {
                        for (int d = 1; d <= MM_LINE.length; d++) {
                            visible.setBlock(at.east(d), MM_LINE[d - 1].defaultBlockState(), Block.UPDATE_ALL);
                        }
                        layer.setBlock(at, PhaseCraftBlocks.LINE_SHIFTER.defaultBlockState()
                                .setValue(LineShifterBlock.FACING, Direction.EAST).setValue(LineShifterBlock.DELAY, at == MM_BACK ? 1 : 4));
                    }
                    for (BlockPos at : BlockPos.betweenClosed(MM_AREA, MM_AREA_FAR)) {
                        visible.setBlock(at, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    for (BlockPos corner : List.of(MM_AREA, MM_AREA_FAR)) {
                        layer.setBlock(corner, PhaseCraftBlocks.AREA_SHIFTER.defaultBlockState().setValue(AreaShifterBlock.DELAY, 4));
                    }
                    visible.setBlock(MM_SHIFTER, Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    layer.setBlock(MM_SHIFTER, PhaseCraftBlocks.PHASE_SHIFTER.defaultBlockState());
                    visible.setBlock(MM_PISTON.east(), Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    layer.setBlock(MM_PISTON, PhaseCraftBlocks.STICKY_PHASE_PISTON.defaultBlockState()
                            .setValue(BlockStateProperties.FACING, Direction.EAST));
                    // The flying machine of FlyingMachineGameTests (engine A, flying east), hidden obsidian ahead.
                    BlockState observer = Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
                    layer.setBlock(MM_ENGINE, Blocks.SLIME_BLOCK.defaultBlockState());
                    layer.setBlock(MM_ENGINE.east(), Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.WEST));
                    layer.setBlock(MM_ENGINE.south(), Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST));
                    layer.setBlock(MM_ENGINE.south().east(), Blocks.SLIME_BLOCK.defaultBlockState());
                    layer.setBlock(MM_ENGINE.above(), observer);
                    layer.setBlock(MM_ENGINE.south().east().above(), observer);
                    layer.setBlock(MM_ENGINE.north(), PhaseCraftBlocks.PHASE_PISTON.defaultBlockState()
                            .setValue(BlockStateProperties.FACING, Direction.EAST));
                    visible.setBlock(MM_ENGINE.north().east(), Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    for (BlockPos at : BlockPos.betweenClosed(MM_ENGINE.offset(MM_WALL, 0, -1), MM_ENGINE.offset(MM_WALL, 1, 1))) {
                        layer.setBlock(at.immutable(), Blocks.OBSIDIAN.defaultBlockState());
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(hidden.isPositionEntityTicking(MM_TAKING), "the hidden chunk ticks"))
                .thenIdle(5)
                .thenExecute(() -> {
                    layer.setBlock(MM_BACK.west(), redstone);
                    layer.setBlock(MM_SHIFTER.above(), redstone);
                    layer.setBlock(MM_ENGINE.above(2), Blocks.STONE.defaultBlockState());
                    layer.removeBlock(MM_ENGINE.above(2));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(lineAt(hidden, MM_BACK).holding(), MM_LINE.length,
                        "blocks the second line shifter took before it turns around"))
                .thenExecute(() -> {
                    hidden.setBlock(MM_BACK, hidden.getBlockState(MM_BACK).setValue(LineShifterBlock.DELAY, 4), Block.UPDATE_CLIENTS);
                    layer.removeBlock(MM_BACK.west());
                })
                // The line starts back (its first block within a step), then everything else starts.
                .thenWaitUntil(() -> helper.assertValueEqual(lineAt(hidden, MM_BACK).holding(), MM_LINE.length - 1,
                        "blocks the second line shifter holds once it has put one back"))
                .thenExecute(() -> {
                    layer.setBlock(MM_TAKING.west(), redstone);
                    layer.setBlock(MM_AREA.west(), redstone);
                    layer.setBlock(MM_AREA_FAR.east(), redstone);
                    visible.setBlock(MM_SHIFTER, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                    layer.removeBlock(MM_SHIFTER.above());
                    layer.setBlock(MM_PISTON.below(), redstone);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(visible.getBlockState(MM_PISTON.east(2)).getBlock(), Blocks.MOVING_PISTON,
                        "the phase piston's gold sliding out"))
                .thenExecute(() -> visible.setChunkForced(MID_MOVE.x(), MID_MOVE.z(), false))
                .thenWaitUntil(() -> helper.assertFalse(visible.shouldTickBlocksAt(MID_MOVE.pack()) || hidden.shouldTickBlocksAt(MID_MOVE.pack()),
                        "the chunk still ticks"))
                .thenIdle(20)
                .thenExecute(() -> {
                    int taking = lineWhole(helper, visible, hidden, MM_TAKING, "the line being taken, before the restart");
                    helper.assertTrue(taking >= 1 && taking < MM_LINE.length, "the line being taken is not mid-move: " + taking + " held");
                    int back = lineWhole(helper, visible, hidden, MM_BACK, "the line being put back, before the restart");
                    helper.assertTrue(back >= 1 && back < MM_LINE.length, "the line being put back is not mid-move: " + back + " held");
                    int area = areaWhole(helper, visible, hidden, "the box, before the restart");
                    helper.assertTrue(area >= 9 && area < 27, "the box is not mid-move: " + area + " held");
                    helper.assertTrue(hidden.getBlockEntity(MM_SHIFTER) instanceof PhaseShifterBlockEntity shifter
                            && shifter.held() != null && shifter.held().is(Blocks.GOLD_BLOCK), "the waiting shifter holds its gold");
                    helper.assertValueEqual(visible.getBlockState(MM_PISTON.east(2)).getBlock(), Blocks.MOVING_PISTON,
                            "the phase piston's gold, still sliding");
                    int engine = engineAhead(hidden);
                    helper.assertTrue(engine >= -1 && engine < MM_WALL - 4, "the flying machine is not mid-flight: " + engine + " ahead");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 2400)
    public void verifyMachinesMidMoveGoOn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel visible = server.overworld();
        HiddenLayer layer = HiddenLayer.of(visible);
        ServerLevel hidden = layer.level();
        // A test server starts with every game rule at its default (a real one keeps them): on again before the chunk
        // ticks, or the box would come back, as it does when the rule is turned off.
        visible.getGameRules().set(AreaShifterBlock.RULE, true, server);
        loadAround(visible, MID_MOVE);
        visible.setChunkForced(MID_MOVE.x(), MID_MOVE.z(), true);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(visible.isPositionEntityTicking(MM_TAKING), "the visible chunk ticks"))
                .thenExecute(() -> loadAround(hidden, MID_MOVE))
                .thenWaitUntil(() -> helper.assertTrue(hidden.isPositionEntityTicking(MM_TAKING), "the hidden chunk ticks"))
                // Nothing is touched: each move goes on by itself. The flying machine has the farthest to go.
                .thenIdle(240)
                .thenExecute(() -> {
                    helper.assertValueEqual(lineWhole(helper, visible, hidden, MM_TAKING, "the line being taken, after the restart"),
                            MM_LINE.length, "blocks of the line taken after the restart");
                    helper.assertTrue(hidden.getBlockState(MM_TAKING).getValue(LineShifterBlock.POWERED), "the taking line shifter is powered");
                    helper.assertValueEqual(lineWhole(helper, visible, hidden, MM_BACK, "the line being put back, after the restart"),
                            0, "blocks of the line still held after the restart");
                    helper.assertFalse(hidden.getBlockState(MM_BACK).getValue(LineShifterBlock.POWERED), "the other line shifter is powered");
                    helper.assertValueEqual(areaWhole(helper, visible, hidden, "the box, after the restart"), 27,
                            "blocks of the box taken after the restart");
                    helper.assertValueEqual(visible.getBlockState(MM_PISTON.east(2)).getBlock(), Blocks.GOLD_BLOCK,
                            "the phase piston's gold, landed after the restart");
                    helper.assertValueEqual(count(visible, MM_PISTON.west(), MM_PISTON.east(4), Blocks.GOLD_BLOCK), 1, "gold blocks at the phase piston");
                    helper.assertTrue(hidden.getBlockState(MM_PISTON).getValue(BlockStateProperties.EXTENDED), "the powered phase piston is extended");
                    BlockPos from = MM_ENGINE.offset(-2, -1, -2);
                    BlockPos to = MM_ENGINE.offset(MM_WALL + 2, 3, 3);
                    String parts = "";
                    for (Block part : List.of(Blocks.SLIME_BLOCK, Blocks.STICKY_PISTON, Blocks.OBSERVER, PhaseCraftBlocks.PHASE_PISTON,
                            Blocks.MOVING_PISTON, Blocks.PISTON_HEAD)) {
                        parts += " " + count(hidden, from, to, part);
                    }
                    helper.assertValueEqual(parts, " 2 2 2 1 0 0", "the flying machine's slime blocks, sticky pistons, observers,"
                            + " phase piston, moving blocks and piston heads after the restart");
                    helper.assertValueEqual(count(visible, from, to, Blocks.GOLD_BLOCK) + " " + count(visible, from, to, Blocks.MOVING_PISTON),
                            "1 0", "the flying machine's cargo (gold blocks, moving blocks) after the restart");
                    int engine = engineAhead(hidden);
                    helper.assertTrue(engine >= MM_WALL - 4, "the flying machine did not fly on after the restart: " + engine + " ahead");
                    helper.assertTrue(visible.getBlockState(MM_ENGINE.north().east(engine + 1)).is(Blocks.GOLD_BLOCK)
                            || visible.getBlockState(MM_ENGINE.north().east(engine + 2)).is(Blocks.GOLD_BLOCK),
                            "the cargo is not in front of the flying machine's phase piston");
                    helper.assertTrue(hidden.getBlockEntity(MM_SHIFTER) instanceof PhaseShifterBlockEntity shifter
                            && shifter.held() != null && shifter.held().is(Blocks.GOLD_BLOCK), "the waiting shifter holds its gold after the restart");
                    helper.assertValueEqual(visible.getBlockState(MM_SHIFTER).getBlock(), Blocks.BEDROCK, "the block in the waiting shifter's spot");
                    // The bedrock goes: the shifter, which has been trying every tick, puts its gold back.
                    visible.setBlock(MM_SHIFTER, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    layer.removeBlock(MM_TAKING.west());
                    layer.removeBlock(MM_AREA.west());
                    layer.removeBlock(MM_PISTON.below());
                })
                .thenIdle(80)
                .thenExecute(() -> {
                    helper.assertValueEqual(visible.getBlockState(MM_SHIFTER).getBlock(), Blocks.GOLD_BLOCK, "the waiting shifter's gold, put back");
                    helper.assertValueEqual(lineWhole(helper, visible, hidden, MM_TAKING, "the line, put back"), 0, "blocks of the line still held");
                    helper.assertValueEqual(areaWhole(helper, visible, hidden, "the box, put back"), 0, "blocks of the box still held");
                    helper.assertValueEqual(visible.getBlockState(MM_PISTON.east()).getBlock(), Blocks.GOLD_BLOCK, "the phase piston's gold, pulled back");
                    helper.assertValueEqual(count(visible, MM_PISTON.west(), MM_PISTON.east(4), Blocks.GOLD_BLOCK), 1, "gold blocks at the phase piston");
                    visible.setChunkForced(MID_MOVE.x(), MID_MOVE.z(), false);
                })
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
        helper.assertFalse(Access.applyServerDefault(server, true, overworld.getDataStorage()),
                "a dedicated server switched survival_access off a second time, after a restart");
        helper.assertTrue(server.getGameRules().get(Access.SURVIVAL_ACCESS), "the admin's survival_access setting was undone by a restart");
        HiddenLayer layer = HiddenLayer.of(overworld);
        helper.assertValueEqual(overworld.getBlockState(SHIFTED).getBlock(), Blocks.AIR, "visible spot of the shifted gold after restart");
        ClaimMachineGameTests.assertOwner(helper, SHIFTED, SHIFTER_OWNER, "the shifter's owner after restart");
        ClaimMachineGameTests.assertOwner(helper, SHIFTED_CHEST, null, "the owner of a machine nobody placed, after restart");
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
