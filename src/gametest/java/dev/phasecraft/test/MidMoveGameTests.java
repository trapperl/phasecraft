package dev.phasecraft.test;

import dev.phasecraft.PhaseCraftBlocks;
import dev.phasecraft.layer.HiddenLayer;
import dev.phasecraft.link.PhasePortBlock;
import dev.phasecraft.shifter.AreaShifterBlock;
import dev.phasecraft.shifter.AreaShifterBlockEntity;
import dev.phasecraft.shifter.LineShifterBlock;
import dev.phasecraft.shifter.LineShifterBlockEntity;
import dev.phasecraft.shifter.PhaseShifterBlockEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Moving machines interrupted mid-move, as players interrupt them all the time: they walk away (the chunks unload and
 * load again later), or the hidden layer ticks where the visible world is not there (a chunk at the edge of what is
 * loaded; a Creative player inside the hidden layer, whose hidden chunks tick farther out than the visible world is
 * kept loaded for them). Whatever happens, a machine finishes its move or goes on where it left off, every block is
 * in exactly one place, and power is right afterwards. (A server restart mid-move: PhaseLayerRestartPhases.)
 *
 * <p>Each test works at a site of its own, far from the other tests' structures: a visible chunk it keeps loaded
 * itself. Chunks are loaded by the test, not waited for: the test server's ticks run flat out while loading takes
 * its own time.</p>
 *
 * <p>The sites are fixed places on a grid, 16 chunks apart. Far enough apart that a site let go is written out and
 * dropped from memory though its neighbours are loaded (a chunk stays in memory within a dozen chunks of a loaded
 * one). Near enough that they share most of the unfinished chunks vanilla generates around every loaded chunk (eight
 * rings of them: seventeen sites at random places were some 7,500 new chunks a layer in every run, 130 MB more in
 * the test world each time, and minutes of saving when the server stopped). And fixed, so a second run on the same
 * world generates nothing; a site is therefore wiped before it is used, in case the run before failed there.</p>
 */
public class MidMoveGameTests {
    /** Chunks unloaded (written out and dropped from memory), as "dimension x z", since a site last asked. */
    private static final Set<String> UNLOADED = new HashSet<>();
    private static boolean listening;
    /** Sites to let go at the start of their hidden level's next tick (see Site.letGoBehind). */
    private static final List<Site> BEHIND = new ArrayList<>();

    /** The grid's first site (in the middle of a region file, far from everything), and how many sites make a row. */
    private static final int GRID_X = 204_816;
    private static final int GRID_Z = 204_816;
    private static final int GRID_ROW = 4;

    private static String key(ServerLevel level, ChunkPos chunk) {
        return level.dimension().identifier() + " " + chunk.x() + " " + chunk.z();
    }

    /** A far visible chunk and its hidden twin, loaded and let go by the test. */
    private static final class Site {
        final GameTestHelper helper;
        final ServerLevel visible;
        final ServerLevel hidden;
        final HiddenLayer layer;
        final ChunkPos chunk;
        /** The middle of the chunk, in the air. */
        final BlockPos origin;
        /** Everything the test built, to take away at the end: hidden spots, then visible ones. */
        final List<BlockPos> hiddenBuilt = new ArrayList<>();
        final List<BlockPos> visibleBuilt = new ArrayList<>();

        /** The site with this number on the grid: every test has its own. */
        Site(GameTestHelper helper, int number) {
            this.helper = helper;
            visible = helper.getLevel();
            layer = HiddenLayer.of(visible);
            hidden = layer.level();
            chunk = new ChunkPos(GRID_X + 16 * (number % GRID_ROW), GRID_Z + 16 * (number / GRID_ROW));
            origin = new BlockPos(chunk.getMinBlockX() + 8, 80, chunk.getMinBlockZ() + 8);
            if (!listening) {
                listening = true;
                ServerChunkEvents.CHUNK_UNLOAD.register((level, unloaded) -> UNLOADED.add(key(level, unloaded.getPos())));
                ServerTickEvents.START_LEVEL_TICK.register(level -> {
                    for (Site site : List.copyOf(BEHIND)) {
                        if (site.hidden == level) {
                            BEHIND.remove(site);
                            site.letGo();
                            site.visible.getChunkSource().pollTask();
                        }
                    }
                });
            }
        }

        /** Loads the visible chunks around now, and keeps the site's chunk ticking (its neighbours tick blocks too). */
        void load() {
            loadVisible();
            visible.setChunkForced(chunk.x(), chunk.z(), true);
        }

        /** Loads the visible chunks around now, without making them tick. */
        void loadVisible() {
            around(visible);
        }

        /** Loads the hidden chunks around now (their tickets then act at once). */
        void loadHidden() {
            around(hidden);
        }

