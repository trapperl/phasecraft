#!/usr/bin/env python3
"""The hole in the ocean: a lever opens a dry, glass-walled 3 x 3 shaft down to the seabed of a pool 8 deep, and
closes it again; repeatable, no sponges, nothing showing while it is closed.

Writes src/gametest/resources/contraptions/ocean_hole.txt and its GameTest area, and checks the controller's timing
in tools/mcb's redstone simulator. Needs the game rule phasecraft:area_shifters (off by default).

How: two hidden area shifters at opposite corners of the shaft's box (its 5 x 5 glass ring and 3 x 3 inside, 8
high) hold its glass walls and a filler while it is closed: the sea fills the box. Under the seabed, a hidden line
shifter faces up under each of the 9 inside columns, powered through hidden wire on top of it (a wire powers the
block it stands on, which is the only way into the middle one).
- Opening: the area shifters lose their power (A falls) and put back the walls and the filler, layer by layer from
  the seabed, each block in place of the water; then the line shifters are powered (S rises) and take the filler,
  from the top: the shaft is dry.
- Closing: S falls first and the line shifters put the filler back, from the seabed up; then A rises and the area
  shifters take everything, from the top: the sea flows in, a layer at a time, each layer over solid filler, so it
  becomes still water again.
So A = NOT (L OR Ld) and S = L AND Ld, Ld the lever's signal L delayed by DELAY redstone ticks: A falls at once and
rises late, S rises late and falls at once. Four torches: A on a block L and Ld power; S on a block NOT L and
NOT Ld power. Every line is laid by mcb.router.
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

NAME = "ocean_hole"
SIZE = (24, 13, 32)             # the GameTest area: its corners 16 blocks or more from any other test's (C17)
DELAY = 16                      # redstone ticks between the lever and Ld: more than a move of 8 layers or blocks
LAYERS = 8                      # the pool's depth, the shaft's height
POOL = ((3, 12), (15, 24))      # its walls' x and z, from (x0, z0) to (x1, z1); its rim one above the water
LOW, HIGH = (7, 1, 16), (11, LAYERS, 20)      # the shaft's box: its glass ring, the inside x 8..10, z 17..19
INSIDE = [(x, z) for x in range(8, 11) for z in range(17, 20)]
PORT = (5, 0, 11)               # under the lever, before the pool
STONE = "minecraft:stone"
WIRE = "minecraft:redstone_wire"
BOX = ((0, 0, 0), (23, 12, 27))       # where the router may lay lines

# The gates: a block, a torch on its south face; (block, torch, signal out, signal in).
GATES = {
    "A": ((20, 1, 3), (20, 1, 4)),     # NOT (L OR Ld): the area shifters
    "NL": ((2, 1, 3), (2, 1, 4)),      # NOT L
    "NLd": ((8, 1, 3), (8, 1, 4)),     # NOT Ld
    "S": ((14, 1, 7), (14, 1, 8)),     # NOT (NOT L OR NOT Ld): the line shifters
}


def add(p, v):
    return (p[0] + v[0], p[1] + v[1], p[2] + v[2])


def nets():
    """(net, signal, source signal) for each line, in routing order."""
    a, nl, nld, s = (GATES[k][0] for k in ("A", "NL", "NLd", "S"))
    return [
        (router.TreeNet((3, 1, 11), "east", [(add(nl, (0, 0, -1)), "south"), (add(a, (0, 0, -1)), "south")], "L"), "L", "L"),
        (router.Net((7, 1, 11), "west", add(a, (-1, 0, 0)), "east", delay=DELAY, name="Ld to A"), "Ld", "L"),
        (router.Net((5, 1, 9), "south", add(nld, (0, 0, -1)), "south", delay=DELAY, name="Ld to NOT Ld"), "Ld", "L"),
        (router.Net(add(GATES["NL"][1], (0, 0, 1)), "north", add(s, (-1, 0, 0)), "east", name="NOT L"), "NL", "NL"),
        (router.Net(add(GATES["NLd"][1], (0, 0, 1)), "north", add(s, (0, 0, -1)), "south", name="NOT Ld"), "NLd", "NLd"),
        (router.Net(add(GATES["S"][1], (0, 0, 1)), "north", (12, 1, 18), "west", name="S"), "S", "S"),
        (router.TreeNet(add(GATES["A"][1], (0, 0, 1)), "north",
                        [(add(LOW, (0, 0, -1)), "south"), (add(HIGH, (1, 0, 0)), "west")], "A"), "A", "A"),
    ]


def build():
    """The hidden scene and each part's signal tag."""
    scene, tags = Scene(), {}

    def put(p, state, tag=None):
        scene.set("hidden", p, state)
        if tag:
            tags[p] = tag

    put(PORT, "phasecraft:phase_port", "L")
    put(add(PORT, (0, 1, 0)), WIRE, "L")
    for v in ((-1, 0, 0), (1, 0, 0), (0, 0, -1)):    # a wire each way but south: the lines start a block further,
        put(add(PORT, v), STONE)                    # clear of the port (a step below them is other redstone)
        put(add(PORT, add(v, (0, 1, 0))), WIRE, "L")
    for name, (block, torch) in GATES.items():
        put(add(block, (0, -1, 0)), STONE)
        put(block, STONE)
        put(torch, "minecraft:redstone_wall_torch[facing=south,lit=true]",
            {"A": "A<L", "NL": "NL<L", "NLd": "NLd<Ld", "S": "S<NL"}[name])
    for x, z in INSIDE:
        put((x, 0, z), "phasecraft:line_shifter[delay=1,facing=up,powered=false]", "S")
        put((x, 1, z), WIRE, "S")
    put((11, 0, 18), STONE)          # S comes in here (the router keeps a line's end clear of other redstone, and
    put((11, 1, 18), WIRE, "S")      # a step below this wire is a line shifter)
    put(LOW, "phasecraft:area_shifter[delay=1,powered=false]", "A")
    put(HIGH, "phasecraft:area_shifter[delay=1,powered=false]", "A")
    todo = nets()
    routes = router.route_all(scene, [n for n, _, _ in todo], BOX)
    for (net, sig, src), r in zip(todo, routes):
        start = net.start
        for p, state in r.parts:
            k = state.split("[")[0].split(":")[-1]
            if k == "redstone_wire":
                tags[p] = sig
            elif k == "repeater":
                tags[p] = f"{sig}<{src}" if p == start else f"{sig}<{sig}"
        r.apply(scene)
    return scene, tags


