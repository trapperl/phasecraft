#!/usr/bin/env python3
"""The treasure reveal: an altar with an offering barrel, and empty pedestals behind it. Put anything in the barrel
and the treasure appears: a gold, a diamond and an emerald block on the pedestals, and two sea lanterns on the
pillars beside them; take the offering back and the treasure goes. Nothing but the shrine shows.

Writes src/gametest/resources/contraptions/treasure_reveal.txt and its GameTest area, and checks it in tools/mcb's
redstone simulator.

How, all in the hidden layer: a comparator on a phase block reads the visible barrel behind it (a phased
comparator). Its output powers a block with a torch on it: "hide", on while the barrel is empty. Hide powers a phase
shifter at each treasure block's spot, which holds the block out of the visible world while powered and puts it
back when hide goes off. Hide's lines are laid by mcb.router.
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import gen_door  # noqa: E402
from mcb import router  # noqa: E402
from mcb.redstone import Sim  # noqa: E402
from mcb.report import hidden_size  # noqa: E402
from mcb.scene import Scene  # noqa: E402

NAME = "treasure_reveal"
SIZE = (19, 8, 18)
BARREL = (9, 1, 4)
COMPARATOR = (9, 1, 5)            # on a phase block, reading the barrel (north of it)
GATE = (9, 1, 6)                  # the comparator powers it; the torch on its south face is "hide"
TREASURE = {(6, 2, 12): "minecraft:gold_block", (9, 2, 13): "minecraft:diamond_block",
            (12, 2, 12): "minecraft:emerald_block", (4, 3, 10): "minecraft:sea_lantern",
            (14, 3, 10): "minecraft:sea_lantern"}
STONE = "minecraft:stone"
WIRE = "minecraft:redstone_wire"
BOX = ((0, 0, 0), (SIZE[0] - 1, SIZE[1] - 1, SIZE[2] - 1))


def add(p, v):
    return (p[0] + v[0], p[1] + v[1], p[2] + v[2])


def build():
    scene, tags = Scene(), {}

    def put(p, state, tag=None):
        scene.set("hidden", p, state)
        if tag:
            tags[p] = tag

    put(add(COMPARATOR, (0, -1, 0)), "phasecraft:phase_block")
    put(COMPARATOR, "minecraft:comparator[facing=north,mode=compare,powered=false]", "O")
    put(add(GATE, (0, -1, 0)), STONE)
    put(GATE, STONE)
    torch = add(GATE, (0, 0, 1))
    put(torch, "minecraft:redstone_wall_torch[facing=south,lit=true]", "H<O")
    for p in TREASURE:
        put(p, "phasecraft:phase_shifter[powered=false]", "H")
    ends = [(add(p, (0, 0, -1)), "south") for p in TREASURE]      # each fed from the north
    net = router.TreeNet(add(torch, (0, 0, 1)), "north", ends, "H")
    tree = router.route_all(scene, [net], BOX)[0]
    for p, state in tree.parts:
        k = state.split("[")[0].split(":")[-1]
        if k == "redstone_wire":
            tags[p] = "H"
        elif k == "repeater":
            tags[p] = "H<H"
    tree.apply(scene)
    return scene, tags


def check(scene):
    """At rest (the barrel empty) every treasure shifter is powered; with something in the barrel none is."""
    sim = Sim(scene)
    if sim.popped:
        sys.exit(f"parts with nothing to stand on: {sim.popped[:5]}")
    sim.run(60)
    held = set(sim.powered_shifters())
    if held != set(TREASURE):
        sys.exit(f"at rest, powered shifters: {sorted(held)}; want all of {sorted(TREASURE)}")
    return sim


def visible(at):
    lines = [f"fill visible {at((0, 0, 0))} {at((SIZE[0] - 1, 0, SIZE[2] - 1))} minecraft:grass_block",
             f"fill visible {at((2, 0, 2))} {at((16, 0, 15))} minecraft:polished_andesite",
             f"fill visible {at((8, 1, 4))} {at((10, 1, 4))} minecraft:chiseled_stone_bricks",       # the altar
             f"visible {at(BARREL)} minecraft:barrel[facing=up,open=false]"]
    for p, block in TREASURE.items():
        below = range(1, p[1])
        for y in below:                  # a quartz pedestal under a block, a stone brick pillar under a lantern
            pillar = "minecraft:quartz_pillar" if block != "minecraft:sea_lantern" else "minecraft:stone_bricks"
            lines.append(f"visible {at((p[0], y, p[2]))} {pillar}")
        lines.append(f"visible {at(p)} {block}")
    return lines


def write(scene, tags, sim):
    g = gen_door.Grid()
    order = list(scene.layers["hidden"])
    plain = [p for p in order if scene.get("hidden", p) in (STONE, "minecraft:glass", "phasecraft:phase_block")]
    shifters = [p for p in order if p in TREASURE]
    rest = [p for p in order if p not in plain and p not in shifters]
    for p in plain + rest + shifters:
        name = scene.get("hidden", p).split("[")[0]
        k, props = sim.parts.get(p, (None, {}))
        props = {key: v for key, v in props.items() if not key.startswith("_")} if k else {}
        if p in shifters:
            props = {"powered": "false"}        # placed last: they find their power and take the treasure
        g.parts[p] = [name, props, tags.get(p), None]
        g.order.append(p)
    gen_door.shapes(g)
    for p, v in g.parts.items():
        if v[0] == WIRE and sim.wire_power.get(p):
            v[1]["power"] = str(sim.wire_power[p])

    def at(p):
        return f"{p[0]} {p[1]} {p[2]}"

    header = [f"# {line}".rstrip() for line in __doc__.strip().splitlines()]
    header.insert(0, "# Written by tools/gen_treasure.py; change the generator and run it again.")
    header.append("# Signals: O the phased comparator's reading of the barrel; H hide (NOT O); a diode <out><<in>.")
    lines = header + [f"size {SIZE[0]} {SIZE[1]} {SIZE[2]}"] + visible(at)
    for p in plain + rest + shifters:
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
    sim = check(scene)
    write(scene, tags, sim)
    print("simulated: with the barrel empty all five treasure shifters are powered (the treasure held)")


if __name__ == "__main__":
    main()