        private void around(ServerLevel level) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    level.getChunk(chunk.x() + dx, chunk.z() + dz);
                }
            }
        }

        /** Lets the visible chunk go: it and its hidden twin stop ticking and unload. Nothing if it was let go already. */
        void letGo() {
            if (!visible.getForceLoadedChunks().contains(chunk.pack())) {
                return;
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    ChunkPos at = new ChunkPos(chunk.x() + dx, chunk.z() + dz);
                    UNLOADED.remove(key(visible, at));
                    UNLOADED.remove(key(hidden, at));
                }
            }
            visible.setChunkForced(chunk.x(), chunk.z(), false);
        }

        /**
         * Lets the visible chunk go as a server that is behind does (one with no time left between ticks, when
         * players have most reason to walk away): the visible level hears of it in its own tick, and the hidden level,
         * ticked after it, runs its scheduled ticks once more before it hears: the hidden layer ticks for one tick
         * with no visible world.
         */
        void letGoBehind() {
            BEHIND.add(this);
        }

        boolean ticking(BlockPos pos) {
            return visible.isPositionEntityTicking(pos) && hidden.isPositionEntityTicking(pos);
        }

        /** Both layers' chunks at these spots were written out and dropped since {@link #letGo}. */
        boolean gone(BlockPos... spots) {
            for (BlockPos spot : spots) {
                ChunkPos at = ChunkPos.containing(spot);
                if (!UNLOADED.contains(key(visible, at)) || !UNLOADED.contains(key(hidden, at))) {
                    return false;
                }
            }
            return true;
        }

        /** For a failure's message: what is still there of each layer at these spots. */
        String state(BlockPos... spots) {
            StringBuilder out = new StringBuilder();
            for (BlockPos spot : spots) {
                ChunkPos at = ChunkPos.containing(spot);
                for (ServerLevel level : List.of(visible, hidden)) {
                    if (!UNLOADED.contains(key(level, at))) {
                        out.append("; ").append(level == visible ? "visible" : "hidden").append(" chunk ").append(at.x()).append(' ')
                                .append(at.z()).append(level.getChunkSource().getChunkNow(at.x(), at.z()) == null ? " not ready" : " ready")
                                .append(level.isLoaded(spot) ? ", loaded" : ", not loaded")
                                .append(level.shouldTickBlocksAt(at.pack()) ? ", ticking" : ", not ticking");
                    }
                }
            }
            return out.toString();
        }

        void hiddenBlock(BlockPos pos, BlockState state) {
            layer.setBlock(pos, state);
            hiddenBuilt.add(pos);
        }

        void visibleBlock(BlockPos pos, BlockState state) {
            visible.setBlock(pos, state, Block.UPDATE_ALL);
            visibleBuilt.add(pos);
        }

        /** Takes everything away and lets the chunk go. */
        void clear() {
            for (BlockPos pos : hiddenBuilt.reversed()) {
                layer.removeBlock(pos);
            }
            for (BlockPos pos : visibleBuilt) {
                visible.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            visible.setChunkForced(chunk.x(), chunk.z(), false);
            hidden.setChunkForced(chunk.x(), chunk.z(), false);
        }

        /** Start: the chunk loaded and ticking, then the build, then its hidden chunks ticking too. */
        GameTestSequence start(Runnable build, BlockPos... spots) {
            load();
            return helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(visible.isPositionEntityTicking(origin), "the visible chunk ticks"))
                    .thenExecute(() -> {
                        loadHidden();
                        wipe();
                        build.run();
                    })
                    .thenWaitUntil(() -> assertTicking(spots));
        }

        /** Takes away whatever an earlier run left at the site (one that failed before it cleared up). */
        private void wipe() {
            hidden.setChunkForced(chunk.x(), chunk.z(), false);
            BlockPos from = new BlockPos(chunk.getMinBlockX() - 16, 72, chunk.getMinBlockZ() - 16);
            BlockPos to = new BlockPos(chunk.getMaxBlockX() + 16, 96, chunk.getMaxBlockZ() + 16);
            for (ServerLevel level : List.of(hidden, visible)) {
                for (BlockPos at : BlockPos.betweenClosed(from, to)) {
                    if (!level.getBlockState(at).isAir()) {
                        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
                    }
                }
            }
        }

        void assertTicking(BlockPos... spots) {
            helper.assertTrue(ticking(origin), "both layers tick at the site");
            for (BlockPos spot : spots) {
                helper.assertTrue(visible.shouldTickBlocksAt(ChunkPos.pack(spot)) && hidden.shouldTickBlocksAt(ChunkPos.pack(spot))
                        && hidden.getChunkSource().getChunkNow(spot.getX() >> 4, spot.getZ() >> 4) != null,
                        "both layers tick blocks at " + spot.toShortString());
            }
        }

        /** The player walks away and comes back: both layers' chunks unload, then load and tick again. */
        GameTestSequence awayAndBack(GameTestSequence sequence, BlockPos... spots) {
            return sequence
                    .thenExecute(this::letGo)
                    .thenWaitUntil(() -> helper.assertTrue(gone(spots), "both layers' chunks unloaded" + state(spots)))
                    .thenExecute(() -> {
                        load();
                        loadHidden();
                    })
                    .thenWaitUntil(() -> assertTicking(spots));
        }
    }

    private static final Block[] LINE = {Blocks.STONE, Blocks.GOLD_BLOCK, Blocks.BRICKS, Blocks.OAK_PLANKS,
            Blocks.COBBLESTONE, Blocks.IRON_BLOCK};

    /** A line shifter (delay 4) facing east two blocks from its chunk's east border, with six visible blocks in front. */
    private static BlockPos buildLine(Site site) {
        BlockPos at = new BlockPos(site.chunk.getMaxBlockX() - 2, 80, site.origin.getZ());
        for (int d = 1; d <= LINE.length; d++) {
            site.visibleBlock(at.east(d), LINE[d - 1].defaultBlockState());
        }
        site.hiddenBlock(at, PhaseCraftBlocks.LINE_SHIFTER.defaultBlockState()
                .setValue(LineShifterBlock.FACING, Direction.EAST).setValue(LineShifterBlock.DELAY, 4));
        return at;
    }

    private static LineShifterBlockEntity line(Site site, BlockPos at) {
        return (LineShifterBlockEntity) site.hidden.getBlockEntity(at);
    }

    /** Every block of the line is held or in the visible world, never both, never neither; how many are held. */
    private static int assertLineWhole(Site site, BlockPos at, String when) {
        LineShifterBlockEntity entity = line(site, at);
        int held = 0;
        for (int d = 1; d <= LINE.length; d++) {
            boolean there = site.visible.getBlockState(at.east(d)).is(LINE[d - 1]);
            boolean holds = entity.held(d) != null && entity.held(d).is(LINE[d - 1]);
            site.helper.assertTrue(there != holds, "the line's block " + d + " " + when + ": "
                    + (there ? "both in the world and held" : "neither in the world nor held"));
            site.helper.assertTrue(there || site.visible.getBlockState(at.east(d)).isAir(), "the line's spot " + d + " " + when);
            held += holds ? 1 : 0;
        }
        site.helper.assertValueEqual(entity.holding(), held, "blocks the line shifter holds " + when);
        return held;
    }

    /**
     * A line across a chunk border, left while it is being taken and again while it is being put back: after coming
     * back the move goes on by itself to its end.
     */
    @GameTest(maxTicks = 6000)
    public void aLineShifterGoesOnAfterItsChunksUnloadMidMove(GameTestHelper helper) {
        Site site = new Site(helper, 0);
        BlockPos[] at = new BlockPos[1];
        GameTestSequence sequence = site.start(() -> at[0] = buildLine(site), site.origin.east(8))
                .thenExecute(() -> site.hiddenBlock(at[0].west(), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenIdle(10)
                .thenExecute(() -> {
                    int held = assertLineWhole(site, at[0], "mid-take");
                    helper.assertTrue(held >= 1 && held < LINE.length, "not mid-take: holds " + held);
                });
        sequence = site.awayAndBack(sequence, site.origin, site.origin.east(8))
                .thenIdle(60)
                .thenExecute(() -> {
                    helper.assertValueEqual(assertLineWhole(site, at[0], "after coming back mid-take"), LINE.length,
                            "blocks taken after coming back mid-take");
                    site.layer.removeBlock(at[0].west());
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    int held = assertLineWhole(site, at[0], "mid-put-back");
                    helper.assertTrue(held >= 1 && held < LINE.length, "not mid-put-back: holds " + held);
                });
        site.awayAndBack(sequence, site.origin, site.origin.east(8))
                .thenIdle(60)
                .thenExecute(() -> {
                    helper.assertValueEqual(assertLineWhole(site, at[0], "after coming back mid-put-back"), 0,
                            "blocks still held after coming back mid-put-back");
                    helper.assertFalse(site.hidden.getBlockState(at[0]).getValue(LineShifterBlock.POWERED), "powered at the end");
                    site.clear();
                })
                .thenSucceed();
    }

    /**
     * The hidden layer ticks where the visible world is not loaded (here the hidden chunk is kept ticking on its own,
     * as around a Creative player inside the hidden layer). A line shifter powered then waits, and takes its line
     * once the visible world is there.
     */
    @GameTest(maxTicks = 6000)
    public void aLineShifterPoweredWithNoVisibleWorldWaitsForIt(GameTestHelper helper) {
        Site site = new Site(helper, 1);
        BlockPos[] at = new BlockPos[1];
        site.start(() -> at[0] = buildLine(site), site.origin.east(8))
                .thenExecute(() -> {
                    site.hidden.setChunkForced(site.chunk.x(), site.chunk.z(), true);
                    site.letGo();
                })
                .thenWaitUntil(() -> helper.assertFalse(site.visible.isLoaded(at[0]) || site.visible.isLoaded(at[0].east(4)),
                        "the visible chunks are still loaded"))
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(site.hidden.shouldTickBlocksAt(ChunkPos.pack(at[0])), "the hidden chunk ticks on its own");
                    site.hiddenBlock(at[0].west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertValueEqual(line(site, at[0]).holding(), 0, "blocks held with no visible world");
                    site.load();
                })
                .thenWaitUntil(() -> site.assertTicking(site.origin.east(8)))
                .thenIdle(80)
                .thenExecute(() -> {
                    helper.assertValueEqual(assertLineWhole(site, at[0], "once the visible world is back"), LINE.length,
                            "blocks taken once the visible world is back");
                    site.clear();
                })
                .thenSucceed();
    }

    /**
     * The visible world goes while a line shifter is taking its line and the hidden layer ticks on: the blocks it
     * could not reach are not skipped, it takes them once the visible world is back.
     */
    @GameTest(maxTicks = 6000)
    public void aLineShifterLosingTheVisibleWorldMidTakeFinishesLater(GameTestHelper helper) {
        Site site = new Site(helper, 2);
        BlockPos[] at = new BlockPos[1];
        site.start(() -> at[0] = buildLine(site), site.origin.east(8))
                .thenExecute(() -> {
                    site.hidden.setChunkForced(site.chunk.x(), site.chunk.z(), true);
                    site.hiddenBlock(at[0].west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    helper.assertValueEqual(line(site, at[0]).holding(), 1, "blocks taken at once");
                    site.letGo();
                })
                .thenWaitUntil(() -> helper.assertFalse(site.visible.isLoaded(at[0]) || site.visible.isLoaded(at[0].east(4)),
                        "the visible chunks are still loaded"))
                .thenIdle(80)
                .thenExecute(site::load)
                .thenWaitUntil(() -> site.assertTicking(site.origin.east(8)))
                .thenIdle(80)
                .thenExecute(() -> {
                    helper.assertValueEqual(assertLineWhole(site, at[0], "once the visible world is back"), LINE.length,
                            "blocks taken once the visible world is back");
                    site.layer.removeBlock(at[0].west());
                })
                .thenIdle(80)
                .thenExecute(() -> {
                    helper.assertValueEqual(assertLineWhole(site, at[0], "put back"), 0, "blocks still held at the end");
                    site.clear();
                })
                .thenSucceed();
    }

    /** A phase shifter powered where the visible world is not loaded takes its block once it is. */
    @GameTest(maxTicks = 6000)
    public void aPhaseShifterPoweredWithNoVisibleWorldWaitsForIt(GameTestHelper helper) {
        Site site = new Site(helper, 3);
        BlockPos at = site.origin;
        site.start(() -> {
            site.visibleBlock(at, Blocks.GOLD_BLOCK.defaultBlockState());
            site.hiddenBlock(at, PhaseCraftBlocks.PHASE_SHIFTER.defaultBlockState());
        })
                .thenExecute(() -> {
                    site.hidden.setChunkForced(site.chunk.x(), site.chunk.z(), true);
                    site.letGo();
                })
                .thenWaitUntil(() -> helper.assertFalse(site.visible.isLoaded(at), "the visible chunk is still loaded"))
                .thenIdle(5)
                .thenExecute(() -> site.hiddenBlock(at.above(), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenIdle(20)
                .thenExecute(site::load)
                .thenWaitUntil(() -> site.assertTicking())
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertTrue(site.hidden.getBlockEntity(at) instanceof PhaseShifterBlockEntity entity
                            && entity.held() != null && entity.held().is(Blocks.GOLD_BLOCK), "the powered shifter holds the gold");
                    helper.assertTrue(site.visible.getBlockState(at).isAir(), "the gold is still in the visible world");
                    site.layer.removeBlock(at.above());
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertValueEqual(site.visible.getBlockState(at).getBlock(), Blocks.GOLD_BLOCK, "the gold put back");
                    site.clear();
                })
                .thenSucceed();
    }

    private static BlockState phasePiston(boolean sticky) {
        return (sticky ? PhaseCraftBlocks.STICKY_PHASE_PISTON : PhaseCraftBlocks.PHASE_PISTON).defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST);
    }

    private static int count(ServerLevel level, BlockPos from, int length, Block block) {
        int n = 0;
        for (int d = 0; d < length; d++) {
            n += level.getBlockState(from.east(d)).is(block) ? 1 : 0;
        }
        return n;
    }

    /** A phase piston powered where the visible world is not loaded pushes once it is. */
    @GameTest(maxTicks = 6000)
    public void aPhasePistonPoweredWithNoVisibleWorldWaitsForIt(GameTestHelper helper) {
        Site site = new Site(helper, 4);
        BlockPos at = site.origin;
        site.start(() -> {
            site.visibleBlock(at.east(), Blocks.GOLD_BLOCK.defaultBlockState());
            site.visibleBlock(at.east(2), Blocks.AIR.defaultBlockState());
            site.hiddenBlock(at, phasePiston(false));
        })
                .thenExecute(() -> {
                    site.hidden.setChunkForced(site.chunk.x(), site.chunk.z(), true);
                    site.letGo();
                })
                .thenWaitUntil(() -> helper.assertFalse(site.visible.isLoaded(at), "the visible chunk is still loaded"))
                .thenIdle(5)
                .thenExecute(() -> site.hiddenBlock(at.below(), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenIdle(20)
                .thenExecute(site::load)
                .thenWaitUntil(() -> site.assertTicking())
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertValueEqual(site.visible.getBlockState(at.east(2)).getBlock(), Blocks.GOLD_BLOCK, "the pushed gold");
                    helper.assertValueEqual(count(site.visible, at, 5, Blocks.GOLD_BLOCK), 1, "gold blocks");
                    helper.assertTrue(site.hidden.getBlockState(at).getValue(BlockStateProperties.EXTENDED), "the powered piston is extended");
                    site.clear();
                })
                .thenSucceed();
    }

    /**
     * A sticky phase piston left while the block it pushes is sliding, and again while the block it pulls is: the
     * block lands, once, and the piston's state is right.
     */
    @GameTest(maxTicks = 6000)
    public void aPhasePistonsBlockLandsAfterItsChunksUnloadMidSlide(GameTestHelper helper) {
        Site site = new Site(helper, 5);
        // Pushes across the chunk's east border.
        BlockPos at = new BlockPos(site.chunk.getMaxBlockX() - 1, 80, site.origin.getZ());
        GameTestSequence sequence = site.start(() -> {
            site.visibleBlock(at.east(), Blocks.GOLD_BLOCK.defaultBlockState());
            site.visibleBlock(at.east(2), Blocks.AIR.defaultBlockState());
            site.hiddenBlock(at, phasePiston(true));
        }, at.east(2))
                .thenExecute(() -> site.hiddenBlock(at.below(), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenWaitUntil(() -> helper.assertValueEqual(site.visible.getBlockState(at.east(2)).getBlock(), Blocks.MOVING_PISTON,
                        "the gold sliding out"));
        sequence = site.awayAndBack(sequence, at, at.east(2))
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertValueEqual(site.visible.getBlockState(at.east(2)).getBlock(), Blocks.GOLD_BLOCK, "the pushed gold");
                    helper.assertValueEqual(count(site.visible, at, 5, Blocks.GOLD_BLOCK), 1, "gold blocks after the push");
                    helper.assertTrue(site.hidden.getBlockState(at).getValue(BlockStateProperties.EXTENDED), "the powered piston is extended");
                    site.layer.removeBlock(at.below());
                })
                .thenWaitUntil(() -> helper.assertValueEqual(site.visible.getBlockState(at.east()).getBlock(), Blocks.MOVING_PISTON,
                        "the gold sliding back"));
        site.awayAndBack(sequence, at, at.east(2))
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertValueEqual(site.visible.getBlockState(at.east()).getBlock(), Blocks.GOLD_BLOCK, "the pulled gold");
                    helper.assertValueEqual(count(site.visible, at, 5, Blocks.GOLD_BLOCK), 1, "gold blocks after the pull");
                    helper.assertFalse(site.hidden.getBlockState(at).getValue(BlockStateProperties.EXTENDED), "the unpowered piston is retracted");
                    site.clear();
                })
                .thenSucceed();
    }

    /**
     * A visible wire powered by a hidden port stays powered when something updates it while the port's hidden chunk is
     * not loaded yet (the visible chunk is there first: at the edge of what ticks, or for the few ticks its hidden
     * twin takes to load): once the hidden chunk ticks, the port's power is back.
     */
    @GameTest(maxTicks = 6000)
    public void aPortsPowerIsBackOnceItsHiddenChunkTicks(GameTestHelper helper) {
        Site site = new Site(helper, 6);
        BlockPos port = site.origin;
        BlockPos wire = port.east();
        site.start(() -> {
            site.visibleBlock(wire.below(), Blocks.STONE.defaultBlockState());
            site.visibleBlock(wire, Blocks.REDSTONE_WIRE.defaultBlockState());
            site.hiddenBlock(port.below(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            site.hiddenBlock(port, PhaseCraftBlocks.PHASE_PORT.defaultBlockState());
        })
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertValueEqual(site.hidden.getBlockState(port).getValue(PhasePortBlock.UP), 15, "the port's up");
                    helper.assertValueEqual(site.visible.getBlockState(wire).getValue(BlockStateProperties.POWER), 15, "the wire's power");
                    site.letGo();
                })
                .thenWaitUntil(() -> helper.assertTrue(site.gone(port), "both layers' chunks unloaded"))
                .thenExecute(() -> {
                    // The visible chunk is there, its hidden twin not yet; a block placed beside the wire updates it.
                    site.loadVisible();
                    helper.assertTrue(site.hidden.getChunkSource().getChunkNow(site.chunk.x(), site.chunk.z()) == null,
                            "the hidden chunk loaded before its twin ticked");
                    site.visibleBlock(wire.east(), Blocks.STONE.defaultBlockState());
                    site.load();
                    site.loadHidden();
                })
                .thenWaitUntil(() -> site.assertTicking())
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertValueEqual(site.hidden.getBlockState(port).getValue(PhasePortBlock.UP), 15, "the port's up after coming back");
                    helper.assertValueEqual(site.visible.getBlockState(wire).getValue(BlockStateProperties.POWER), 15,
                            "the wire's power once the port's hidden chunk ticks again");
                    site.clear();
                })
                .thenSucceed();
    }

    /** The box: 4 x 3 x 4 around the corner where four chunks meet, with a chest. */
    private static BlockState areaBlock(BlockPos low, BlockPos at) {
        int i = (at.getX() - low.getX()) + 4 * (at.getZ() - low.getZ()) + 16 * (at.getY() - low.getY());
        return i == 21 ? Blocks.CHEST.defaultBlockState() : (i % 3 == 0 ? Blocks.STONE_BRICKS : i % 3 == 1 ? Blocks.GOLD_BLOCK
                : Blocks.OAK_PLANKS).defaultBlockState();
    }

    private static BlockPos chestOf(BlockPos low) {
        return low.offset(1, 1, 1);
    }

    private static void buildArea(Site site, BlockPos low, BlockPos high) {
        for (BlockPos at : BlockPos.betweenClosed(low, high)) {
            site.visibleBlock(at.immutable(), areaBlock(low, at));
        }
        ((ChestBlockEntity) site.visible.getBlockEntity(chestOf(low))).setItem(4, new ItemStack(Items.EMERALD, 5));
        for (BlockPos corner : List.of(low, high)) {
            site.hiddenBlock(corner, PhaseCraftBlocks.AREA_SHIFTER.defaultBlockState().setValue(AreaShifterBlock.DELAY, 4));
        }
    }

    /** Every block of the box is held or in the visible world, never both, never neither; how many are held. */
    private static int assertAreaWhole(Site site, BlockPos low, BlockPos high, String when) {
        AreaShifterBlockEntity leader = (AreaShifterBlockEntity) site.hidden.getBlockEntity(low);
        int held = 0;
        for (BlockPos at : BlockPos.betweenClosed(low, high)) {
            BlockState is = areaBlock(low, at);
            boolean there = site.visible.getBlockState(at).is(is.getBlock());
            boolean holds = leader.held(at) != null && leader.held(at).is(is.getBlock());
            site.helper.assertTrue(there != holds, "the box's block at " + at.toShortString() + " " + when + ": "
                    + (there ? "both in the world and held" : "neither in the world nor held"));
            held += holds ? 1 : 0;
        }
        site.helper.assertValueEqual(leader.holding(), held, "blocks the area shifter holds " + when);
        if (site.visible.getBlockEntity(chestOf(low)) instanceof ChestBlockEntity chest) {
            site.helper.assertTrue(chest.getItem(4).is(Items.EMERALD) && chest.getItem(4).getCount() == 5, "the chest's emeralds " + when);
        }
        return held;
    }

    /**
     * An area shifter box over the corner of four chunks, left while it is being taken and again while it is being
     * put back: after coming back the move goes on by itself to its end.
     */
    @GameTest(maxTicks = 6000, environment = AreaShifterGameTests.RULE_ON)
    public void anAreaShifterGoesOnAfterItsChunksUnloadMidMove(GameTestHelper helper) {
        Site site = new Site(helper, 7);
        BlockPos low = new BlockPos(site.chunk.getMaxBlockX() - 1, 80, site.chunk.getMaxBlockZ() - 1);
        BlockPos high = low.offset(3, 2, 3);
        BlockPos[] spots = {low, high, low.east(3), low.south(3)};
        GameTestSequence sequence = site.start(() -> buildArea(site, low, high), spots)
                .thenExecute(() -> {
                    site.hiddenBlock(low.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    site.hiddenBlock(high.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    int held = assertAreaWhole(site, low, high, "mid-take");
                    helper.assertTrue(held >= 16 && held < 48, "not mid-take: holds " + held);
                });
        sequence = site.awayAndBack(sequence, spots)
                .thenIdle(60)
                .thenExecute(() -> {
                    helper.assertValueEqual(assertAreaWhole(site, low, high, "after coming back mid-take"), 48,
                            "blocks taken after coming back mid-take");
                    site.layer.removeBlock(high.east());
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    int held = assertAreaWhole(site, low, high, "mid-put-back");
                    helper.assertTrue(held >= 16 && held < 48, "not mid-put-back: holds " + held);
                });
        site.awayAndBack(sequence, spots)
                .thenIdle(60)
                .thenExecute(() -> {
                    helper.assertValueEqual(assertAreaWhole(site, low, high, "after coming back mid-put-back"), 0,
                            "blocks still held after coming back mid-put-back");
                    site.clear();
                })
                .thenSucceed();
    }

    /**
     * The visible world goes while an area shifter is taking its box and the hidden layer ticks on: the layers it
     * could not reach are not skipped, it takes them once the visible world is back.
     */
    @GameTest(maxTicks = 6000, environment = AreaShifterGameTests.RULE_ON)
    public void anAreaShifterLosingTheVisibleWorldMidTakeFinishesLater(GameTestHelper helper) {
        Site site = new Site(helper, 8);
        BlockPos low = site.origin;
        BlockPos high = low.offset(3, 2, 3);
        site.start(() -> buildArea(site, low, high))
                .thenExecute(() -> {
                    site.hidden.setChunkForced(site.chunk.x(), site.chunk.z(), true);
                    site.hiddenBlock(low.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    site.hiddenBlock(high.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
                    helper.assertValueEqual(assertAreaWhole(site, low, high, "at once"), 16, "blocks taken at once");
                    site.letGo();
                })
                .thenWaitUntil(() -> helper.assertFalse(site.visible.isLoaded(low), "the visible chunk is still loaded"))
                .thenIdle(80)
                .thenExecute(site::load)
                .thenWaitUntil(() -> site.assertTicking())
                .thenIdle(80)
                .thenExecute(() -> {
                    helper.assertValueEqual(assertAreaWhole(site, low, high, "once the visible world is back"), 48,
                            "blocks taken once the visible world is back");
                    site.layer.removeBlock(low.west());
                })
                .thenIdle(80)
                .thenExecute(() -> {
                    helper.assertValueEqual(assertAreaWhole(site, low, high, "put back"), 0, "blocks still held at the end");
                    site.clear();
                })
                .thenSucceed();
    }

    /**
     * A server that is behind runs the hidden layer for one more tick after the visible chunk went. A line shifter
     * (delay 1: a block every two ticks) whose step falls in that tick does not skip its block: back again, it has
     * taken all twelve. Two tests, let go one tick apart, so one of them has its step in that tick.
     */
    private static void lineShifterLeftBehind(GameTestHelper helper, int number, int wait) {
        Site site = new Site(helper, number);
        BlockPos at = new BlockPos(site.chunk.getMinBlockX() + 1, 80, site.origin.getZ());
        site.start(() -> {
            for (int d = 1; d <= 12; d++) {
                site.visibleBlock(at.east(d), Blocks.STONE.defaultBlockState());
            }
            site.hiddenBlock(at, PhaseCraftBlocks.LINE_SHIFTER.defaultBlockState()
                    .setValue(LineShifterBlock.FACING, Direction.EAST).setValue(LineShifterBlock.DELAY, 1));
        })
                .thenExecute(() -> site.hiddenBlock(at.west(), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .thenIdle(wait)
                .thenExecute(site::letGoBehind)
                .thenIdle(1)
                .thenWaitUntil(() -> helper.assertTrue(site.gone(site.origin), "both layers' chunks unloaded" + site.state(site.origin)))
                .thenExecute(() -> {
                    site.load();
                    site.loadHidden();
                })
                .thenWaitUntil(() -> site.assertTicking())
                .thenIdle(60)
                .thenExecute(() -> {
                    int held = line(site, at).holding();
                    int there = count(site.visible, at.east(), 12, Blocks.STONE);
                    site.clear();
                    helper.assertTrue(held == 12 && there == 0, "the line shifter holds " + held + " of 12, " + there
                            + " still in the world");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 6000)
    public void aLineShifterSkipsNoBlockWhenTheServerIsBehindOddTick(GameTestHelper helper) {
        lineShifterLeftBehind(helper, 9, 3);
    }

    @GameTest(maxTicks = 6000)
    public void aLineShifterSkipsNoBlockWhenTheServerIsBehindEvenTick(GameTestHelper helper) {
        lineShifterLeftBehind(helper, 16, 4);
    }

    /**
     * A hidden port keeps its power while the visible world at its spot is not loaded and the hidden layer ticks
     * on: what reaches it there is unknown then, not nothing. (It once dropped to 0 at the first hidden update
     * beside it, and came back on later: a pulse that toggles a hidden latch.)
     */
    @GameTest(maxTicks = 6000)
    public void aPortKeepsItsPowerWhileTheVisibleWorldIsGone(GameTestHelper helper) {
        Site site = new Site(helper, 10);
        BlockPos port = site.origin;
        site.start(() -> {
            site.visibleBlock(port, Blocks.REDSTONE_BLOCK.defaultBlockState());
            site.hiddenBlock(port, PhaseCraftBlocks.PHASE_PORT.defaultBlockState());
        })
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertValueEqual(site.hidden.getBlockState(port).getValue(PhasePortBlock.POWER), 15, "the port's power");
                    site.hidden.setChunkForced(site.chunk.x(), site.chunk.z(), true);
                    site.letGo();
                })
                .thenWaitUntil(() -> helper.assertFalse(site.visible.isLoaded(port), "the visible chunk is still loaded"))
                .thenIdle(5)
                .thenExecute(() -> site.hiddenBlock(port.east(), Blocks.STONE.defaultBlockState()))
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertFalse(site.visible.isLoaded(port), "the visible chunk was loaded again");
                    helper.assertValueEqual(site.hidden.getBlockState(port).getValue(PhasePortBlock.POWER), 15,
                            "the port's power with no visible world");
                    // The power does go while it is away, and the port hears of it once its chunk ticks again.
                    site.loadVisible();
                    site.visible.setBlock(port, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    site.load();
                })
                .thenWaitUntil(() -> site.assertTicking())
                .thenWaitUntil(() -> helper.assertValueEqual(site.hidden.getBlockState(port).getValue(PhasePortBlock.POWER), 0,
                        "the port's power once the visible world is back, its redstone block gone"))
                .thenExecute(site::clear)
                .thenSucceed();
    }

    /** Engine A flying east (FlyingMachineGameTests), its north-west slime block at engine, with visible gold as cargo. */
    private static void buildFlyingMachine(Site site, BlockPos engine, int wallAhead) {
        BlockState observer = Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
        BlockState stickyWest = Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.WEST);
        BlockState stickyEast = Blocks.STICKY_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST);
        site.layer.setBlock(engine, Blocks.SLIME_BLOCK.defaultBlockState());
        site.layer.setBlock(engine.east(), stickyWest);
        site.layer.setBlock(engine.south(), stickyEast);
        site.layer.setBlock(engine.south().east(), Blocks.SLIME_BLOCK.defaultBlockState());
        site.layer.setBlock(engine.above(), observer);
        site.layer.setBlock(engine.south().east().above(), observer);
        site.layer.setBlock(engine.north(), phasePiston(false));
        site.visible.setBlock(engine.north().east(), Blocks.GOLD_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        for (BlockPos at : BlockPos.betweenClosed(engine.offset(wallAhead, 0, -1), engine.offset(wallAhead, 1, 1))) {
            site.layer.setBlock(at.immutable(), Blocks.OBSIDIAN.defaultBlockState());
        }
    }

    /** How many of a block are in the machine's corridor (from two behind the start to the wall), in a level. */
    private static int inCorridor(ServerLevel level, BlockPos engine, int wallAhead, Block block) {
        int n = 0;
        for (BlockPos at : BlockPos.betweenClosed(engine.offset(-2, -1, -2), engine.offset(wallAhead + 2, 3, 3))) {
            n += level.getBlockState(at).is(block) ? 1 : 0;
        }
        return n;
    }

    /** Where (how far east of the start) the first such block is on the row at dz, dy; -99 if none. */
    private static int ahead(ServerLevel level, BlockPos engine, int wallAhead, int dy, int dz, Block block) {
        for (int dx = -2; dx <= wallAhead + 2; dx++) {
            if (level.getBlockState(engine.offset(dx, dy, dz)).is(block)) {
                return dx;
            }
        }
        return -99;
    }

    private static void clearCorridor(Site site, BlockPos engine, int wallAhead) {
        for (ServerLevel level : List.of(site.hidden, site.visible)) {
            for (BlockPos at : BlockPos.betweenClosed(engine.offset(-2, -1, -2), engine.offset(wallAhead + 2, 3, 3))) {
                if (!level.getBlockState(at).isAir()) {
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
                }
            }
        }
    }

    /**
     * The flying machine (a hidden engine whose phase piston pushes visible cargo), flying across a chunk border,
     * left mid-flight and come back to: it flies on to the hidden obsidian with its cargo, every part still there
     * once. Left at the given tick of its cycle (some stop a vanilla engine for good: then only what is there counts).
     */
    private static void flyingMachineLeftMidFlight(GameTestHelper helper, int number, int wait, boolean fliesOn, boolean behind) {
        if (HiddenChunkDelay.TICKS > 0) {
            // Held back that long, the hidden layer goes on ticking with no visible world, and the engine flies on
            // without its cargo, out of the chunk the test would come back to: nothing left to check.
            helper.succeed();
            return;
        }
        Site site = new Site(helper, number);
        int wall = 14;
        BlockPos engine = new BlockPos(site.chunk.getMaxBlockX() - 5, 80, site.origin.getZ());
        BlockPos[] spots = {engine, engine.east(wall)};
        GameTestSequence sequence = site.start(() -> buildFlyingMachine(site, engine, wall), spots)
                .thenIdle(5)
                .thenExecute(() -> {
                    site.layer.setBlock(engine.above(2), Blocks.STONE.defaultBlockState());
                    site.layer.removeBlock(engine.above(2));
                })
                .thenIdle(20 + wait)
                .thenExecute(() -> {
                    int at = ahead(site.hidden, engine, wall, 0, -1, PhaseCraftBlocks.PHASE_PISTON);
                    helper.assertTrue(at < wall - 4, "not mid-flight: the phase piston is " + at + " ahead");
                });
        if (behind) {
            sequence = sequence.thenExecute(site::letGoBehind).thenIdle(1);
        }
        // (After letGoBehind this lets nothing go a second time, and waits for the chunks.)
        site.awayAndBack(sequence, spots)
                .thenIdle(200)
                .thenExecute(() -> {
                    String found = "";
                    for (Block part : List.of(Blocks.SLIME_BLOCK, Blocks.STICKY_PISTON, Blocks.OBSERVER, PhaseCraftBlocks.PHASE_PISTON,
                            Blocks.MOVING_PISTON, Blocks.PISTON_HEAD)) {
                        found += " " + inCorridor(site.hidden, engine, wall, part);
                    }
                    int gold = inCorridor(site.visible, engine, wall, Blocks.GOLD_BLOCK);
                    int moving = inCorridor(site.visible, engine, wall, Blocks.MOVING_PISTON);
                    int piston = ahead(site.hidden, engine, wall, 0, -1, PhaseCraftBlocks.PHASE_PISTON);
                    int cargo = ahead(site.visible, engine, wall, 0, -1, Blocks.GOLD_BLOCK);
                    clearCorridor(site, engine, wall);
                    site.clear();
                    helper.assertValueEqual(found, " 2 2 2 1 0 0",
                            "the engine's slime blocks, sticky pistons, observers, phase piston, moving blocks and piston heads");
                    helper.assertTrue(gold == 1 && moving == 0, "the cargo: " + gold + " gold blocks, " + moving + " moving");
                    helper.assertTrue(!fliesOn || piston >= wall - 4, "the engine did not fly on to the obsidian: its phase piston is " + piston + " ahead");
                    helper.assertTrue(cargo > piston, "the cargo (" + cargo + " ahead) fell behind the phase piston (" + piston + " ahead)");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 6000)
    public void aFlyingMachineFliesOnAfterItsChunksUnloadMidFlight(GameTestHelper helper) {
        flyingMachineLeftMidFlight(helper, 11, 0, true, false);
    }

    @GameTest(maxTicks = 6000)
    public void aFlyingMachineFliesOnAfterItsChunksUnloadLaterInItsCycle(GameTestHelper helper) {
        flyingMachineLeftMidFlight(helper, 12, 7, true, false);
    }

    /**
     * Left at this tick of its cycle the engine stops for good, and so does the same engine built of vanilla blocks
     * in the visible world, left at the same tick (tried, 2026-11-27: vanilla's doing, not the hidden layer's). What
     * matters here: nothing of it or of its cargo is lost or doubled, and the cargo stays in front of the phase piston.
     */
    @GameTest(maxTicks = 6000)
    public void aFlyingMachineLeftWhereVanillaStallsLosesNothing(GameTestHelper helper) {
        flyingMachineLeftMidFlight(helper, 13, 4, false, false);
    }

    /**
     * Let go the way a server that is behind does it, at a tick of its cycle where the engine's observer fires in
     * the one tick the hidden layer runs with no visible world: the phase piston's push keeps its place before the
     * engine's own pistons, so back again it pushes the cargo before the engine carries it on. (It once looked
     * for the visible world only then, and the engine flew on without its cargo.)
     */
    @GameTest(maxTicks = 6000)
    public void aFlyingMachineKeepsItsCargoWhenTheServerIsBehind(GameTestHelper helper) {
        flyingMachineLeftMidFlight(helper, 14, 1, true, true);
    }

    @GameTest(maxTicks = 6000)
    public void aFlyingMachineKeepsItsCargoWhenTheServerIsBehindNextCycle(GameTestHelper helper) {
        flyingMachineLeftMidFlight(helper, 15, 11, true, true);
    }
}