def settle(scene):
    """The parts' states with the lever off, once the controller has settled (the shifters still unpowered: they
    are placed last, powered, so that the area shifters take the box when it is built)."""
    sim = Sim(scene)
    if sim.popped:
        sys.exit(f"parts with nothing to stand on: {sim.popped[:5]}")
    sim.run(100)
    return sim


def check(scene, port):
    """In the simulator: A falls before S rises, and the area shifters' move of LAYERS layers ends before it;
    S falls before A rises, and the line shifters' move of LAYERS blocks ends before it; twice."""
    sim = settle(scene)
    corners = {LOW, HIGH}
    shifters = {(x, 0, z) for x, z in INSIDE}
    move = 2 * LAYERS                        # game ticks for a move at delay 1
    report = []
    for _ in range(2):
        for on in (True, False):
            sim.events.clear()
            t0 = sim.tick
            sim.set_input(port, on)
            sim.run(2 * DELAY + 80)
            a = {p: t - t0 for t, p, k, v in sim.events if p in corners}
            s = {p: t - t0 for t, p, k, v in sim.events if p in shifters}
            if set(a) != corners or set(s) != shifters:
                sys.exit(f"lever {'on' if on else 'off'}: corners {a}, line shifters {len(s)} of 9 switched")
            ta, ts = max(a.values()), (min(s.values()) if on else max(s.values()))
            if on and not max(a.values()) + move < min(s.values()):
                sys.exit(f"opening: the area shifters lose power at {a}, the line shifters take at {min(s.values())}")
            if not on and not max(s.values()) + move < min(a.values()):
                sys.exit(f"closing: the line shifters put back at {max(s.values())}, the area shifters take at "
                         f"{min(a.values())}")
            report.append((on, sorted(set(a.values())), sorted(set(s.values()))))
    return report


