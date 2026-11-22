# PhaseCraft architecture

## The hidden layer is a real dimension

Each vanilla dimension has a hidden twin at the same coordinates:

| Visible | Hidden |
| --- | --- |
| `minecraft:overworld` | `phasecraft:hidden_overworld` |
| `minecraft:the_nether` | `phasecraft:hidden_the_nether` |
| `minecraft:the_end` | `phasecraft:hidden_the_end` |

They are ordinary data-pack dimensions (`data/phasecraft/dimension/*.json`):
empty void worlds using the **same dimension type** as their visible twin, so
the same height range and rules. Minecraft itself stores, ticks and runs
everything in them: full block states, block entities, scheduled ticks,
redstone, pistons. Hidden machinery therefore behaves exactly by vanilla
rules, including the inconvenient ones (redstone wire with nothing under it
breaks, as it would anywhere).

`HiddenLayer.of(level)` gives the visible/hidden pair for either side.
Writing through it never touches the visible world.

## Crossing between layers: only where you put a crossing block

The layers are independent except at blocks made for crossing. So far there
is one; phase pistons (movement) are next.

**Phase port** (`phasecraft:phase_port`, Redstone tab). A port gives out, in
its own layer and to every side like a redstone block, the power that reaches
its position in the *other* layer: from a signal source standing there (a
lever, torch, wire, repeater...), or from that position's neighbours there.
To send power down into the hidden layer, put the port in the hidden layer
under the visible lever; to send it up, put it in the visible layer.

- **Ports ignore other ports**, at the same spot or next to it, so two ports
  cannot pass power back and forth or hold each other on.
- **Crossing takes up to one game tick.** A change near a position queues a
  refresh of any port at that position in the other layer, delivered at the
  end of the tick (`PhaseUpdateQueue`). A loop through both layers therefore
  advances one hop per tick, like a clock, instead of recursing.
- A change counts if a signal source or conductor changes within two blocks
  (direct power, or power through a conductor).

**Hidden chunks tick with the visible world.** A chunk ticket (`phasecraft:phase_link`)
in the hidden layer follows each visible chunk's ticking status, so hidden
machinery runs wherever the world around it runs, and nowhere else. The
hidden layer's own chunks around a player standing in it also tick, as usual.

### Decision: explicit crossing points (decided 2026-11-22)

A first version let power cross at every position (any block heard a source
at its own spot in the other layer). The person preferred power to cross
only where wanted, and movement to cross only through special pistons. Ports
make every connection visible and deliberate, and the rest of the world pays
nothing for the hidden layer.

`/phasecraft layer hidden|visible` moves a player to the other layer at the
same position. Only Creative or Spectator players may enter: the hidden layer
has no ground of its own.

### Decision: real dimension, not a block list (decided 2026-11-22)

The first prototype kept hidden blocks as a per-dimension list in saved data.
That made coexistence trivial but left nothing able to *run*: every redstone
and piston rule would have had to be re-implemented. The person chose a real
dimension so vanilla does the simulating and PhaseCraft only adds the links
between layers. Costs accepted: an extra loaded level per dimension, chunk
loading to keep in step with the visible world (not done yet), and making
sure players cannot wander in by accident (portals in the hidden layer are
not handled yet).

### Upgrading worlds from the block-list builds

`LegacyLayerMigration` runs at server start. For each visible dimension it
reads the old list (`<world>/dimensions/<dim>/data/phasecraft/layer_1.dat`,
`{pos, state}` cells, or the first prototype's `{pos, block}` cells as
default states), copies every cell into the hidden dimension **exactly**
(no placement logic, so unsupported wire is not lost during the copy), and
removes it from the list. Running again moves nothing.

Cells outside the hidden dimension's height range (the old list accepted any
Y; the Nether starts at Y=0) cannot be placed: they **stay in the old list**
and a warning is logged, rather than being silently dropped.

The pre-26.3 (Minecraft 1.21.1) prototype's file
(`<world>/data/phasecraft_layer_1.dat`, `Cells` of `{Pos, Block}`) is **not
migrated**: no world in use was created with that build.

**Known risk (old list only):** a cell whose block no longer exists cannot be
decoded and is lost at the next save. In the hidden dimension, Minecraft's own
handling of missing blocks applies, as for any world.

## Open work and decisions

- **Phase pistons** (next): a special piston in the hidden layer that moves
  visible blocks by vanilla push rules (push limit 12, obsidian immovable).
  Ordinary pistons stay in their own layer. Decide what counts as an
  obstruction across layers.
- **Keeping players out:** portals built in the hidden layer, and deaths or
  logouts while in it.
- **Overlay:** hidden blocks drawn over the visible world; server state must
  reach observing clients first.

### Acceptance targets

1. **Hidden piston door:** a visible lever opens a 2x2 door whose pistons and
   wiring are all in the hidden layer. Power half done (lever, hidden port,
   hidden piston moves); needs phase pistons to move the visible door blocks.
2. **Flying machine (stretch):** a hidden slime/observer engine carrying
   visible blocks. Needs phase pistons to move mixed-layer structures.
3. **Mob farm:** not a target; the hidden layer does not interact with mobs.

## Validation status

All of these run on every `./gradlew build`, locally and in CI:

- **JUnit** (`src/test`): `LayerCellStore` invariants (used by the old list).
- **GameTests** (`src/gametest`, headless 26.3 server; a test-only mixin adds
  data-pack dimensions, which vanilla's test server leaves out):
  - a hidden block and a visible block coexist at the same XYZ;
  - breaking the visible block leaves the hidden block intact;
  - full block states are kept; vanilla rules apply (floating wire breaks);
  - vanilla redstone runs in the hidden layer (hidden power lights a hidden lamp);
  - Overworld, Nether and End have independent hidden dimensions;
  - hidden access far away does not load the visible chunk there;
  - old block-list cells migrate exactly; out-of-range ones are kept;
  - old `{pos, block}` files still load;
  - `/phasecraft set|get|clear` give the documented output, accept
    `/setblock` syntax, refuse unknown blocks and out-of-range positions, and
    require op level 2;
  - `/phasecraft layer` moves a Creative player there and back at the same
    position, and refuses Survival players;
  - without a port nothing crosses; a visible lever through a hidden port
    lights a hidden lamp and drives a hidden piston out and back; a hidden
    lever through a visible port lights a visible lamp; ports next to or at
    the same spot as other ports stay off; hidden chunks tick exactly where
    the visible world does.
- **JUnit** also checks the update queue: work added while a batch runs waits
  for the next tick.
- **Restart test** (`./gradlew restartTest`): two separate server processes
  on one world folder. The first places hidden blocks in all three hidden
  dimensions (one far away and unloaded) and stores some cells the old way;
  the second checks every hidden block and that the old cells were migrated.

Each test was checked by breaking the code on purpose and seeing it fail.

Checked by hand on the RCON dev server: a world with old-style hidden blocks
upgraded at startup, and `tools/examples.py` verifies the examples world,
including a hidden lamp lit by hidden power.

**Not yet automated:** multiplayer, and the client side of layer switching.
