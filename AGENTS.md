# Agent instructions for PhaseCraft

You are contributing to an experimental Minecraft Java Edition **Fabric** mod.
The goal is a hidden block layer sharing XYZ coordinates with ordinary world
blocks, with future vanilla-compatible cross-layer redstone and pistons.

## Invariants
- Never modify the visible world's blocks when adding/removing a hidden cell.
- Persist hidden data per dimension and keep writes on the logical server.
- Preserve vanilla piston rules; do not add phase pistons until storage and
  interactions are demonstrated and tested.
- Never claim something is working without a passing build/test in CI or a
  reproducible local run. Do not silently bypass failures.
- Keep milestones small and open PRs; do not force-push or push to main.
  The person lets the agent merge its own PRs once CI is green
  (2026-11-23: "merge what you want, you're running this show"); say so
  in chat when one is merged.
- Never use commands that wipe player saves or unrelated files.

## Stack
- Minecraft Java **26.3** / Fabric / Java **25** / Gradle **9.8.2**.
- Mojang-mapped source as provided by Fabric 26.3 (no `mappings` declaration).
- New unobfuscated Loom plugin ID: `net.fabricmc.fabric-loom`.
- Tests under src/test/java use JUnit 5 (`./gradlew build`).
- In-game GameTests live in src/gametest (test mod `phasecraft-test`).
  `./gradlew build` runs JUnit, `runGameTest` and `restartTest`; CI runs it.
  - Name ordinary test classes `*GameTests` (the runGameTest filter is
    `phasecraft-test:*_game_tests_*`) and list every test class under the
    `fabric-gametest` entrypoint in src/gametest/resources/fabric.mod.json,
    or it is silently not run.
  - Behaviour that must survive a restart goes in `PhaseLayerRestartPhases`
    (write phase, then verify phase in a new server process).
  - Client behaviour (rendering, keys, syncing) goes in client GameTests
    (src/gametest/java/dev/phasecraft/test/client, listed under the
    `fabric-client-gametest` entrypoint). `./gradlew clientTest` runs them
    with OpenGL, with Vulkan, and with OpenGL plus the Sodium mod (pinned
    by `sodium_version` in gradle.properties; most players use it) on an
    Xvfb virtual display. A client test that needs ordinary terrain makes
    its world with `setUseConsistentSettings(false)` (the usual test world
    is flat): InsideViewTerrainClientGameTest. `runGameTestLithium` (part of build) runs the
    server GameTests with the Lithium mod (`lithium_version`); PhaseCraft
    turns off Lithium's hopper option in fabric.mod.json, as phased
    hoppers need vanilla's. `runGameTestOpac` (part of build) runs the
    claim tests that need the real Open Parties and Claims mod
    (`opac_version`, `forge_config_api_port_version`): classes named
    `Opac*Checks` (filter `phasecraft-test:opac_*`), which the other runs
    leave out; each such test gets a test environment of its own (claims
    cover whole chunks), and only its helper `OpacClaims` may name OPAC's
    classes. PhaseCraft answers the Common Protection API for OPAC
    (`dev.phasecraft.compat.OpacProtection`, loaded only with OPAC; compiled
    against it, never shipped). Check what is
    drawn by comparing screenshot pixels, and look at the screenshots
    (build/run/clientGameTest*/screenshots) before calling a visual change done.
- Only hidden chunks that hold something load and tick (HiddenChunks, an
  index saved per hidden dimension; docs/ARCHITECTURE.md). The GameTest
  harness lists the chunks under each test's structure; a test that uses
  hidden chunks elsewhere gets them listed by putting a block there, and
  must allow a few ticks for them to start ticking.
