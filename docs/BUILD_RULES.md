# Build rules

Rules for every contraption PhaseCraft ships as an example or a test: what a build must do, what makes
hidden redstone correct, and how small, fast and well-checked we keep it. Each rule says where it comes
from (a lesson in this project, or an established practice of the redstone community) and how it is checked.
"Checked by" names the tool that enforces it today; "by hand" means it is not automated yet.

Tools: `tools/redstone_check.py` (static: which signal reaches each part), `tools/mcb` (the simulator
`mcb.redstone`, checked tick by tick against the real game; the router; shapes), the generators
(`tools/gen_door.py` and its configs, `tools/gen_path.py`), GameTests, `tools/examples.py`.

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
  contents. Checked by: GameTests (shifter tests).
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
  torches, blocks are safe side by side). Lesson: the appearing bridge. Checked by: the simulator; the
  generators' searches.
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
- **C7. A phase shifter is fed through a stone, never by a diode or torch pointing straight into it.**
  A shifter is a solid conductor: strongly powered, it would power the shifters beside it. Lesson: the
  iris door. Checked by: `tools/redstone_check.py` (each shifter gets its own signal only).
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
  Checked by: the GameTest's settling check, the simulator from the written state.
- **C13. Sensors see visible entities only when phased**: a hidden plate stands on a phase block, a string
  line ends at a hook on a phase block; a string at head height stays pressed through a jump. Lessons:
  stations 13, 19, 25.

## 3. Size

- **S1. Every generator reports its hidden block count and the box the hidden parts fill**, and a change
  to a generator says how they moved. Checked by: the generators' output (to do: all of them).
- **S2. Parts stand on blocks that are there anyway** (stone of the logic, the build's own rock) before
  new glass. About 40% of the doors' hidden blocks are glass supports today.
- **S3. Levels are shared where signals can be routed past each other**, rather than one level per signal
  (the mountain door's 8 rings take 18 blocks of depth today).
- **S4. The hidden parts stay within the build's visible footprint where they can**, so a station fits
  its surroundings (the mountain door needed foothills for its depth).

## 4. Speed

- **P1. Every generator reports the delay from input to first motion**, in redstone ticks, and the
  simulator checks it. A torch or repeater costs 1 redstone tick (0.1 s) each; wire costs nothing.
- **P2. Doors respond within about 2.5 s; things players walk into within 0.5 s** (today: iris 2.2 s,
  mountain 2.4 s, waterfall 1.3 s, bridge 0.4 s). Tall torch towers are the main cost.
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
  points at (C2). [wiki: Redstone Dust] Checked by: the checker, the simulator.
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
  sensor) must not drive a torch that fast. [wiki: Repeater, Torch] By hand; the simulator does not model
  burnout.
- **R7. Never rely on the order in which dust updates its neighbours within one tick**: in Java it
  depends on position and direction, so a circuit can behave differently elsewhere. Timing goes through
  diodes. [MC-11193; vanilla's experimental redstone changes this, unconfirmed for 26.3] By hand.
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
