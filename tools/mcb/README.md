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

It reproduces the iris door's GameTest to the tick (rings at 44, 52, 60 and 68 game ticks after
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

## Buildings and scenery (`mcb.shapes`)

Shapes drawn into a scene layer: `box`, `walls`, `cylinder`, `sphere` (or a dome), `arch`;
roofs of stairs and slabs (`gable_roof`, `hip_roof`); `house` (timber frame, windows, door,
gable roof) and `tower` (round, arrow slits, a cone roof); scenery: `terrain` (noise hills,
grass over dirt over stone, sand beaches, water below a line; `scene.ground` is the height map),
`pad` (levels a building plot into the hills), `tree` (oak, birch, spruce), `forest`, `path`.
A `Palette` mixes blocks by weight for texture, the same at the same spot every time.
`shapes.problems(scene)` lists what the game would change on placing it (grass under a solid
block turns to dirt; a door on a path block or air breaks off): placing the demo village and
exporting it back found both, and it now matches block for block.

`python3 -m mcb.examples.village OUT.png` draws the demo (64x64: hills, a lake, four houses, a
watchtower, paths, a forest); `--place X Y Z` builds it on the dev server (it stands at
300, -61, 200 there).

## Wire router (`mcb.router`)

`router.route(scene, start, start_from, end, end_into, box, delay=None)` finds the shortest safe
line of redstone (A*) from a start to an end through a crowded layer, and `.apply(scene)` adds
it. Both ends are repeaters, so the line takes its signal only from `start_from` and powers only
`end_into`. Its rules, each one a lesson from the iris door:
- every wire and repeater gets a block to stand on, placed before it;
- nothing new touches another circuit's redstone, even diagonally (wire joins diagonal steps),
  or anything but air, glass or the line itself, and the line never passes next to itself;
- steps up and down stand the upper wire on stone, as vanilla joins a step only over a
  conductor (a glass step breaks the line), with nothing else touching that stone;
- a repeater comes before the signal has faded;
- with `delay=` (redstone ticks) the delays are set, and repeaters added, to arrive then.

Tested in the simulator (around a wall, over a ridge, a long line, an exact delay, across a busy
area next to another circuit, no route when walled in), and the crossing case on the dev server.

## Modules

`nbt` (NBT files), `structure` (vanilla structure files), `scene` (a build as blocks
per layer; from and to layout files and structures; `diff` to compare a build with what the
server has), `server` (RCON and the `/mcb` commands), `render` (pictures), `redstone` (the simulator),
`verify` (the simulator against the real game), `shapes` (buildings and scenery), `router` (wiring), `examples`.

Why: building a layout over RCON takes one command per block, spread over many ticks, so
half-built redstone runs while it is placed (the iris door flickered for half a second after
every build); `/mcb place` puts both layers in at once. Looking at a build took one
`phasecraft get` per block, or a client run for screenshots; `export` plus `render` takes a
second.

Tests: `python3 -B -m unittest discover -s tools/mcb/tests -t tools` (run by `./gradlew check`).
`-B`: a mutation restored within the same second, at the same size, otherwise runs from Python's
stale bytecode cache.

Next: pistons and comparators in the simulator, when a build needs them. No MCP
server (the person, 2026-11-24: "Let's drop MCP").
