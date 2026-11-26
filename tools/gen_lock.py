#!/usr/bin/env python3
"""The combination lock: four levers on a stone-brick wall, and a door in the wall that looks like the rest of it.
Set the levers to the combination (on, off, on, on from west to east) and the door opens onto a small vault with a
chest; any other setting closes it. Nothing but the levers shows: the logic and the door's machinery are hidden.

Writes src/gametest/resources/contraptions/combination_lock.txt and its GameTest area, and checks every one of the
16 settings in tools/mcb's redstone simulator.

How, all in the hidden layer:
- Each lever hangs on the wall's south face; a phase port at the wall block it hangs on brings its power into the
  hidden layer (the block is strongly powered in the visible world; the port gives it out like a redstone block).
- A lever whose right setting is on goes through a torch (it is wrong while off); one whose right setting is off
  goes straight (it is wrong while on). The four "wrong" lines all run into one block, W; a torch standing on W is
  "open": on only when no line is wrong.
- The door is a doorway's 1 x 2 blocks; a phase shifter at each of its spots takes the visible block while "open" is
  on and puts it back when it goes off. Each is powered through a stone right behind it: the lower one's from the
  north, the upper one's from the south, so the lines' ends never sit a step from the other shifter.
Every line is laid by mcb.router.
"""

import itertools
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import gen_door  # noqa: E402
from mcb import router  # noqa: E402
from mcb.redstone import Sim  # noqa: E402
from mcb.report import hidden_size  # noqa: E402
from mcb.scene import Scene  # noqa: E402

NAME = "combination_lock"
SIZE = (16, 8, 18)
WALL_Z = 13
LEVERS = [1, 4, 11, 14]               # their x; they hang at y = 2 on the wall's south face (z = WALL_Z + 1)
COMBINATION = [True, False, True, True]
DOOR = [(8, 1), (8, 2)]              # a doorway, 1 wide and 2 high
STONE = "minecraft:stone"
WIRE = "minecraft:redstone_wire"
BOX = ((0, 0, 0), (SIZE[0] - 1, SIZE[1] - 1, SIZE[2] - 1))
W_BLOCK = (7, 1, 2)                   # the "wrong" block; "open" is the torch on its south face
J_BLOCK = (12, 1, 1)                  # two wrong lines (levers 3 and 4) join here first: W has three free sides
INVERTERS = {0: (1, 1, 7), 2: (11, 1, 7), 3: (14, 1, 7)}     # lever index -> its torch's block (torch on its north)


def add(p, v):
    return (p[0] + v[0], p[1] + v[1], p[2] + v[2])


def port(i):
    return (LEVERS[i], 2, WALL_Z)


def build():
    scene, tags = Scene(), {}

    def put(p, state, tag=None):
        scene.set("hidden", p, state)
        if tag:
            tags[p] = tag

    def block(p):
        put(add(p, (0, -1, 0)), STONE)
        put(p, STONE)

    W, J = W_BLOCK, J_BLOCK
    for p in (W, J, *INVERTERS.values()):
        block(p)
    for i, b in INVERTERS.items():
        put(add(b, (0, 0, -1)), "minecraft:redstone_wall_torch[facing=north,lit=true]", f"N{i + 1}<L{i + 1}")
    put(add(W, (0, 0, 1)), "minecraft:redstone_wall_torch[facing=south,lit=false]", "O<W")
    for i in range(4):
        put(port(i), "phasecraft:phase_port", f"L{i + 1}")

    def start(i):
        return add(port(i), (0, 0, -1))                # behind the wall, fed by the port

    def after(i):
        return add(INVERTERS[i], (0, 0, -2))           # past an inverter's torch

    nets = [
        (router.Net(start(0), "south", add(INVERTERS[0], (0, 0, 1)), "north", name="L1"), "L1", "L1"),
        (router.Net(after(0), "south", add(W, (-1, 0, 0)), "east", name="N1"), "N1", "N1"),
        (router.Net(start(1), "south", add(W, (0, 0, -1)), "south", name="L2"), "L2", "L2"),
        (router.Net(start(2), "south", add(INVERTERS[2], (0, 0, 1)), "north", name="L3"), "L3", "L3"),
        (router.Net(after(2), "south", add(J, (-1, 0, 0)), "east", name="N3"), "N3", "N3"),
        (router.Net(start(3), "south", add(INVERTERS[3], (0, 0, 1)), "north", name="L4"), "L4", "L4"),
        (router.Net(after(3), "south", add(J, (1, 0, 0)), "west", name="N4"), "N4", "N4"),
        (router.Net(add(J, (0, 0, 1)), "north", add(W, (1, 0, 0)), "west", name="J"), "J", "J"),
    ]
    ends = []
    for x, y in DOOR:          # a stone behind each shifter, powered by the line's end (the lower from the north,
        put((x, y, WALL_Z), "phasecraft:phase_shifter[powered=false]", "O")    # the upper from the south)
        side = -1 if y == 1 else 1
        stone = (x, y, WALL_Z + side)
        if y > 1:
            put(add(stone, (0, -1, 0)), STONE)
        put(stone, STONE)
        ends.append((add(stone, (0, 0, side)), "south" if side < 0 else "north"))
    nets.append((router.TreeNet(add(W, (0, 0, 2)), "north", ends, "O"), "O", "O"))
    routes = router.route_all(scene, [n for n, _, _ in nets], BOX)
    for (net, sig, src), r in zip(nets, routes):
        for p, state in r.parts:
            k = state.split("[")[0].split(":")[-1]
            if k == "redstone_wire":
                tags[p] = sig
            elif k == "repeater":
                tags[p] = f"{sig}<{src}"
        r.apply(scene)
    return scene, tags


