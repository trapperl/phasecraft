# Getting started with PhaseCraft

## Requirements
- Java Development Kit 21
- Git and Gradle 9.0.0
- Visual Studio Code with Java and Gradle extensions, or IntelliJ IDEA

The repository's first iteration uses the official Fabric example's pinned
Minecraft 1.21.1 dependency versions. A Gradle wrapper has **not** been
committed yet; GitHub Actions installs Gradle 9.0.0 independently. Once Gradle
is installed locally, run `gradle wrapper --gradle-version 9.0.0` and commit
the generated `gradlew`, `gradlew.bat`, and wrapper directory in a subsequent
change. Do not handwrite or omit the wrapper JAR.

## Build and run

```sh
gradle build
gradle runClient
```

In a creative test world, grant operator permission / enable cheats and run:

```text
/setblock 10 64 10 minecraft:stone
/phasecraft set 10 64 10 minecraft:redstone_wire
/phasecraft get 10 64 10
/phasecraft clear 10 64 10
/phasecraft get 10 64 10
```

The first `get` should report visible stone and hidden redstone wire.
The second should report visible stone and hidden air. The redstone wire is
**data only** at this stage: it will not render, tick, or power anything.

To manually inspect persistence, set a hidden block, save and quit, reopen the
same world and run `/phasecraft get` again. This is currently a manual test,
not a verified automated save/reload GameTest.

## Current limitations

Only a single hidden layer exists. Hidden entries store default registry block
IDs (no facing, powered, or other BlockState properties). No world overlay,
editing GUI, cross-layer redstone, piston actuation, or simulated block ticks
exist yet. The commands require permission level 2.
