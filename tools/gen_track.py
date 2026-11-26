#!/usr/bin/env python3
"""The minecart track: a track across a chasm that is there only around a minecart, laid ahead of it and taken up
behind it. Two cliffs with ordinary rails; between them, COLUMNS columns of a deck block with a rail on it, each
there only while a minecart is within REACH columns.

Writes src/gametest/resources/contraptions/minecart_track.txt and its GameTest area, and checks it in tools/mcb's
redstone simulator.

How: the appearing bridge's machinery (tools/gen_path.py), one block lower and a little wider. Under each column a
hidden line shifter faces up: its line is the deck block and the rail on it, so it puts the deck back before the
rail (a rail needs a block under it) and takes the rail before the deck. The sensors are hidden strings at rail
height, phased, so a minecart (or anyone) on the track trips them; the wiring powers a column's shifter, holding its
blocks, while nothing is within REACH of it. A minecart covers at most 0.4 blocks a game tick and a column appears
within a few game ticks of a string being tripped, so REACH = 5 keeps the track ahead of the fastest cart.

On each cliff: a wall at the end, a powered rail before it and a button beside it to start from (unpowered, it
stops a cart that arrives; powered, it pushes a stopped cart away from the wall), and boosters, powered rails a
hidden phase port powers from below.
"""

import pathlib
import random
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import gen_path  # noqa: E402

NAME = "minecart_track"
COLUMNS = 20              # the track across the chasm
REACH = 5                 # columns around a minecart that are there
CLIFF = 13                # each cliff's track, from the chasm's edge to the wall
BOOSTERS = 4              # powered rails after the start
DEPTH = 8                 # the chasm's water, below the track bed
DECK = "minecraft:spruce_planks"
RAIL = "minecraft:rail[shape=east_west]"
LINE_SHIFTER = "phasecraft:line_shifter[delay=1,facing=up,powered=false]"
Y0 = -1                   # gen_path's plane, one block lower: its shifter under the deck, its strings at rail height

# gen_path's machinery, for a track: a reach of 5, no jumps to ride out
gen_path.REACH = REACH
gen_path.BRANCH = 14 - REACH
gen_path.OUT = gen_path.BRANCH + 2
gen_path.DELAY = 1


def hidden_parts():
    """Every column's plane (approaches REACH past both ends), a line shifter under each column, and the boosters'
    ports, each powered by a hidden redstone block under it."""
    parts = []
    for k in range(-REACH, COLUMNS + REACH):
        parts += gen_path.plane(k, 0 <= k < COLUMNS, y0=Y0)
    parts += [((k, Y0, 0), LINE_SHIFTER, "F") for k in range(COLUMNS)]
    for x in boosters():
        parts += [((x, -1, 0), "minecraft:redstone_block", None), ((x, 0, 0), "phasecraft:phase_port", None)]
    return parts


def ends():
    """The west and east walls' x."""
    return -CLIFF, COLUMNS + CLIFF - 1


def boosters():
    west, east = ends()
    # a plain rail between them and the start rail: powered rails pass power on to their neighbours, and a start
    # rail always powered would send a cart that has just arrived straight back
    return [west + 3 + i for i in range(BOOSTERS)] + [east - 3 - i for i in range(BOOSTERS)]


def hooks(k):
    s = 1 if k % 2 == 0 else -1
    return [(k, gen_path.SENSOR_Y + Y0, -s), (k, gen_path.SENSOR_Y + Y0, (gen_path.OUT - 1) * s)]


def simulate():
    """Nothing at rest; a cart over each column (or between two) gets exactly the columns within REACH, and after it
    leaves nothing again. Returns the most game ticks a column's shifter took to let go."""
    from mcb.redstone import Sim
    from mcb.scene import Scene
    scene = Scene()
    for pos, state, _ in hidden_parts():
        scene.set("hidden", pos, state)
    sim = Sim(scene)
    if sim.popped:
        sys.exit(f"parts with nothing to stand on: {sim.popped[:5]}")
    sim.run(40)
    everything = set(range(COLUMNS))

    def held():
        return {p[0] for p, (k, props) in sim.parts.items() if k == "line_shifter" and props.get("powered") == "true"}
    if held() != everything:
        sys.exit(f"at rest, columns there: {sorted(everything - held())}")
    slowest = 0
    for where in [(j,) for j in range(-REACH - 1, COLUMNS + REACH + 1)] + [(j, j + 1) for j in range(-REACH, COLUMNS + REACH - 1)]:
        for j in where:
            for h in hooks(j) if -REACH <= j < COLUMNS + REACH else []:
                sim.set_input(h, True)
        t0 = sim.tick
        sim.events.clear()
        sim.run(30)
        want = {k for k in everything if all(abs(k - j) > REACH for j in where)}
        if held() != want:
            sys.exit(f"a cart at {where}: held {sorted(held())}, expected {sorted(want)}")
        slowest = max([slowest] + [t - t0 for t, *_ in sim.events])
        for j in where:
            for h in hooks(j) if -REACH <= j < COLUMNS + REACH else []:
                sim.set_input(h, False)
        sim.run(30)
        if held() != everything:
            sys.exit(f"after a cart left {where}: columns still there {sorted(everything - held())}")
    return slowest


FACE = {"minecraft:stone": 8, "minecraft:andesite": 2, "minecraft:tuff": 1, "minecraft:coal_ore": 1}


def pick(weights, pos):
    r = random.Random(hash(pos) & 0xFFFF).random() * sum(weights.values())
    for block, w in weights.items():
        r -= w
        if r < 0:
            return block
    return next(iter(weights))


