#!/usr/bin/env python3
"""The appearing staircase (examples station 26, AppearingStaircaseGameTests): a staircase up to a cliff's ledge whose
steps are there only within REACH steps of someone on it (or coming to it): it appears ahead as they climb and
vanishes behind them.

Usage: python3 tools/gen_stairs.py && python3 tools/redstone_check.py src/gametest/resources/contraptions/appearing_staircase.txt

The bridge's idea (tools/gen_path.py), on a slope. Every step's block is held by a hidden phase shifter at its spot;
each step's logic is in its own plane (x = k), raised one block per step with the step. So two neighbouring planes
are one block apart in height: a part at (y, z) of one plane's template is beside the part at (y - 1, z) of the next
plane's and (y + 1, z) of the one before. The bridge fed each shifter from a stone right under it; on a slope that
stone would be beside the step below's shifter. So here each shifter is fed from the side (z = +1).

Per step k, in its plane (y up from the step's block, z across; the block at y = 0, z = 0):
- The sensor, as the bridge's: a hidden string across the walkway at head height (y = 2) between two hooks, one on a
  phase block (so visible players trip it), the other on a stone, H, which the hook powers strongly when tripped.
  Even steps' strings and branches go out south, odd steps' north.
- Two lines of wire along the whole staircase, one on each side (y = -2, z = +-3), each a wire staircase (a stone
  under every wire, nothing over it), level along the approaches. A branch of BRANCH wires runs from under H down to
  its line, so a tripped sensor puts REACH + 1 into its line at its step: the line is powered within REACH steps.
- The consumer: a repeater from the south line, and three from the north line, into a stone (BASE, y = -2, z = 1);
  a torch on BASE; the stone on the torch (FEED, y = 0, z = 1) beside the shifter. So the torch, and with it the
  shifter, is off (the block there) while someone is near, on (the block held) while not. Nothing over or under a
  shifter carries power.

Approach planes (sensors and branches, no block) reach REACH steps past both ends: before the stairs level with the
ground (as if a step at y = -1), after them level with the ledge (as if a step at y = STEPS).
"""

import pathlib
import random
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

REACH = 3                 # steps around a player that are there
BRANCH = 14 - REACH       # wire from H to its line: 15 at the first block, REACH + 2 at the last, REACH + 1 in the line
DELAY = 3                 # redstone ticks from a line to BASE: rides out the head leaving the string at a jump's top
SENSOR_Y = 2
LINE_Y, LINE_Z = -2, 3    # the lines, z = +-LINE_Z
OUT = BRANCH + LINE_Z     # z of H: the branch's first wire is under it, its last at z = LINE_Z + 1

WIRE = "minecraft:redstone_wire[east=side,north=side,south=side,west=side]"
STONE, GLASS = "minecraft:stone", "minecraft:glass"
BASE, TORCH, FEED = (-2, 1), (-1, 1), (0, 1)


def mirror(c, s):
    return (c[0], c[1] * s)


def branch(ends=(LINE_Y + 1, LINE_Y, LINE_Y - 1)):
    """The branch from under H to its line, as (y, z): under H, a step down, then towards the line one column a block,
    stepping down as late as it can, ending beside the line or a step above or below it."""
    first, second = (SENSOR_Y - 1, OUT), (SENSOR_Y - 2, OUT - 1)
    for end in ends:
        drop = second[0] - end
        flats = (OUT - 1 - (LINE_Z + 1)) - drop
        if drop < 0 or flats < 0:
            continue
        path = [first, second]
        y = second[0]
        for z in range(OUT - 2, LINE_Z, -1):
            if flats > 0:
                flats -= 1
            else:
                y -= 1
            path.append((y, z))
        if path[-1][0] == end:
            return path
    raise ValueError("no branch shape")