- A GameTest starts only once the hidden chunks under its structure tick
  (GameTestInfoHiddenChunksMixin, test mod): they start some ticks after the
  visible ones, by chance, which made rare late or stalled hidden steps
  (issue #3). To see it: `./gradlew runGameTest -PgameTestVmArgs="-Dphasecraft.test.waitForHiddenChunks=false
  -Dphasecraft.test.hiddenChunkDelay=40"`; `-PgameTestFilter=...` picks tests.
- The GameTest server runs ticks as fast as it can, but chunks a ticket asks
  for load in their own time on other threads: a test that waits a number
  of ticks for such chunks passes or fails by luck. Load them in the test
  (`level.getChunk(x, z)`), as InsideViewGameTests does.
- `./gradlew runPerf` measures server tick cost with and without PhaseCraft's
  parts (src/perf, a mod never shipped; method and options in
  src/perf/README.md); not part of build. Rerun it after a change on a hot
  path (signal reads, block changes, per-tick scans).
- Client-only code lives in src/client (split source sets); src/main must not
  reference client classes.
- Test games never talk to Mojang or Microsoft (2026-11-22: dozens of test
  runs got the person's internet address blocked by Mojang's front door,
  which also broke their own logins and Realms). build.gradle's
  `offlineTestArgs` point service discovery and Realms at local addresses,
  test dedicated servers run with `online-mode=false`, and a test mixin skips
  the blocked-servers download. NoMojangTrafficGameTests guards the settings;
  any new test run configuration must add `offlineTestArgs`.
- Whatever a player does to the hidden layer is checked against the visible
  world's protection at that spot first: `mayInteract` (spawn protection,
  world border) and `dev.phasecraft.edit.Protection` (claim mods, through
  the Common Protection API, jar-in-jar). Never fire Fabric's use/attack/
  break events for a block nobody clicked. Known gap (mods not answering
  the API): "Claims hold for the hidden layer" in docs/ARCHITECTURE.md.
- Whatever a hidden machine does to the visible world with no player acting
  (shifters, phase pistons, phased hoppers, droppers, dispensers) is checked
  too (the person, 2026-11-27: "Let's close that gap"): it acts with the
  rights of whoever placed it (`MachineOwners`, saved per hidden dimension;
  a machine block must be listed in `MachineOwners.isMachine`), and asks
  `dev.phasecraft.edit.MachineRights` for every visible spot it is about to
  change; a refused spot is an immovable block to it. Any new machine, or
  new way to change the visible world from the hidden layer, must ask
  there, with a `ClaimMachineGameTests` test. The agent's defaults, open to
  change: a machine with no owner works in a claim only if its own spot is
  protected; nobody is told of a refusal. See "Claims hold against hidden
  machines" in docs/ARCHITECTURE.md.
- New behaviour needs a GameTest, and a new test should be shown to fail when
  the behaviour is broken (break the code on purpose, run, restore).
