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
  (direct power, or power through a conductor). Nothing is queued where no
  chunk section within those two blocks can hold a port in the other layer
  (its palette says), which is almost everywhere: queueing 25 refreshes for
  every such change, and looking for a hidden port on every visible signal
  read, took a fifth of the tick on a busy wire network (`src/perf`).
- **Ports catch up when their chunk starts ticking.** A refresh for a port
  whose chunk is not loaded is dropped, and a hidden chunk loads a few ticks
  after its visible twin, so the visible power at a port can change before
  then (issue #17). When a chunk starts ticking, the ports in it and in the
  other layer's chunk at the same place are refreshed. Not on load: a chunk
  kept in memory comes back without loading again, and a hidden chunk read
  early loads before its visible twin. `PhaseLayerRestartPhases` saves a
  port with stale power and checks it is right after the restart.

**Hidden chunks tick with the visible world.** A chunk ticket (`phasecraft:phase_link`)
in the hidden layer follows each visible chunk's ticking status, so hidden
machinery runs wherever the world around it runs, and nowhere else: blocks
tick where visible blocks tick, and entities (falling sand, TNT, items,
minecarts) where visible entities tick. (Until 2026-11-22 only blocks did,
so hidden falling blocks hung in the air.) The
hidden layer's own chunks around a player standing in it also tick, as usual.

**Only hidden chunks that hold something** (`HiddenChunks`; since
2026-11-27, the person: "Let's fix that"). Giving every ticking visible chunk
a ticking hidden twin cost about 1 µs a chunk a tick and 50 to 90 KB of
memory each, though almost every twin is empty: +0.4 ms a tick and 40 MB for
one player's view at distance 10 (`src/perf`, twins). Now each hidden
dimension keeps an index, saved with it (`data/phasecraft/hidden_chunks`), of
its chunks that hold something, and only those get the ticket:
- A chunk goes in when a block other than air is set in it (every successful
  setBlock: placed, moved by a piston, flowed, built by a structure), when a
  hidden entity other than a player is in it at the end of a tick (an item
  thrown, a minecart rolling on), and, as a backstop, when it loads holding
  blocks.
- A chunk comes out when its twin stops ticking and it is found empty then:
  only air, no block entities, its entities loaded and none in it.
- A world from before the index starts with every hidden chunk on disk
  listed (read from the region and entity files' headers); the empty ones
  come out as each is left. Nothing changes for them until then.
- A chunk that goes in gets its ticket at the end of the tick and loads from
  the next: a first hidden block in a new chunk is placed at once, but what it
  schedules waits those few ticks. A ticking hidden chunk's neighbours are
  loaded as vanilla always does, so water, pistons and falling blocks can
  reach into them, and that puts them in the index in turn.
- Everything that reads the hidden layer from the visible side already
  treats a chunk that is not loaded as air, which is what an empty one is;
  the overlay sends an empty unlisted chunk as empty without loading it.
- GameTests: the harness lists the chunks under each test's structure before
  it starts (GameTestInfoHiddenChunksMixin). `HiddenChunkGameTests` checks the
  rules above; `PhaseLayerRestartPhases` that a saved index brings a chunk
  back (the stale port) and that an old world's files are listed (the
  Nether's hidden layer forgets its index before the restart).

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

**Phase shifter** (`phasecraft:phase_shifter`, Redstone tab; recipe: Phase
Block + ender pearl + piston makes two; the person's idea, 2026-11-23). Placed in the hidden layer: when
hidden power reaches it, it takes the visible block at its own spot out of
the visible world and holds it (`PhaseShifterBlockEntity`, saved with the
world); when the power goes, it puts that block back exactly as it was.
Nothing shows and nothing is heard in the visible world: the block is held,
not placed in the hidden layer, so it never collides with hidden machinery.
- Power: like a piston, it is powered by any signal into its spot and passes
  none on (not a redstone conductor; the person, 2026-11-25), so a repeater
  into one shifter of a wall takes that block only. Until then it was a
  conductor, and builds fed every shifter through a stone beside it.
- It takes what a piston could push without breaking, and blocks with
  contents too (vanilla's `PistonBaseBlock.isPushable` without its last
  rule, "no block entities": pistons refuse those because they would carry
  them somewhere else, while the shifter always puts a block back at its
  own spot). Not air, fluids, fragile blocks (torches, flowers) or
  immovable ones (obsidian, bedrock). It takes only when the power arrives,
  so a block placed in the gap while it is powered stays.
- Contents (2026-11-24, from the person's list): a chest, furnace, barrel...
  goes with what is in it and its name. The block entity is saved with
  `saveWithoutMetadata` (its data and components) into the shifter's
  `held_data`, and the visible block removed with
  `UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS` so nothing spills; put back, the
  data is loaded into the new block entity. The block is put back with the
  shape its neighbours give it now (`updateFromNeighbourShapes`, as a placed
  block): half of a double chest joins its other half again, or stands
  alone if that half is gone.
- Putting back breaks a visible block that has taken the spot meanwhile
  (the person, 2026-11-24: "I think it should break the main world block"):
  `destroyBlock` with drops, as if broken with no tool, contents spilled,
  silenced like a phased dispenser (`PhasedItems.silently`). A mob or player
  in the spot (not a spectator; a rider goes up with its vehicle) is lifted
  to stand on the block, as a piston pushing up would move them (the person, 2026-11-26, chose this over waiting: the hole in the
  ocean left a filler pillar in the sea when someone was in the shaft as it
  closed). It waits, retrying every tick, while one of them has no room above
  (a ceiling), or stands below the block with only its head in the spot
  (never lifted through it), or while the block there is unbreakable
  (destroy speed -1: bedrock, a moving piston...).
- Removed while holding a block, it puts it back the same way, or, if it
  cannot, drops it there as an item, with its contents: never lost, never
  doubled.
- `PhaseShifterGameTests`, and `PhaseLayerRestartPhases` (a held block, and
  a held chest with its items, survive a restart). Examples station 18:
  four bricks of a wall vanish into a doorway; station 21: a chest in a
  wall vanishes with its contents.

**Line shifter**, named **Shifter Piston** in the game (the person, 2026-11-26;
the id stays, so placed blocks survive) (`phasecraft:line_shifter`, Redstone tab; recipe: Phase
Shifter + piston; the person's idea, 2026-11-25: "'pushing' phase blocks in
a row or column would be an intuitive fix", after the castle tower needed
3,483 hidden blocks to give each of its 112 blocks its own wire). A phase
shifter for a whole line, with a piston's rules: it faces a way, as a piston
does (placed facing the player), and acts on the visible blocks in front of
it, not on its own spot.
- Powered, it takes the line one block at a time from the far end; unpowered,
  it puts it back one block at a time from the near end, so the line grows
  back out of it. Each block is taken and put back as a phase shifter would
  (`Shifting`, shared by both blocks: contents go along, putting back breaks
  a block in the way, lifts a mob or player in the spot, waits for
  unbreakable blocks).
- The line is the one a piston in its place could push
  (`PistonStructureResolver`, straight line only): up to the first air, or
  the first block a piston would break (a torch, a cobweb, water), which
  stays. Twelve blocks at most: with a thirteenth, or an immovable block in
  the line, nothing happens, as with a piston. Blocks it already holds count
  towards the twelve. Slime and honey are taken like any block and pull
  nothing along.
- Speed: one block every 1 to 4 redstone ticks, its `delay`, set by
  clicking it as on a repeater (default 1). The first block goes the moment
  the power arrives.
- Turned around mid-move, it goes the other way: what it took comes back,
  nearest first; or it takes the line again from its new far end (the
  visible line up to the first spot it still holds). The next block moves one
  step after the last one, so the rhythm holds (it never waits longer than
  one step, whatever game time does).
- `LineShifterBlockEntity` saves the held blocks by distance and the distance
  still to take; the step itself is a block tick, saved with the chunk, so a
  move goes on after a restart. The next step's game time is not saved: a
  GameTest server's game time starts again after a restart (CI caught a move
  stalled for thousands of ticks).
- Removed, it puts back everything it holds, nearest first, or drops what
  cannot go back. Not a redstone conductor, like the phase shifter.
- A comparator behind it reads how many blocks it holds (0 to 12), as it
  reads a chest's fill level (the agent's proposal, 2026-11-25, the person:
  "Let's do that"), so a build can wait for a line to be all gone or all
  back before its next part moves: vanilla logic instead of timing
  redstone. A comparator updates once per redstone tick, so at delay 1 it
  can skip a count; from delay 2 it shows every one
  (`aComparatorReadsHowManyBlocksItHolds`). `tools/redstone_check.py` takes
  a comparator reading a line shifter as reading a container.
- `LineShifterGameTests` (order and timing, delay by clicking, turning
  around, a piston's line, contents, waiting, removal), `RecipeGameTests`,
  and `PhaseLayerRestartPhases` (what a move under way saves: the blocks held and those still to take; put back after the restart).

**Area shifter** (`phasecraft:area_shifter`, Redstone tab; recipe: Line
Shifter + diamond block makes two; the person's idea, 2026-11-25: two corners
make a box "and then you could turn the whole cube off and on"; "It does feel
like cheating though. We could keep it as an option"). **An option:** it does
nothing unless the game rule `phasecraft:area_shifters` is on (off by
default).
- Two area shifters in the hidden layer at opposite corners of a box (their
  own spots included, at most 16 x 16 x 16) pair: each pairs with its nearest
  other area shifter at most 15 blocks away on every axis, if that one's
  nearest is it, and not on a tie. Keep boxes apart.
- The box goes only while both corners are powered (one switch wired to
  both; a stray signal into one corner does nothing), layer by layer from the
  top; when either loses its power it comes back layer by layer from the
  bottom. A layer every 1 to 4 redstone ticks, or 0: all at once (`delay`,
  set by clicking, default 1; the leader's counts). A layer with nothing to
  take costs no time. Turned around mid-move, it goes the other way with the
  rhythm kept.
- Each block is taken and put back as a phase shifter does it (`Shifting`):
  piston rules, blocks with contents too; air, fragile blocks (torches,
  flowers) and immovable ones (obsidian) stay. A torch on a block that goes
  pops, as it would off a pushed block. Putting back breaks what is in the
  way and lifts a mob in a spot onto the block (a pig under the hut's chest
  ends up standing on the chest); where it must wait, the layers above wait
  for it.
- The leader (the corner whose position sorts first) holds the blocks
  (`AreaShifterBlockEntity`, saved by offset) and runs the move; the other
  corner only remembers its leader. Removing the leader puts everything back
  at once (or drops what cannot go back); removing the other corner makes the
  leader put the box back layer by layer.
- `AreaShifterGameTests` (layers and their ticks both ways, contents, one
  corner is not enough, delay 0, piston rules, too far apart and a tie do not
  pair, the 16-block cap at its edge, turned around, removing either corner)
  run in a test environment with the rule on
  (`phasecraft-test:area_shifters_on`), apart from `offByDefault`. Their
  structure is 24 x 17 x 24 though only 8 x 17 x 8 is used: the GameTest
  server lays tests 5 blocks apart in a row and rows 6 apart, batch after
  batch on one grid, and a corner looks 15 blocks around, so in a smaller
  structure corners paired with the next test's hut (BUILD_RULES C17);
  `RecipeGameTests`; `PhaseLayerRestartPhases` (a box held across a restart,
  put back after it).

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

Cost: the geometry of each 16x16x16 section's ghosts is built once (the same
code as before, recorded instead of drawn: positions relative to the chunk,
colours without the pass's alpha) and kept until hidden blocks in that
section, or on the border of a neighbouring one, change
(`GhostLayer.takeChangedSections`), the section is far out of range, or the
block models are reloaded (per chunk at first; per section since the inside
view, below, which holds whole landscapes). Each frame replays the meshes of
sections within about 100 blocks, with the camera transform and each pass's
alpha. On
18,432 hidden blocks around the camera that took the overlay's CPU time per
frame from 9.4 ms to 0.9 ms (`OverlayCacheClientGameTest` logs it), and the
cached geometry matches drawing every ghost from its model, vertex for
vertex, also after changes and across chunk borders.

## The inside view: seeing the visible world from inside the hidden layer

The person (2026-11-27): "Should we have a command that puts the player into
the underworld ... and either ignores the main world or makes the main world
transparent that way they can work on their contraption without disturbing
the main world. ... It would be helpful for players in creative mode or mods
that are making some interactive contraption to not have to disassemble the
world in order to build something that interacts with it."

Inside a hidden dimension the hidden blocks are real blocks and nothing of
the visible world is there: a void with machinery floating in it. The inside
view is the overlay in reverse: the visible world, drawn faint around the
player, to build against.

**Going in and out: `LayerSwitch`.** `/phasecraft layer hidden|visible`
(operators) and the key **K** (`phasecraft:layer_toggle`, a serverbound
payload with nothing in it) both call `LayerSwitch.move`: the same spot in
the other layer, looking the same way. Going in is for Creative and
Spectator players, checked on the server whoever asks; the key needs no
cheats and no operator level (a Creative player may build anywhere already,
and a Spectator sees the hidden layer through the overlay anyway). A player
who goes in is set flying: there is no ground. `HiddenLayerGuard` still
moves out anyone who stops being Creative or Spectator inside.

**Server: `InsideView`.** For each player in a hidden dimension who may be
there and may see the overlay (`InsideView.shows`), with PhaseCraft on the
client:

- **Surface only.** A block is sent if it is not air and has an open face:
  toward air, toward a block that is not a full opaque cube (glass, leaves,
  a slab, water), or toward the sky; water against water (and lava against
  lava) is not open, so a sea is its top and its floor. Each block goes with
  a bit per open face, and only those faces are drawn. Solid ground is
  hollow in what is sent.
- **Near only.** Chunks within 4 of the player (the overlay's 9x9 square),
  nearest first, at most 2 new chunks a tick; chunks that leave the square
  are forgotten (`visible_forget`). A chunk is scanned
  (`InsideView.surface`, straight from its sections) only when it is sent,
  never per tick.
- **Changes.** A visible block change can change whether it and its six
  neighbours are on the surface, and which of their faces are open; those
  seven spots are looked at again at the end of the tick (`shownAt`) and sent
  to the players who hold their chunk (`visible_blocks`; air: no longer
  shown). The hook is the same one the phase link uses; it returns at once
  while nobody is inside.
- **Leaving.** Back in the visible world, or no longer allowed (Survival,
  Adventure), the client is told to drop everything (`visible_reset`) and
  nothing more is sent. Survival and Adventure players are never sent it, not
  even for the tick before the guard moves them out.
- **The visible world stays loaded.** Nobody may be standing in it there, so
  each inside player holds a ticket on the visible level around their spot
  (`phasecraft:inside_view`, radius 6: the 9x9 square ticks entities too, and
  one ring more is loaded for the scan; two players in one chunk share the
  ticket, counted, as vanilla keeps one per chunk and radius). Without it the visible world there
  would unload a few seconds after its last player went inside, and the
  machine they went in to work on would have nothing to act on. It also
  means flying around inside generates visible terrain, as flying around
  outside does.

Messages (`OverlayPayloads`): `phasecraft:visible_chunk` (a chunk's surface
blocks, written short: a byte for x and z, a short for y, the block state and
the faces, 5 to 6 bytes a block), `visible_blocks` (changes: position, state,
faces), `visible_forget`, `visible_reset`.

**Client.** A second `GhostLayer` (`PhaseCraftClient.visibleLayer()`) holds
them, and `GhostRenderer.collectInside` draws it with the overlay's cached
meshes in one pass (`GhostRenderTypes.inside()`): translucent at half the
chosen opacity (22% by default), behind real hidden blocks in front of it,
writing no depth, and with no face culling, so a floor shows from underneath
as well (only open faces exist; its underside is the back of its top faces).
It is not sorted: at that opacity the order changes nothing one can see, and
sorting every quad each frame is what cost most. H shows and hides it, `]`
and `[` change the opacity, as for the overlay. Sections further than 48
blocks from the camera are not drawn.

**What it costs** (`InsideViewTerrainClientGameTest` logs it; a default
world, seed "phasecraft", the 81 chunks around the spawn point: hills, a
lake, caves):

| | |
| --- | --- |
| Surface blocks per chunk | 715 to 3,814, mean 2,219: 7.7% of a chunk's 28,734 blocks other than air |
| `visible_chunk` per chunk | mean 12.8 kB, largest 21.7 kB (5.7 kB as Minecraft compresses packets) |
| All 81 chunks, on going in | 1.04 MB, 458 kB compressed, over about 2 seconds; then 9 chunks for each chunk border crossed |
| Sending every block instead | 13.6 MB, 4.6 MB compressed (measured by switching the surface rule off) |
| Server: scanning one chunk | 1.4 to 1.7 ms, at most 2 chunks a tick per player going in or moving |
| Client: held | 180,000 blocks |
| Client: drawn within 48 blocks | 181,000 vertices, 1.5 ms of CPU a frame (64 blocks: 415,000 vertices, 3.4 ms) |

The first version drew every face of every surface block of every chunk in
range, per chunk column: 1.9 million vertices and 14.7 ms a frame. Three
things brought that down: the open faces only (the server knows them, the
client cannot: a missing neighbour is air or solid rock), meshes per section
instead of per chunk column, so that caves far below are left out, and a
draw distance of 48 blocks. No cap on blocks per chunk was needed: the
largest chunk fits one message (4,096 blocks; a larger one is split, as for
the overlay).

**Claims and spawn protection hold inside: `InsideProtection`.** Before the
key, only operators could get in (the command). Now any Creative player
can, and in there they place and break with vanilla's own clicks, in a
dimension no claim mod knows and vanilla's spawn protection does not cover.
So what editing mode asks before a hidden edit (`Protection`, "Claims hold
for the hidden layer" below) is asked for these clicks too, about the
visible twin level at the same spot: breaking asks `canBreakBlock`;
clicking a hidden block asks `canInteractBlock` at its spot and, with
something in hand, `canPlaceBlock` there and at the spot beside it; a
bucket asks `canBreakBlock` and `canPlaceBlock` where it is aimed; and all
of them the world border and spawn protection. A refused click does
nothing and says why, as in editing mode. They are heard through Fabric's
events for the player's own clicks in their own level
(`PlayerBlockBreakEvents.BEFORE`, `UseBlockCallback`, `UseItemCallback`);
nothing is fired for a block nobody clicked. Machines placed inside have
their placer as owner, as before (`MachineOwners`). Not covered: what a
player inside does without clicking a block (a mod's area tool, entities
such as a minecart with TNT placed in there), and the same known gap as
editing mode for claim mods that do not answer the API.
`InsideProtectionGameTests`.

**Inside, the player is really there.** A hidden pressure plate under them
is pressed by them; a visible one at the same spot is not (they are not in
the visible world, and a phased plate watches the visible world). Visible
mobs and players are not shown, and clicks go to the hidden blocks only:
the visible world can be seen from inside, not touched. To change it, press
K and step out.

Decided by the agent, open to change: the key (K); coming out puts the
player at the same spot even inside a visible block (as the command always
did; Creative players are pushed out or dig, Spectators pass through); the
look (22%, both sides, 48 blocks); block entities' contents and entities of
the visible world are not shown.

Tests: `InsideViewGameTests` (what is scanned, what a Creative player inside
is really sent and when, who is sent nothing, who the key lets in, one of
two players leaving), `InsideProtectionGameTests`,
`InsideViewClientGameTest` (K, the faint wall and ground in screenshots
against H off, a real hidden block behind them, changes, out of range and
back, improved transparency, K again), `InsideViewTerrainClientGameTest`
(ordinary terrain: the numbers above, held inside bounds).

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
within reach, hit point on the block, and the visible level's `mayInteract`
at that spot: spawn protection and the world border hold for the hidden
layer too, since a hidden shifter or phased hopper acts on the visible
block there; `EditingSecurityGameTests`; and claims, see below), then:

- *use*: vanilla's `ServerPlayerGameMode.useItemOn` steps, carried out in the
  hidden level (vanilla's would take the player's own, visible level): the
  hidden block's own use first (levers, repeaters), then the held item with a
  context in the hidden level. Beds and respawn anchors are not used (a
  red line says so; the person, 2026-11-27): vanilla's bed sets the respawn
  point in the dimension the player stands in, so a hidden bed set it in
  the visible world at its spot, with no bed there and maybe inside a wall,
  and put the player to sleep there; in the Nether and the End it blew up
  instead, and a charged anchor did outside the Nether, breaking hidden
  blocks around it whoever built them. They can still be placed, for show. Aimed at a visible block with nothing hidden
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

### Claims hold for the hidden layer: `Protection` (the person, 2026-11-27: fix it)

Claim and anti-grief mods guard the visible world: they listen to vanilla's
clicks there and never see a hidden edit. So without a check a Survival
player could hide a phase shifter, or a phased hopper or dropper, inside
someone's claim and take their blocks or empty their chests. Editing mode
therefore asks first, for the player's own (visible) level and the spot,
whether a protection mod would refuse the same action on the visible block
there, and refuses the hidden edit if so (action bar: "Protected by a
claim", and a dispenser's failing click only that player hears:
`AimCheck.tellRefused`, which spawn protection and the world border use
too).

Shown before the click (`AimCheck`; the person, 2026-11-27, asked for
"something better than the protector message"): while editing, the client
asks the server about the spot it aims at whenever its aim moves to another
spot, and again every 2 seconds (`AimCheck.Aim`); the server answers, at
most once a tick per player and only for players who may edit, about spots
within reach, what would refuse an edit there (`AimCheck.Answer`: a claim,
asked as `canBreakBlock` for a hidden block aimed at and `canPlaceBlock`
for a visible spot; spawn protection; the world border). The client draws
the target's outline red instead of purple and ends the readout with the
reason. Asking only asks. `AimCheckGameTests` checks the answers,
`EditingClientGameTest` the red outline (screenshot pixels) and the readout.

| Hidden edit | Question, at |
| --- | --- |
| using a hidden block (lever, repeater, chest) | `canInteractBlock`, the clicked spot; refused, the whole click is |
| placing a block | `canPlaceBlock`, where vanilla would put it (the clicked spot, or the one beside it) |
| another item (flint and steel, bone meal...) | `canPlaceBlock`, the clicked spot and the one beside it |
| pouring a bucket | `canPlaceBlock`, where the fluid goes |
| scooping with a bucket, mining, breaking | `canBreakBlock`, the block's spot |

Every player is asked about, Creative and operators too: who passes is the
protection mod's business. Picking (middle click) only reads, and is not
asked about.

The questions go through Patbox's **Common Protection API**
(`eu.pb4:common-protection-api` 2.0.0, MIT, 7 kB, shipped inside the
PhaseCraft jar as the claim mods ship it). Chosen because it only asks:
nothing is clicked, placed or broken. Read in the mods' sources on
2026-11-27 (not run with them): Flan and Get Off My Lawn answer it
themselves, FTB Chunks with FTB XMod Compat installed, ItsOurs too; with no
such mod every answer is yes. The alternative, firing Fabric's
`UseBlockCallback` / `AttackBlockCallback` / `PlayerBlockBreakEvents.BEFORE`
for the visible block, was rejected: other listeners act on those events
(PhaseCraft's own `PhasedInput.use` is one; loggers, harvest and vein-mining
mods are others), they would see a click on the wrong block, and several
mods guard placing in a `BlockItem.place` mixin that no event reaches.
`ClaimProtectionGameTests` (a test claim mod, `TestClaims`, answers the API).

**Open Parties and Claims** (OPAC; the person, 2026-11-27: "Yes, let's add
the support") does not answer the API, so PhaseCraft answers it for OPAC:
when OPAC is installed (`ProtectionAdapters`, an entrypoint of its own;
mod id `openpartiesandclaims`), `dev.phasecraft.compat.OpacProtection` is
registered as a Common Protection API provider backed by OPAC's server API
(`OpenPACServerAPI.get(server)`; read in OPAC 0.33.0 for 26.3, LGPL-3.0,
compiled against as `compileOnly`, never shipped, no class of it touched
without the mod). `Protection` is unchanged, and any other mod asking the
API hears OPAC's claims too. The questions, for the asked level's dimension
(a hidden dimension is one of its own to OPAC: no claims there) and the
spot's chunk:

| Question | With a player | With a profile only (player `null`) |
| --- | --- | --- |
| `canPlaceBlock` | `IChunkProtectionAPI.onEntityPlaceBlock(player, level, pos)` | standing, blocks |
| `canBreakBlock` | `onBlockInteraction(player, MAIN_HAND, null, level, pos, UP, true, false, true)` | standing, blocks |
| `canInteractBlock` | the same with `breaking` false | standing, blocks |
| `canInteractEntity`, `canDamageEntity` | `onEntityInteraction(null, player, entity, null, MAIN_HAND, attack, false, true)` | standing, full access only |
| `isProtected`, `isAreaProtected` | | someone with no standing has no full access to the chunk (any chunk of the area) |
| `canExplodeBlock` | the API's default: `canBreakBlock` | |

*With a player* these are the checks OPAC's own handlers make for a click
on the visible block (`CommonEvents.onDestroyBlock`, `onRightClickBlock`),
with messages off: the owner, the party and allies as the claim's options
say, admin and server-claim modes, per-block exceptions and item rules all
count; nothing is clicked. The item is the main hand's, the face the top
one (the API names neither). OPAC is not told which block is placed, so a
claim's exception for using one particular item does not allow placing.
OPAC may still tell a player who is over the claim limit so, and notes
when it last let them through: its own bookkeeping, not switched off by
"no messages".

*With a profile only* (a hidden machine acting for whoever built it, who
may be offline) OPAC has no check for an action, so the answer is that
player's standing by UUID: `hasChunkAccess(uuid, dimension, chunkX,
chunkZ)` (true with claims off, in an unprotected chunk, for the owner,
and for the group the claim gives full access to: by default the owner's
party), or, for blocks, `checkPlayerGroupExceptionOption(
CLAIM_EXCEPTION_BLOCKS_BY_PLAYERS, config, uuid)` (the group the claim
lets break and use blocks). On the careful side of what the player would
get online: per-block, per-entity and per-item exceptions (a claim whose
doors anyone may use) do not count, nor the modes a player switches on
while online (admin mode, server claim mode, impersonation; non-ally mode
would refuse more). Asked off the server thread, or before OPAC has loaded
its data, a server level gets the careful answer (refused, protected); a
client level is not OPAC's to answer for.

Tested with the real mod, as Lithium is: `./gradlew runGameTestOpac` (part
of `build`; OPAC and Forge Config API Port from Modrinth, `opac_version`
and `forge_config_api_port_version` in gradle.properties, a fresh world
each run, at least 3 tests or the task fails) runs `OpacClaimChecks`: a
real claim made through OPAC's API (`OpacClaims`) refuses another player's
hidden placing, breaking and using and allows the owner's and everything
in the chunk beside it, and profiles with no player get the owner's, a
party member's and a stranger's answers. The class is not named
`*GameTests`, so runs without OPAC leave it out; each test has a test
environment of its own, since claims cover whole chunks that neighbouring
tests share. OPAC is never loaded into a client test (its client looks up
a patron list online).

**Known gaps** (tell server admins; `survival_access` off closes the first
for Survival players):

1. *Mods that do not answer the API.* Yet Another World Protector and
   Leukocyte do not (2026-11-27), nor FTB Chunks without FTB XMod Compat:
   their claims do not stop hidden edits. A fix: one more adapter each, as
   for OPAC above (FTB Chunks' `shouldPreventInteraction`), compiled
   against the mod and used only when it is loaded, tested with the real
   mod in a GameTest run of its own.
2. *Hidden blocks act with no player.* Closed (the person, 2026-11-27:
   "Let's close that gap"), see the next section; what is left of it is
   listed there.

### Claims hold against hidden machines: `MachineRights`, `MachineOwners`

A hidden machine changes the visible world with no player acting: a phase
shifter, a line shifter or an area shifter takes blocks and puts them back,
a phase piston pushes and pulls them, a phased hopper empties a chest, a
phased dropper or dispenser puts things out. Placed outside a claim, a line
shifter's line, an area shifter's box or a phase piston's push reached
across the border; claim mods stop vanilla pistons and hoppers there with
their own mixins, which know nothing of PhaseCraft's blocks. So:

**A machine acts with the rights of the player who placed it.**
`MachineOwners` keeps the placer (UUID and name) of every machine block
placed by a player in a hidden dimension, from editing mode or standing in
the hidden layer (`BlockItemMixin`, on vanilla's `BlockItem.place`, which
both go through), per hidden dimension, by position, in saved data
`phasecraft:machine_owners` (it survives a restart: `PhaseLayerRestartPhases`).
Machine blocks are the three shifters, both phase pistons, and hoppers,
droppers and dispensers (phased or not: the phase block can come later).
The entry is forgotten when the block at its spot becomes another block
(`ServerLevelPhaseLinkMixin`, the block-change hook), not when only its
state changes. A machine a hidden piston moves keeps its owner
(`PistonBaseBlockMixin`; only phase pistons can be moved, the others have
block entities): a flying machine's phase piston stays its builder's.

**Every visible spot a machine is about to change is asked about first**
(`MachineRights`), with the visible level, the owner's profile and no player
(the owner may be offline), through the same Common Protection API:

| Machine | Question, at | Refused |
| --- | --- | --- |
| phase shifter, taking | `canBreakBlock`, its own spot | it takes nothing |
| phase shifter, putting back | `canPlaceBlock`, its own spot; and `canBreakBlock` there if a block that would be broken has taken the spot | it keeps holding the block and tries again every tick, as it waits for an unbreakable block |
| line shifter, taking | `canBreakBlock`, each block of its line, when the line is worked out and again as each is taken | the block is an immovable block to it: nothing of the line moves, as with obsidian in it |
| line shifter, putting back | as the phase shifter, each held spot | it waits there; the blocks behind wait their turn |
| area shifter, taking | `canBreakBlock`, each block of the box, for both corners' owners | that block stays (the rest of the box goes), as an immovable block does |
| area shifter, putting back | as the phase shifter, each held spot, both corners' owners (the leader's alone once the other corner is gone) | that block waits; the layers above wait for its layer |
| a shifter removed while holding blocks | as putting back | the block is dropped at its spot as an item, with its contents, and what stands there is left alone |
| phase piston, pushing or (sticky) pulling | `canBreakBlock` at each moved block's spot and `canPlaceBlock` at the spot it moves into; `canBreakBlock` at each block the move would break (a torch in the way) | nothing moves, as with an immovable block: it does not extend; a sticky one retracts and leaves the block |
| phased hopper | `canInteractBlock`, the spot above it (a container there, or items lying there) | it pulls nothing |
| phased dropper | `canInteractBlock`, the spot in front, if a container is there; otherwise `canPlaceBlock` there | it does nothing and keeps its items |
| phased dispenser | `canPlaceBlock` and `canBreakBlock`, the spot in front (it places and pours, and also scoops up, shears, lights) | it does nothing and keeps its items |

Nobody is told of a refusal: there is nobody to tell. The checks sit where
the visible world is changed (`Shifting.take` and `putBack`, which every
shifter goes through, `PhasePistonMover`, `PhasedItems.mayPull` and
`mayPutOut`), so **a new machine, or a new way for one to change the
visible world, must ask `MachineRights` for each visible spot**.

**A machine with no owner** (placed by a command or a structure, or from
before owners were kept: every machine built with an earlier version) may
change a protected spot (`CommonProtection.isProtected`) only if its own
spot is protected too: it was built inside. This is the nearest thing to
claim mods' "a piston does not cross a claim's border" that the API allows.
Outside any claim nothing changes for it.

Cost: with no protection mod installed (`MachineRights.anyProtection`)
nothing is looked up and every answer is yes; with one, a hash lookup for
the owner per machine action and one question per changed spot (two for a
line or area shifter's takes). No per-tick scan was added; a shifter that
waits to put back, and a phased hopper, ask on the ticks they already run.

`ClaimMachineGameTests`: each machine built four times, a stranger's, the
claim owner's, nobody's outside the claim and nobody's inside it. With the
real Open Parties and Claims (`OpacClaimChecks`, `runGameTestOpac`): a
stranger's line shifter beside a claimed chunk takes nothing across the
border, the claim owner's does. OPAC answers for a profile with no player
by that player's standing in the claim (see above): the owner and those
the claim gives full or block access to, not per-block exceptions.

**Known limits:**

1. *Two claims side by side.* The API cannot say whether two spots are in
   the same claim, so a machine with no owner standing in one claim can
   reach into the claim next to it. (A machine with an owner cannot: its
   owner is asked about.)
2. *A machine with no owner pushed into a claim.* A hidden piston outside a
   claim could push an ownerless phase piston onto a protected spot, where
   it would count as built inside. So an ownerless machine that a piston
   moves from an unprotected spot onto a protected one gets
   `CommonProtection.UNKNOWN` as its owner: nobody, whom claims refuse.
   Side effect: a flying machine built by commands that flies into a claim
   stops pushing visible blocks there; one built by hand keeps its
   builder's rights.
3. *A claim made over a working machine.* A stranger's shifter that holds a
   block when the spot becomes someone's claim cannot put it back (the
   claim refuses the placing): the block stays held until the claim's owner
   lets them, or the shifter is removed and drops it.
4. *Out of scope:* phased blocks that only read the visible world (pressure
   plates, buttons and levers, observers, comparators, tripwire, lights),
   and phase ports: redstone power crosses claim borders in vanilla too,
   and claim mods do not stop it. A phased button in a claimed wall is
   pressed by a click on the visible block, which the claim mod sees.
5. *Items lying on the ground* above a phased hopper are asked about as the
   container is (`canInteractBlock` at that spot): the API has no question
   for picking items up.

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
  plate, so `PhasedInput` keeps a list of the plates in loaded hidden chunks,
  by chunk (block changes and chunk loads; a chunk's are dropped when it
  unloads), and checks the released ones every tick in the chunks that tick
  blocks; once pressed, the plate's own scheduled ticks keep counting.
  (Checking every plate ever seen, wherever it was, read its block every
  tick, which kept hidden chunks with plates from ever unloading:
  `src/perf`, plates_unloaded.)
- **Watchers** (`PhasedWatchers`; the third step, as the person approved
  it, 2026-11-23):
  - A phased **observer** watches the visible block in front of it. Every
    visible block change (the same server hook as the link) looks for
    hidden observers on phase blocks facing that spot and starts their
    pulse, as vanilla's `updateShape` does. The visible block at the
    observer's own spot is not in front of it and is not watched.
  - A phased **comparator** reads the visible block behind it, by vanilla's
    rules: a block with a reading right behind it (a chest's fill level, a
    cake, a composter...), or, behind one solid block, the block or item
    frame after it (`ComparatorBlockMixin`; the higher of the hidden and
    visible readings wins). It never reads visible redstone power: the
    phase block carries no power (`phasedComparatorIgnoresVisiblePower`).
    It looks again when a visible block within two of it changes, or when
    a visible container's contents change (`LevelOutputSignalMixin`, on
    vanilla's `updateNeighbourForOutputSignal`).
  - A hidden **tripwire** string whose line ends at a hook on a phase block
    (the hook counts as hanging on the block behind it; one phased hook is
    enough) is tripped by visible players, mobs and items at the string's
    spot. As for plates, `PhasedInput` checks released strings every tick,
    and `TripWireBlockMixin` makes vanilla's own check (and its rechecks
    every 10 ticks while tripped) count the visible entities.
  Hidden observers, comparators and tripwire not on a phase block see only
  the hidden layer, as before.
- **Items** (`PhasedItems`; the agent's default, open to change, following
  the same rule):
  - A phased **hopper** pulls from the visible world above it: vanilla's
    own pull (`suckInItems`) run against the visible level
    (`HopperBlockEntityMixin`), so the visible container above, or visible
    items lying on top of its spot, by vanilla's rules. It pushes into
    hidden containers as usual.
  - A phased **dropper** or **dispenser** works on the visible world in
    front of it: its `BlockSource` carries the visible level, so vanilla's
    dispense behaviours (items, arrows, buckets, fire charges...) act there,
    and a dropper's container in front is looked up there
    (`DispenserBlockMixin`, `DropperBlockMixin`). Items come out of, and go
    back into, the hidden dispenser's own inventory.
  - Silence: while a phased dropper or dispenser acts on the visible level,
    that level's sounds and level events (the click, the smoke) are dropped
    (`ServerLevelSilenceMixin`). The test `SoundRecorder` now records what
    is sent to players (at the broadcast) and level events too, so a
    cancelled sound does not count as heard.
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
  nearby blocks change, so every player with PhaseCraft is sent phased
  lights, and changes; the client's light engine uses them through the
  same mixin. Players without PhaseCraft see what the server sends, which
  can go stale. Which lights: those of the chunks the player is tracking
  (vanilla's `ChunkTrackingView`, the chunks it sends them anyway), in any
  game mode, since the light is there for everyone. When the view moves,
  the client is sent the lights that came into it and told to drop
  (light 0) those that left; a change goes to the players whose view holds
  its chunk. Until 2026-11-27 everyone was sent the whole dimension's list
  on joining: a modified client could read every hidden lamp on the server
  off it, Adventure players and players far away included (found by a
  security review).
  The game rule `phasecraft:phased_lights` (on by default; the person,
  2026-11-27: "make a separate light enable, I wanted builds to be able to
  have atmospheric light") turns phased light off for the whole server,
  apart from `phasecraft:survival_access`: the table is still kept, but
  the mixin is given none of it and nothing is sent. `PhasedLights` reads
  the rule every tick; on a change it has the light engine look at every
  phased light's spot again (dark, or lit again) and clients are told to
  forget theirs, or are sent their view afresh.
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
game rule `phasecraft:survival_access` is on; Adventure never. The rule is
on in single player and on LAN, and off on a dedicated server until an
admin turns it on (the person, 2026-11-27, after a security review: with
it on, any Survival player sees every hidden build within 4 chunks, opens
other people's hidden chests and builds inside their walls, past claim
mods that do not answer the Common Protection API, see "Claims hold for the
hidden layer"). A game rule has one default for every kind of server, so
`Access.applyServerDefault` does it at `SERVER_STARTED`: on a dedicated
server (`MinecraftServer.isDedicatedServer()`; an integrated server opened
to LAN is not one; the GameTest server says it is one, because Fabric's
GameTest API makes it, and is left out by class, so tests run with the
rule on as single player does) whose world has no mark yet, it sets the rule to false, marks the world (saved data
`phasecraft:server_defaults` in the overworld's storage) and logs one line
with the command to turn it on. A marked world is left alone, so the
admin's setting holds. Worlds that an earlier version ran on a dedicated
server are switched off once too: a rule left at its default cannot be
told from one set on purpose. The dev server is a dedicated server;
`tools/examples.py` turns the rule on there. Each client is told whether it may edit, so J can
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

- **Only Creative and Spectator players may be there** (they go in with the
  key K or `/phasecraft layer hidden`: `LayerSwitch`). Any other player
  found in a hidden dimension at the end of a tick (switched to Survival
  inside, a forced game mode on login, a teleport by command) is moved to the
  visible layer at the same spot, or the first spot above it where they fit,
  with a message. Logging out inside is fine: you come back where you were,
  and the same rule applies.
- **No respawn point in the hidden layer.** Beds and respawn anchors used
  there do not set your spawn (`ServerPlayerRespawnMixin`), so a death never
  brings you back over the void. From editing mode they are not used at all
  (see `HiddenEditing`).
- **Portals in the hidden layer do nothing** (`EntityPortalMixin`, on
  `Entity.setAsInsidePortal`, which every portal block goes through). They
  would lead into the real Nether or End and build a visible portal there.
- **No mob is born in the hidden layer** (the person, 2026-11-27: "should
  not allow spawning in the underworld"). `EntityTypeHiddenSpawnMixin`, on
  `EntityType.create(Level, EntitySpawnRequest)`, which every new entity goes
  through with the reason it is made, gives nothing back for a mob in a
  hidden dimension (`HiddenLayerGuard.mayBeBorn`): no spawners, trial
  spawners, spawn eggs (also used from inside), dispensers, breeding,
  buckets, raids or patrols, golems built from blocks. A hidden mob could
  not be seen from the visible world, would keep its hidden chunk loaded
  and ticking, and could stand on hidden plates. Let through (the agent's
  defaults, open to change): mobs loaded from a save, put there by a command
  (/summon, the tests), a mob turning into another (a zombie drowning) and
  dimension travel; and anything that is not a mob (armor stands, minecarts,
  items, falling blocks). Natural spawning had nothing to spawn anyway: the
  hidden dimensions use the void biome. A hidden spawner just never spawns;
  a dispenser with a spawn egg uses the egg up, as vanilla's does when the
  mob cannot be made.

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
     the dev server): the layers stay separate. Only an observer standing
     on a phase block watches the visible world (see Watchers above).

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
7. **Giant iris door: done.** A round 7x7 door (37 blocks) flush in a wall:
   one visible lever opens it from the centre outward, ring by ring, and
   closes it from the outside in (the person's idea, 2026-11-23: "you can't
   really do that in normal Minecraft"; `IrisDoorGameTests`, examples station
   22). Rings by distance from the centre: 1, 8, 12 and 16 blocks, 4 redstone
   ticks apart; the centre opens 2.2 s after the lever.
   - Each door block is held by a hidden phase shifter at its own spot, so no
     block travels: the hard part is only getting each ring its signal at the
     right moment, through a solid 7x7 wall of shifters, all hidden.
   - The layout (`contraptions/iris_door.txt`, about 1,700 hidden blocks) is
     written by `tools/gen_iris.py`, which also sets every repeater's delay
     and the resting state of every part; change the generator, not the file.
   - Feeding a shifter: through a stone right behind it, powered by a
     repeater. A shifter is a solid block, so a repeater pointing into it
     would power it strongly and switch its neighbours too. Even rows are
     fed from behind the wall, odd rows from in front, so every repeater has
     a free row under it for the glass it stands on.
   - In each row, a run of same-ring blocks shares a wire; a redstone torch
     tower right behind it brings that ring's signal up from under the
     floor. Towers of upper rows stand deeper than lower rows' wiring, which
     they pass. Where the tower would leave the signal upside down, a torch
     on the tower's top block turns it over.
   - Under the floor, each ring has its own level (y = -3 to -9 below the
     floor) with a wire along x = 9 (the bus) and a branch to each tower.
   - The controller: the lever (a phase port under it) feeds a staircase of
     wire down past the four levels. On each level two lines of different
     delay merge: the ring rises with the short line and falls with the long
     one. Rings 2 and 3 do this on the inverted lever signal and are turned
     back on the way up, so they rise with the long line and fall with the
     short one: opening runs centre first, closing outside first.
   - Lessons: dust fades one level per block, so long wires need repeaters
     (the checker does not model strength: the ring 3 bus ran dry under the
     front rows, which stood open); a wire must be placed after the block it
     stands on, or the next update pops it; the resting state written to the
     layout must match the settled one (two bus repeaters started off, and
     the inverted rings flickered open after building). Built by RCON over
     many ticks, the door flickers for about half a second while it settles;
     built in one tick (the GameTest), it does not. And vanilla's
     `GameTestHelper.relativePos` turns an unrotated test half round.
   - `tools/redstone_check.py` now checks shifters (each must get its own
     signal and no other), and a torch now goes out only on a direct signal
     into its block, as in vanilla (`SignalGetter.getSignal` of a
     conductor): side-by-side towers are fine.
8. **Parting waterfall: done.** A pool on a cliff spills in five ribbons of
   water between rock ribs, a cave behind the middle; one lever stops them
   from the middle outward, each draining from the top, and starts them
   again from the outside in (the person, 2026-11-24: "Waterfall opening in
   the middle"; `WaterfallGameTests`, examples station 23).
   - Each ribbon is fed through a gate spot on the cliff's edge; a hidden
     phase shifter there holds a stone while the water runs (it takes it
     when placed, powered at rest) and puts it back to stop the ribbon: a
     put-back block replaces the water in its spot.
   - Water takes its level from the water beside it, falling water counting
     as full. So nothing may run beside a ribbon: in one 9-wide fall the
     gated columns stayed wet next to running ones, and a 3-wide stream
     drained, but each level only after the one above (about 25 s). One
     column between rock ribs drains top to bottom in about 3.5 s.
   - The wiring is the iris door's: `tools/gen_door.py` builds both, from a
     `Door` (cells, rings, step, planes, lever, polarity: a powered shifter
     meaning open, as in the iris, or closed, as here). `gen_iris.py` and
     `gen_waterfall.py` describe the two; the iris layout came out byte for
     byte as before.
9. **Mountain door: done.** An arched door, 7 wide and 9 high (57 blocks), at
   the back of a gorge cut into a mountain; one lever opens it like a clock
   hand sweeping round, from twelve o'clock clockwise as you face it, an
   eighth every 2 redstone ticks, and winds it shut the other way. Behind it
   a tunnel leads to a vault (the person, 2026-11-24: "Door into a mountain
   that opens/closes with a pattern"; `MountainDoorGameTests`, examples
   station 24). The first eighth goes 0.8 s after the lever.
   - The pattern is the door's rings: eight sectors round the door's middle
     (`tools/gen_mountain.py`, in whole numbers so the GameTest computes the
     same).
   - The wiring is hand-built (a Fable 5.2 agent's design, 2026-11-24), no
     longer `gen_door.py`'s: 1234 hidden blocks in a 9 x 13 x 37 box, within
     the door's width and 3 blocks under the gorge's floor (gen_door.py's took
     2837 blocks in 36 x 27 x 38 and 2.4 s). How: each door block is a stone and
     repeaters behind it (rows alternate, even rows fed from behind, odd from in
     front); the runs of one eighth in a row share a wire, at depth 3 for even
     eighths and 5 for odd ones, so neighbouring runs never have wire side by
     side (what defeated routing); lanes on the free levels between rows feed
     the runs, read through glass steps (dust climbs a glass step but never
     comes down it). Opening delay plus closing delay is the same for every
     eighth, so eighths pair up (j with 7 - j): from L delayed 2j and L delayed
     14 - 2j, one small plane makes the OR (on early, off late) for j and the
     AND (on late, off early) for 7 - j. Four planes behind the door, four in
     front, fed by two glass ladders (L, and L delayed 8).
   - The layout's signal names come from a simulated run (`a<on>c<off>`, in
     redstone ticks after the lever), and its `allow` lines only where the run
     shows an input is never on while the wire it joins is off (the ORs, the
     NORs of two torches).
   - Lessons: the simulator had wire steps over glass backwards (#52), and the
     checker let a wire pick up a block powered only by wire (vanilla turns
     wires' signals off while a wire works out its strength).
   - The earlier gen_door.py version taught it: the lever's wire staircase
     needs a repeater every 12 steps (a landing), and so does any long run of
     flexible spots turned to wire (ring 0's line to its bus ran 20 blocks of
     dust and faded out; the simulator caught it, the checker did not). The
     lever's line is now wire but for its two end repeaters (one redstone
     tick off every door).
   - In the examples world the mountain stands on the world's grass (the
     earlier wiring went 18 blocks deep, on foothills).
10. **Appearing bridge: done.** A bridge of 14 blocks across a chasm whose
   blocks are there only within 3 steps of someone: it appears ahead as you
   walk and vanishes behind you (the person, 2026-11-24: "Staircase
   appearing, maybe just in front of the player"; the bridge first, a
   staircase next; `AppearingBridgeGameTests`, examples station 25). A step
   appears within 8 game ticks of someone coming within 3 of it.
   - The layout (`contraptions/appearing_bridge.txt`, about 950 hidden blocks)
     is written by `tools/gen_path.py`, which checks it in mcb's simulator
     for someone at every step and between every two.
   - Sensing: a hidden string over each step at head height, between a hook
     on a phase block and a hook on a stone. Head height because a jump lifts
     the head clear of it for only about 3 ticks at the top; feet clear a
     string at foot height for most of a jump (the GameTest's jumps, paused
     0 to 11 ticks so the string's 10-tick recheck falls on every tick of a
     jump, drop the step underfoot with strings at foot height).
   - Reach: dust fades one level a block. Each tripped sensor puts exactly 4
     into a line of wire along the bridge, through a branch 11 blocks long
     from its hook's stone, so the line is powered exactly within 3 steps.
     Each step's inverter (a repeater into a stone under a torch, the torch
     powering the stone under the shifter) holds the block when its line is
     off.
   - The geometry is the hard part: every step's logic sits in its own
     plane, and neighbouring planes touch, so wire at the same spot (or a
     step above or below) in two neighbouring planes joins. And wire laid in
     one upright plane cannot turn back across it (a turn needs one block to
     be both stone, to carry the step, and not stone, to let it through), so
     a branch of 11 blocks spans 11 columns: the strings reach 12 blocks out
     to the side. Even steps feed a line on one side, odd steps a line on the
     other, so no two neighbouring planes have a branch on the same side;
     each step's inverter reads both lines.
   - The simulator now knows tripwire hooks (inputs, powering like a lever on
     a wall), and so does `tools/redstone_check.py`.
   - **The staircase: done** (`AppearingStaircaseGameTests`, examples station
     26, `tools/gen_stairs.py`): 12 steps from a meadow up to a cliff's
     ledge, the same sensing and reach (918 hidden blocks in 18 x 19 x 29).
     Each plane rises a block with its step, so a part one block up in a
     plane's template sits beside the part one block down in the next
     plane's: the bridge's stone under each shifter would sit beside the step
     below's shifter and power it. So each shifter is fed from the side (a
     torch on a stone the lines' repeaters power, under a stone beside the
     shifter), and nothing above or below a shifter carries power. The lines
     are wire staircases (stone under every wire, nothing over), level along
     the approaches: before the stairs level with the ground, after them with
     the ledge.
11. **Sorting machine: done** (`SortingMachineGameTests`, examples station 27,
   `tools/gen_sorter.py`; on the person's showcase list). Put items in a
   chest on top of a wall: they come out sorted into the chests below it, one
   kind per chest (cobblestone, dirt, oak logs, iron ingots), everything else
   in the last. Visible: the wall and the chests; 123 hidden blocks in
   17 x 8 x 8. The redstone community's hopper item sorter in the hidden
   layer: a phased hopper under the input chest feeds a line of hoppers in
   the wall; under it, per kind, a filter hopper (18 of its kind and 4 sticks
   named "Filter": a comparator reads 1) and a gate hopper that is unlocked
   while the filter holds one more (the comparator reads 2, two wires on, a
   stone). Phased droppers under the chests push the items up into them,
   fired by a clock (two observers watching each other) through their phase
   blocks; a full chest's items wait in its dropper. Changes to the classic:
   - A drain hopper under each gate pulls the item straight out. The gate is
     locked again 6 game ticks after it pulls (comparator, repeater,
     comparator) and a hopper waits 8 before it pushes, so a gate on its own
     would keep the last item of each kind until the next one came.
   - The lock is a comparator in subtract mode with a redstone block behind
     it, which a repeater into its side turns off, not the classic redstone
     torch: a stack of one kind toggles the lock 64 times in about 30
     seconds, and a torch forced off 8 times in 60 game ticks burns out.
   - A block that changes itself when it is placed sends its neighbours no
     update (vanilla's setBlock updates them only if the block is still the
     one placed): a gate written locked unlocked itself before its lock was
     on, and the lock never heard of it. Written unlocked, it is locked once
     the lock comes on (BUILD_RULES C12).
   - The checker now knows hoppers, chests and redstone blocks; it reads a
     comparator's side as vanilla does (a redstone block, a wire or a
     source; a powered stone gives nothing).
12. **Castle tower: done** (`CastleTowerGameTests`, examples station 28,
   `tools/gen_tower.py`; on the person's showcase list). A 5 x 5 stone tower,
   eight courses with battlements, a doorway and barred windows (118
   blocks). It stands at rest; its lever takes it down block by block from
   the top and, flipped back, it builds itself up from the ground. A line
   shifter under each of its 16 columns does it: 226 hidden blocks (91
   glass) in a 10 x 6 x 12 box.
   - The first build (PR #58, not merged) was all redstone and phase
     shifters: every block needed its own timed wire through its only free
     side, outwards, so 112 blocks took 3,483 hidden blocks in 58 x 13 x 43
     (channels, eight rings, a tapped delay line with gates, a lock). The
     person asked whether something was missing; "pushing" a row or column,
     as a piston does, became the line shifter, and the tower was rebuilt on
     it.
   - Order: a helix. Column k (clockwise from the north-west corner, face by
     face, each corner the first of its face) starts a redstone tick after
     column k - 1, and a column moves a block every 4 redstone ticks (the
     line shifters' delay). Coming down, the block at course y goes
     k + 4 (7 - y) redstone ticks after the first, 4 game ticks after the
     lever; going up, k + 4 y + 4: the top comes off in a spiral, a course
     lower every face, and comes back the same way. 4.3 s each way.
   - Columns of different heights: a battlement gap (crenel) makes a column
     a block shorter at the top, so coming down it starts 4 redstone ticks
     later than a full column, and going up at the same moment. Its line
     shifter takes when its power comes and puts back when it goes, so a
     full column's shifter is fed twice, ring A (signal at k) and ring B (at
     k + 4): an OR, on at the earlier and off at the later; a crenel's only
     by ring B. Every column so starts going up 4 redstone ticks after its
     turn (the + 4 above), and no AND gate is needed (BUILD_RULES C15).
   - Rings: square rings of wire four blocks out, a repeater three blocks
     out (delay c + 1, c the column along its face) into a stone at each
     column: above the shifter (ring A, at the tower's lowest course, inside
     its walls) or under it (ring B). 4 redstone ticks between faces. The
     lever's port feeds ring A through one repeater and ring B through two
     (5 redstone ticks).
   - The doorway's column starts at its lintel; its shifter stands in the
     doorway, just under it, ring A's stone under it in the doorway's
     floor. It must put back at k + 12, the signal of the west face's
     middle column: a wire from that column's ring A stone runs through the
     tower's inside onto the doorway's shifter.
   - The arrow slits became barred windows (iron bars, the agent's choice,
     open to change): a slit splits its column in two, and its parts would
     need their own timing.
   - Flipped back during a move, every column turns around (no lock: a line
     shifter keeps its own state), and no block ever hangs over a gap; the
     tower stands again as it was (`flippedBackMidwayItTurnsAround`).
   - Lesson: the GameTest's barrier ceiling sat on the full columns, and a
     piston's line with an immovable block in it is refused: the full
     columns never moved while the crenels (ending in air) did. The test
     structure now has headroom (BUILD_RULES C15).
   - The checker now accepts a shifter fed by signals it is made of (an OR,
     allowed), as it does a gate's torch.
13. **Vanishing cottage: done** (`VanishingCottageGameTests`, examples
   station 29, `contraptions/vanishing_cottage.txt`; a showcase for the area
   shifter). A 7 x 5 x 7 plank cottage with log corners, glass windows, a
   spruce roof and a chest, crafting table and furnace inside, on a
   stone-brick foundation. Its lever takes the whole cottage out of the
   visible world, roof first, a layer every 3 redstone ticks (1.5 s), and
   flipped back builds it again from the floor, the chest's contents kept.
   - Two area shifters at the cottage's opposite corners; the lever's port
     feeds the low one through a wire and the high one up a hidden wire
     stair east of the cottage (a repeater halfway): 44 hidden blocks. The
     game rule must be on; `tools/examples.py` turns it on in the dev world.
   - The foundation: grass under the cottage turns to dirt, and showed as a
     patch once it was gone.
   - Timing seen from a test: a move the lever starts begins as its power
     crosses into the hidden layer, at another point of the game tick than
     the scheduled steps that follow, so coming back the first gap looks a
     tick short. Flipped again right after a move ends, the next waits a
     layer's time: the rhythm holds (`AreaShifterGameTests`).
14. **Hole in the ocean: done** (`OceanHoleGameTests`, examples station 30,
   `tools/gen_ocean.py`; on the person's showcase list). A pool 8 deep; its
   lever opens a dry 3 x 3 shaft with glass walls down to the sand, and
   flipped back the sea closes over it as still water. No sponges, as often
   as you like, and nothing shows while it is closed.
   - Two area shifters at opposite corners of the shaft's box (its 5 x 5
     glass ring and 3 x 3 inside, 8 high) hold the glass and a filler (light
     blue glass) while it is closed; the sea fills the box. Under the sand a
     line shifter faces up under each of the 9 inside columns. Opening, the
     area shifters put the glass and filler back from the sand up, each block
     in place of the water; then the line shifters take the filler, from the
     top: dry. Closing, the line shifters put the filler back first; then the
     area shifters take everything from the top, and each layer of sea comes
     in over solid filler, so it settles as still water.
   - The order is the controller's: A (the area shifters) = NOT (L OR Ld), S
     (the line shifters) = L AND Ld, Ld the lever's L delayed 16 redstone
     ticks: A falls at once and rises late, S rises late and falls at once.
     Four torches; every line laid by `mcb.router` (`route_all`), checked in
     the simulator (opening: A at 16 game ticks, S at 44; closing: S at 18,
     A at 40, each after the other's 16-tick move). 320 hidden blocks.
   - The line shifters are powered by hidden wire on top of them (a wire
     powers the block it stands on): the middle one has no free side.
   - A line shifter's line starts in front of it: placed at the sand's spot
     (in the hidden layer), it takes from the first water layer up, and the
     line ends at the air above the pool (8 blocks, under the 12 cap).
   - Router lessons: a line's start may not have other redstone a step below
     it (the port under the lever), so a short hand-laid wire leaves the port
     first; and a line's end may not either (the line shifters under the
     inside), so one hand wire joins the line to the wire on top of them.
   - The test also checks, after each close, that the sky light in the box
     is the same as beside it in the pool. A lighter patch over the shaft in
     the tour's screenshots looking down into the pool is how the game draws
     sand through deep water from there: an untouched copy of the pool
     (cloned, no hidden parts) shows it too, and the client's blocks and
     light in the box match the pool's.
   - Broken on purpose: with the delayed lines' repeaters at 1 tick the
     filler goes before the walls are back and stays; without the wire into
     the line shifters the shaft never empties. Both fail the test.
15. **Light-up dance floor: done** (`DanceFloorGameTests`, examples station
   31, `contraptions/dance_floor.txt`; on the person's liked list). A 6 x 6
   floor of redstone lamps in a blackstone border: each lamp lights while
   someone stands on it and goes out a second after they step off, so a
   dancer leaves a short trail. Nothing shows but the lamps.
   - Per tile, three hidden blocks: a pressure plate where the feet are, on a
     phase block at the lamp's spot (so it feels visible players and mobs),
     and a phase port under that. The pressed plate strongly powers the phase
     block (it conducts in the hidden layer; it only carries nothing across
     layers), which powers the port, which gives the power out in the visible
     world like an invisible redstone block under the lamp. 108 hidden
     blocks, no wire.
   - Each tile lights alone: a port's invisible redstone block touches only
     the lamp above it (the others are diagonal), ports ignore each other,
     and a powered phase block touches only its own port.
   - Broken on purpose: without the ports, or on plain stone instead of
     phase blocks, no lamp lights; both fail the test.
16. **Combination lock: done** (`CombinationLockGameTests`, examples station
   32, `tools/gen_lock.py`; on the person's liked list). Four levers on a
   stone-brick wall and a doorway in it that looks like the rest of the wall:
   on, off, on, on (west to east) opens it onto a vault with a chest; any
   other setting keeps it, or puts it back, shut. Only the levers show.
   - A phase port at the wall block each lever hangs on brings its power into
     the hidden layer. A lever that must be on goes through a torch (wrong
     while off), one that must be off goes straight (wrong while on); the
     four wrong lines run into one block (two of them first join in another,
     J: a block has three free sides beside the torch), and the torch on it
     is "open". A phase shifter at each door block takes it while open,
     powered through a stone right behind it: the lower from the north, the
     upper from the south, so no line ends a step from the other shifter
     (the router refuses that). 251 hidden blocks, all lines by
     `mcb.router.route_all`.
   - The generator checks all 16 settings in the simulator; the GameTest
     goes through all 16 in the real game, one lever at a time (a Gray code,
     so each lever is flipped both ways), 40 game ticks each: the door opens
     about 24 game ticks after the last lever.
   - The checker now lets a repeater read a block that lines of the signals
     it is declared to be made of power (an OR), as it did a shifter or a
     gate's torch.
   - Broken on purpose: without lever 1's torch, off, off, on, on opens it.
17. **Pixel wall: done** (`PixelWallGameTests`, examples station 33,
   `tools/gen_pixels.py`; the person's "shifter display (pixel wall)"). A
   lime wall 10 x 10 with black behind it; its lever draws a creeper's face:
   the eyes, then the nose, the mouth and the mouth's corners, 4 redstone
   ticks apart (36 to 60 game ticks after the lever); the lever back wipes it
   in the opposite order. A hidden phase shifter at each of the face's 20
   pixels takes the lime block there and the black shows through. The
   wiring is gen_door's (the iris door's): a picture is a door whose cells
   switch in rings. 726 hidden blocks.
   - gen_door grouped a row's cells of one ring into one run whenever they
     came one after the other, even across a gap: the two eyes shared one
     wire, which fed only the first, and the right eye and a mouth corner
     never moved (the GameTest found it). A run now ends at a gap, and the
     generator's simulation checks that every cell's shifter switches, not
     only each ring's first (shown to catch the old grouping). The iris door
     and the waterfall come out the same.
   - On the dev server it stands on a platform like the iris (its wiring
     goes 9 blocks under its floor), south of the path and facing it.
18. **Treasure reveal: done** (`TreasureRevealGameTests`, examples station
   34, `tools/gen_treasure.py`; on the person's liked list). An altar with a
   barrel and empty pedestals behind it: put anything in the barrel and the
   treasure appears (a gold, a diamond and an emerald block on the
   pedestals, sea lanterns on the pillars beside them); take the offering out
   and it goes. A hidden comparator on a phase block reads the visible
   barrel; its output powers a block whose torch is "hide", and hide powers
   a phase shifter at each treasure block's spot (a router-laid tree). 58
   hidden blocks.
   - The checker now knows a phased comparator: on a phase block it reads
     the visible block behind it, an input of the layout.
   - Broken on purpose: on plain stone instead of a phase block the
     comparator reads nothing hidden and the treasure never comes.
19. **Minecart track: done** (`MinecartTrackGameTests`, examples station 35,
   `tools/gen_track.py`; on the person's liked list). A chasm with a track
   across it that is there only around a minecart: each of its 20 columns,
   a spruce deck block and the rail on it, is there only while a cart is
   within 5 columns, so the track is laid ahead of the cart and taken up
   behind it. A button by the wall on each cliff starts a cart; boosters (a
   hidden phase port under each) bring it to a cart's top speed, 0.4 blocks
   a game tick. 1,256 hidden blocks.
   - The appearing bridge's machinery (`tools/gen_path.py`), one block lower:
     a line shifter facing up under each column holds deck and rail (it puts
     the deck back first, which the rail needs, and takes the rail first),
     and the phased strings sit at rail height, where a cart trips them.
   - Rails were thought fragile to pistons; they are not (pistons push them),
     so shifters take them as any block: checked on the dev server.
   - A reach of 5: a column's shifter lets go within 4 game ticks (simulated)
     and the deck and rail take 2 more, while a cart at top speed covers 2.4
     blocks; the GameTest rides at top speed and checks every tick that the
     cart's column and the next are there and nothing more than 6 ahead.
   - Broken on purpose: slower line shifters (delay 4) leave the column ahead
     of the cart missing; a reach of 8 shows columns too far ahead.
20. **Skeleton farm: done** (`SkeletonFarmGameTests`, examples station 36,
   `tools/gen_skeleton.py`; the person chose a skeleton farm, and that the
   fall kills, over XP). A dark tower with a skeleton spawner in a 7 x 7 room
   at its top; stairs wind up its outside to a gallery with a window of
   tinted glass (it lets no light into the room) and a button. Pressed, the
   room's floor is gone for a second: the skeletons fall 28 blocks to the lit
   floor at the tower's foot (25 damage, 20 health) and die. 206 hidden
   blocks, nothing in the shaft.
   - The floor: a phase shifter at each of its 49 blocks, a grid of wire on
     them (C16); a phase port at the wall block the button hangs on brings
     its power down a wire into the grid.
   - The loot: a phased hopper under each landing block pulls what lands on
     it; the hoppers pass it east, then south, out under the wall into a
     phased dropper facing up under the chest, fired by an observer clock
     (as in the sorting machine).
   - Broken on purpose: plain stone instead of the phase blocks leaves the
     chest empty; without the port the skeletons never fall.
   - The dev world is Peaceful, where a spawner makes nothing: the examples
     check opens the floor and drops loot in by hand; the GameTest drops real
     skeletons.

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
    mob standing in a hidden Nether portal stays put; no mob is born there
    (a hidden spawner beside a player inside, a spawn egg used inside), while
    commands and saves still bring them; hidden entities tick
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
    wears the tool; with the rule off nothing is placed or broken; a
    dedicated server's first start switches the rule off, once, and leaves
    the admin's setting alone after that and after a restart, while single
    player and the GameTest server keep it on; middle
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
    block's side lights; with the game rule phasecraft:phased_lights off
    there is no light, and turning it on and off while the server runs
    brings it and takes it away (`PhasedLightRuleGameTests`); a light's
    position is for players tracking its chunk only, not for one beyond
    view distance, and follows them as they come and go. A client
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
- **Client GameTests** (`./gradlew clientTest`, a real game client, run
  with OpenGL, with Vulkan, and with OpenGL plus Sodium, software-rendered
  on a virtual display; a test-only mixin pins the backend so neither
  silently falls back). Sodium replaces Minecraft's chunk renderer and is in
  most players' mod lists; the Sodium run loads it (from Modrinth's Maven,
  pinned by `sodium_version`) with Fabric Loader's `fabric.addMods`, so it
  never becomes part of the mod or the other runs, and
  `OverlayClientGameTest` checks it is loaded there and only there. Nothing
  needed changing for Sodium 0.9.3: the overlay draws through Fabric's
  `LevelRenderEvents`, phased light goes through vanilla's light engine,
  which Sodium reads, and the piston animation uses vanilla moving-piston
  block entities.
- **Open Parties and Claims** (`./gradlew runGameTestOpac`, part of
  `build`): the OPAC claim tests (`OpacClaimChecks`) with the real mod; see
  "Claims hold for the hidden layer".
- **Lithium** (`./gradlew runGameTestLithium`, part of `build`): the server
  GameTests again with Lithium, the most used server performance mod, loaded
  the same way (pinned by `lithium_version`). Lithium's hopper optimization
  breaks phased hoppers twice over: it swaps the item lookup inside
  `suckInItems` for one that reads the hopper's own level (the hidden one,
  where no items lie), and it puts idle hoppers to sleep until something
  changes in their own level, so a phased hopper never wakes for items landing
  in the visible world. That option has no finer switch, so PhaseCraft's
  fabric.mod.json turns it off (`"custom": {"lithium:options":
  {"mixin.block.hopper": false}}`, Lithium's own way for a mod to do this);
  without it `PhasedItemGameTests` and `SkeletonFarmGameTests` fail. The rest
  of Lithium stays on and every test passes with it.
  - screenshots with the overlay on, off, and at higher opacity, compared
    pixel by pixel where each test block appears: a ghost floating in the air,
    a ghost tinting the visible block at its own spot, a ghost inside the
    ground, and a ghost completely behind a visible wall (must show through,
    fainter than one in front); also with improved transparency;
  - H and `]` change the settings;
  - removing a hidden block removes the ghost; chunks out of range are
    forgotten and come back; Survival players and players inside the hidden
    layer are sent nothing;
  - over a real network connection to a dedicated server: the game rule
    phasecraft:survival_access is off there without anyone setting it,
    Survival gets nothing, Creative gets the hidden block, and nothing from
    the previous world is left over;
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
  restart (it hung the server before the next-tick chunk scan), and that a
  world a dedicated server switched survival_access off for is not switched
  off again after the admin turned it on.

Each test was checked by breaking the code on purpose and seeing it fail.

Checked by hand on the RCON dev server: a world with old-style hidden blocks
upgraded at startup, and `tools/examples.py` verifies the examples world,
including a hidden lamp lit by hidden power.

**Not yet automated:** multiplayer with more than one player, and the
client side of layer switching.
