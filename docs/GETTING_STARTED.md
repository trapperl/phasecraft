# Getting started with PhaseCraft

## Requirements
- **Minecraft Java Edition 26.3** (stable, not the 26.4 snapshot)
- Fabric Loader for 26.3 and a **Fabric API JAR for 26.3**
- Java Development Kit 25 (for local builds)
- Git (Gradle 9.8.2 is fetched by the committed wrapper, `./gradlew`)
- Visual Studio Code with Java and Gradle extensions, or IntelliJ IDEA

The project targets 26.3; the older 1.21.1 PhaseCraft build **will not load**
under Minecraft 26.3. Download a fresh artifact from the latest successful
GitHub Actions run and put the mod JAR (not the artifact ZIP) into the
instance's `mods` folder together with the Fabric API for 26.3.

The Gradle wrapper is committed and pins Gradle 9.8.2; use `./gradlew`
(or `gradlew.bat` on Windows) rather than a system Gradle. Only `JAVA_HOME`
pointing at a JDK 25 is needed.

## Build and run

```sh
./gradlew build
./gradlew runClient
```

The hidden layer is a real dimension at the same coordinates
(`phasecraft:hidden_overworld` for the Overworld). In a Creative world with
cheats enabled:

```text
/setblock 10 64 10 minecraft:stone
/phasecraft set 10 63 10 minecraft:stone
/phasecraft set 10 64 10 minecraft:redstone_wire
/phasecraft get 10 64 10
/phasecraft layer hidden
/phasecraft layer visible
```

The hidden layer follows vanilla rules, so the hidden wire needs a hidden
block under it (the second line); without one it breaks, as it would
anywhere. The block argument uses the same syntax as `/setblock`, with tab
completion: `redstone_wire`, `minecraft:redstone_wire` and
`minecraft:repeater[facing=east,delay=3]` all work.

`get` reports visible stone and hidden
`minecraft:redstone_wire[east=none,north=none,power=0,south=none,west=none]`.
`/phasecraft layer hidden` takes you to the same spot in the hidden layer
(Creative or Spectator only: it has no ground), where you can see and build
hidden circuits with normal blocks and commands; `layer visible` brings you
back. Hidden redstone runs by vanilla rules.

To connect the layers, use a phase port: it outputs, in its own layer, the
power reaching its spot in the other layer.

```text
/setblock 12 64 10 minecraft:lever[face=floor,powered=false]
/phasecraft set 12 63 10 minecraft:stone
/phasecraft set 12 64 10 phasecraft:phase_port
/phasecraft set 13 64 10 minecraft:redstone_lamp
```

Flip the (visible) lever and the hidden lamp next to the hidden port lights.

Save and exit your test world, reopen it, and run `/phasecraft get` again to
manually inspect persistence. The same check runs automatically as
`./gradlew restartTest` (see below).

## Current limitations

Power crosses only through **phase ports** (Redstone tab, or
`/give @s phasecraft:phase_port`); see docs/ARCHITECTURE.md. Moving visible
blocks from the hidden layer (phase pistons) and the overlay are not done
yet. Hidden machinery runs wherever the visible world around it runs. Commands require operator permission level 2. Worlds from earlier builds
are upgraded automatically at server start.

Use a **new test world**, not your personal saves: the storage format has
no migration path yet.

## Tests and the build-to-play loop

| Command | What it does |
| --- | --- |
| `./gradlew build` | Compiles, then runs **every** test below (CI runs this). |
| `./gradlew runGameTest` | In-game tests: every class named `*GameTests` in `src/gametest`. |
| `./gradlew restartTest` | Full restart: one server writes hidden cells and shuts down, a second server process loads the same world and checks them. |
| `./gradlew installToMinecraft` | `build`, then (only if everything passed) replaces PhaseCraft in your Minecraft mods folder. Restart Minecraft afterwards. |

`installToMinecraft` reads your mods folder from `local.properties`
(git-ignored), for example:

```properties
minecraftModsDir=/mnt/c/Users/<you>/AppData/Roaming/.minecraft/mods
```

Reports are written to `build/reports/gametest/TEST-*.xml`. Test servers run
headless; Fabric API's GameTest mode does not read `eula.txt`, so nothing needs
accepting. A server you run yourself (for example for RCON) still does.

## Local dev server with RCON

For checks in a real (non-test) world, run a local dedicated server from this
project and drive it over RCON:

```sh
./gradlew runServer                      # first run creates run/; accept the EULA in run/eula.txt
python3 tools/rcon.py "phasecraft get 10 64 10"
python3 tools/rcon.py "stop"
```

`run/` is git-ignored. Set these in `run/server.properties` before the first
real start: `enable-rcon=true`, a random `rcon.password`, and
`server-ip=127.0.0.1` so neither the game port nor RCON is reachable from
other machines. `tools/rcon.py` reads the host, port and password from that
file. Join from the Fabric 26.3 profile at `localhost`; to use `/phasecraft`
in game, make yourself an operator with
`python3 tools/rcon.py "op <your name>"`.

### Examples world

`python3 tools/examples.py` builds a row of example stations near spawn on
the running server (best on a flat world: `level-type=minecraft\:flat`),
then reads every hidden cell back and exits 1 if one is wrong. Each station
has a sign; hidden blocks are invisible for now, so inspect them with
`/phasecraft get <x> -60 0` (or `execute in minecraft:the_nether run ...`
for station 5). Rerunning it rebuilds the area from scratch.
