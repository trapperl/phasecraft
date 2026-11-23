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

In a creative world with cheats enabled, run the first storage experiment:

```text
/setblock 10 64 10 minecraft:stone
/phasecraft set 10 64 10 redstone_wire
/phasecraft get 10 64 10
/phasecraft clear 10 64 10
/phasecraft get 10 64 10
```

At present the prototype's command uses an unquoted word argument for the
block name, so **use `redstone_wire`, not `minecraft:redstone_wire`**.
The first `get` should report visible stone and hidden redstone wire; the
second should report visible stone and hidden air. Redstone wire is stored as
**data only**; it will not render, tick, or power anything.

Save and exit your test world, reopen it, and run `/phasecraft get` again to
manually inspect persistence. The same check runs automatically as
`./gradlew restartTest` (see below).

## Current limitations

Only one hidden layer exists; it stores block registry IDs, not full block
states. No overlay, block ticking, cross-layer redstone, or piston movement.
Commands require operator permission level 2.

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