- `./gradlew installToMinecraft` installs a passing build into the local
  Minecraft mods folder set in git-ignored `local.properties`. Run it from
  main after every merge (the person, 2026-11-23: "Just install it every
  time please"); it replaces only the PhaseCraft jar.

## Next work
The hidden layer is a real dimension per visible dimension (see
docs/ARCHITECTURE.md); vanilla runs it. Acceptance target for 1: the
hidden piston door.
Power crosses only through phase ports (done). The person wants every
crossing explicit: a dedicated block, never every position. And nothing of
the hidden layer may show in the visible world (the person, 2026-11-23:
"The whole point of this is to have NO BLOCKS showing from the subworld";
"IF we have a block to move power between visible and hidden worlds it
should not be visible in the main world"). So the port sits in the hidden
layer and carries power both ways at its spot (up: like an invisible
redstone block there); the phase block only phases and carries no power
(the person chose this split, 2026-11-23: "Do that"). Hidden blocks make no
sound in the visible world either (2026-11-23: "Let's make them silent in
the overworld"; taken as every visible dimension).
Phase ports, phase pistons, the hidden 2x2 door, the hidden staircase, the 3x3 flush door, the overlay (including
chests, signs and fluids), editing mode (including pick) and keeping players
out of the hidden layer are done. The person expects the hidden layer to look like a
transparent layer, not a separate world.
1. The order (chosen by the person, 2026-11-23), top to bottom:
   a. Famous redstone contraptions, PhaseCraft style (the person's idea; a
      Mumbo Jumbo video inspired the mod): examples stations with GameTests,
      machinery all hidden, showing what vanilla cannot do; not
      block-for-block copies of anyone's builds. Hidden staircase (done,
      station 11), 3x3 flush door (done, station 12), secret room behind a
      bookshelf wall (done, station 16: the switch is a bookshelf hiding a
      phased button, a hidden copper bulb keeps the state), trapdoor floor
      (done, station 17: a phased pressure plate before the trap drops the
      next two tiles into a pit). A giant iris door (the person's idea,
      2026-11-23; once on hold, then on the person's list, 2026-11-24): done
      (station 22), a round 7x7 door of phase shifters that opens from the
      centre out and closes from the outside in (the agent's default), its
      layout written by tools/gen_iris.py.
   a2. The phase shifter (the person's idea, 2026-11-23: "pulling blocks
      from the main world into the sub world and then putting them back";
      "let's build it"): a hidden block that, powered, takes the visible
      block at its own spot out of the visible world and holds it, and puts
      it back when unpowered. Done (station 18). Piston rules decide what it
      can take, except that blocks with contents (chests...) are taken
      too, with their contents (done, station 21; on the person's list,
      2026-11-24); instant, no effect (the agent's default,
      open to change). Putting back breaks a visible block in the spot
      (the person, 2026-11-24: "it should break the main world block";
      done), silently; it waits for unbreakable blocks (the agent's
      default), and lifts a mob or player in the spot to stand on the
      block, as a piston pushing up would (the person, 2026-11-26, over
      waiting: the hole in the ocean left a pillar standing when someone
      was in the shaft); with no room above them, or only their head in
      the spot, it waits. Recipe (the person, 2026-11-23: ender pearls, "since
      this would be pretty powerful", and "make two"): Phase Block + ender
      pearl + piston makes 2.
   a3. The line shifter, shown to players as the Shifter Piston (the
      person's name, chosen 2026-11-26; the id stays phasecraft:line_shifter
      so placed blocks survive; the code and developer docs keep "line
      shifter") (the person's idea, 2026-11-25: "'pushing' phase
      blocks in a row or column would be an intuitive fix"; "I like this,
      let's implement and test"): a hidden block facing a way, as a piston,
      that takes the line in front of it (a piston's line: up to the first
      gap, at most 12, nothing if 13 or an immovable block) one block at a
      time from the far end, and puts it back from the near end. Done
      (`LineShifterGameTests`). The agent's defaults, open to change: a new
      block (the plain shifter unchanged), recipe Phase Shifter + piston,
      speed 1 to 4 redstone ticks a block set by clicking. The castle tower
      (station 28) runs on line shifters: 226 hidden blocks instead of 3,483.
      A comparator reads how many blocks a line shifter holds (done; the
      agent's proposal, the person: "Let's do that").
   a4. The area shifter (the person's idea, 2026-11-25: two corners make a
      box that can be turned off and on; "It does feel like cheating
      though. We could keep it as an option"; "Let's do that"): two area
      shifters at opposite corners (hidden), each pairs with its nearest
      other within 16; the box moves only while both are powered, layer by
      layer from the top, back from the bottom, speed by clicking; piston
      rules per block, contents kept; a game rule, off by default; a size
      cap of 16; recipe Line Shifter + diamond block makes 2 (the agent's
      defaults, open to change). Done (`AreaShifterGameTests`); shown off by
      the vanishing cottage (station 29).
   b. The phase block (decided by the person, replacing dedicated
      plate/button blocks): a hidden block standing on a phase block, or
      hanging on its side, is phased: it reacts to the visible world at its
      own spot (clicks for buttons/levers, visible entities for plates;
      later maybe observers, comparators, tripwire). The phase block cannot
      be moved by pistons. Phase pistons and phase ports stay as they are.
      First step: buttons, levers, pressure plates; blocks not supported yet
      just don't react (list the supported ones in the docs). Built so the
      person can try it and reassess. First step done (station 13). Second step (the person, 2026-11-23:
      "lights would be cool"): phased light sources light the visible
      world at their spot. Done (station 14). They are for everyone, in
      any game mode, with their own game rule, phasecraft:phased_lights,
      on by default (the person, 2026-11-27: "make a separate light
      enable, I wanted builds to be able to have atmospheric light"); a
      client is sent only the lights of the chunks its player tracks (a
      security review: the whole dimension's list gave bases away). Third step (the person,
      2026-11-23: "we can just move right into the other ones"):
      observers, comparators, tripwire; the person approved the agent's
      proposal (2026-11-23: "this seems reasonable the way that you have
      it laid out"): a phased observer watches the visible block in front
      of it; a phased comparator reads the visible block behind it (a
      chest's fill level...); hidden tripwire hooks on phase blocks with
      hidden string are tripped by visible players and mobs at the
      string's spots. Done (station 19); one phased hook is enough for a
      line (the agent's default, open to change). Fourth step (the person,
      2026-11-24: "Start those whenever you feel like it", after the agent
      suggested them): phased hoppers pull from the visible world above
      them; phased droppers and dispensers put out into the visible world
      in front of them, silently (the agent's default, open to change).
      Done (station 20). The person's list (2026-11-24) is done: the
      shifter takes chests with their contents, and the iris door.
   c. Survival play (done): one game rule, on by default (off on dedicated
      servers, see below), that lets Survival
      players use the overlay and editing mode (with Survival rules:
      placing uses the item, breaking takes mining time and the right tool
      and drops the block, normal reach), plus crafting recipes for phase
      pistons, ports and the phase block. Recipes, the person's starting
      point for balancing (2026-11-23; an ender pearl was "pretty
      expensive"): Phase Block x4 = amethyst shard in the middle, redstone
      dust on the 4 sides, cobblestone in the corners; Phase Port = Phase
      Block + redstone torch; Phase Piston = piston + Phase Block; Sticky
      Phase Piston = Phase Piston + slime ball (or sticky piston + Phase
      Block). Cheaper fallback if needed: a copper ingot instead of the
      shard. Entering the hidden layer stays Creative/Spectator only.
      Servers can turn the rule off (on a public server Survival editing
      can build inside other people's walls, past claim mods that do not
      answer the Common Protection API).
   d. Overlay performance for large hidden builds (done: per-chunk geometry
      cache, about 10x less CPU per frame).
   e. Flying machine (done, the person's option A; see the flying machine
      in docs/ARCHITECTURE.md): a vanilla engine (the wiki's "engine A") in
      the hidden layer carries a phase piston that pushes the visible cargo
      each cycle (station 15). Option B (one structure spanning both layers,
      new rules) was not chosen.
   Every contraption follows docs/BUILD_RULES.md (the person, 2026-11-24:
   "Let's build out rules for what we do", and adopt redstone projects'
   rules where they can); add a rule when a build teaches one.
   Stacking phase pistons? Read the quasi-connectivity lesson under the
   staircase in docs/ARCHITECTURE.md first. New contraptions: write a
   layout file (src/gametest/resources/contraptions/README.md), run
   tools/redstone_check.py on it until it is clean, then build it from the
   GameTest and examples.py with the shared loaders.
2. Stretch (done as 1e): a flying machine with a hidden engine moving
   visible blocks.
3. Fish buckets stay refused in editing mode (the person, 2026-11-23:
   "let's not do anything"). Buckets work, and Ctrl + middle click copies a
   hidden chest's contents.
4. Hidden beds and respawn anchors are not used in editing mode (the
   person, 2026-11-27: "no beds in editing mode"; a security review found a
   hidden bed set the respawn point in the visible world at its spot, and
   anchors blew up hidden builds). Placing them stays allowed.

Decided by the person: phase pistons show no visible arm; missing blocks
are handled as vanilla does; Survival players may see and edit while the
game rule phasecraft:survival_access is on: the default in single player
and on LAN, but off on a dedicated server until an admin turns it on (the
person, 2026-11-27, after a security review: on a public server it shows
every Survival player the hidden builds near them and opens other people's
hidden chests). A game rule has one default, so a dedicated server sets it
to false the first time it starts a world, once (Access.applyServerDefault;
worlds from earlier versions too), and leaves the admin's setting alone
after that. GameTest servers are left out (Fabric makes them say they are
dedicated), so tests run with the rule on unless they set it; the client
GameTests' dedicated server and the dev server are real ones, and
tools/examples.py turns the rule on on the dev server. Decided by the agent,
open to change: Spectators see the overlay but cannot edit, Adventure
players neither; ghosts show through
visible blocks (fainter); in editing mode (J) a click on a visible block
places the hidden block at that same spot.
Building from inside the hidden layer (the person, 2026-11-27: "Should we
have a command that puts the player into the underworld ... and either
ignores the main world or makes the main world transparent that way they can
work on their contraption without disturbing the main world"; for "players
in creative mode or mods"; "I don't think that we had set up making the main
world transparent"): done. The key K (LayerSwitch; Creative and Spectator
only, no cheats needed; the command stays) goes in and out at the same spot,
and inside, the visible world's surface near the player is drawn faint
(InsideView on the server, a second GhostLayer on the client; "The inside
view" in docs/ARCHITECTURE.md, with what it costs). The agent's defaults,
open to change: the key, the look (22% opaque, both sides of each surface,
48 blocks), coming out at the same spot even inside a block. Inside, the
player is a real entity there: hidden plates feel them, visible ones do not.
Their clicks in there are checked against the visible world's protection at
the spot, as editing mode's are (InsideProtection; the key lets non-operators
in, the command never did).
Each PhaseCraft item's tooltip says what it does (TooltipGameTests); in
editing mode the HUD names the hidden block aimed at and its state (and,
for shifters, how many blocks they hold: OverlayPayloads.Holding).

## Showcase builds (the person, 2026-11-24: "builds that impress people")
Showcasing blocks pulled into and out of the main world. The person's list,
in the agent's proposed order (the person: "I'm game"): a waterfall that
parts in the middle (done, station 23: five ribbons between rock ribs, as
water beside running water never drains), a door into a mountain that opens in a pattern
(done, station 24: a clock-hand sweep, the agent's choice of pattern, open to change),
a staircase (or bridge) that appears in front of the player (the bridge done, station 25:
blocks there within 3 steps of someone, tools/gen_path.py; the staircase done, station 26,
tools/gen_stairs.py), then a castle
tower built and unbuilt block by block (done, station 28: it stands at rest, the agent's default,
open to change; the lever takes
it down from the top in a helix, on line shifters, tools/gen_tower.py; its arrow slits became
barred windows, the agent's choice, open to change). Also on the list: a hole in the
ocean (done, station 30: area shifters put glass walls and a filler back into
the sea, hidden line shifters under the sand take the filler: dry and
repeatable, no sponges; tools/gen_ocean.py), a
sorting machine (done, station 27: a hidden hopper sorter, tools/gen_sorter.py;
its four kinds the agent's choice, open to change), mob farms (a skeleton
farm, the person's choice, 2026-11-26: done, station 36: a button takes the
spawner room's floor away, the fall kills, hidden hoppers fill a chest; the
fall kill over XP the person's choice; tools/gen_skeleton.py). A shifter
passes no power on, as a piston does not (the person, 2026-11-25: "Let's make
the change, I think it opens up more building opportunities"), so every block
of a one-block wall or tower can be fed on its own; a shifter still needs a free
neighbour (the person: "Free neighbor is what a piston needs too"), so the
ocean hole is at most two blocks across, or hollowed another way (it was:
line shifters powered by wire on top of them). Liked (the person: "I like your suggestions
too"): a shifter display (pixel wall; done, station 33: a creeper's face drawn
on a lime wall, tools/gen_pixels.py), a track built ahead of a minecart (done, station 35: a
chasm crossed on rails there only around the cart; tools/gen_track.py),
a shifting maze, a light-up dance floor (done, station 31: hidden plates on
phase blocks, ports under the lamps), a treasure reveal (done, station 34: an
offering in a barrel brings it out; tools/gen_treasure.py), a combination
lock (done, station 32: on, off, on, on opens a door that looks like the wall;
tools/gen_lock.py). No movable shifters (the person, 2026-11-24: "Nah"), so no tunneling
flying machine. Once something is cool: a trailer (the person; look up how
others make them first). First cut done (2026-11-26; most mod trailers use the
Replay Mod, the person chose a camera scripted by the agent instead):
tools/trailer.py scripts a camera through the stations, TrailerClientGameTest
renders it frame by frame with the world frozen and stepped a tick a frame,
and the tool titles, captions and encodes it to build/trailer/*.mp4. No sound.

## Building tools (tools/mcb)
The person (2026-11-24): "build whatever harness and tools you want for
building in Minecraft. Redstone, buildings, scenery etc." The agent's plan,
one PR each: (1) done: tools/mcb (place a scene in one tick, export a region
of both layers, render pictures; src/devtools is a dev-server-only mod with
the /mcb commands, never shipped); (2) done: a redstone simulator
(mcb.redstone), checked against the real game tick by tick (mcb.verify);
check a new contraption in it before its GameTest; (3) shapes for
buildings and scenery (done: mcb.shapes, the demo village) and a wire
router (done: mcb.router). For a new contraption: lay it out with
mcb.shapes and mcb.router, check it in mcb.redstone, place and export it
with mcb.server, then write its GameTest.
No MCP server (the person, 2026-11-24: "Let's drop MCP"): the toolkit stays
a command line and a Python library. See tools/mcb/README.md.

## Local dev server (RCON)
- `./gradlew runServer` runs a local server in run/ (git-ignored) with RCON
  bound to 127.0.0.1; `python3 tools/rcon.py "<command>"` sends commands.
- Keep `pause-when-empty-seconds=0` in run/server.properties: a paused server
  does not tick, so cross-layer updates never arrive (`/phasecraft link`
  shows `pending` stuck above 0).
- Use it for real-world checks (restart, joining with a client). It does not
  replace GameTests: anything worth checking twice belongs in a test.
- The dev server's world (run/, level `phasecraft-examples`) is throwaway dev
  data and only the person plays on it: tearing it down, rebuilding it
  (`tools/examples.py`) or restarting the server needs no permission (the
  person said so, 2026-11-22). Still never touch their own Minecraft saves,
  or their mods folder beyond installing PhaseCraft after a merge.
- `python3 tools/examples.py` rebuilds and verifies the examples area. When a
  milestone adds something a person can see or try, add a station for it.
