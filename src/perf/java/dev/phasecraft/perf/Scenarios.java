package dev.phasecraft.perf;

import dev.phasecraft.PhaseCraftBlocks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/** The scenarios (what each builds and compares is in its description, which goes into the results). */
final class Scenarios {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState WIRE = Blocks.REDSTONE_WIRE.defaultBlockState();
    private static final BlockState REDSTONE_BLOCK = Blocks.REDSTONE_BLOCK.defaultBlockState();
    private static final BlockState LAMP = Blocks.REDSTONE_LAMP.defaultBlockState();
    private static final BlockState PHASE_BLOCK = PhaseCraftBlocks.PHASE_BLOCK.defaultBlockState();
    private static final Runnable AS_SHIPPED = () -> { };

    private Scenarios() {}

    static List<Scenario> all() {
        List<Scenario> all = new ArrayList<>();
        all.add(wire("wire_visible_510", false, 17));
        all.add(wire("wire_visible_2040", false, 68));
        all.add(wire("wire_hidden_2040", true, 68));
        all.add(lamps());
        all.add(hoppers("hoppers_idle_1024", 32, false));
        all.add(hoppers("hoppers_pulling_256", 16, true));
        all.add(shifters());
        all.add(towers("tower_x1", 1));
        all.add(towers("tower_x16", 4));
        all.add(area("area_16", 1));
        all.add(area("area_16_x4", 2));
        all.add(twins("twins_441", 10));
        all.add(twins("twins_1089", 16));
        all.add(twins("twins_2601", 25));
        all.add(overlayStatic());
        all.add(overlayMoving());
        all.add(overlayChanges("overlay_changes_1p", 1));
        all.add(overlayChanges("overlay_changes_10p", 10));
        // Last: PhasedInput never forgets a plate it has seen (see plates_unloaded), so every measurement after
        // these would carry their plates' cost in all its variants.
        all.add(plates("plates_256", 16, 0));
        all.add(plates("plates_4096", 64, 0));
        all.add(plates("plates_4096_stands", 64, 200));
        all.add(platesUnloaded());
        return all;
    }

    private static Scenario platesUnloaded() {
        Scenario s = new Scenario("plates_unloaded", "Nothing here; 4,096 phased pressure plates built 640 blocks away in chunks "
                + "that nothing keeps loaded (gone from memory by the time of measuring: see hiddenChunks of the area, which "
                + "counts only this one's), plus every plate of the measurements before this one (in a full run: 3 rounds "
                + "of 2 x 256 + 4 x 4,096). PhasedInput keeps every plate it has seen and looks each up every tick. "
                + "`input_tick_off`: PhasedInput.tick does nothing.").chunks(1, 1);
        s.settle = 400;
        s.variant("as_shipped", AS_SHIPPED, c -> plateFloor(new Ctx(c.server, c.visible, c.hidden, c.origin.offset(640, 0, 0)), 64, 0),
                Scenarios::farLoaded);
        s.variant("input_tick_off", () -> Switches.inputTick = false,
                c -> plateFloor(new Ctx(c.server, c.visible, c.hidden, c.origin.offset(640, 0, 0)), 64, 0), Scenarios::farLoaded);
        return s;
    }

