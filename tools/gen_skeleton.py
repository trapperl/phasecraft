#!/usr/bin/env python3
"""The skeleton farm: a dark tower with a skeleton spawner in a room at its top. Press the button in the gallery
outside the room and the room's floor is gone for a second: every skeleton in it falls down the tower to the lit
floor at its foot and dies of the fall, and their bones and arrows turn up in a chest by the tower's door. No water,
no visible redstone, nothing in the shaft: the floor's machinery and the loot's way to the chest are hidden.

Writes src/gametest/resources/contraptions/skeleton_farm.txt and its GameTest area, and checks the floor's wiring in
tools/mcb's redstone simulator. The spawner is placed by tools/examples.py (a layout line carries no spawner data):
in the GameTest the room is fed skeletons by hand.

Generator space: the shaft's inside is x, z = -3..3; the walls are at |x| = 4 or |z| = 4; the landing floor at y = 0,
the room's floor at y = ROOM. Hidden, all of it:
- Under each of the room's 49 floor blocks a phase shifter at its spot, with a grid of wire on top of them (a wire
  powers the block it stands on: the only way into the middle ones, C16).
- The button hangs on the gallery side of the tower's wall; a phase port at that wall block brings its power into the
  hidden layer, down a wire into the grid's corner. Pressed, every shifter holds its floor block: the floor is gone
  while the button is on (a stone button: a second).
- Under each of the landing floor's 49 blocks a phased hopper (on a phase block) pulls what lands on it; they pass
  it east, then south, out under the wall, into a phased dropper facing up under the chest, which a clock (two
  observers watching each other) fires, as in the sorting machine (station 27).
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from mcb.redstone import Sim  # noqa: E402
from mcb.report import hidden_size  # noqa: E402
from mcb.scene import Scene  # noqa: E402

NAME = "skeleton_farm"
ROOM = 28                 # the room's floor: a skeleton falls 28 blocks to the landing floor, 25 damage, 20 health
TOP = ROOM + 5            # the room's ceiling: the inside is 4 high
INSIDE = range(-3, 4)
WALL = "minecraft:deepslate_bricks"
CHEST = (5, 1, 3)         # outside the east wall, on the ground
BUTTON_BLOCK = (4, ROOM + 2, 3)     # the wall block the button hangs on (gallery side), its port behind it
SPAWNER = (0, ROOM + 2, 0)
OFFSET = (7, 3, 7)        # generator (0, 0, 0) in the layout
SIZE = (17, TOP + 5, 15)

WIRE = "minecraft:redstone_wire[east=side,north=side,south=side,west=side]"
STONE = "minecraft:stone"


def hidden_parts():
    """[(pos, state, tag)], supports first."""
    parts = []
    # the floor: shifters, the wire grid on them, the button's port and the wire down into the grid
    for x in INSIDE:
        for z in INSIDE:
            parts.append(((x, ROOM, z), "phasecraft:phase_shifter[powered=false]", "B"))
    for x in INSIDE:
        for z in INSIDE:
            parts.append(((x, ROOM + 1, z), WIRE, "B"))
    bx, by, bz = BUTTON_BLOCK
    parts += [((bx, by - 2, bz), STONE, None),
              ((bx, by - 1, bz), "minecraft:redstone_wire[east=none,north=none,south=none,west=side]", "B"),
              ((bx, by, bz), "phasecraft:phase_port", "B")]
    # the loot: phased hoppers under the landing floor, east along each row, south down the last column, out east
    for x in INSIDE:
        for z in INSIDE:
            parts.append(((x, -1, z), "phasecraft:phase_block", None))
    for x in INSIDE:
        for z in INSIDE:
            facing = "east" if x < 3 or z == 3 else "south"
            parts.append(((x, 0, z), f"minecraft:hopper[enabled=true,facing={facing}]", None))
    cx, cy, cz = CHEST
    parts += [((4, 0, cz), "minecraft:hopper[enabled=true,facing=east]", None),
              ((cx, -1, cz), "phasecraft:phase_block", None),
              ((cx, 0, cz), "minecraft:dropper[facing=up,triggered=false]", None)]
    # the clock: two observers watching each other, a repeater from it into the dropper's phase block
    parts += [((cx + 1, -2, cz), STONE, None),
              ((cx + 1, -1, cz), "minecraft:repeater[delay=1,facing=east,powered=false]", "K"),
              ((cx + 2, -1, cz), "minecraft:observer[facing=east,powered=false]", "K"),
              ((cx + 3, -1, cz), "minecraft:observer[facing=west,powered=false]", "K")]
    return parts


def stairs():
    """The steps up to the gallery, one block out from the tower: west along the south side, north up the west side,
    east along the north side, each one higher, the last flush with the gallery's floor; (pos, facing)."""
    path = [(x, 5) for x in range(2, -6, -1)] + [(-5, z) for z in range(4, -6, -1)] + [(x, -5) for x in range(-4, 6)]
    assert len(path) == ROOM, len(path)
    out = []
    for i, (x, z) in enumerate(path):
        nxt = path[i + 1] if i + 1 < len(path) else (5, -4)
        dx, dz = nxt[0] - x, nxt[1] - z
        facing = {(1, 0): "east", (-1, 0): "west", (0, 1): "south", (0, -1): "north"}[(dx, dz)]
        out.append(((x, i + 1, z), facing))
    return out


