#!/usr/bin/env python3
"""A generator for paths that appear around the player: a bridge whose blocks are there only within REACH steps of
someone on it (or coming to it), appearing ahead as they walk and vanishing behind them.

Every step's block is held by a hidden phase shifter at its spot; the logic is hidden, each step's in its own plane
(x = k). Neighbouring planes touch, so a part in one can leak into the next: wire at the same spot in two neighbouring
planes joins, as does wire a block above or below (a step across). So no two neighbouring planes may have wire near
the same (y, z), except two lines along the whole path, meant to join.

Per step k, in its plane (y up, z across; the step's block at y = 0, z = 0):
- The sensor: a hidden string across the walkway at head height (y = 2) between two hooks, one on a phase block (so
  visible players trip it), the other on a stone, H, which the hook powers strongly when tripped. At head height so a
  jump does not release it: feet leave a string at y = 1 for most of a jump; the head leaves one at y = 2 only for a
  few ticks at the top, which the consumer's repeater (DELAY) rides out.
- Two lines of wire along the whole path, one on each side (y = -3, z = +-2). Even steps feed the south line, odd
  steps the north one: the string runs out to that side, 12 blocks, to H, and a branch of wire BRANCH blocks long runs
  from H back to the line. Dust fades one level a block: a tripped sensor puts REACH + 1 into its line at its step,
  so the line is powered exactly within REACH steps of it. (Why so far out: wire laid in one plane cannot turn back
  in z, a turn needs a block that is both stone and not, so a branch of BRANCH blocks spans BRANCH columns.)
  Branches of even steps are two planes apart, and the odd ones' are on the other side: none touch.
- The consumer: each line powers a repeater, both into a stone under a torch, the torch powers the stone under the
  shifter. So the torch, and with it the shifter, is off (the block there) when someone is near; on (the block held)
  when not.

Approach steps (sensors and branches, no block) reach REACH steps past both ends, so the path appears as someone
comes near.
"""

import pathlib
import random
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

REACH = 3                 # steps around a player that are there
BRANCH = 14 - REACH       # wire from H to its line: 15 at the first block, REACH + 2 at the last, REACH + 1 in the line
DELAY = 3                 # the consumer's repeaters, redstone ticks: a head-height string is released for up to 4
                          # game ticks at the top of a jump; the repeaters ride that out
OUT = BRANCH + 2          # z of H: the branch's first block is under H, the next one step down and one in

WIRE = "minecraft:redstone_wire[east=side,north=side,south=side,west=side]"
STONE, GLASS = "minecraft:stone", "minecraft:glass"

# the template, (y, z) in a step's plane, for an even step (sensor and branch south); odd steps are mirrored in z
SHIFTER, FEED, TORCH, BASE = (0, 0), (-1, 0), (-2, 0), (-3, 0)
LINE_Y = -3
SENSOR_Y = 2


def mirror(c, s):
    return (c[0], c[1] * s)


def branch(ends=(-2, -3, -4)):
    """The branch from H (z = OUT) to the line (z = 2), as (y, z): under H, a step down, then on towards the line one
    column a block, stepping down as late as it can, ending beside the line (y = -3), or a step above or below it."""
    first, second = (SENSOR_Y - 1, OUT), (SENSOR_Y - 2, OUT - 1)
    for end in ends:
        drop = second[0] - end            # steps down still to take
        flats = (OUT - 1 - 3) - drop      # columns from second to the last (z = 3), less the steps
        if drop < 0 or flats < 0:
            continue
        path = [first, second]
        y = second[0]
        for z in range(OUT - 2, 2, -1):
            if flats > 0:
                flats -= 1
            else:
                y -= 1
            path.append((y, z))
        if path[-1][0] == end:
            return path
    raise ValueError("no branch shape")


def supports(path):
    """The block under each wire: stone under the higher of a step (vanilla carries a step only over a conductor),
    glass elsewhere. The last one steps into the line, at (y = -3, z = 2), if it is not level with it."""
    out = {}
    for i, c in enumerate(path):
        nxt = path[i + 1] if i + 1 < len(path) else (LINE_Y, 2)
        prv = path[i - 1] if i else None
        upper = nxt[0] < c[0] or (prv is not None and prv[0] < c[0])
        out[(c[0] - 1, c[1])] = STONE if upper else GLASS
    return out


