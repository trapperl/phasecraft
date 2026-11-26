# Contraption layouts

Every layout follows docs/BUILD_RULES.md.

One file per contraption. The same file is built by the GameTests
(`ContraptionLayout`), by `tools/examples.py` on the dev server, and checked
by `tools/redstone_check.py`, so the test, the examples station and the
check never drift apart.

Coordinates are relative to the test area's corner; lines are applied in
order (later lines overwrite earlier ones), `#` starts a comment.

```
size X Y Z                                   the test area (needs a matching empty_XxYxZ structure)
lever x y z                                  the visible lever that operates it
click x y z <face>                           a visible block a player clicks to operate it (a phased
                                             button or lever hidden there), and the face clicked
step x y z                                   where a visible mob or player standing operates it (a phased
                                             pressure plate hidden there)
door <block>                                 the block the door is made of (for the checks)
fill visible|hidden x1 y1 z1 x2 y2 z2 <block>
visible x y z <block>
hidden x y z <block> @signal                 a hidden redstone part or piston, and the signal it carries
hidden x y z <block> @out<in                 a repeater or torch that turns signal "in" into "out"
allow <signal> <other>...                    a part of <signal> may also receive <other> (nested signals)
items visible|hidden x y z <item>*<count>... a container's contents, slot by slot (items as /give takes them),
                                             put in once the contraption has settled
```

Blocks use `/setblock` syntax. A wire with nothing to connect to is a dot (it powers only the
block under it) unless its sides are set: `redstone_wire[north=side,south=side,east=side,west=side]`
is the cross a player places. Place hidden parts in signal order and in
their settled state (repeaters `powered=true` where powered, pistons
`extended=true` where they should be), pistons last: the examples tour
builds everything in one tick, with no time to settle.

A layout too big to write by hand can come from a generator: `iris_door.txt` and `waterfall.txt` are written
by `tools/gen_iris.py` and `tools/gen_waterfall.py` (with their GameTest areas, `<name>.snbt`), both through
`tools/gen_door.py` (doors of phase shifters switching in rings); `mountain_door.txt` by `tools/gen_mountain.py`
(hand-built wiring, signal names from a simulated run); `appearing_bridge.txt` by `tools/gen_path.py`; `appearing_staircase.txt` by `tools/gen_stairs.py`; `sorting_machine.txt` by `tools/gen_sorter.py`; `castle_tower.txt` by `tools/gen_tower.py` (its lines laid by `mcb.router`)); `ocean_hole.txt` by `tools/gen_ocean.py` (every line laid by `mcb.router.route_all`); `combination_lock.txt` by `tools/gen_lock.py` (likewise); `pixel_wall.txt` by `tools/gen_pixels.py` (through `tools/gen_door.py`); `treasure_reveal.txt` by `tools/gen_treasure.py`. Change
the generator and run it again.

`python3 tools/redstone_check.py <file>` reports every part or piston that
receives a signal other than its own, following vanilla's power rules
(including quasi-connectivity) roughly. Run it after every change; the
GameTest decides.
