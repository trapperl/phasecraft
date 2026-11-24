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
    Xvfb virtual display. Check what is
    drawn by comparing screenshot pixels, and look at the screenshots
    (build/run/clientGameTest*/screenshots) before calling a visual change done.
- Client-only code lives in src/client (split source sets); src/main must not
  reference client classes.
- Test games never talk to Mojang or Microsoft (2026-11-22: dozens of test
  runs got the person's internet address blocked by Mojang's front door,
  which also broke their own logins and Realms). build.gradle's
  `offlineTestArgs` point service discovery and Realms at local addresses,
  test dedicated servers run with `online-mode=false`, and a test mixin skips
  the blocked-servers download. NoMojangTrafficGameTests guards the settings;
  any new test run configuration must add `offlineTestArgs`.
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
      done), silently; it waits for mobs and unbreakable blocks (the
      agent's default). Recipe (the person, 2026-11-23: ender pearls, "since
      this would be pretty powerful", and "make two"): Phase Block + ender
      pearl + piston makes 2.
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
      world at their spot. Done (station 14). Third step (the person,
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
   c. Survival play (done): one game rule, on by default, that lets Survival
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
      can build inside other people's walls, past claim mods).
   d. Overlay performance for large hidden builds (done: per-chunk geometry
      cache, about 10x less CPU per frame).
   e. Flying machine (done, the person's option A; see the flying machine
      in docs/ARCHITECTURE.md): a vanilla engine (the wiki's "engine A") in
      the hidden layer carries a phase piston that pushes the visible cargo
      each cycle (station 15). Option B (one structure spanning both layers,
      new rules) was not chosen.
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

Decided by the person: phase pistons show no visible arm; missing blocks
are handled as vanilla does; Survival players may see and edit while the
game rule phasecraft:survival_access is on (default). Decided by the agent,
open to change: Spectators see the overlay but cannot edit, Adventure
players neither; ghosts show through
visible blocks (fainter); in editing mode (J) a click on a visible block
places the hidden block at that same spot.

## Building tools (tools/mcb)
The person (2026-11-24): "build whatever harness and tools you want for
building in Minecraft. Redstone, buildings, scenery etc." The agent's plan,
one PR each: (1) done: tools/mcb (place a scene in one tick, export a region
of both layers, render pictures; src/devtools is a dev-server-only mod with
the /mcb commands, never shipped); (2) a redstone simulator checked against
the real game; (3) a wire router and shapes for buildings and scenery.
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