def setting_opens(scene, setting):
    """Whether the door's two shifters are all powered, and nothing else, with the levers so (from all off)."""
    sim = Sim(scene)
    sim.run(40)
    for i, on in enumerate(setting):
        if on:
            sim.set_input(port(i), True)
    sim.run(40)
    door = {(x, y, WALL_Z) for x, y in DOOR}
    powered = set(sim.powered_shifters())
    if powered and powered != door:
        sys.exit(f"setting {setting}: only {sorted(powered)} of the door's shifters powered")
    return powered == door, sim


def check(scene):
    """Every one of the 16 settings: the door opens for the combination only."""
    opened = []
    for setting in itertools.product((False, True), repeat=4):
        ok, _ = setting_opens(scene, list(setting))
        if ok:
            opened.append(list(setting))
    if opened != [COMBINATION]:
        sys.exit(f"the door opens for {opened}, not only for {COMBINATION}")


def visible(at):
    lines = [f"fill visible {at((0, 0, 0))} {at((SIZE[0] - 1, 0, SIZE[2] - 1))} minecraft:grass_block",
             f"fill visible {at((0, 1, WALL_Z))} {at((SIZE[0] - 1, 4, WALL_Z))} minecraft:stone_bricks",
             # the vault behind the door: walls, a roof, a chest of treasure
             f"fill visible {at((5, 1, WALL_Z - 5))} {at((11, 4, WALL_Z - 1))} minecraft:stone_bricks",
             f"fill visible {at((6, 1, WALL_Z - 4))} {at((10, 3, WALL_Z - 1))} minecraft:air",
             # its floor, through the doorway too (grass under a roof turns to dirt)
             f"fill visible {at((5, 0, WALL_Z - 5))} {at((11, 0, WALL_Z))} minecraft:stone_bricks",
             f"visible {at((8, 1, WALL_Z - 4))} minecraft:chest[facing=south]"]
    for x in LEVERS:
        lines.append(f"visible {at((x, 2, WALL_Z + 1))} minecraft:lever[face=wall,facing=south,powered=false]")
    lines.append(f"items visible {at((8, 1, WALL_Z - 4))} minecraft:diamond*9 minecraft:gold_ingot*12 minecraft:emerald*5")
    return lines


def write(scene, tags, sim):
    g = gen_door.Grid()
    order = list(scene.layers["hidden"])
    plain = [p for p in order if scene.get("hidden", p) in (STONE, "minecraft:glass")]
    rest = [p for p in order if p not in plain]
    for p in plain + rest:
        name = scene.get("hidden", p).split("[")[0]
        k, props = sim.parts.get(p, (None, {}))
        props = {key: v for key, v in props.items() if not key.startswith("_")} if k else {}
        g.parts[p] = [name, props, tags.get(p), None]
        g.order.append(p)
    gen_door.shapes(g)
    for p, v in g.parts.items():
        if v[0] == WIRE and sim.wire_power.get(p):
            v[1]["power"] = str(sim.wire_power[p])

    def at(p):
        return f"{p[0]} {p[1]} {p[2]}"

    header = [f"# {line}".rstrip() for line in __doc__.strip().splitlines()]
    header.insert(0, "# Written by tools/gen_lock.py; change the generator and run it again.")
    header.append("# Signals: L1..L4 the levers (west to east); N<k> lever k inverted; O open (the torch on the "
                  "block all wrong lines run into); a diode <out><<in>.")
    lines = header + [f"size {SIZE[0]} {SIZE[1]} {SIZE[2]}", "door minecraft:stone_bricks",
                      "allow W N1 L2 J", "allow J N3 N4"]        # the two OR blocks: W (the gate) and J
    lines += visible(at)
    for p in plain + rest:
        name, props, tag, _ = g.parts[p]
        state = name + ("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else "")
        lines.append(f"hidden {at(p)} {state}" + (f" @{tag}" if tag else ""))
    out = ROOT / f"src/gametest/resources/contraptions/{NAME}.txt"
    out.write_text("\n".join(lines) + "\n")
    structure = ROOT / f"src/gametest/resources/data/phasecraft-test/gametest/structure/{NAME}.snbt"
    structure.write_text("{\n    DataVersion: 2730,\n    size: [%d, %d, %d],\n    data: [],\n    palette: []\n}\n" % SIZE)
    print(f"wrote {out.relative_to(ROOT)}: area {SIZE[0]}x{SIZE[1]}x{SIZE[2]}; "
          + hidden_size([(p, g.parts[p][0]) for p in order]))


def main():
    scene, tags = build()
    check(scene)
    _, sim = setting_opens(scene, [False] * 4)
    write(scene, tags, sim)
    print(f"simulated: of the 16 settings, only {COMBINATION} opens the door")


if __name__ == "__main__":
    main()
