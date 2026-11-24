# mcb: building in Minecraft, both layers

Tools for building, seeing and checking builds on the dev server, made while building the
contraption stations (the person, 2026-11-24: "build whatever harness and tools you want for
building in Minecraft").

Pictures need Pillow (`python3-pil` on Debian and Ubuntu, CI installs it).
Run from `tools/`: `python3 -m mcb ...` (or import `mcb` in a script). Needs the dev server
(`./gradlew runServer`, which loads the `phasecraft-devtools` mod) for `export` and `place`.

| | |
|---|---|
| `python3 -m mcb export X1 Y1 Z1 X2 Y2 Z2 NAME` | both layers of a region into `run/<world>/generated/mcb/structure/NAME/{visible,hidden}.nbt`, in one command (`/mcb export`) |
| `python3 -m mcb place SOURCE X Y Z [--strict]` | a layout file or exported NAME placed with its corner at X Y Z, both layers in the same tick (`/mcb place`); `--strict` places every block exactly as written, without updates |
| `python3 -m mcb render SOURCE OUT.png [--view iso\|top\|north\|...] [--corner 0-3] [--visible]` | a picture, coloured from the game's textures; hidden blocks drawn see-through and purple, as the overlay shows them; `--visible` for what a player sees without it |

## Redstone simulator (`mcb.redstone`)

Runs one layer of a scene tick by tick by vanilla's rules (read from the 26.3 sources): wire
strength and connections, repeaters (delay, locking, vanilla's tick priorities), torches,
levers, redstone blocks, phase ports (inputs) and phase shifters (outputs, with the tick each
was powered). Parts with nothing to stand on break off, as when a layout is placed. Not yet:
comparators, observers, pistons, buttons, plates, copper bulbs, torch burnout.

```python
from mcb.scene import Scene
from mcb.redstone import Sim
sim = Sim(Scene.from_layout("../src/gametest/resources/contraptions/iris_door.txt"))
sim.run(40); sim.set_input(port, True); sim.run(100); sim.events   # [(tick, pos, "shifter", True), ...]
```

It reproduces the iris door's GameTest to the tick (rings at 46, 54, 62 and 70 game ticks after
the lever), and the two bugs of its first build: the faded bus (strength 0, 0, 1, 2, 3 where the
server showed the same) and the rings that stood open. `tools/gen_iris.py` now checks every
layout it writes this way. 1,700 parts, 240 ticks: under 3 s.

`python3 -m mcb.verify --random N --seed S` checks it against the real game: random small
circuits are placed on the dev server, left to settle, then frozen (`/tick freeze`) and stepped
one tick at a time (`/tick step 1`) with levers switched on both sides, comparing every
repeater, torch, wire strength and shifter each tick (the simulator starts from the server's
settled state: placing runs half-built redstone in an order it does not copy). Circuits that
never settle (clocks) are skipped. So far 15 of 15 settling circuits agree on every tick for 90
ticks.

## Modules

`nbt` (NBT files), `structure` (vanilla structure files), `scene` (a build as blocks
per layer; from and to layout files and structures; `diff` to compare a build with what the
server has), `server` (RCON and the `/mcb` commands), `render` (pictures), `redstone` (the simulator),
`verify` (the simulator against the real game).

Why: building a layout over RCON takes one command per block, spread over many ticks, so
half-built redstone runs while it is placed (the iris door flickered for half a second after
every build); `/mcb place` puts both layers in at once. Looking at a build took one
`phasecraft get` per block, or a client run for screenshots; `export` plus `render` takes a
second.

Tests: `python3 -B -m unittest discover -s tools/mcb/tests -t tools` (run by `./gradlew check`).
`-B`: a mutation restored within the same second, at the same size, otherwise runs from Python's
stale bytecode cache.

Next: a wire router, and shapes for buildings and scenery; pistons and comparators in the
simulator. No MCP
server (the person, 2026-11-24: "Let's drop MCP").
