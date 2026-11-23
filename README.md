# PhaseCraft

A Minecraft Java Edition mod: a second block layer that shares the exact XYZ coordinates of the overworld, so
redstone and pistons hidden in that layer can act on the blocks of the visible world, by vanilla rules.

**Target: Minecraft Java Edition 26.3** with Fabric and Java 25. Working on the development branches: the
hidden layer (a real dimension, run by vanilla), phase ports that carry power between layers, phase pistons
that move visible blocks from the hidden layer, a hidden 2x2 piston door, a hidden staircase (flat floor
that opens into stairs down to a cellar), the transparent overlay (press **H** in Creative to see hidden blocks drawn as
see-through ghosts over the world; `[` and `]` change the opacity) and editing mode (press **J**, and your
clicks place, use and break blocks in the hidden layer instead of the visible world). See [Getting started](docs/GETTING_STARTED.md) and [Architecture](docs/ARCHITECTURE.md).

| Overlay off | Overlay on (H) |
| --- | --- |
| ![The hidden door from above, overlay off: a plain wall](docs/images/overlay-door-off.png) | ![The same view, overlay on: the hidden pistons, wiring and repeaters show through the wall](docs/images/overlay-door.png) |

The hidden staircase (examples station 11): one lever turns flat floor into stairs down to a cellar.

| Closed | Closed, overlay on (H) | Open |
| --- | --- | --- |
| ![Flat stone floor](docs/images/stairs-closed.png) | ![The same floor with the hidden pistons and wiring underneath showing through](docs/images/stairs-closed-overlay.png) | ![The floor has opened into stairs down to a cellar with a chest and a lantern](docs/images/stairs-open.png) |

## Approach

- Minecraft Java Edition with Fabric (NeoForge is the other reasonable choice), Gradle and Git.
- Coding agents do most of the implementation; a person picks the features, approves milestones and playtests.
- Behaviour is proved by automated tests in a running game (Minecraft's GameTest framework), not by claims.
  No step counts as done without passing tests.

## Roadmap

1. **A working mod.** Fabric, Gradle, automated builds and GameTests. A mod that loads without errors.
2. **Overlapping block storage.** Stone in the overworld and redstone at the same XYZ in the hidden layer,
   surviving a save and reload.
3. **Layer switching.** An editing mode that switches between the real world and the hidden circuitry.
4. **Redstone across layers.** A lever in the overworld lights a redstone lamp in the hidden layer.
5. **The phase piston.** A powered piston in the hidden layer moves a block in the overworld, respecting
   vanilla piston rules.
6. **The transparent overlay.** Hidden circuitry drawn over the real world, with adjustable opacity.

The phase piston is the biggest technical uncertainty and the first major proof of concept: vanilla pistons
assume the blocks they move are in their own world. Block updates, timing, chunk loading and multiplayer
synchronisation all need investigating. Polished graphics come after it works.

## Tests to write

- A hidden-layer block and an overworld block can coexist.
- A hidden-layer piston can push an overworld block.
- A piston cannot push 13 ordinary blocks.
- Obsidian remains immovable.
- Breaking an overworld block does not destroy hidden machinery.
- All layers restore correctly after saving and restarting.

## The first instruction for the lead agent

> You are the lead developer of PhaseCraft, a Minecraft Java Edition mod.
>
> PhaseCraft enables a secondary block layer sharing the exact XYZ coordinates of the overworld. Eventually,
> redstone and pistons in this layer must manipulate overworld blocks according to vanilla Minecraft rules.
>
> Start by selecting a supported Minecraft and Fabric version, scaffolding the project, configuring automated
> builds and GameTests, and documenting the architecture.
>
> Then create a minimal proof of concept where two independently stored blocks occupy the same XYZ coordinates
> in separate layers and persist after a save/reload.
>
> Do not implement rendering overlays or phase pistons yet. Document technical risks. Run tests after each
> implementation step. Do not claim success without passing tests.
