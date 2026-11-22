# PhaseCraft architecture — first prototype

## What this change implements

There is **one real Minecraft level** plus a separate, persisted map of
hidden-layer cells for each dimension. A cell key is the packed 64-bit position
from `BlockPos.asLong()`; its value is a Minecraft block registry ID.
`PhaseLayerData` uses Minecraft 26.3's Codec-backed `SavedDataType`, storing
one independently scoped collection per dimension. Its identifier is
`phasecraft:layer_1`, saved as `data/phasecraft/layer_1.dat`. It declares no
`DataFixTypes`; Fabric API skips data fixing when that is null. Editing the map does **not** call
`ServerLevel.setBlock` and therefore does not alter visible terrain.

The map currently stores **block IDs only**. A saved piston is not yet a
Minecraft block entity, a real BlockState, or a ticking redstone component.
This is a storage proof of concept, not a second running Minecraft dimension.

## Why use a virtual map instead of a real second dimension?

It makes same-coordinate coexistence straightforward, independent of the
world's normal chunks. A future simulation layer can decide how to schedule
block ticks, inspect neighbor states, and bridge signals into the visible
world without immediately creating another player-accessible dimension.

## Future work and important decisions

- Preserve full `BlockState` properties, ticking schedules, and any block
  entities; define chunk-scoped or bounded persistence to scale up.
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

The pure Java `LayerCellStore` has JUnit tests for map invariants. The GitHub
Actions workflow will check compilation and unit tests when the branch is
pushed. No in-game GameTests or save/reload verification have run yet.
