# Build rules

Rules for every contraption PhaseCraft ships as an example or a test: what a build must do, what makes
hidden redstone correct, and how small, fast and well-checked we keep it. Each rule says where it comes
from (a lesson in this project, or an established practice of the redstone community) and how it is checked.
"Checked by" names the tool that enforces it today; "by hand" means it is not automated yet.

Tools: `tools/redstone_check.py` (static: which signal reaches each part), `tools/mcb` (the simulator
`mcb.redstone`, checked tick by tick against the real game; the router; shapes), the generators
(`tools/gen_door.py` and its configs, `tools/gen_path.py`, `tools/gen_sorter.py`, `tools/gen_tower.py`,
`tools/gen_ocean.py`, `tools/gen_lock.py`, `tools/gen_pixels.py`, `tools/gen_treasure.py`,
`tools/gen_track.py`), GameTests, `tools/examples.py`.

## 1. What a build must do

- **B1. Nothing of the hidden layer shows or sounds in the visible world.** Only blocks a player is meant
  to see are visible; ports, phase blocks and wiring are hidden. (The person, 2026-11-23: "NO BLOCKS
  showing from the subworld".) Checked by: the layout format (every redstone part is a `hidden` line), by hand.
- **B2. Vanilla redstone only, plus PhaseCraft's own blocks** (phase ports, phase blocks, phase shifters,
  phase pistons). No command blocks, no scripts. Checked by: the layout format.