def supports(path):
    """The block under each wire: stone under the higher of a step (a step carries a signal down only over a
    conductor), glass elsewhere."""
    out = {}
    for i, c in enumerate(path):
        nxt = path[i + 1] if i + 1 < len(path) else (LINE_Y, LINE_Z)
        prv = path[i - 1] if i else None
        upper = nxt[0] < c[0] or (prv is not None and prv[0] < c[0])
        out[(c[0] - 1, c[1])] = STONE if upper else GLASS
    return out


def rise(k, n):
    """The height of plane k's step: level with the ground before the stairs and with the ledge after them."""
    return max(-1, min(k, n))


def plane(k, n):
    """The hidden parts of plane k: [(pos, state, tag)], supports before what stands on them."""
    s = 1 if k % 2 == 0 else -1            # the side its sensor's stone and branch are on
    toward = {1: "north", -1: "south"}     # a hook faces along the string, away from its block
    y0 = rise(k, n)

    def at(c, side=1):
        c = mirror(c, side)
        return (k, y0 + c[0], c[1])
    parts = [
        (at((SENSOR_Y, -2), s), "phasecraft:phase_block", None),
        (at((SENSOR_Y, -1), s), f"minecraft:tripwire_hook[attached=true,facing={toward[-s]},powered=false]", "N"),
        (at((SENSOR_Y, OUT), s), STONE, None),
        (at((SENSOR_Y, OUT - 1), s), f"minecraft:tripwire_hook[attached=true,facing={toward[s]},powered=false]", "N"),
    ]
    for z in range(0, OUT - 1):
        parts.append((at((SENSOR_Y, z), s), "minecraft:tripwire[attached=true,disarmed=false,east=false,north=true,"
                                             "powered=false,south=true,west=false]", None))
    for side in (1, -1):
        parts += [(at((LINE_Y - 1, LINE_Z), side), STONE, None), (at((LINE_Y, LINE_Z), side), WIRE, "N")]
    path = branch()
    for c, block in supports(path).items():
        parts.append((at(c, s), block, None))
    for c in path:
        parts.append((at(c, s), WIRE, "N"))
    if 0 <= k < n:
        rep = "minecraft:repeater[delay={},facing={},powered=false]"
        parts += [(at((LINE_Y - 1, 2)), GLASS, None), (at((LINE_Y, 2)), rep.format(DELAY, "south"), "N")]
        for z, d in ((-2, 1), (-1, 1), (0, DELAY - 2)):
            parts += [(at((LINE_Y - 1, z)), GLASS, None), (at((LINE_Y, z)), rep.format(d, "north"), "N")]
        parts += [(at(BASE), STONE, None),
                  (at(TORCH), "minecraft:redstone_torch[lit=true]", "F<N"),
                  (at(FEED), STONE, None)]
    return parts


def hidden_parts(n):
    """Every plane's parts, approach planes (no block) REACH past both ends."""
    out = []
    for k in range(-REACH, n + REACH):
        out += plane(k, n)
    return out


def shifters(n):
    return [((k, k, 0), "phasecraft:phase_shifter[powered=false]", "F") for k in range(n)]


def hooks(k, n):
    s = 1 if k % 2 == 0 else -1
    y = rise(k, n) + SENSOR_Y
    return [(k, y, -s), (k, y, (OUT - 1) * s)]


def simulate(n):
    """Checks the staircase in mcb's simulator: nothing at rest; someone at each step (or between two) gets exactly
    the steps within REACH, and after they leave nothing again. Returns the most game ticks a step took to appear."""
    from mcb.redstone import Sim
    from mcb.scene import Scene
    scene = Scene()
    for pos, state, _ in hidden_parts(n) + shifters(n):
        scene.set("hidden", pos, state)
    sim = Sim(scene)
    if sim.popped:
        sys.exit(f"parts with nothing to stand on: {sim.popped[:5]}")
    sim.run(40)
    everything = set(range(n))

    def held():
        return {p[0] for p in sim.powered_shifters()}
    if held() != everything:
        sys.exit(f"at rest, steps there: {sorted(everything - held())}")
    slowest = 0
    for where in [(j,) for j in range(-REACH - 1, n + REACH + 1)] + [(j, j + 1) for j in range(-REACH, n + REACH - 1)]:
        for j in where:
            for h in hooks(j, n) if -REACH <= j < n + REACH else []:
                sim.set_input(h, True)
        t0 = sim.tick
        sim.events.clear()
        sim.run(30)
        want = {k for k in everything if all(abs(k - j) > REACH for j in where)}
        if held() != want:
            sys.exit(f"someone at {where}: held {sorted(held())}, expected {sorted(want)}")
        slowest = max([slowest] + [t - t0 for t, *_ in sim.events])
        for j in where:
            for h in hooks(j, n) if -REACH <= j < n + REACH else []:
                sim.set_input(h, False)
        sim.run(30)
        if held() != everything:
            sys.exit(f"after someone left {where}: steps still there {sorted(everything - held())}")
    return slowest