def visible(at):
    """The pool, the water, and what the shaft is made of while it is open (written so the area shifters, placed
    powered afterwards, take it and the sea fills the box)."""
    (x0, z0), (x1, z1) = POOL
    lines = [f"fill visible {at((0, 0, 0))} {at((SIZE[0] - 1, 0, SIZE[2] - 1))} minecraft:grass_block",
             f"fill visible {at((x0, 0, z0))} {at((x1, LAYERS + 1, z1))} minecraft:stone_bricks",
             f"fill visible {at((x0 + 1, 0, z0 + 1))} {at((x1 - 1, 0, z1 - 1))} minecraft:sand",
             f"fill visible {at((x0 + 1, 1, z0 + 1))} {at((x1 - 1, LAYERS + 1, z1 - 1))} minecraft:air",
             f"fill visible {at((x0 + 1, 1, z0 + 1))} {at((x1 - 1, LAYERS, z1 - 1))} minecraft:water",
             f"fill visible {at(LOW)} {at(HIGH)} minecraft:glass",
             f"fill visible {at((8, 1, 17))} {at((10, LAYERS, 19))} minecraft:light_blue_stained_glass",
             f"visible {at(add(PORT, (0, 1, 0)))} minecraft:lever[face=floor,facing=south,powered=false]"]
    return lines


def write(scene, tags, sim):
    g = gen_door.Grid()
    order = [p for p, (s, _) in scene.layers["hidden"].items()]
    plain = [p for p in order if scene.get("hidden", p) in (STONE, "minecraft:glass")]
    rest = [p for p in order if p not in plain and "area_shifter" not in scene.get("hidden", p)]
    last = [LOW, HIGH]
    for p in plain + rest + last:
        name = scene.get("hidden", p).split("[")[0]
        k, props = sim.parts.get(p, (None, {}))
        props = {key: v for key, v in props.items() if not key.startswith("_")} if k else {}
        if name.endswith("area_shifter"):
            props = {"delay": "1", "powered": "false"}         # placed last: they find their power and take the box
        g.parts[p] = [name, props, tags.get(p), None]
        g.order.append(p)
    gen_door.shapes(g)
    for p, v in g.parts.items():
        if v[0] == WIRE and sim.wire_power.get(p):
            v[1]["power"] = str(sim.wire_power[p])

    def at(p):
        return f"{p[0]} {p[1]} {p[2]}"

    header = [f"# {line}".rstrip() for line in __doc__.strip().splitlines()]
    header.insert(0, "# Written by tools/gen_ocean.py; change the generator and run it again.")
    header.append("# Signals: L the lever; Ld L delayed; NL, NLd their inverses; A the area shifters (NOT (L OR Ld));"
                  " S the line shifters (L AND Ld); a diode <out><<in>.")
    lines = header + [f"size {SIZE[0]} {SIZE[1]} {SIZE[2]}", f"lever {at(add(PORT, (0, 1, 0)))}",
                      "allow L Ld", "allow NL NLd"]      # the two gates: a torch on a block two lines power (OR)
    lines += visible(at)
    for p in plain + rest + last:
        name, props, tag, _ = g.parts[p]
        state = name + ("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else "")
        lines.append(f"hidden {at(p)} {state}" + (f" @{tag}" if tag else ""))
    out = ROOT / f"src/gametest/resources/contraptions/{NAME}.txt"
    out.write_text("\n".join(lines) + "\n")
    structure = ROOT / f"src/gametest/resources/data/phasecraft-test/gametest/structure/{NAME}.snbt"
    structure.write_text("{\n    DataVersion: 2730,\n    size: [%d, %d, %d],\n    data: [],\n    palette: []\n}\n" % SIZE)
    print(f"wrote {out.relative_to(ROOT)}: area {SIZE[0]}x{SIZE[1]}x{SIZE[2]}; "
          + hidden_size([(p, g.parts[p][0]) for p in order]))
    return out


def main():
    scene, tags = build()
    sim = settle(scene)
    write(scene, tags, sim)
    for on, a, s in check(scene, PORT):
        print(f"lever {'on ' if on else 'off'}: area shifters switch at {a}, line shifters at {s} (game ticks)")


if __name__ == "__main__":
    main()