def plane(k, consumer, y0=0):
    """The hidden parts of step k's plane: [(pos, state, tag)], supports before what stands on them."""
    s = 1 if k % 2 == 0 else -1            # the side its sensor's stone and branch are on
    toward = {1: "north", -1: "south"}     # a hook faces along the string, away from its block

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
        parts += [(at((LINE_Y - 1, 2), side), STONE, None), (at((LINE_Y, 2), side), WIRE, "N")]
    path = branch()
    for c, block in supports(path).items():
        parts.append((at(c, s), block, None))
    for c in path:
        parts.append((at(c, s), WIRE, "N"))
    if consumer:
        for side in (1, -1):
            facing = "south" if side == 1 else "north"          # a repeater's facing is its input side
            parts += [(at((LINE_Y - 1, 1), side), GLASS, None),
                      (at((LINE_Y, 1), side), f"minecraft:repeater[delay={DELAY},facing={facing},powered=false]", "N")]
        parts += [
            (at(BASE), STONE, None),
            (at(TORCH), "minecraft:redstone_torch[lit=true]", "F<N"),
            (at(FEED), STONE, None),
        ]
    return parts


def hidden_parts(n):
    """Every plane's parts, approach planes (no block) REACH past both ends."""
    out = []
    for k in range(-REACH, n + REACH):
        out += plane(k, 0 <= k < n)
    return out


def hooks(k):
    s = 1 if k % 2 == 0 else -1
    return [(k, SENSOR_Y, -s), (k, SENSOR_Y, (OUT - 1) * s)]


def simulate(n):
    """Checks the path in mcb's simulator: nothing at rest; someone at each step (or between two) gets exactly the
    steps within REACH, and after they leave nothing again. Returns the most game ticks a step took to appear."""
    from mcb.redstone import Sim
    from mcb.scene import Scene
    scene = Scene()
    for pos, state, _ in hidden_parts(n):
        scene.set("hidden", pos, state)
    for k in range(n):
        scene.set("hidden", (k, 0, 0), "phasecraft:phase_shifter[powered=false]")
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
            for h in hooks(j) if -REACH <= j < n + REACH else []:
                sim.set_input(h, True)
        t0 = sim.tick
        sim.events.clear()
        sim.run(30)
        want = {k for k in everything if all(abs(k - j) > REACH for j in where)}
        if held() != want:
            sys.exit(f"someone at {where}: held {sorted(held())}, expected {sorted(want)}")
        slowest = max([slowest] + [t - t0 for t, *_ in sim.events])
        for j in where:
            for h in hooks(j) if -REACH <= j < n + REACH else []:
                sim.set_input(h, False)
        sim.run(30)
        if held() != everything:
            sys.exit(f"after someone left {where}: steps still there {sorted(everything - held())}")
    return slowest


# --- the appearing bridge: a chasm and a bridge of STEPS across it ---
NAME = "appearing_bridge"
STEPS = 14
BRIDGE = {"minecraft:stone_bricks": 5, "minecraft:mossy_stone_bricks": 2, "minecraft:cracked_stone_bricks": 1}
FACE = {"minecraft:stone": 8, "minecraft:andesite": 2, "minecraft:tuff": 1, "minecraft:coal_ore": 1}
DEPTH = 8                 # the chasm's water, below the bridge (the examples world ends 9 below it)
MARGINS = ((-12, -DEPTH - 1, -15), (STEPS + 11, 10, 15))


def pick(weights, pos):
    r = random.Random(hash(pos) & 0xFFFF).random() * sum(weights.values())
    for block, w in weights.items():
        r -= w
        if r < 0:
            return block
    return next(iter(weights))