def visible():
    """(kind, a, b, state): the ground, the tower, the room, the gallery and its steps, the chest."""
    out = [("fill", (-7, 0, -7), (9, 0, 7), "minecraft:grass_block")]
    out.append(("fill", (-4, 0, -4), (4, TOP, 4), WALL))                     # the tower, solid, then hollowed
    out.append(("fill", (-3, 1, -3), (3, ROOM - 1, 3), "minecraft:air"))    # the shaft
    out.append(("fill", (-3, ROOM + 1, -3), (3, TOP - 1, 3), "minecraft:air"))  # the room
    out.append(("fill", (-3, 0, -3), (3, 0, 3), "minecraft:polished_deepslate"))      # the landing floor
    out.append(("fill", (-3, ROOM, -3), (3, ROOM, 3), "minecraft:deepslate_tiles"))   # the room's floor
    for p in ((0, 1, 4), (0, 1, -4), (4, 1, 0), (-4, 1, 0)):                # lights at the foot: nothing spawns there
        out.append(("set", p, p, "minecraft:shroomlight"))
    out.append(("set", SPAWNER, SPAWNER, "minecraft:spawner"))
    out.append(("fill", (4, ROOM + 2, -1), (4, ROOM + 3, 1), "minecraft:tinted_glass"))   # a window that lets no light in
    out.append(("fill", (5, ROOM, -4), (7, ROOM, 4), "minecraft:polished_deepslate"))     # the gallery
    for x in range(6, 9):
        out.append(("set", (x, ROOM + 1, -5), (x, ROOM + 1, -5), "minecraft:deepslate_brick_wall"))
        out.append(("set", (x, ROOM + 1, 5), (x, ROOM + 1, 5), "minecraft:deepslate_brick_wall"))
    out.append(("set", (5, ROOM + 1, 5), (5, ROOM + 1, 5), "minecraft:deepslate_brick_wall"))
    out.append(("fill", (8, ROOM + 1, -4), (8, ROOM + 1, 4), "minecraft:deepslate_brick_wall"))
    out.append(("fill", (5, ROOM, -5), (8, ROOM, -5), "minecraft:polished_deepslate"))
    out.append(("fill", (5, ROOM, 5), (8, ROOM, 5), "minecraft:polished_deepslate"))
    out.append(("fill", (8, ROOM, -4), (8, ROOM, 4), "minecraft:polished_deepslate"))
    bx, by, bz = BUTTON_BLOCK
    out.append(("set", (bx + 1, by, bz), (bx + 1, by, bz), "minecraft:stone_button[face=wall,facing=east,powered=false]"))
    for (x, y, z), facing in stairs():
        out.append(("set", (x, y, z), (x, y, z), f"minecraft:deepslate_brick_stairs[facing={facing}]"))
    out.append(("set", CHEST, CHEST, "minecraft:chest[facing=east]"))
    return out


def check():
    """The floor's wiring in the simulator: at rest no shifter is powered; with the button's power every one is."""
    scene = Scene()
    for pos, state, _ in hidden_parts():
        scene.set("hidden", pos, state)
    sim = Sim(scene)
    if sim.popped:
        sys.exit(f"parts with nothing to stand on: {sim.popped[:5]}")
    floor = {(x, ROOM, z) for x in INSIDE for z in INSIDE}
    sim.run(20)
    if sim.powered_shifters() & floor:
        sys.exit(f"at rest, powered: {sorted(sim.powered_shifters() & floor)[:5]}")
    sim.set_input(BUTTON_BLOCK, True)
    sim.run(4)
    if sim.powered_shifters() & floor != floor:
        sys.exit(f"pressed, unpowered: {sorted(floor - sim.powered_shifters())[:5]}")
    sim.set_input(BUTTON_BLOCK, False)
    sim.run(4)
    if sim.powered_shifters() & floor:
        sys.exit(f"released, still powered: {sorted(sim.powered_shifters() & floor)[:5]}")


def write():
    def at(p):
        return f"{p[0] + OFFSET[0]} {p[1] + OFFSET[1]} {p[2] + OFFSET[2]}"
    header = [f"# {line}".rstrip() for line in __doc__.strip().splitlines()]
    header.insert(0, "# Written by tools/gen_skeleton.py; change the generator and run it again.")
    header.append("# Signals: B the button (the port, the wire, the floor's shifters); K the dropper's clock.")
    lines = header + [f"size {SIZE[0]} {SIZE[1]} {SIZE[2]}"]
    for kind, a, b, state in visible():
        lines.append(f"fill visible {at(a)} {at(b)} {state}" if kind == "fill" else f"visible {at(a)} {state}")
    parts = hidden_parts()
    for pos, state, tag in parts:
        lines.append(f"hidden {at(pos)} {state}" + (f" @{tag}" if tag else ""))
    out = ROOT / f"src/gametest/resources/contraptions/{NAME}.txt"
    out.write_text("\n".join(lines) + "\n")
    structure = ROOT / f"src/gametest/resources/data/phasecraft-test/gametest/structure/{NAME}.snbt"
    structure.write_text("{\n    DataVersion: 2730,\n    size: [%d, %d, %d],\n    data: [],\n    palette: []\n}\n" % SIZE)
    print(f"wrote {out.relative_to(ROOT)}: area {SIZE[0]}x{SIZE[1]}x{SIZE[2]}; "
          + hidden_size([(p, s) for p, s, _ in parts]))


if __name__ == "__main__":
    check()
    write()
    print("simulated: the button powers all 49 floor shifters, and only while it is on")
