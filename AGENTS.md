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
- Keep milestones small and open PRs; do not force-push or merge to main.
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
    with OpenGL and with Vulkan on an Xvfb virtual display. Check what is
    drawn by comparing screenshot pixels, and look at the screenshots
    (build/run/clientGameTest*/screenshots) before calling a visual change done.
- Client-only code lives in src/client (split source sets); src/main must not
  reference client classes.
- New behaviour needs a GameTest, and a new test should be shown to fail when
  the behaviour is broken (break the code on purpose, run, restore).
- `./gradlew installToMinecraft` installs a passing build into the local
  Minecraft mods folder set in git-ignored `local.properties`.

## Next work
The hidden layer is a real dimension per visible dimension (see
docs/ARCHITECTURE.md); vanilla runs it. Acceptance target for 1: the
hidden piston door.
Power crosses only through phase ports (done). The person wants every
crossing explicit: a dedicated block, never every position.
Phase ports, phase pistons, the hidden 2x2 door and the overlay are done.
The person expects the hidden layer to look like a transparent layer, not a
separate world.
1. Editing mode: a key switches which layer you place/break in, staying in
   the visible world (roadmap step 3), using the overlay to see.
2. Keep players out of the hidden dimension by accident (portals, death, logout).
3. Overlay leftovers: hidden block entities and fluids are not drawn.
4. Stretch: a flying machine with a hidden engine carrying visible blocks.

Decided by the person: phase pistons show no visible arm; missing blocks
are handled as vanilla does. Decided by the agent, open to change: only
Creative/Spectator players are sent the overlay; ghosts show through
visible blocks (fainter).

## Local dev server (RCON)
- `./gradlew runServer` runs a local server in run/ (git-ignored) with RCON
  bound to 127.0.0.1; `python3 tools/rcon.py "<command>"` sends commands.
- Keep `pause-when-empty-seconds=0` in run/server.properties: a paused server
  does not tick, so cross-layer updates never arrive (`/phasecraft link`
  shows `pending` stuck above 0).
- Use it for real-world checks (restart, joining with a client). It does not
  replace GameTests: anything worth checking twice belongs in a test.
- `python3 tools/examples.py` rebuilds and verifies the examples area. When a
  milestone adds something a person can see or try, add a station for it.