# --- the scene: a meadow, the staircase climbing to a cliff's ledge, a lookout on top ---
NAME = "appearing_staircase"
STEPS = 12
TREAD = {"minecraft:stone_bricks": 5, "minecraft:mossy_stone_bricks": 2, "minecraft:cracked_stone_bricks": 1}
FACE = {"minecraft:stone": 8, "minecraft:andesite": 2, "minecraft:tuff": 1, "minecraft:coal_ore": 1}
CLIFF = (STEPS, STEPS + 11)               # x of the cliff, its top at y = STEPS
MARGINS = ((-12, -1, -16), (CLIFF[1] + 2, STEPS + 12, 16))


def pick(weights, pos):
    r = random.Random(hash(pos) & 0xFFFF).random() * sum(weights.values())
    for block, w in weights.items():
        r -= w
        if r < 0:
            return block
    return next(iter(weights))


def cliff_top(x, z):
    """The cliff's top at (x, z), or None off it: a rounded mass whose face at x = STEPS is straight where the
    staircase meets it."""
    if not CLIFF[0] <= x <= CLIFF[1] or abs(z) > 12:
        return None
    edge = 12 - (abs(z) // 3 if x == CLIFF[0] else 0) - max(0, x - CLIFF[1] + 3) * 2
    if abs(z) > edge:
        return None
    return STEPS - (1 if (x * 7 + z * 3) % 11 == 0 and abs(z) > 2 else 0)


def visible(at):
    """The cliff (rough face, grass on top), a lookout of stone bricks on the ledge, a path to the first step, trees,
    the staircase's blocks."""
    lines = []
    blocks = {}
    for x in range(CLIFF[0], CLIFF[1] + 1):
        for z in range(-12, 13):
            top = cliff_top(x, z)
            if top is None:
                continue
            lines.append(f"fill visible {at((x, -1, z))} {at((x, top - 1, z))} minecraft:stone")
            blocks[(x, top, z)] = "minecraft:grass_block"
            for y in range(0, top):
                b = pick(FACE, (x, y, z))
                if b != "minecraft:stone" and any(cliff_top(x + dx, z + dz) is None for dx, dz in
                                                  ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    blocks[(x, y, z)] = b
    # the lookout: a low ring of wall round a lantern, open towards the stairs
    lx, lz = STEPS + 5, 0
    for dx in range(-2, 3):
        for dz in range(-2, 3):
            if max(abs(dx), abs(dz)) == 2 and not (dx == -2 and abs(dz) <= 1):
                blocks[(lx + dx, STEPS + 1, lz + dz)] = "minecraft:stone_brick_wall"
            blocks[(lx + dx, STEPS, lz + dz)] = "minecraft:stone_bricks"
    blocks[(lx, STEPS + 1, lz)] = "minecraft:stone_brick_wall"
    blocks[(lx, STEPS + 2, lz)] = "minecraft:lantern[hanging=false]"
    for x in range(STEPS, lx - 2):        # a path from the top step to the lookout
        blocks[(x, STEPS, 0)] = "minecraft:dirt_path"
    for x in range(-11, 0):               # and up to the first step
        blocks[(x, -1, 0)] = "minecraft:dirt_path"
    for z in (-1, 1):                     # lanterns on posts at the foot
        blocks[(-1, 0, z)] = "minecraft:stone_brick_wall"
        blocks[(-1, 1, z)] = "minecraft:lantern[hanging=false]"
    for x, z, ground in ((-7, -6, -1), (-9, 5, -1), (-4, 9, -1), (-10, -11, -1), (CLIFF[0] + 3, 7, STEPS),
                         (CLIFF[0] + 7, -6, STEPS)):
        h = 5 + (x * 7 + z) % 3
        blocks[(x, ground, z)] = "minecraft:dirt"
        for y in range(ground + 1, ground + h + 1):
            blocks[(x, y, z)] = "minecraft:spruce_log[axis=y]"
        for y in range(ground + 3, ground + h + 3):
            r = max(0, (ground + h + 2 - y) // 2)
            for dx in range(-r, r + 1):
                for dz in range(-r, r + 1):
                    if (dx or dz) and abs(dx) + abs(dz) <= r + 1:
                        blocks.setdefault((x + dx, y, z + dz), "minecraft:spruce_leaves[persistent=true]")
        blocks[(x, ground + h + 3, z)] = "minecraft:spruce_leaves[persistent=true]"
    for k in range(STEPS):                # the steps: the shifters take these when placed (nobody near)
        blocks[(k, k, 0)] = pick(TREAD, (k, k, 0))
    lines += [f"visible {at(p)} {b}" for p, b in blocks.items()]
    return lines


def write():
    parts = hidden_parts(STEPS) + shifters(STEPS)
    (mx0, my0, mz0), (mx1, my1, mz1) = MARGINS
    xs = [p[0] for p, *_ in parts] + [mx0, mx1]
    ys = [p[1] for p, *_ in parts] + [my0, my1]
    zs = [p[2] for p, *_ in parts] + [mz0, mz1]
    ox, oy, oz = -min(xs), -min(ys), -min(zs)
    size = (max(xs) + ox + 1, max(ys) + oy + 1, max(zs) + oz + 1)

    def at(p):
        return f"{p[0] + ox} {p[1] + oy} {p[2] + oz}"
    header = [
        "The appearing staircase (AppearingStaircaseGameTests, examples station 26). Written by tools/gen_stairs.py:",
        "do not edit, change the generator and run it again; check with: python3 tools/redstone_check.py <this file>",
        "",
        f"A staircase of {STEPS} steps up to a cliff's ledge, the first at x = {ox}, y = {oy}, z = {oz}, each one up and",
        f"one along. A step is there only while someone is within {REACH} steps of it: the stairs appear ahead and vanish",
        "behind. Every step is held by a hidden phase shifter; hidden strings at head height over each step (and the",
        "approaches) sense who is near. Signals: N near (the lines and their branches, the repeaters); F far (the",
        "torches, shifters).",
    ]
    lines = [f"# {h}" if h else "#" for h in header] + [f"size {size[0]} {size[1]} {size[2]}"]
    lines += visible(at)
    for pos, state, tag in parts:
        lines.append(f"hidden {at(pos)} {state}" + (f" @{tag}" if tag else ""))
    out = ROOT / f"src/gametest/resources/contraptions/{NAME}.txt"
    out.write_text("\n".join(lines) + "\n")
    structure = ROOT / f"src/gametest/resources/data/phasecraft-test/gametest/structure/{NAME}.snbt"
    structure.write_text("{\n    DataVersion: 2730,\n    size: [%d, %d, %d],\n    data: [],\n    palette: []\n}\n" % size)
    from mcb.report import hidden_size
    print(f"wrote {out.relative_to(ROOT)}: area {size[0]}x{size[1]}x{size[2]}; "
          + hidden_size((pos, state) for pos, state, _ in parts))


if __name__ == "__main__":
    print(f"simulated: a step appears within {simulate(STEPS)} game ticks of someone coming within {REACH} of it")
    write()