    /** farPlatesLoadedPerTick in the results: the share of measured ticks in which the far plates' chunk was in memory. */
    private static void farLoaded(Ctx c, int tick) {
        BlockPos far = c.origin.offset(640, 0, 0);
        if (c.hidden.getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) != null) {
            c.add("farPlatesLoaded", 1);
        }
    }

    // ---- redstone with no ports anywhere: the block-change hooks ----

    /** One row: two observers facing each other (a clock: 2 game ticks on, 2 off) each driving 15 redstone dust. */
    private static void wireRow(Ctx c, ServerLevel level, int z) {
        c.fillQuiet(level, -16, -1, z, 17, -1, z, STONE);
        for (int x = 1; x <= 15; x++) {
            c.set(level, -x, 0, z, WIRE);
            c.set(level, 1 + x, 0, z, WIRE);
        }
        c.set(level, 0, 0, z, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.EAST));
        c.set(level, 1, 0, z, Blocks.OBSERVER.defaultBlockState().setValue(ObserverBlock.FACING, Direction.WEST));
    }

    private static void wireRows(Ctx c, ServerLevel level, int rows) {
        for (int row = 0; row < rows; row++) {
            wireRow(c, level, 2 * row - rows);
        }
    }

    private static Scenario wire(String name, boolean hidden, int rows) {
        Scenario s = new Scenario(name, rows + " observer clocks (2 game ticks on, 2 off), each driving two lines of 15 redstone dust: "
                + rows * 30 + " dust, in the " + (hidden ? "hidden layer" : "visible world") + ". No phase ports, no players. "
                + "`link_off`: PhaseLink.onBlockChanged does nothing; `hooks_off`: none of the five block-change hooks do "
                + "anything; `hooks_bridge_off`: also no look for a hidden port on every visible signal read (as near to "
                + "vanilla as the mod gets).").chunks(2, 5);
        s.variant("as_shipped", AS_SHIPPED, c -> wireRows(c, hidden ? c.hidden : c.visible, rows));
        s.variant("link_off", () -> Switches.linkHook = false, c -> wireRows(c, hidden ? c.hidden : c.visible, rows));
        s.variant("hooks_off", Switches::hooksOff, c -> wireRows(c, hidden ? c.hidden : c.visible, rows));
        s.variant("hooks_bridge_off", () -> {
            Switches.hooksOff();
            Switches.bridge = false;
        }, c -> wireRows(c, hidden ? c.hidden : c.visible, rows));
        return s;
    }

    // ---- phased input: released plates are checked every tick ----

    private static Scenario plates(String name, int side, int stands) {
        Scenario s = new Scenario(name, "A " + side + " x " + side + " floor of phased pressure plates (hidden stone plates on phase "
                + "blocks), nobody on them" + (stands > 0 ? ", " + stands + " armour stands floating three blocks over the floor "
                + "(entities in the sections the plates search)" : "") + ". `input_tick_off`: PhasedInput.tick does nothing.")
                .chunks(side / 32 + 1, side / 32 + 1);
        s.variant("as_shipped", AS_SHIPPED, c -> plateFloor(c, side, stands));
        s.variant("input_tick_off", () -> Switches.inputTick = false, c -> plateFloor(c, side, stands));
        return s;
    }

    private static void plateFloor(Ctx c, int side, int stands) {
        int half = side / 2;
        c.fillQuiet(c.hidden, -half, -1, -half, half - 1, -1, half - 1, PHASE_BLOCK);
        for (int x = -half; x < half; x++) {
            for (int z = -half; z < half; z++) {
                c.set(c.hidden, x, 0, z, Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
            }
        }
        for (int i = 0; i < stands; i++) {
            Entity stand = EntityTypes.ARMOR_STAND.create(c.visible, EntitySpawnReason.COMMAND);
            stand.setNoGravity(true);
            BlockPos at = c.at((i * 37) % side - half, 3, (i * 53) % side - half);
            stand.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            c.visible.addFreshEntity(stand);
        }
    }

    // ---- phased lights ----

    private static Scenario lamps() {
        Scenario s = new Scenario("lamps_256", "256 redstone lamps (a 16 x 16 grid, two blocks apart) switched on and off together "
                + "every 10 ticks by redstone blocks the harness places over them. `phased`: hidden lamps on phase blocks, "
                + "lighting the visible world; `hidden_plain`: hidden lamps on stone (no light in the visible world); "
                + "`visible`: the same lamps in the visible world (vanilla).").chunks(2, 2);
        s.measure = 200;
        s.variant("phased", AS_SHIPPED, c -> lampGrid(c, c.hidden, PHASE_BLOCK), (c, tick) -> lampSwitch(c, c.hidden, tick));
        s.variant("hidden_plain", AS_SHIPPED, c -> lampGrid(c, c.hidden, STONE), (c, tick) -> lampSwitch(c, c.hidden, tick));
        s.variant("visible", AS_SHIPPED, c -> lampGrid(c, c.visible, STONE), (c, tick) -> lampSwitch(c, c.visible, tick));
        return s;
    }

    private static void lampGrid(Ctx c, ServerLevel level, BlockState under) {
        // A floor two blocks under the lamps for their light to fall on, in the visible world.
        c.fillQuiet(c.visible, -18, -3, -18, 17, -3, 17, STONE);
        for (int x = -16; x < 16; x += 2) {
            for (int z = -16; z < 16; z += 2) {
                c.quiet(level, x, -1, z, under);
                c.set(level, x, 0, z, LAMP);
            }
        }
    }

    private static void lampSwitch(Ctx c, ServerLevel level, int tick) {
        if (tick % 10 != 0) {
            return;
        }
        BlockState state = tick % 20 == 0 ? REDSTONE_BLOCK : AIR;
        for (int x = -16; x < 16; x += 2) {
            for (int z = -16; z < 16; z += 2) {
                c.set(level, x, 1, z, state);
            }
        }
    }

    // ---- phased hoppers ----

    private static Scenario hoppers(String name, int side, boolean pulling) {
        Scenario s = new Scenario(name, side * side + " hoppers in a " + side + " x " + side + " square"
                + (pulling ? ", a barrel full of stone on each (every hopper pulls an item every 8 ticks and fills up)"
                        : ", empty, nothing over them (each looks for something to pull every tick)")
                + ". `phased`: hidden hoppers on phase blocks pulling from the visible world" + (pulling ? " (visible barrels)" : "")
                + "; `hidden_plain`: hidden hoppers on stone" + (pulling ? " under hidden barrels" : "")
                + "; `visible`: vanilla hoppers in the visible world.").chunks(1, 1);
        s.variant("phased", AS_SHIPPED, c -> hopperFloor(c, c.hidden, PHASE_BLOCK, pulling ? c.visible : null, side));
        s.variant("hidden_plain", AS_SHIPPED, c -> hopperFloor(c, c.hidden, STONE, pulling ? c.hidden : null, side));
        s.variant("visible", AS_SHIPPED, c -> hopperFloor(c, c.visible, STONE, pulling ? c.visible : null, side));
        return s;
    }

    private static void hopperFloor(Ctx c, ServerLevel level, BlockState under, ServerLevel barrels, int side) {
        int half = side / 2;
        c.fillQuiet(level, -half, -1, -half, half - 1, -1, half - 1, under);
        for (int x = -half; x < half; x++) {
            for (int z = -half; z < half; z++) {
                if (barrels != null) {
                    c.set(barrels, x, 1, z, Blocks.BARREL.defaultBlockState());
                    if (barrels.getBlockEntity(c.at(x, 1, z)) instanceof BaseContainerBlockEntity barrel) {
                        for (int slot = 0; slot < barrel.getContainerSize(); slot++) {
                            barrel.setItem(slot, new ItemStack(Items.STONE, 64));
                        }
                    }
                }
                c.set(level, x, 0, z, Blocks.HOPPER.defaultBlockState());
            }
        }
    }

    // ---- shifters ----

    private static Scenario shifters() {
        Scenario s = new Scenario("shifters_4096", "A 64 x 64 visible stone floor with a hidden phase shifter in every block of it, all "
                + "switched together every 20 ticks by hidden redstone blocks the harness places under them (the floor goes, "
                + "comes back, goes...). `lamps`: hidden redstone lamps in place of the shifters, no floor (the cheapest "
                + "vanilla block that reacts to the same power); `pistons`: hidden vanilla sticky pistons pushing 4,096 hidden "
                + "stone blocks up and pulling them back (the nearest vanilla thing to moving 4,096 blocks at once).").chunks(3, 3);
        s.measure = 240;
        s.variant("shifters", AS_SHIPPED, c -> {
            c.fillQuiet(c.visible, -32, 0, -32, 31, 0, 31, STONE);
            floor(c, PhaseCraftBlocks.PHASE_SHIFTER.defaultBlockState());
        }, Scenarios::floorSwitch);
        s.variant("lamps", AS_SHIPPED, c -> floor(c, LAMP), Scenarios::floorSwitch);
        s.variant("pistons", AS_SHIPPED, c -> {
            floor(c, Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.UP));
            c.fillQuiet(c.hidden, -32, 1, -32, 31, 1, 31, STONE);
        }, Scenarios::floorSwitch);
        return s;
    }

    private static void floor(Ctx c, BlockState state) {
        for (int x = -32; x < 32; x++) {
            for (int z = -32; z < 32; z++) {
                c.set(c.hidden, x, 0, z, state);
            }
        }
    }

    private static void floorSwitch(Ctx c, int tick) {
        if (tick % 20 != 5) {
            return;
        }
        BlockState state = tick % 40 == 5 ? REDSTONE_BLOCK : AIR;
        for (int x = -32; x < 32; x++) {
            for (int z = -32; z < 32; z++) {
                c.set(c.hidden, x, -1, z, state);
            }
        }
    }

    private static Scenario towers(String name, int side) {
        Layout layout = Layout.load("castle_tower");
        Scenario s = new Scenario(name, side * side + " castle tower(s) (examples station 28: 118 visible blocks on 16 hidden line "
                + "shifters, 226 hidden blocks each), 20 blocks apart. `run`: every lever pulled at tick 10 (the towers come "
                + "down block by block) and again at tick 160 (they build up); `idle`: built and left alone.").chunks(3, 3);
        s.measure = 320;
        s.settle = 100;
        s.variant("run", AS_SHIPPED, c -> towerGrid(c, layout, side), (c, tick) -> {
            if (tick == 10 || tick == 160) {
                forEachTower(side, (dx, dz) -> {
                    BlockPos lever = c.at(dx + layout.lever.getX(), layout.lever.getY(), dz + layout.lever.getZ());
                    BlockState state = c.visible.getBlockState(lever);
                    if (state.getBlock() instanceof LeverBlock block) {
                        block.pull(state, c.visible, lever, null);
                    } else {
                        throw new IllegalStateException("no lever at " + lever);
                    }
                });
            }
        });
        s.variant("idle", AS_SHIPPED, c -> towerGrid(c, layout, side));
        return s;
    }

    private interface At {
        void at(int dx, int dz);
    }

    private static void forEachTower(int side, At action) {
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                action.at(20 * i - 10 * side, 20 * j - 10 * side);
            }
        }
    }

    private static void towerGrid(Ctx c, Layout layout, int side) {
        forEachTower(side, (dx, dz) -> layout.build(c, dx, 0, dz));
    }

    private static Scenario area(String name, int side) {
        int boxes = side * side;
        Scenario s = new Scenario(name, boxes + " full 16 x 16 x 16 box(es) of visible stone (4,096 blocks each) between two hidden area "
                + "shifters (a layer of 256 every 2 ticks, the default). `run`: powered at tick 10 (the box goes, top down), "
                + "unpowered at tick 150 (it comes back); `idle`: left alone; `plain_setblock`: no shifters, the harness "
                + "removes and replaces the same layers at the same times with plain block changes (what moving that many "
                + "blocks costs in vanilla, as /fill would).").chunks(3, 3);
        s.measure = 240;
        s.variant("run", AS_SHIPPED, c -> boxes(c, side, true), (c, tick) -> {
            if (tick == 10 || tick == 150) {
                forEachBox(side, (dx, dz) -> {
                    c.set(c.hidden, dx - 1, 0, dz, tick == 10 ? REDSTONE_BLOCK : AIR);
                    if (tick == 10) {
                        c.set(c.hidden, dx + 16, 15, dz + 15, REDSTONE_BLOCK);
                    }
                });
            }
        });
        s.variant("idle", AS_SHIPPED, c -> boxes(c, side, true));
        s.variant("plain_setblock", AS_SHIPPED, c -> boxes(c, side, false), (c, tick) -> {
            int down = tick - 10;
            int up = tick - 150;
            if (down >= 0 && down < 32 && down % 2 == 0) {
                int y = 15 - down / 2;
                forEachBox(side, (dx, dz) -> layer(c, dx, y, dz, AIR));
            } else if (up >= 0 && up < 32 && up % 2 == 0) {
                int y = up / 2;
                forEachBox(side, (dx, dz) -> layer(c, dx, y, dz, STONE));
            }
        });
        return s;
    }

    private static void forEachBox(int side, At action) {
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                action.at(40 * i - 20 * side, 40 * j - 20 * side);
            }
        }
    }

    private static void boxes(Ctx c, int side, boolean shifters) {
        forEachBox(side, (dx, dz) -> {
            c.fillQuiet(c.visible, dx, 0, dz, dx + 15, 15, dz + 15, STONE);
            if (shifters) {
                c.set(c.hidden, dx, 0, dz, PhaseCraftBlocks.AREA_SHIFTER.defaultBlockState());
                c.set(c.hidden, dx + 15, 15, dz + 15, PhaseCraftBlocks.AREA_SHIFTER.defaultBlockState());
            }
        });
    }

    private static void layer(Ctx c, int dx, int y, int dz, BlockState state) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                c.visible.setBlock(c.at(dx + x, y, dz + z), state, Block.UPDATE_ALL);
            }
        }
    }

    // ---- the hidden dimension itself ----

    private static Scenario twins(String name, int radius) {
        int chunks = (2 * radius + 1) * (2 * radius + 1);
        Scenario s = new Scenario(name, chunks + " visible chunks of a flat world kept loaded and ticking (a " + (2 * radius + 1) + " x "
                + (2 * radius + 1) + " square" + (radius == 10 ? ", one player's view at view distance 10" : "") + "), nothing "
                + "built. `follow_on`: as shipped, an empty hidden chunk loaded and ticking under each (hiddenChunks); "
                + "`follow_off`: PhaseLink.followTicking does nothing, so no hidden chunk is loaded. heapMb: heap in use "
                + "after a full collection (the difference is the hidden chunks' memory, roughly).").chunks(radius, radius);
        s.heap = true;
        s.settle = 200;
        s.cooldown = 200;
        s.variant("follow_on", AS_SHIPPED, c -> { });
        s.variant("follow_off", () -> Switches.followTicking = false, c -> { });
        return s;
    }

    // ---- the overlay sync ----

    private static void joinPlayers(Ctx c, int players) {
        for (int i = c.players.size(); i < players; i++) {
            c.players.add(FakePlayers.join(c.server, c.visible, c.origin.getX() + 1.5 + i, c.origin.getY() + 4, c.origin.getZ() + 8.5, i));
        }
    }

    private static Scenario overlayStatic() {
        Scenario s = new Scenario("overlay_static_10p", "41,472 hidden blocks (a 144 x 144 slab two thick: the 9 x 9 chunks the overlay "
                + "sends) and 10 Creative players with PhaseCraft who all join in the middle at tick 20 and stand still. p50 is "
                + "the steady state; max is the tick they join in (vanilla's join included in both). `overlay_off`: "
                + "OverlaySync does nothing. phasecraftBytesPerTick / phasecraftEncodeMsPerTick: PhaseCraft's messages, encoded "
                + "by the harness as the network threads would, off the tick.").chunks(7, 7);
        s.variant("as_shipped", AS_SHIPPED, c -> slab(c, 4, 4, 2), (c, tick) -> {
            if (tick == 20) {
                joinPlayers(c, 10);
            }
        });
        s.variant("overlay_off", () -> Switches.overlay = false, c -> slab(c, 4, 4, 2), (c, tick) -> {
            if (tick == 20) {
                joinPlayers(c, 10);
            }
        });
        return s;
    }

    /** A slab of hidden stone over (2 rx + 1) x (2 rz + 1) chunks around the origin, some layers thick, just under y. */
    private static void slab(Ctx c, int rx, int rz, int layers) {
        c.fillQuiet(c.hidden, -16 * rx, -1 - layers, -16 * rz, 16 * rx + 15, -2, 16 * rz + 15, STONE);
    }

    private static Scenario overlayMoving() {
        Scenario s = new Scenario("overlay_moving_10p", "10 Creative players with PhaseCraft flying along a strip of hidden stone (one "
                + "layer, 256 blocks a chunk, 37 x 9 chunks) at a chunk every 4 ticks (80 blocks a second, a fast elytra), "
                + "back and forth: every 4 ticks each gets 9 new hidden chunks and forgets 9. `overlay_off`: OverlaySync "
                + "does nothing.").chunks(20, 6);
        s.settle = 80;
        s.variant("as_shipped", AS_SHIPPED, c -> {
            slab(c, 18, 4, 1);
            joinPlayers(c, 10);
        }, Scenarios::fly);
        s.variant("overlay_off", () -> Switches.overlay = false, c -> {
            slab(c, 18, 4, 1);
            joinPlayers(c, 10);
        }, Scenarios::fly);
        return s;
    }

    private static void fly(Ctx c, int tick) {
        if (tick % 4 != 0) {
            return;
        }
        // A triangle wave over chunks -12..12.
        int step = (tick / 4) % 48;
        int chunk = step < 24 ? step - 12 : 36 - step;
        for (int i = 0; i < c.players.size(); i++) {
            FakePlayers.move(c.players.get(i), c.origin.getX() + 16 * chunk + 1.5 + i, c.origin.getY() + 4, c.origin.getZ() + 8.5);
        }
    }

    private static Scenario overlayChanges(String name, int players) {
        Scenario s = new Scenario(name, "The 2,040-dust hidden clock network of wire_hidden_2040 with " + players + " Creative "
                + "player(s) with PhaseCraft standing in the middle of it: every hidden block change is sent to each. "
                + "`overlay_off`: OverlaySync does nothing.").chunks(7, 7);
        s.variant("as_shipped", AS_SHIPPED, c -> {
            wireRows(c, c.hidden, 68);
            joinPlayers(c, players);
        });
        s.variant("overlay_off", () -> Switches.overlay = false, c -> {
            wireRows(c, c.hidden, 68);
            joinPlayers(c, players);
        });
        return s;
    }
}
