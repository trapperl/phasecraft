# mcb: building in Minecraft, both layers

Tools for building, seeing and checking builds on the dev server, made while building the
contraption stations (the person, 2026-11-24: "build whatever harness and tools you want for
building in Minecraft").

Run from `tools/`: `python3 -m mcb ...` (or import `mcb` in a script). Needs the dev server
(`./gradlew runServer`, which loads the `phasecraft-devtools` mod) for `export` and `place`.

| | |
|---|---|
| `python3 -m mcb export X1 Y1 Z1 X2 Y2 Z2 NAME` | both layers of a region into `run/<world>/generated/mcb/structure/NAME/{visible,hidden}.nbt`, in one command (`/mcb export`) |
| `python3 -m mcb place SOURCE X Y Z [--strict]` | a layout file or exported NAME placed with its corner at X Y Z, both layers in the same tick (`/mcb place`); `--strict` places every block exactly as written, without updates |
| `python3 -m mcb render SOURCE OUT.png [--view iso\|top\|north\|...] [--corner 0-3] [--visible]` | a picture, coloured from the game's textures; hidden blocks drawn see-through and purple, as the overlay shows them; `--visible` for what a player sees without it |

Modules: `nbt` (NBT files), `structure` (vanilla structure files), `scene` (a build as blocks
per layer; from and to layout files and structures; `diff` to compare a build with what the
server has), `server` (RCON and the `/mcb` commands), `render` (pictures).

Why: building a layout over RCON takes one command per block, spread over many ticks, so
half-built redstone runs while it is placed (the iris door flickered for half a second after
every build); `/mcb place` puts both layers in at once. Looking at a build took one
`phasecraft get` per block, or a client run for screenshots; `export` plus `render` takes a
second.

Tests: `python3 -B -m unittest discover -s tools/mcb/tests -t tools` (run by `./gradlew check`).
`-B`: a mutation restored within the same second, at the same size, otherwise runs from Python's
stale bytecode cache.

Next: a redstone simulator checked against the real game (export a region tick by tick with
`/tick freeze` and `/tick step`), a wire router, and shapes for buildings and scenery. An MCP
server only if wanted for building from a client without a shell.