- **B3. It works for real players**: walking, sprinting, jumping, two players at once, leaving halfway.
  The GameTest covers each behaviour the build depends on (the bridge's jumps with pauses swept over the
  string's 10-tick recheck). Checked by: GameTests.
- **B4. It never loses or duplicates anything**: a shifter that cannot put a block back drops it, with its
  contents. An item machine leaves nothing inside once it is done, and waits while its output is full
  (the sorting machine's drain hoppers). Checked by: GameTests (shifter tests, sorting machine).
- **B5. It survives being built**: placed in one tick (GameTests, `/mcb place`) and placed over many ticks
  (RCON in `tools/examples.py`), it settles into its resting state without moving anything visible.
  Checked by: the GameTest's "stays closed while settling" check, `tools/examples.py`.

## 2. Correctness (hard rules)

- **C1. Every part stands or hangs on a block, placed before it.** Wire, repeaters, comparators, torches,
  levers, buttons, hooks: a part placed on air pops at the next update. Supports are glass unless something
  needs a conductor there. Checked by: the simulator (`popped`), `Grid.stand` in the generators.
- **C2. Each part receives its own signal and no other.** A leak is the commonest failure in tight 3D
  layouts. Tag every hidden part with its signal (`@signal`, `@out<in`, `allow` lines for nesting).
  Checked by: `tools/redstone_check.py`.
- **C3. Neighbouring planes do not share wire.** When each step, row or ring has its logic in its own
  plane, wire at the same spot in two neighbouring planes joins, and so does wire one block above or below
  at the same spot (a step across). Use the other side, another height, or non-wire parts (repeaters,
  torches, blocks are safe side by side). Lesson: the appearing bridge. On a slope (planes rising a block
  each), a part one block up in a plane's template sits beside the part one block down in the next plane's,
  so a template's vertical neighbours are also neighbours across planes: feed a step's block from the side,
  never from under it (the appearing staircase). Checked by: the simulator; the generators' searches.
- **C4. A wire step carries a signal down only over a conductor**: the lower wire reads the upper one when the
  upper wire stands on a conductor and nothing conductive is above the lower wire. Up, the upper wire reads the
  lower one when the block beside it (at its own height) is not a conductor, whatever it stands on. So a step on
  glass carries a signal up and not down: a one-way step, useful on purpose. Lessons: the router; the simulator had
  the direction backwards until a dev-server check (2026-11-24). Checked by: the simulator (`mcb.verify` on the
  server), `mcb.router` (which uses stone steps only).
- **C5. Wire in one upright plane cannot turn back** (a turn needs one block to be both stone, to carry the
  step, and not stone, to let it through). A wire of n blocks in a plane spans n columns. Lesson: the
  appearing bridge.
- **C6. Dust fades one level a block: a repeater (or other diode) at least every 15 blocks, and every
  strength the design relies on is counted.** Flexible repeater spots turned to wire still need one every
  12. Lessons: the iris's ring 3 bus, the mountain door's staircase and bus line. Checked by: the simulator
  (the static checker does not model strength).
- **C7. A phase shifter passes no power on** (since 2026-11-25, the person: "Let's make the change, I think it
  opens up more building opportunities"; as a piston): a repeater, comparator or torch pointing into it
  powers that shifter only, so any free side of a block can feed it, even a cell that touches other
  shifters. A powered stone beside a shifter still powers it, and every other mechanism it touches, so a
  feed stone must touch no other shifter. A shifter still needs a free neighbour, as a piston does: the
  middle of a solid volume three blocks thick cannot be fed. Until then a shifter conducted: strongly
  powered, it powered the shifters beside it (lesson: the iris door), so builds fed every shifter through
  a stone. Checked by: `tools/redstone_check.py` (each shifter gets its own signal only),
  `PhaseShifterGameTests`.
- **C8. Torches go out only on a direct signal into their block.** Side-by-side torch towers are safe.
  Lesson: the iris door (the checker had this wrong). Checked by: the checker and the simulator.
- **C9. Pistons, phase pistons included, have quasi-connectivity**: whatever powers the block above a
  piston powers it. Keep other signals away from the cell above a piston, or use it on purpose. Lesson:
  the hidden staircase, the 3x3 door, the trapdoor floor. Checked by: `tools/redstone_check.py`.
- **C10. Facing conventions**: a repeater's (and comparator's) `facing` is its input side; a wall torch's
  and a hook's `facing` points away from the block it hangs on. Checked by: the simulator.
- **C11. A wire placed by a layout gets its sides set** (`[north=side,...]`); a lone dot powers only the
  block under it. Lesson: the secret room. Checked by: the generators (`shapes`), the checker.
- **C12. The layout is written in its settled state**: repeaters `powered`, torches `lit` as they rest;
  shifters unpowered (so a powered one takes its block when placed); an extended piston after whatever
  holds it out. Lessons: the iris door (repeaters written off flickered it open), the trapdoor floor.
  Except a block that changes itself when placed: write it as it will be at that moment. Vanilla updates a
  placed block's neighbours only if it is still the block placed, so a hopper written locked, whose lock is
  not on yet, unlocks itself and its lock never hears of it (the sorting machine).
  Checked by: the GameTest's settling check, the simulator from the written state.
- **C13. Sensors see visible entities only when phased**: a hidden plate stands on a phase block, a string
  line ends at a hook on a phase block; a string at head height stays pressed through a jump. Lessons:
  stations 13, 19, 25.
- **C14. A sequence timed by a tapped delay line locks its input while it runs.** Each tap sees the lever's
  whole history, so a lever flipped back during a move sends a second wave through the line: blocks long done
  blink out and back seconds later (the castle tower's first build: 53 blocks after a 7-game-tick flick). Lock the
  line's first repeater while its first and last taps differ (two comparators in subtract mode into its sides), and
  delay the last tap's signal to the lock until the move's last block is done: a flip during a move then waits for
  its end, and a flick makes one whole cycle. The first repeater's delay must be longer than the lock takes to
  close. Lesson: the castle tower's first, all-redstone layout (PR #58, not merged; the tower now runs on line
  shifters, which keep their own state, so a flip just turns each column around). Checked by: nothing now; no
  build uses a tapped delay line.
- **C15. A line shifter's line must end in air.** It takes a piston's line: an immovable block right after the
  line's end (a GameTest's barrier ceiling, bedrock, an extended piston) refuses the whole line, as it would stop a
  piston. Leave headroom over every column, in GameTest structures too. Lines of different lengths moved "from the
  top down, from the ground up" start at different moments one way and together the other: feed a line shifter
  twice for an OR (on at the earlier signal, off at the later), and delay every column's other move by the longest
  shortfall, so no AND gate is needed. Lesson: the castle tower (its full columns never moved under the test's
  barrier ceiling). Checked by: `CastleTowerGameTests`, the generator's simulation.
- **C16. Power a shifter with no free side through wire on top of it.** A wire powers the block it stands on, so
  a grid of wire laid on a grid of line shifters (or any shifters) powers every one, the middle ones included;
  shifters pass nothing on, so the grid powers nothing else. Lesson: the hole in the ocean (the middle of its 3 x 3
  line shifters). Checked by: `OceanHoleGameTests`, `redstone_check.py`.
- **C17. Area shifters pair with the nearest corner within 15: keep other corners further away**, other builds'
  and other GameTests' too. The GameTest server lays every test on one grid, batch after batch: 5 blocks between
  tests in a row, 6 between rows. So a test with area shifters gets an area large enough that its corners, plus
  the gap, plus a neighbour's, make 16 or more: the area shifter tests use 24 x 24 areas, the hole in the ocean
  24 x 32 (its high corner was 15 blocks from the next row's corners, and an area shifter test there failed).
  Lesson: the area shifter's own tests paired with each other's huts. Checked by: `AreaShifterGameTests`.
- **C18. On the dev server, place what a controller drives after the controller has settled.** RCON builds over
  many ticks, and torches flicker while the circuit is half placed (a GameTest builds in one tick): the hole in the
  ocean's line shifters put filler back while its area shifters were already taking the box. `tools/examples.py`
  places such parts a second later (the ocean's corners, the flying machine's observers). Checked by: the examples
  script's checks.
- **C19. Keep a rail that must rest unpowered (a start or brake rail) a plain rail away from powered rails.** A
  powered rail passes its power on to the powered rails next to it, up to 8 along: a start rail next to the boosters
  would always be on, and send a cart that has just arrived straight back. Lesson: the minecart track's start rails.
  Checked by: `MinecartTrackGameTests` (the cart stops on the far start rail).

## 3. Size

- **S1. Every generator reports its hidden block count and the box the hidden parts fill**, and a change
  to a generator says how they moved. Checked by: the generators' output (to do: all of them).
- **S2. Parts stand on blocks that are there anyway** (stone of the logic, the build's own rock) before
  new glass. About 40% of the doors' hidden blocks are glass supports today.
- **S3. Levels are shared where signals can be routed past each other**, rather than one level per signal
  (gen_door.py's 8 rings took 18 blocks of depth; the hand-built mountain door shares levels by giving
  neighbouring runs wire at different depths: 3 below the floor).
- **S4. The hidden parts stay within the build's visible footprint where they can**, so a station fits
  its surroundings (the mountain door's old wiring needed foothills; the new one stays within its width).
- **S5. Compute a timing once and share it, rather than per block**: a door's eighths pair up because
  opening plus closing delay is constant, so one small plane makes two eighths' signals (the mountain door:
  2837 blocks to 1234). Look for such sums and symmetries before laying out.

## 4. Speed

- **P1. Every generator reports the delay from input to first motion**, in redstone ticks, and the
  simulator checks it. A torch or repeater costs 1 redstone tick (0.1 s) each; wire costs nothing.
- **P2. Doors respond within about 2.5 s; things players walk into within 0.5 s** (today: iris 2.2 s,
  mountain 0.8 s, waterfall 1.3 s, bridge 0.4 s). Tall torch towers are the main cost (the mountain's
  went from 2.4 s to 0.8 s without them).
- **P3. Timing a sequence relies on counted delays, checked in the simulator and measured in a GameTest**
  (the iris's rings, 44/52/60/68 game ticks, match the simulator to the tick).

## 5. How a build is made

- **M1. Big layouts come from a generator**: change the generator, never the generated file.
- **M2. In order**: lay out (mcb.shapes, mcb.router, a generator) → static check → simulate every input
  state (and, for sensors, every position) → GameTest → break the behaviour on purpose and see the test
  fail → examples station verified by `tools/examples.py` → tour screenshot looked at → docs.
- **M3. One build, one layout file**, shared by its GameTest, its examples station and the checker.
- **M4. A layout's header says what it is, how it works, its signals, and which generator wrote it.**

## 6. From the redstone community

Rules adopted from established sources (Minecraft Wiki, MCHPRS's Redpiler compiler, the Dewey
place-and-route compiler, LogicLoom). Open Redstone Engineers publishes no build standards (its rules page
is about conduct), and the HDL-to-redstone projects document few rules, so these come mostly from the
wiki and the tools.

- **R1. Dust weakly powers the block it sits on and the blocks it points at; a weakly powered block drives
  the repeaters, comparators and mechanisms next to it, not dust.** Check every block a wire sits on or
  points at (C2). [wiki: Redstone Dust] Checked by: the checker (since 2026-11-24: it used to let dust pick
  up such a block), the simulator.
- **R2. Pistons update from as far as 2 blocks (taxicab) away**: dust and torches update blocks up to 2 away,
  so quasi-connectivity (C9) can fire from a powered part 2 blocks off. Keep powered parts 2 blocks from a
  piston's upper cell unless meant. [wiki: Redstone mechanics, Quasi-connectivity] By hand.
- **R3. Transparent supports never pass signal down and do not cut dust**: dust stepping up onto glass is a free
  one-way diode, up only (C4). [wiki: Transmission]
- **R4. Every net has exactly one driver**, and every wire is within 14 blocks of it (Redpiler drops links of
  15 or more; Dewey inserts repeaters by walking from each net's driver). Our generators' lines that join on
  purpose (the bridge's lines, a door's OR) are the exception, listed with `allow`. Checked by: the
  simulator (strength), the checker (signals).
- **R5. Repeater locking**: a powered repeater or comparator facing a repeater's side locks it. Flag any
  diode facing a repeater's side that is not meant to lock it. [wiki: Redstone Repeater] To do: the checker.
- **R6. Pulses**: a repeater stretches shorter on-pulses to its delay and drops shorter off-pulses (the
  bridge's jump rule rides on this); torches and comparators do not reliably answer 2-game-tick pulses; a
  torch burns out when forced off more than 8 times in 60 game ticks. Anything a player can flicker (a
  sensor) must not drive a torch that fast, nor anything items drive (a stack goes through a sorter's filter
  at 2.5 items a second): the sorting machine's lock is a comparator in subtract mode with a redstone block
  behind it. [wiki: Repeater, Torch] By hand; the simulator does not model burnout.
- **R7. Never rely on the order in which dust updates its neighbours within one tick**: in Java it
  depends on position and direction, so a circuit can behave differently elsewhere. Timing goes through
  diodes. [MC-11193; vanilla's experimental redstone changes this, unconfirmed for 26.3] Nor on which of two
  diodes due in the same game tick (same priority) ticks first: vanilla takes them in the order its block updates
  reached them, the simulator in position order (mcb.verify found such a race between two comparators). By hand.
- **R8. Vertical transport costs** (pick by cost): dust staircase (no delay, a block across per block up;
  repeater every 15), torch tower (up only, 1 redstone tick per 2 blocks), torch ladder (down, 1 redstone tick
  per block), transparent-block ladder (up only). [wiki: Transmission] Our doors' torch towers set most of
  their delay (P2).
- **R9. Crossings**: a dust bridge crosses without delay (1x3x4); a repeater bridge is lower (2x3x3) but adds
  a redstone tick to both lines. [wiki: Transmission]
- **R10. Routers rip up and reroute**: route every net, let conflicts happen, then reroute the conflicted
  ones until none remain (Dewey: a maze router with rip-up and reroute; FPGA tools: PathFinder's negotiated
  congestion). A router must say when it cannot route, never loop. [Dewey] Checked by: `mcb.router.route_all`
  (two-ended lines; trees, one source to many ends, to do).
- **R11. Say how a build tiles**: whether copies placed side by side (or stacked) stay independent, and at
  what pitch ("1-wide tileable"). Our planes are this (C3). [wiki: Redstone circuits]
- **R12. Give sizes the wiki's way**: shortest horizontal x other horizontal x height, support blocks
  included; with the delay. [wiki: Style guide/Redstone] Checked by: the size report (S1), to adopt.
- **R13. Test continuity after settling, with every input forced on and then off**, and test short pulses
  and rapid toggling. [LogicLoom check_wires; wiki: Redstone tips] Our simulator checks do the first; the
  second, to do.
- **R14. A simulator optimises or reasons about settled circuits only** (Redpiler's optimiser breaks
  unsettled ones). Ours starts from the written settled state (C12) or the server's.

Sources: https://minecraft.wiki/w/Redstone_mechanics, https://minecraft.wiki/w/Redstone_Dust,
https://minecraft.wiki/w/Redstone_Repeater, https://minecraft.wiki/w/Redstone_Torch,
https://minecraft.wiki/w/Tutorial:Quasi-connectivity, https://minecraft.wiki/w/Redstone_circuits/Transmission,
https://minecraft.wiki/w/Redstone_circuits, https://minecraft.wiki/w/MCW:Style_guide/Redstone,
https://minecraft.wiki/w/Tutorial:Redstone_tips, https://github.com/MCHPR/MCHPRS/blob/master/docs/Redpiler.md,
https://github.com/qmn/dewey (and its report, https://www.ocf.berkeley.edu/~qmn/assets/nguyen_dewey_2018.pdf),
https://modrinth.com/mod/logicloom, https://bugs.mojang.com/browse/MC-11193, https://openredstone.org/rules/.
