# PhaseCraft architecture — first prototype

## What this change implements

There is **one real Minecraft level** plus a separate, persisted map of
hidden-layer cells for each dimension. A cell key is the packed 64-bit position
from `BlockPos.asLong()`; its value is a full Minecraft `BlockState`
(block plus properties such as `facing` or `delay`).
`PhaseLayerData` uses Minecraft 26.3's Codec-backed `SavedDataType`, storing
one independently scoped collection per dimension. Its identifier is
`phasecraft:layer_1`, saved in each dimension's own data folder, e.g.
`<world>/dimensions/minecraft/overworld/data/phasecraft/layer_1.dat`. It
declares no `DataFixTypes`; Fabric API skips data fixing when that is null.
Editing the map does **not** call
`ServerLevel.setBlock` and therefore does not alter visible terrain.

Cells are saved as `{pos, state}`, with `state` in Minecraft's own
`BlockState.CODEC` format (a plain block name for default states, otherwise
`{Name, Properties}`). Files from the first prototype, which saved
`{pos, block}` with a block ID only, are still read: each such cell becomes
that block's default state, and is rewritten in the new format on the next
save.

The pre-26.3 (Minecraft 1.21.1) prototype used a different file
(`<world>/data/phasecraft_layer_1.dat`, NBT list `Cells` of `{Pos, Block}`).
It is **not migrated**: no world in use was created with that build, and a
1.21.1 world opened in 26.3 would need Minecraft's own world upgrade first.
Such a file is ignored, not read or deleted.

A hidden piston is still only data: no block entity, no ticking, no redstone.
This is a storage proof of concept, not a second running Minecraft dimension.

**Known risk:** if a stored block stops existing (a mod is removed, or a
Minecraft update drops a block), that cell cannot be decoded. Minecraft logs
the parse error, and the cell is lost at the next save. This is not handled
or tested yet.

## Why use a virtual map instead of a real second dimension?

It makes same-coordinate coexistence straightforward, independent of the
world's normal chunks. A future simulation layer can decide how to schedule
block ticks, inspect neighbor states, and bridge signals into the visible
world without immediately creating another player-accessible dimension.

## Future work and important decisions

- Preserve ticking schedules and block entities (full `BlockState`
  properties are done); define chunk-scoped or bounded persistence to scale up.
- Decide what happens to cells whose block no longer exists (see above).
- Limit expensive hidden-world work to loaded chunks and protect against
  feedback loops between layer interactions.
- Specify whether phase pistons act *at their own location* or through a
  dedicated actuator port, and exactly what counts as an obstruction.
- Reuse vanilla piston movement rules rather than overriding push limits.
- Authoritative server state must synchronize to observing clients before
  any transparent overlay is drawn.
- Add GameTests for coexistence, persistence after server restart, redstone,
  and pushability, including unloaded chunks and multiplayer.
- The old Minecraft 1.21.1 prototype's NBT storage schema is not compatible
  with the 26.3 Codec-backed storage; use a fresh test world for now.
- Plan a data migration format before making the storage schema public.

## Validation status

All of these run on every `./gradlew build`, locally and in CI:

- **JUnit** (`src/test`): `LayerCellStore` map invariants.
- **GameTests** (`src/gametest`, headless 26.3 server):
  - a hidden block and a visible block coexist at the same XYZ;
  - breaking the visible block leaves the hidden cell intact;
  - Overworld, Nether and End keep independent layers at the same XYZ;
  - hidden cells in unloaded chunks can be set and read without loading them;
  - hidden cells written to disk are read back by a fresh `SavedDataStorage`;
  - full block states (e.g. a repeater facing east with delay 3) are kept,
    including through a fresh reload;
  - layer files in the first prototype's `{pos, block}` format still load;
  - `/phasecraft set|get|clear` give the documented output, accept
    namespaced IDs and `[property=value]` syntax, refuse unknown blocks, and
    require op level 2.
- **Restart test** (`./gradlew restartTest`): two separate server processes
  on one world folder. The first saves empty layers, edits all three
  dimensions (including a far, unloaded position and block states with
  properties) and shuts down normally;
  the second checks every cell.

Each was checked by breaking the code on purpose (dropping loaded cells,
never marking edits dirty, sharing one layer across dimensions, lowering
the permission, saving without properties, ignoring the command's
properties, no longer reading the old format) and seeing the matching test fail.

**Not yet automated:** multiplayer (needs a second client), and anything
about hidden-layer behaviour beyond storage, which does not exist yet.