def visible(at, margins):
    """Two cliffs with the track's bed and rails, the chasm between with a river, the columns' blocks."""
    (x0, _, z0), (x1, _, z1) = margins
    west, east = ends()
    lines = []
    for lo, hi in ((x0, -1), (COLUMNS, x1)):
        lines += [f"fill visible {at((lo, -DEPTH - 1, z0))} {at((hi, -1, z1))} minecraft:stone",
                  f"fill visible {at((lo, 0, z0))} {at((hi, 0, z1))} minecraft:grass_block",
                  f"fill visible {at((max(lo, west), 0, -1))} {at((min(hi, east), 0, 1))} minecraft:gravel"]
    lines += [f"fill visible {at((0, -DEPTH - 1, z0))} {at((COLUMNS - 1, -DEPTH - 1, z1))} minecraft:gravel",
              f"fill visible {at((0, -DEPTH, z0))} {at((COLUMNS - 1, -DEPTH, z1))} minecraft:water"]
    blocks = {}
    for x in (-1, COLUMNS):               # the cliffs' faces
        for y in range(-DEPTH, 0):
            for z in range(z0, z1 + 1):
                b = pick(FACE, (x, y, z))
                if b != "minecraft:stone":
                    blocks[(x, y, z)] = b
    for x in list(range(west + 1, 0)) + list(range(COLUMNS, east)):      # the cliffs' rails
        blocks[(x, 1, 0)] = RAIL
    for x in boosters():
        blocks[(x, 0, 0)] = "minecraft:stone_bricks"
        blocks[(x, 1, 0)] = "minecraft:powered_rail[shape=east_west]"
    for x, facing in ((west, "north"), (east, "north")):                  # the walls, a start rail and a button
        start = west + 1 if x == west else east - 1
        blocks[(start, 1, 0)] = "minecraft:powered_rail[shape=east_west]"
        for z in (-1, 0, 1):
            blocks[(x, 1, z)] = "minecraft:stone_bricks"
        blocks[(x, 2, 0)] = "minecraft:stone_brick_slab"
        blocks[(start, 1, -1)] = f"minecraft:stone_button[face=floor,facing={facing}]"
    for x in (-1, COLUMNS):               # lanterns on posts at the chasm's edges
        for z in (-1, 1):
            blocks[(x, 1, z)] = "minecraft:stone_brick_wall"
            blocks[(x, 2, z)] = "minecraft:lantern[hanging=false]"
    for k in range(COLUMNS):              # the track across: the shifters take these when placed (no cart near)
        blocks[(k, 0, 0)] = DECK
        blocks[(k, 1, 0)] = RAIL
    lines += [f"visible {at(p)} {b}" for p, b in blocks.items()]
    return lines


def write(slowest):
    parts = hidden_parts()
    west, east = ends()
    margins = ((west - 2, -DEPTH - 1, -gen_path.OUT - 1), (east + 2, 4, gen_path.OUT + 1))
    (mx0, my0, mz0), (mx1, my1, mz1) = margins
    xs = [p[0] for p, *_ in parts] + [mx0, mx1]
    ys = [p[1] for p, *_ in parts] + [my0, my1]
    zs = [p[2] for p, *_ in parts] + [mz0, mz1]
    ox, oy, oz = -min(xs), -min(ys), -min(zs)
    size = (max(xs) + ox + 1, max(ys) + oy + 1, max(zs) + oz + 1)

    def at(p):
        return f"{p[0] + ox} {p[1] + oy} {p[2] + oz}"
    header = [
        "The minecart track (MinecartTrackGameTests, examples station 35). Written by tools/gen_track.py: do not",
        "edit, change the generator and run it again; check with: python3 tools/redstone_check.py <this file>",
        "",
        f"A track of {COLUMNS} columns across a chasm, from x = {ox} to {ox + COLUMNS - 1}, the rails at y = {oy + 1},",
        f"z = {oz}. A column (a deck block and its rail) is there only while a minecart is within {REACH} of it. Each is",
        "held by a hidden line shifter under it; hidden strings at rail height sense the cart. On each cliff, a",
        f"start rail and button by the wall, and boosters a hidden port powers. Simulated: a column is back within {slowest}",
        "game ticks. Signals: N near (the lines, their branches, the repeaters); F far (the torches, shifters).",
    ]
    lines = [f"# {h}" if h else "#" for h in header] + [f"size {size[0]} {size[1]} {size[2]}"]
    lines += visible(at, margins)
    for pos, state, tag in parts:
        lines.append(f"hidden {at(pos)} {state}" + (f" @{tag}" if tag else ""))
    out = ROOT / f"src/gametest/resources/contraptions/{NAME}.txt"
    out.write_text("\n".join(lines) + "\n")
    structure = ROOT / f"src/gametest/resources/data/phasecraft-test/gametest/structure/{NAME}.snbt"
    structure.write_text("{\n    DataVersion: 2730,\n    size: [%d, %d, %d],\n    data: [],\n    palette: []\n}\n" % size)
    from mcb.report import hidden_size
    print(f"wrote {out.relative_to(ROOT)}: area {size[0]}x{size[1]}x{size[2]}, origin offset {(ox, oy, oz)}; "
          + hidden_size((pos, state) for pos, state, _ in parts))


if __name__ == "__main__":
    slowest = simulate()
    print(f"simulated: a column's shifter lets go within {slowest} game ticks of a cart coming within {REACH} of it")
    write(slowest)