def visible(at):
    """Two cliffs, the chasm between with a river at the bottom, paths to the edges, trees, the bridge's blocks."""
    (x0, _, z0), (x1, _, z1) = MARGINS
    lines = []
    for lo, hi in ((x0, -1), (STEPS, x1)):
        lines += [f"fill visible {at((lo, -DEPTH - 1, z0))} {at((hi, -1, z1))} minecraft:stone",
                  f"fill visible {at((lo, 0, z0))} {at((hi, 0, z1))} minecraft:grass_block"]
    lines += [f"fill visible {at((0, -DEPTH - 1, z0))} {at((STEPS - 1, -DEPTH - 1, z1))} minecraft:gravel",
              f"fill visible {at((0, -DEPTH, z0))} {at((STEPS - 1, -DEPTH, z1))} minecraft:water"]
    blocks = {}
    for x in (-1, STEPS):                 # the cliffs' faces
        for y in range(-DEPTH, 0):
            for z in range(z0, z1 + 1):
                b = pick(FACE, (x, y, z))
                if b != "minecraft:stone":
                    blocks[(x, y, z)] = b
    for x in list(range(x0, -1)) + list(range(STEPS + 1, x1 + 1)):     # paths to the edges
        blocks[(x, 0, 0)] = "minecraft:dirt_path"
    for x, z in ((-7, -6), (-9, 5), (-4, 9), (-10, -11), (STEPS + 5, 6), (STEPS + 8, -5), (STEPS + 3, -10),
                 (STEPS + 9, 10)):
        h = 5 + (x * 7 + z) % 3
        blocks[(x, 0, z)] = "minecraft:dirt"
        for y in range(1, h + 1):
            blocks[(x, y, z)] = "minecraft:spruce_log[axis=y]"
        for y in range(3, h + 3):
            r = max(0, (h + 2 - y) // 2)
            for dx in range(-r, r + 1):
                for dz in range(-r, r + 1):
                    if (dx or dz) and abs(dx) + abs(dz) <= r + 1:
                        blocks.setdefault((x + dx, y, z + dz), "minecraft:spruce_leaves[persistent=true]")
        blocks[(x, h + 3, z)] = "minecraft:spruce_leaves[persistent=true]"
    for x in (-1, STEPS):                 # lanterns on posts at the edges
        for z in (-1, 1):
            blocks[(x, 1, z)] = "minecraft:stone_brick_wall"
            blocks[(x, 2, z)] = "minecraft:lantern[hanging=false]"
    for k in range(STEPS):                # the bridge: the shifters take these when placed (nobody near)
        blocks[(k, 0, 0)] = pick(BRIDGE, (k, 0, 0))
    lines += [f"visible {at(p)} {b}" for p, b in blocks.items()]
    return lines


def write():
    parts = hidden_parts(STEPS) + [((k, 0, 0), "phasecraft:phase_shifter[powered=false]", "F") for k in range(STEPS)]
    (mx0, my0, mz0), (mx1, my1, mz1) = MARGINS
    xs = [p[0] for p, *_ in parts] + [mx0, mx1]
    ys = [p[1] for p, *_ in parts] + [my0, my1]
    zs = [p[2] for p, *_ in parts] + [mz0, mz1]
    ox, oy, oz = -min(xs), -min(ys), -min(zs)
    size = (max(xs) + ox + 1, max(ys) + oy + 1, max(zs) + oz + 1)

    def at(p):
        return f"{p[0] + ox} {p[1] + oy} {p[2] + oz}"
    header = [
        "The appearing bridge (AppearingBridgeGameTests, examples station 25). Written by tools/gen_path.py: do not",
        "edit, change the generator and run it again; check with: python3 tools/redstone_check.py <this file>",
        "",
        f"A bridge of {STEPS} blocks across a chasm, from x = {ox} to {ox + STEPS - 1}, y = {oy}, z = {oz}. A block is there",
        f"only while someone is within {REACH} steps of it: the bridge appears ahead and vanishes behind. Every block is",
        "held by a hidden phase shifter; hidden strings at head height over each step (and the approaches) sense",
        "who is near. Signals: N near (the lines and their branches, the repeaters); F far (the torches, shifters).",
    ]
    lines = [f"# {h}" if h else "#" for h in header] + [f"size {size[0]} {size[1]} {size[2]}"]
    lines += visible(lambda p: at(p))
    for pos, state, tag in parts:
        lines.append(f"hidden {at(pos)} {state}" + (f" @{tag}" if tag else ""))
    out = ROOT / f"src/gametest/resources/contraptions/{NAME}.txt"
    out.write_text("\n".join(lines) + "\n")
    structure = ROOT / f"src/gametest/resources/data/phasecraft-test/gametest/structure/{NAME}.snbt"
    structure.write_text("{\n    DataVersion: 2730,\n    size: [%d, %d, %d],\n    data: [],\n    palette: []\n}\n" % size)
    print(f"wrote {out.relative_to(ROOT)}: size {size[0]}x{size[1]}x{size[2]}, {len(parts)} hidden blocks")


if __name__ == "__main__":
    print(f"simulated: a step appears within {simulate(STEPS)} game ticks of someone coming within {REACH} of it")
    write()
