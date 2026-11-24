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

The layers are independent except at blocks made for crossing: phase ports
(power) and phase pistons (movement). Ordinary blocks, including ordinary
pistons, stay in their own layer.

**Phase port** (`phasecraft:phase_port`, Redstone tab). It belongs in the
hidden layer, where it carries power both ways at its spot and nothing shows
in the visible world (the person, 2026-11-23: "IF we have a block to move
power between visible and hidden worlds it should not be visible in the main
world").

- **Down:** the power reaching its spot in the visible world (a signal source
  standing there, such as a lever, or that spot's neighbours there) comes out
  of the port in the hidden layer, to every side like a redstone block.
- **Up:** the hidden power reaching the port is given out in the visible world
  at its spot, like an invisible redstone block standing there: a visible
  lamp, door or piston next to that spot is powered.
  `ServerLevelPhaseBridgeMixin` adds it to every signal read in the visible
  level.
- **No loops:** reading the visible world for down ignores what ports put up,
  and a port ignores other ports, so a port cannot hold itself or another
  port on (`HiddenPortGameTests`).

The phase block carries no power: it only makes a hidden block on it react to
the visible world (below). A port placed in the visible layer still works as
it did before 2026-11-23 (it gives out hidden power there), but it shows, so
the examples no longer use one.

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
machinery runs wherever the world around it runs, and nowhere else: blocks
tick where visible blocks tick, and entities (falling sand, TNT, items,
minecarts) where visible entities tick. (Until 2026-11-22 only blocks did,
so hidden falling blocks hung in the air.) The
hidden layer's own chunks around a player standing in it also tick, as usual.

**Phase piston / sticky phase piston** (Redstone tab). Placed in the hidden
layer, powered by hidden redstone exactly like a vanilla piston (including
quasi-connectivity), it moves the **visible** blocks in front of its
position. Which blocks move comes from vanilla's own `PistonStructureResolver`
run on the visible level: push limit 12, obsidian and other immovable blocks
stop it, slime and honey drag neighbours, fragile blocks break. The moved
blocks slide on players' screens as in vanilla: a vanilla piston has each
client run the move itself, which a hidden piston cannot, so the server sends
the moved blocks to nearby players (`PhasePistonAnimation`) and their game
places the same moving-block entities (until 2026-11-23 moved blocks
blinked out for two ticks; `PhasePistonAnimationClientGameTest`). It has **no arm in either layer**: where a
vanilla piston's head would be, the visible space is left empty. A sticky
phase piston pulls the visible block back by one on retracting, with
vanilla's sticky rules. Placed in the visible layer it does nothing.
`PhasePistonMover` is vanilla's `moveBlocks` minus the arm.

**Phase shifter** (`phasecraft:phase_shifter`, Redstone tab, Creative only
for now; the person's idea, 2026-11-23). Placed in the hidden layer: when
hidden power reaches it, it takes the visible block at its own spot out of
the visible world and holds it (`PhaseShifterBlockEntity`, saved with the
world); when the power goes, it puts that block back exactly as it was.
Nothing shows and nothing is heard in the visible world: the block is held,
not placed in the hidden layer, so it never collides with hidden machinery.
- It takes what a piston could push without breaking (vanilla's
  `PistonBaseBlock.isPushable`): not air, fluids, fragile blocks (torches,
  flowers), immovable ones (obsidian, bedrock) or blocks with contents
  (chests). It takes only when the power arrives, so a block placed in the
  gap while it is powered stays.
- Putting back waits, retrying every tick, while the spot is taken by a
  block or by a mob or player standing there.
- Removed while holding a block, it puts it back if the spot is free, or
  drops it there as an item: never lost, never doubled.
- `PhaseShifterGameTests`, and `PhaseLayerRestartPhases` (a held block
  survives a restart). Examples station 18: four bricks of a wall vanish
  into a doorway.

## The overlay: seeing the hidden layer from the visible world

The hidden layer is a separate dimension, but it is meant to be seen as a
transparent layer over the visible world. Two halves:

**Server: `OverlaySync`.** Each tick, for each eligible player, the server
sends the non-air hidden blocks of every hidden chunk within 4 chunks of the
player (a 9x9 square) that the client does not have yet, tells the client to
forget chunks that left that square, and passes on hidden block changes in
chunks the client has (from the same block-change hook as the phase link).
Large chunks are split into messages of at most 4096 blocks. Only hidden
chunks that are already loaded are sent; nothing is loaded for the overlay.
Messages: `phasecraft:hidden_chunk`, `hidden_blocks`, `hidden_forget`,
`hidden_reset` (`OverlayPayloads`).

Eligible means: the client has PhaseCraft (vanilla clients get nothing), the
player is in Creative or Spectator, and the player is in a visible dimension
with a hidden twin. A player who stops being eligible (changes to Survival,
enters the hidden layer) or changes dimension gets a reset. A client clears
its copy whenever a new connection starts.

**Client: `GhostLayer`, `GhostRenderer`** (in `src/client`, a client-only
source set). The client keeps what it was sent and draws each hidden block's
own model, full-bright, at the chosen opacity, in two passes:

1. like a normal translucent block (vanilla's translucent moving-block render
   type): hidden by visible blocks in front of it. Ghosts are drawn 0.3%
   larger than a block, so a hidden block sharing its spot with a visible one
   lies on the visible block's surface as a tint instead of flickering;
2. without depth test, at 40% of that opacity (`GhostRenderTypes`, a PhaseCraft
   render pipeline using vanilla's block shader, with an order-independent
   transparency variant for "improved transparency"): hidden machinery inside
   a wall or under the floor shows through, fainter.

Faces between two hidden full blocks are left out, as in the normal world.
Redstone wire and other tinted blocks keep their tint (wire shows its power).
Blocks that vanilla draws with special renderers from block entity data the
client does not have (chests, signs, beds, banners, heads) are shown as
their shape in their particle texture. Water and lava are shown as a box of
their height in their own texture and tint, without faces between hidden
fluid of the same kind. Air, barriers and light blocks are not shown. Keys: H toggles, `]` and `[` change the opacity
(15% to 100%); kept in `config/phasecraft-client.properties`.

Cost: the geometry of each chunk's ghosts is built once (the same code as
before, recorded instead of drawn: positions relative to the chunk, colours
without the pass's alpha) and kept until hidden blocks in that chunk, or on
the border of a neighbouring one, change (`GhostLayer.takeChangedChunks`),
or the block models are reloaded. Each frame replays the meshes of chunks
within about 100 blocks, with the camera transform and each pass's alpha. On
18,432 hidden blocks around the camera that took the overlay's CPU time per
frame from 9.4 ms to 0.9 ms (`OverlayCacheClientGameTest` logs it), and the
cached geometry matches drawing every ghost from its model, vertex for
vertex, also after changes and across chunk borders.

## Editing mode: building in the hidden layer from the visible world

**Client: `ClientEditing`** (key J, Creative only). While on, after vanilla
picks what the crosshair is on, PhaseCraft picks again: the nearest hidden
block along the view within reach (a raycast through the client's copy of
the hidden layer, ignoring visible blocks, so hidden blocks inside walls can
be reached as the overlay shows them), or else the visible block looked at.
The target is outlined in purple. Vanilla's own pick is set to "nothing"
and left/right clicks are taken over (`MinecraftEditingMixin`), so nothing
visible is broken, placed or used. Clicks are sent to the server as
`phasecraft:edit_use` (hand + hit) and `phasecraft:edit_break` (position).

**Server: `HiddenEditing`.** Checks as vanilla checks a click (Creative,
within reach, hit point on the block), then:

- *use*: vanilla's `ServerPlayerGameMode.useItemOn` steps, carried out in the
  hidden level (vanilla's would take the player's own, visible level): the
  hidden block's own use first (levers, repeaters), then the held item with a
  context in the hidden level. Aimed at a visible block with nothing hidden
  there, the clicked spot is hidden air, which is replaceable, so by vanilla's
  placement rules the new block goes at that same spot.
- *break*: removes the hidden block as Creative breaking does
  (`playerWillDestroy`, remove, `destroy`); never the visible block.

- *pick* (middle click): a hidden block's item goes to your hand, as vanilla's
  pick does; with Ctrl held, in Creative, with its block entity data too (a
  hidden chest's contents), as vanilla's pick copies it; aimed at a visible
  block, vanilla's own pick.

Sounds play in the visible level, where the player is. While editing, every
right click is taken over, so items that act on air rather than on a
clicked block (ender pearls) do nothing. Buckets work at the editing target
(`useBucket`, vanilla's bucket logic without its own aim): a full bucket
pours into the clicked spot when nothing is hidden there, into a
waterloggable hidden block, or next to the hidden block clicked; an empty
one scoops hidden fluid up. Fish buckets are refused (the fish would be let
out in the hidden layer).

### Decision: a click on a visible block places at that same spot (decided by the agent, 2026-11-22; open to change)

Overlapping a visible block is the point of PhaseCraft (a port under a
lever, a lamp inside a wall), and vanilla's own rule gives it for free:
the hidden spot is air, and air is replaced. Placing *next to* a visible
block works by clicking a hidden block instead (for example the hidden
block just placed).

### Decision: who sees the overlay (decided by the agent, 2026-11-22; open to change)

The same rule as entering the hidden layer: Creative and Spectator. A
Survival player sees nothing, and a modded client cannot ask for more, so a
hidden door in a multiplayer world stays hidden from Survival players. Easy
to widen if the person prefers (for example to operators, or to everyone).

### Decision: see-through by default (decided by the agent, 2026-11-22)

A first version drew ghosts only where no visible block was in front of
them; in the examples world that hid exactly the interesting part, the
door's machinery inside the wall. The person expects a transparent layer, so
ghosts also show through visible blocks, fainter, to keep a sense of depth.

### Decision: explicit crossing points (decided 2026-11-22)

A first version let power cross at every position (any block heard a source
at its own spot in the other layer). The person preferred power to cross
only where wanted, and movement to cross only through special pistons. Ports
make every connection visible and deliberate, and the rest of the world pays
nothing for the hidden layer.

`/phasecraft layer hidden|visible` moves a player to the other layer at the
same position. Only Creative or Spectator players may enter: the hidden layer
has no ground of its own.

### Phased blocks: the phase block (`dev.phasecraft.phased`)

A hidden block standing on a phase block, or hanging on its side (the block
a button or lever is attached to, otherwise the one below), is phased: it
reacts to the visible world at its own spot. The phase block itself is an
ordinary block that pistons cannot move, so moving blocks never quietly
changes what is phased.

- **Buttons and levers** (`PhasedInput`): a right-click on a visible block
  (Fabric's use-block event, server side) uses the phased button or lever
  hidden at that spot, through vanilla's own `useWithoutItem` on the hidden
  level. Sneaking with something in hand passes the click on, as vanilla
  does for buttons. The server swings the arm and undoes any placement the
  client predicted.
- **Pressure plates:** every plate's signal goes through vanilla's
  `getEntityCount`; for a phased plate `BasePressurePlateMixin` counts the
  visible level's entities in the same box. No entity ever touches a hidden
  plate, so `PhasedInput` keeps a list of the plates in loaded hidden chunks
  (block changes and chunk loads) and checks the released ones every tick;
  once pressed, the plate's own scheduled ticks keep counting.
- **No sound:** hidden blocks make no sound in the visible world (the
  person, 2026-11-23: "Let's make them silent in the overworld"), phased or
  not: a phased button's click and a phase piston's push are heard only in
  the hidden layer (`SilentHiddenGameTests`). Until then the clicks were
  echoed into the visible world.
- **Light** (`PhasedLights`, `BlockLightEnginePhasedMixin`): the server
  keeps the light each phased hidden block gives off, per visible
  dimension, from hidden block changes and hidden chunk loads (a loaded
  chunk is scanned on the next tick: reading a block of a chunk while it is
  still loading waits for that very chunk, which hung the server at startup;
  the restart test now stores a phased light). Vanilla's
  block light engine asks a position's emission through `getEmission`;
  the mixin returns the brighter of the visible block's own and the phased
  light at that spot, and lighting a chunk from scratch also starts from
  its phased lights. A change re-checks the spot; a phased light can sit in
  a section vanilla thinks is empty (only air), where it keeps no light, so
  the section is first marked as holding something, as vanilla does when a
  block is placed there. (Lighting a chunk from scratch including its
  phased lights is not covered by a test: GameTest areas are never lit from
  scratch.) Clients: vanilla's light updates alone never showed
  the light on screen (tested), and clients relight by themselves when
  nearby blocks change, so every player with PhaseCraft is sent the phased
  lights of their dimension, and changes; the client's light engine uses
  them through the same mixin. Players without PhaseCraft see what the
  server sends, which can go stale.
  Vanilla starts queued light work only when the server has spare time
  between ticks; GameTests run ticks back to back with none, so a phased
  light check could wait indefinitely (light tests failed at random, on CI
  too). After queuing a check PhasedLights asks the light engine to run
  (`tryScheduleUpdate`), and asks the light engine of every level with a
  hidden twin again every tick, as vanilla does in spare time: the check
  reaches the engine's queue through another thread, so the first ask can
  find it empty, and on a slow, busy machine it can arrive long after
  (all light tests failed together on one CI run with only the first ask;
  a lamp's light stayed on with asks for only 40 ticks after the change).
  Light tests still allow long tick limits.

### Decision: one phase block, not a phased variant of each block (decided by the person, 2026-11-23)

Dedicated blocks (a phase button, a phase plate) were the first plan, like
the phase piston. The person chose one phase block instead: one item and
one rule ("what stands on it is phased") for every kind of block, and still
an explicit crossing. It costs a hidden cell under or behind each phased
block; phase pistons and ports stay separate blocks for that reason (the
staircase stacks pistons with no room for one).

### Who may see and edit: `Access` (Survival play, decided by the person, 2026-11-23)

Creative always; Spectator may see (overlay), not edit; Survival while the
game rule `phasecraft:survival_access` is on (default on; servers can turn
it off: Survival editing can build inside other people's walls, past claim
mods); Adventure never. Each client is told whether it may edit, so J can
say why not. Survival edits by Survival rules (`HiddenEditing`): placing
uses the item (Creative's count is restored); breaking needs a start
message and then the block's mining time by vanilla's own measure
(`getDestroyProgress` for this player and tool, with vanilla's 70%
allowance for lag), drops go to the visible world with vanilla's loot and
tool rules, the tool wears, stats and exhaustion count; middle click only
selects what is carried. The client mines at vanilla's speed with vanilla's
cracks. Recipes are data (`data/phasecraft/recipe`), the person's starting
point for balancing; each unlocks in the recipe book from amethyst shards
(Phase Block) or Phase Blocks (the rest).

### Nobody ends up in the hidden layer by accident (`HiddenLayerGuard`)

- **Only Creative and Spectator players may be there.** Any other player
  found in a hidden dimension at the end of a tick (switched to Survival
  inside, a forced game mode on login, a teleport by command) is moved to the
  visible layer at the same spot, or the first spot above it where they fit,
  with a message. Logging out inside is fine: you come back where you were,
  and the same rule applies.
- **No respawn point in the hidden layer.** Beds and respawn anchors used
  there do not set your spawn (`ServerPlayerRespawnMixin`), so a death never
  brings you back over the void.
- **Portals in the hidden layer do nothing** (`EntityPortalMixin`, on
  `Entity.setAsInsidePortal`, which every portal block goes through). They
  would lead into the real Nether or End and build a visible portal there.
- **No mobs spawn there naturally:** the hidden dimensions use the void
  biome, which has no mob spawns.

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

**Missing blocks: decided, treated as vanilla does** (2026-11-22). In the
hidden dimension, a block that no longer exists (a removed mod, a block a
Minecraft update drops) is handled by Minecraft as in any world. In the old
block list, read only during the one-time upgrade, such a cell cannot be
decoded: the parse error is logged and that cell is dropped. No world in use
has such cells.

## Open work and decisions

- **Phase-piston arm: decided, no visible arm** (2026-11-22). A phase piston
  leaves the visible space in front of it empty when extended, where vanilla
  would show the head. Sliding doors therefore need somewhere hidden to
  slide into (see the door below).
- **Keeping players out: done** (see above).
- **Overlay: done** (see above), including chests, signs and fluids.
- **Editing mode: done** (see above), including middle-click pick and
  buckets (not fish buckets).

### Acceptance targets

1. **Hidden piston door: done.** A visible lever opens and closes a 2x2 flush
   door in a visible wall; every piston and wire is in the hidden layer
   (`HiddenDoorGameTests`, examples station 10). Two stages, as in a vanilla
   flush door: sticky phase pistons hidden in the corridor push the door
   blocks back behind the wall face, then sticky phase pistons hidden inside
   the wall slide them sideways into pockets. Timing is two vanilla
   repeaters: stage 2 follows the lever after a delay; stage 1 follows the
   lever and is held on by stage 2's signal through a second delay, so on
   closing stage 2 pulls back first.
2. **Hidden staircase: done.** A visible lever turns a 2x3 piece of flat floor
   into stairs down to a cellar, and back (`HiddenStaircaseGameTests`,
   examples station 11). Two stages: up-facing sticky phase pistons in the
   ground hold the six floor blocks up and let them drop one block (the front
   pair becomes the first step); then sideways sticky phase pistons push the
   back four into pockets inside the side walls, under the floor, uncovering
   two fixed steps built lower in the pit. The timing is the door's, with a
   torch, since stage A is powered while the stairs are closed.
   Lesson for stacked phase pistons: like vanilla pistons they have
   quasi-connectivity (power reaching the block above a piston powers it).
   Stage A pistons sit right under stage B pistons, so anything pointing into
   a stage B piston would fire stage A too; stage B is powered only through
   its own quasi-connectivity, by repeaters pointing into the empty hidden
   cell above each B piston.
3. **3x3 flush door: done.** The famously hard vanilla door: one lever opens a
   3x3 door flush with a wall at the end of a tunnel, and closes it flush
   again (`ThreeByThreeDoorGameTests`, examples station 12; layout in
   `src/gametest/resources/contraptions/flush_door_3x3.txt`). The hard part
   is the centre block, surrounded by doorway on every side, so it must move
   twice. Stage 1 pushes all nine blocks back into the wall. Stage 2, with
   pistons hidden inside the doorway and the wall: sticky pullers outside
   the side columns pull them into pockets, a sticky puller under the floor
   drops the bottom-middle block into the floor, and two pushes from below
   lift the centre and top-middle blocks, as one line, into a two-high
   pocket over the doorway. Closing pushes them down from above (two
   pistons, the top one powered only through its quasi-connectivity, as in
   the staircase), returns the rest, and pulls the nine forward. Stage 1's
   hold must outlast every return move: with one 4-tick delay it pulled
   while the last blocks were still sliding, and they stayed in the wall.
4. **Flying machine: done (option A, chosen by the person).** A vanilla
   flying machine built entirely in the hidden layer carries a phase piston
   that pushes the visible cargo one block ahead each cycle
   (`FlyingMachineGameTests`, examples station 15). Nothing new was needed.
   - The engine is the 6-block two-way "engine A" from the Minecraft Wiki
     (Tutorial:Flying machines/Java Engine Gallery): two slime blocks, two
     sticky pistons facing each other, two observers facing up whose output
     goes down into the slime blocks. It moves one block about every 10
     ticks. Brute-force searches over small layouts found no engine, and
     observers do not fire just from being moved, so start from a
     documented engine.
   - A phase piston facing the direction of flight is stuck to one slime
     block. The slime block it rides on powers it once per cycle, so each
     cycle it pushes the visible block in front of it one block on, keeping
     pace. The cargo is pushed, not carried: vanilla push rules, up to 12
     blocks, and visible slime drags more.
   - The engine and the cargo are in different layers, so neither blocks
     the other; hidden obsidian ahead of the engine stops it, as in vanilla.
     A visible wall in front of the cargo stops only the cargo.
   - Starting it: a block changing in front of an observer. Station 15 hangs
     a phased lever (on a phase block) above the first observer, so a click
     on the visible post top starts the flight.
   - Built by commands: a plain `/setblock` of an observer fires it (the
     command updates the new block from its neighbours), and placing the
     observers in the same command batch as the rest started the engine
     early. tools/examples.py places them `strict`, a second later.
   - Hidden observers ignore visible changes in front of them (checked on
     the dev server): the layers stay separate.

   What the pieces do (`SlimeAcrossLayersGameTests`): a phase piston pushing
   a visible slime block drags the visible blocks stuck to it; hidden blocks
   at those spots stay put (the layers move independently, so one slime
   structure cannot span both layers); a phase piston moved by a vanilla
   piston in the hidden layer keeps working where it lands. Option B, one
   structure across both layers (for example a phased slime block that also
   moves what is stuck to it in the other layer), would need new rules and
   was not chosen.
5. **Secret room behind a bookshelf wall: done.** A wall of bookshelves with
   a room behind it; two of the bookshelves are the door, and one bookshelf
   on each side of the wall is the switch (`SecretRoomGameTests`, examples
   station 16; layout in `src/gametest/resources/contraptions/secret_room.txt`).
   Nothing redstone is visible, which vanilla cannot do: its switch is always
   a visible button, lever or plate (or a trapped chest, a lectern...).
   - The switch: a hidden phase block inside the wall with a phased button
     hanging on each side, so a click on either bookshelf presses it. A
     hidden waxed copper bulb toggles on each press (vanilla's one-block
     T flip-flop) and a comparator reads it: the door's state.
   - The door: the 2x2 hidden door's mechanism, one block wide. Stage 1
     (sticky phase pistons in the corridor) pushes the two door bookshelves
     back into the wall; stage 2 (sticky phase pistons inside the wall)
     slides them sideways into a pocket. The same repeater timing holds
     stage 1 on until stage 2 has pulled back.
   - Lessons: a lone `redstone_wire` placed by `/setblock` (or a layout) is a
     dot, which powers only the block under it; give it sides
     (`[north=side,...]`) for the cross a player places. And `/setblock` of a
     button does not update what is next to the block the button hangs on,
     as a real press does (tools/examples.py re-places the wire there).
6. **Trapdoor floor: done.** A corridor floor where stepping on one tile drops
   the next two away into a pit; they close again once nobody stands on the
   trigger (`TrapdoorFloorGameTests`, examples station 17; layout in
   `src/gametest/resources/contraptions/trapdoor_floor.txt`). The trigger is
   a phased pressure plate hidden on a phase block at the tile's spot, so the
   floor looks like plain floor; vanilla needs a visible plate or tripwire.
   - Two stages, as in the staircase: up-facing sticky phase pistons under
     the tiles hold them up (powered through torches while closed) and let
     them drop one block; sideways sticky phase pistons push them into
     pockets under the wall. "Open" is the plate at once, held on by stage 2
     delayed, so on closing stage 1 waits until stage 2 has pulled back.
   - The plate is on the tile before the trap, not on the trap tiles: a plate
     strongly powers the phase block it stands on, which would power the
     pistons around it (and, through quasi-connectivity, stage 1).
   - Placed by a layout, an extended piston must come after whatever powers
     it, or it retracts on placement.
7. **Mob farm:** not a target; the hidden layer does not interact with mobs.

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
    lever through a visible port lights a visible lamp, and hidden power
    through a hidden port too (`HiddenPortGameTests`); ports next to or at
    the same spot as other ports stay off; hidden chunks tick exactly where
    the visible world does.
  - editing mode on the server: placing at a visible block's spot, next to a
    hidden block, facing as in vanilla, using a hidden lever, breaking only
    the hidden block, picking the hidden block into the hand; Survival players
    and clicks out of reach refused;
  - keeping out: a player switched to Survival inside the hidden layer is
    moved out at the same spot (lifted out of visible blocks if needed);
    Creative and Spectator players stay; no respawn point can be set there; a
    mob standing in a hidden Nether portal stays put; hidden entities tick
    where visible ones do (hidden sand falls and lands);
  - the hidden 2x2 door opens and closes from one visible lever (and jams
    half-closed if the hold repeater is removed);
  - the hidden staircase opens, stays open, closes, stays closed and opens
    again from one visible lever (and fails without phase-piston
    quasi-connectivity, without sticky pull-back, or without the hold
    repeater);
  - phased blocks: a Survival player's right-click on a plain visible brick
    presses the phased button hidden there (released by vanilla timing) and
    toggles a phased lever; an unphased hidden button ignores the click;
    sneaking with a block places it as usual; a visible pig presses and
    releases a phased plate, but not an unphased one; pistons cannot move a
    phase block (each check shown to fail when its code is removed);
  - Survival play: who may see and edit (Creative, Survival with the rule
    on, never Adventure); Survival placing uses the item; breaking is
    refused before the mining time, then drops in the visible world and
    wears the tool; with the rule off nothing is placed or broken; middle
    click never makes items; the recipes craft what they should (each
    shown to fail with its code removed);
  - the overlay's per-chunk geometry cache draws what drawing every ghost
    directly draws, vertex for vertex (tints, random offsets, model-less
    blocks, fluids), after a change and after a change across a chunk
    border (each shown to fail when its invalidation is removed);
  - Ctrl + middle click in Creative copies a hidden chest's contents into
    the picked chest; a plain pick does not (fails with the copy removed);
  - buckets in editing mode: a water bucket pours hidden water at the
    clicked spot (Creative keeps the bucket, Survival empties it); an empty
    bucket scoops hidden water up (each shown to fail with its code removed);
  - slime across layers: a phase piston drags a visible slime structure;
    hidden blocks at those spots do not move; a phase piston moved by a
    hidden vanilla piston works where it lands (all fail when phase pistons
    cannot extend);
  - phased light: hidden glowstone on a phase block lights the visible
    world (15, fading by vanilla's rules), also high in empty sky; without
    a phase block it does not; a phased lamp follows hidden redstone;
    removing the phase block takes the light away; a wall torch on a phase
    block's side lights. A client
    sees a room lit by glowstone hidden in its wall, still lit after the
    wall block at the light's spot is swapped, and dark once the glowstone
    is removed (each shown to fail with its code removed: the mixin, the
    phase-block check, the neighbour re-check, the wall-torch support, the
    section marking, the client sync);
  - the 3x3 flush door opens and closes twice from one visible lever (and
    fails without phase-piston quasi-connectivity, with a shorter stage-1
    hold, or without the second lift);
  - phase pistons push visible blocks (12 yes, 13 no, obsidian never), a
    sticky one pulls back, none leaves an arm, an ordinary hidden piston
    moves only hidden blocks, a phase piston in the visible layer does
    nothing, and a visible lever, through a hidden port and a hidden sticky
    phase piston, moves a visible block out of a doorway and back.
- **JUnit** also checks the update queue: work added while a batch runs waits
  for the next tick.
- **Client GameTests** (`./gradlew clientTest`, a real game client, run once
  with OpenGL and once with Vulkan, software-rendered on a virtual display;
  a test-only mixin pins the backend so neither silently falls back):
  - screenshots with the overlay on, off, and at higher opacity, compared
    pixel by pixel where each test block appears: a ghost floating in the air,
    a ghost tinting the visible block at its own spot, a ghost inside the
    ground, and a ghost completely behind a visible wall (must show through,
    fainter than one in front); also with improved transparency;
  - H and `]` change the settings;
  - removing a hidden block removes the ghost; chunks out of range are
    forgotten and come back; Survival players and players inside the hidden
    layer are sent nothing;
  - over a real network connection to a dedicated server: Survival gets
    nothing, Creative gets the hidden block, and nothing from the previous
    world is left over;
  - a hidden chest (no ordinary model) and hidden lava are drawn;
  - editing mode by real key presses and mouse clicks: J, right click on a
    visible block puts a hidden block at its spot (the visible one stays),
    right click on that ghost places next to it, left clicks break hidden
    blocks nearest first and nothing visible, J again and the same right click
    builds in the visible world; middle click on a ghost picks the hidden
    block, not the visible one at the same spot; Survival cannot turn it on.
- **Restart test** (`./gradlew restartTest`): two separate server processes
  on one world folder. The first places hidden blocks in all three hidden
  dimensions (one far away and unloaded) and stores some cells the old way;
  the second checks every hidden block and that the old cells were migrated,
  and that a stored phased glowstone lights the visible world after the
  restart (it hung the server before the next-tick chunk scan).

Each test was checked by breaking the code on purpose and seeing it fail.

Checked by hand on the RCON dev server: a world with old-style hidden blocks
upgraded at startup, and `tools/examples.py` verifies the examples world,
including a hidden lamp lit by hidden power.

**Not yet automated:** multiplayer with more than one player, and the
client side of layer switching.
