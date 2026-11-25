#!/usr/bin/env python3
"""Writes the mountain door (examples station 24, MountainDoorGameTests): contraptions/mountain_door.txt.

Usage: python3 tools/gen_mountain.py && python3 tools/redstone_check.py src/gametest/resources/contraptions/mountain_door.txt

An arched door, 7 wide and 9 high (57 blocks), at the back of a gorge cut into a mountain. Flip the lever: the door
goes like a clock hand sweeping round, from twelve o'clock clockwise (as you face it), one eighth every STEP redstone
ticks; flip it back and it winds shut the other way. Behind it a tunnel leads to a vault. Every door block is held by a
hidden phase shifter at its own spot. The wiring: below, built by hand (not tools/gen_door.py).

Generator space: the door in the mountain's face at z = 0, x = -3..3, y = 0..8, the gorge's floor at y = -1 in front
(z < 0); the player faces it looking south, so the player's right is west (-x). The mountain rises from y = -1.
"""

import math
import random
import sys

import gen_door
from mcb.shapes import _noise

ROOT = gen_door.ROOT

STEP = 2
CY = 4                                    # the door's middle row: the clock's centre is (0, CY)
DOOR = "minecraft:polished_blackstone_bricks"
FRAME = "minecraft:stone_bricks"
CELLS = [(x, y) for y in range(9) for x in range(-3, 4) if y <= 5 or x * x + (y - 5) ** 2 <= 12.25]
SECTORS = 8


def ring(x, y):
    """The eighth of the clock the cell is in: 0 from twelve o'clock, clockwise as seen from the front (to the
    player's right, -x). Whole numbers only, so the GameTest computes exactly the same."""
    u, v = -x, y - CY
    if u == 0 and v == 0:
        return 0
    if u >= 0 and v > 0:
        return 0 if u < v else 1
    if u > 0 and v <= 0:
        return 2 if -v < u else 3
    if u <= 0 and v < 0:
        return 4 if -u < -v else 5
    return 6 if v < -u else 7


TOP = SECTORS - 1

# --- the mountain ---
X0, X1, Z0, Z1 = -21, 21, -9, 28          # its footprint
GORGE = 6                                 # the gorge in front of the door: |x| <= GORGE, z < 0, floor at y = -1
TUNNEL = range(1, 9)                      # z of the tunnel behind the door (the door's shape)
VAULT = ((-5, 0, 9), (5, 5, 15))          # the room at the tunnel's end
FACE = {"minecraft:stone": 6, "minecraft:andesite": 2, "minecraft:cobblestone": 1, "minecraft:tuff": 1}


def height(x, z):
    """The mountain's top at (x, z): a cone round a peak behind the door, roughened; the gorge cut out in front."""
    if abs(x) <= GORGE and z < 0:
        return -1
    noise = _noise(7)
    r = math.hypot(x, (z - 9) * 1.1)
    h = 32 - 1.5 * r + 3 * (noise(x, z, 6) - 0.5) + 1.5 * (noise(x + 50, z, 3) - 0.5)
    if abs(x) <= GORGE + 1 and 0 <= z <= 3:
        h = max(h, 11)                    # the cliff over the door
    return max(-1, int(round(h)))


def pick(weights, pos):
    r = random.Random(hash(pos) & 0xFFFF).random() * sum(weights.values())
    for block, w in weights.items():
        r -= w
        if r < 0:
            return block
    return next(iter(weights))


def surface(x, z, h):
    """The top block: snow up high, grass where the slope is gentle, bare rock where it is steep."""
    steep = max(abs(h - height(x + dx, z + dz)) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
    if h >= 22:
        return "minecraft:snow_block"
    if steep <= 1:
        return "minecraft:grass_block"
    return pick({"minecraft:stone": 3, "minecraft:andesite": 1, "minecraft:gravel": 1}, (x, z))


def inside(x, y, z):
    """The hollow behind the door: the tunnel, in the door's shape, and the vault."""
    (vx0, vy0, vz0), (vx1, vy1, vz1) = VAULT
    return (z in TUNNEL and (x, y) in CELLS) or (vx0 <= x <= vx1 and vy0 <= y <= vy1 and vz0 <= z <= vz1)


def visible(at):
    lines = []
    blocks = {}                           # single blocks, after the columns
    for x in range(X0, X1 + 1):
        for z in range(Z0, Z1 + 1):
            h = height(x, z)
            if h > -1:
                lines.append(f"fill visible {at((x, -1, z))} {at((x, h - 1, z))} minecraft:stone")
            blocks[(x, h, z)] = surface(x, z, h) if h > -1 else "minecraft:grass_block"
    # the cliff face round the door and the gorge's walls: rough stone
    for x in range(X0, X1 + 1):
        for z in range(Z0, 4):
            for y in range(0, height(x, z)):
                b = pick(FACE, (x, y, z))
                if b != "minecraft:stone" and any(height(x + dx, z + dz) < y for dx, dz in ((1, 0), (-1, 0), (0, -1))):
                    blocks.setdefault((x, y, z), b)     # (stone is there already)
    # the frame round the door, flush in the face; a keystone on top
    for x, y in CELLS:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (1, 1), (-1, 1)):
            p = (x + dx, y + dy)
            if p not in CELLS:
                blocks[(p[0], p[1], 0)] = FRAME
    blocks[(0, 9, 0)] = "minecraft:chiseled_stone_bricks"
    # the tunnel and the vault: hollow, lined with bricks, a polished floor
    hollow = [(x, y, z) for x in range(-5, 6) for y in range(0, 9) for z in range(1, 16) if inside(x, y, z)]
    for x, y, z in hollow:
        for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, 0, 1)):
            q = (x + d[0], y + d[1], z + d[2])
            if not inside(*q) and q[2] > 0:
                blocks[q] = pick({FRAME: 4, "minecraft:mossy_stone_bricks": 1, "minecraft:cracked_stone_bricks": 1}, q)
        blocks[(x, -1, z)] = "minecraft:polished_andesite"
    for p in hollow:
        blocks[p] = "minecraft:air"
    # light: lanterns hanging in the tunnel, on the vault's floor; the treasure
    for z in (3, 7):
        blocks[(0, 8, z)] = "minecraft:lantern[hanging=true]"
    for x in (-4, 4):
        blocks[(x, 0, 14)] = "minecraft:lantern[hanging=false]"
        blocks[(x, 0, 10)] = "minecraft:lantern[hanging=false]"
    blocks[(0, 0, 14)] = "minecraft:chest[facing=north]"
    for x in (-2, -1, 1, 2):
        blocks[(x, 0, 15 if abs(x) == 1 else 14)] = "minecraft:gold_block"
    blocks[(1, 1, 15)] = "minecraft:gold_block"
    # in the gorge: lanterns on posts either side of the door, a path to it
    for x in (-5, 5):
        blocks[(x, 0, -1)] = "minecraft:stone_brick_wall"
        blocks[(x, 1, -1)] = "minecraft:lantern[hanging=false]"
    for z in range(Z0, 0):
        for x in (-1, 0, 1):
            blocks[(x, -1, z)] = "minecraft:dirt_path"
    # spruces on the lower slopes, clear of the gorge
    rng = random.Random(3)
    trunks = []
    for _ in range(60):
        x, z = rng.randint(X0 + 2, X1 - 2), rng.randint(Z0 + 2, Z1 - 2)
        h = height(x, z)
        if abs(x) <= GORGE + 3 and z < 6 or not 0 <= h <= 9 or surface(x, z, h) != "minecraft:grass_block":
            continue
        if any(abs(x - a) + abs(z - b) < 5 for a, b in trunks):
            continue
        trunks.append((x, z))
        tall = rng.randint(5, 7)
        blocks[(x, h, z)] = "minecraft:dirt"
        for y in range(h + 1, h + tall + 1):
            blocks[(x, y, z)] = "minecraft:spruce_log[axis=y]"
        for level in range(2, tall + 2):
            r = max(0, (tall + 1 - level) // 2)
            for dx in range(-r, r + 1):
                for dz in range(-r, r + 1):
                    q = (x + dx, h + level, z + dz)
                    if (dx or dz) and abs(dx) + abs(dz) <= r + 1 and q[1] > height(q[0], q[2]):
                        blocks.setdefault(q, "minecraft:spruce_leaves[persistent=true]")
        blocks[(x, h + tall + 1, z)] = "minecraft:spruce_leaves[persistent=true]"
    # the door's own blocks, which the shifters take when they are placed if the door is to be open
    for x, y in CELLS:
        blocks[(x, y, 0)] = DOOR
    lines += [f"visible {at(p)} {b}" for p, b in blocks.items()]
    return lines


# --- the wiring (all hidden) ---------------------------------------------------------------------------------------
# Hand-built (a Fable 5.2 agent's design, 2026-11-24; checked in the simulator and on the dev server tick by tick).
#
# The face. Even rows are fed from behind (z > 0), odd rows from the front (z < 0). Each door block: a stone at
# z = +-1 (it powers the shifter; the shifter itself is never strongly powered), then repeaters, then one wire per run
# of one eighth's blocks in a row. That wire is at depth 3 for even eighths and 5 for odd ones, so neighbouring runs
# never have wire side by side. Feed lanes run on the free level between two fed rows of one eighth: their last wire
# is read by the run above through glass (a wire step on glass carries a signal up only) and by the run below
# through a stone under it. Every block is 4 redstone ticks from its lane's end.
# The controllers. Opening delay plus closing delay is the same for every eighth, so eighths pair up, j with 7 - j:
# with X = L delayed 2j and T = L delayed 14 - 2j, eighth j is X OR T (on early, off late) and eighth 7 - j is
# X AND T (on late, off early). One plane per pair, behind the door for the even rows' lanes and in front for the odd
# rows'; two glass ladders climb through the planes, one carrying L and one L delayed 8.
# The lever's signal: a phase port under the lever, down to y = -3 and under the door to both sides.

GLASS, STONE, WIRE = "minecraft:glass", "minecraft:stone", "minecraft:redstone_wire"
SHIFTER = "phasecraft:phase_shifter"
S, N, E, W = "south", "north", "east", "west"
DEPTH = {"A": 3, "B": 5}                  # the depth of a run's wire: even eighths A, odd B
# feed lanes: (eighth, x, level, how the run below reads it: True a stone under its last wire, False none, "plain"
# a lane at a run's own level), behind and in front
BACK = [(3, -2, 1, True), (4, 1, 1, True), (2, -3, 3, True), (5, 3, 3, True),
        (1, -3, 5, False), (6, 3, 5, True), (0, -1, 7, True), (7, 1, 7, True)]
FRONT = [(3, -3, 0, False), (4, 2, 0, False), (2, -3, 3, "plain"), (5, 3, 2, True),
         (1, -2, 4, False), (6, 3, 5, "plain"), (0, 0, 6, True), (7, 1, 6, True)]
LADDERS = {1: [], -1: [(-1, 1), (0, 1)]}  # runs that read the run two rows down through a glass step
LEVER = (5, 0, -3)                        # in the gorge, left of the path as you face the door


class Parts:
    """Hidden parts by position, in placement order (supports first); the same part twice is fine, two are not."""

    def __init__(self):
        self.b = {}

    def put(self, p, state):
        p = tuple(p)
        if p in self.b and self.b[p] != state:
            raise ValueError(f"clash at {p}: {self.b[p]} vs {state}")
        self.b.setdefault(p, state)

    def on_glass(self, p, state):
        below = (p[0], p[1] - 1, p[2])
        if below not in self.b:
            self.put(below, GLASS)
        self.put(p, state)

    def rep(self, p, facing, delay=1):
        self.on_glass(p, f"minecraft:repeater[delay={delay},facing={facing}]")

    def wire(self, p):
        self.on_glass(p, WIRE)

    def torch(self, p, facing):
        self.put(p, f"minecraft:redstone_wall_torch[facing={facing}]")


def cell_class(x, y):
    return "A" if ring(x, y) % 2 == 0 else "B"


def face(b):
    """The door's blocks, their runs and the feed lanes. Returns the lanes' ends: {(side, eighth): (x, level, z)}."""
    ends = {}
    for x, y in CELLS:
        s = 1 if y % 2 == 0 else -1
        inp = S if s > 0 else N
        r = ring(x, y)
        b.put((x, y, s), STONE)
        if (x, y) == (0, CY):             # the centre (eighth 0, between 2 and 6): it reads eighth 0's row-6 wire
            b.rep((x, y, 2), inp)
            b.rep((x, y, 3), inp)
            b.put((0, CY, 4), STONE)
            b.put((0, CY + 1, 4), WIRE)   # on that stone; it reads the wire over the stone below the row-6 wire
            b.put((0, CY + 1, 3), STONE)
            continue
        for z in range(2, DEPTH[cell_class(x, y)]):
            b.rep((x, y, s * z), inp, 2 if (r == 0 and s > 0 and z == 2) else 1)
        b.wire((x, y, s * DEPTH[cell_class(x, y)]))
    for s, lanes in ((1, BACK), (-1, FRONT)):
        inp = S if s > 0 else N
        for r, x, lv, lower in lanes:
            c = "A" if r % 2 == 0 else "B"
            zm = DEPTH[c] + 1
            if lower == "plain":
                for z in range(zm, 7):
                    b.rep((x, lv, s * z), inp)
                b.wire((x, lv, s * 7))
                ends[(s, r)] = (x, lv, s * 7)
                continue
            if lower:
                b.put((x, lv - 1, s * zm), STONE)
            b.wire((x, lv, s * zm))
            reps = [(zm + 1, 1), (zm + 2, 1 if (r == 0 and s > 0) else 2)] if c == "A" else [(zm + 1, 1)]
            for z, d in reps:
                b.rep((x, lv, s * z), inp, d)
            end = reps[-1][0] + 1
            b.wire((x, lv, s * end))
            ends[(s, r)] = (x, lv, s * end)
        for x, y in LADDERS[s]:
            zl = DEPTH[cell_class(x, y)] + 1
            b.put((x, y, s * zl), GLASS)
            b.wire((x, y + 1, s * zl))
    return ends


def station(b, s, lv, j, left_end, right_end):
    """The plane at level lv for the pair (j, 7 - j), on side s: rails along x at |z| = 10 (X) and 16 (T); the OR
    to eighth j's lane on the left, the AND to eighth 7 - j's on the right."""
    def P(x, z):
        return (x, lv, s * z)
    away, door = (S, N) if s > 0 else (N, S)
    for z in (10, 16):
        b.rep(P(-2, z), E, 2)             # into the OR wire at x = -3
        b.wire(P(-1, z))
        b.wire(P(0, z))                   # the rail; its last wire points into the stone
        b.put(P(1, z), STONE)
    for z in range(10, 17):               # the OR wire
        b.wire(P(-3, z))
    b.torch(P(1, 11), away)               # NOT X
    b.torch(P(1, 15), door)               # NOT T
    for z in (12, 13, 14):
        b.wire(P(1, z))
    b.wire(P(2, 13))
    b.put(P(3, 13), STONE)
    b.torch(P(3, 12), door)               # X AND T
    dx, dt = 2 * j, 6 - 2 * j
    for z, d in ((12, max(0, dx - 4)), (11, min(dx, 4))):   # X: from the L ladder (tapped at (-1, 13)), delay 2j
        b.rep(P(-1, z), away, d) if d else b.wire(P(-1, z))
    b.wire(P(-1, 13))
    b.rep(P(0, 18), E, dt - 4) if dt > 4 else b.wire(P(0, 18))   # T: from the L+8 ladder (at (1, 18)), 6 - 2j more
    b.wire(P(-1, 18))
    b.rep(P(-1, 17), away, min(dt, 4)) if dt else b.wire(P(-1, 17))
    b.wire(P(1, 18))
    # out to the lanes' ends (an end one level up reads the last wire from below)
    lx, ly, lz = left_end
    lz = abs(lz) + (ly - lv)
    path = {-3: [(-3, z) for z in range(9, lz - 1, -1)],
            -2: [(-3, 9), (-2, 9), (-2, 8)],
            -1: [(-3, 9), (-3, 8), (-2, 8), (-1, 8), (-1, 7)],
            0: [(-3, 9), (-3, 8), (-3, 7), (-2, 7), (-1, 7), (0, 7)]}[lx]
    for x, z in path:
        b.wire(P(x, z))
    rx, ry, rz = right_end
    rz = abs(rz) + (ry - lv)
    path = [(3, 11), (3, 10), (3, 9)] + ([(3, z) for z in range(8, rz - 1, -1)] if rx == 3 else
                                         [(2, 9)] + [(rx, z) for z in range(9, rz - 1, -1)] if rx == 2 else
                                         [(2, 9), (1, 9)] + [(1, z) for z in range(8, rz - 1, -1)])
    for x, z in path:
        b.wire(P(x, z))


def controller(b, ends, s, top):
    """One side's controller: planes at top, top - 2, top - 4, top - 6; its foot two levels under the lowest; L
    reaches it along y = -3 from under the door."""
    for j in range(4):
        lv = top - 2 * j
        station(b, s, lv, j, ends[(s, j)], ends[(s, 7 - j)])
        if j:                             # the ladders' steps up to the plane above
            b.on_glass((-1, lv + 1, s * 14), WIRE)
            b.on_glass((2, lv + 1, s * 18), WIRE)
    bl = top - 8
    door = N if s > 0 else S
    b.on_glass((-1, bl + 1, s * 14), WIRE)    # the L ladder's foot
    b.wire((-1, bl, s * 13))
    b.rep((-1, bl, s * 12), door, 1)
    b.wire((-1, bl, s * 11))
    b.rep((0, bl, s * 13), W, 4)
    b.rep((1, bl, s * 13), W, 4)              # L delayed 8, to the other ladder's foot
    for z in range(13, 18):
        b.wire((2, bl, s * z))
    b.on_glass((2, bl + 1, s * 18), WIRE)
    y, z = bl, 10
    while y > -3:
        b.wire((-1, y, s * z))
        y, z = y - 1, z - 1
    for zz in range(z, 0, -1):
        b.wire((-1, -3, s * zz))


def lever_line(b):
    """The phase port under the lever; a wire staircase down to y = -3 at z = 0, along under the door, and a repeater
    into a stone between the two sides' lines (a strongly powered block gives both of them full strength)."""
    lx, ly, lz = LEVER
    port = (lx, ly - 1, lz)
    b.put(port, "phasecraft:phase_port")
    y = ly - 1
    for z in range(lz + 1, 1):            # down a step per block (stone under each upper wire), to y = -3 at z = 0
        b.put((lx, y - 1, z), STONE if z < 0 else GLASS)
        b.put((lx, y, z), WIRE)
        y -= 1
    y += 1
    for x in range(lx - 1, 0, -1):
        b.wire((x, y, 0))
    b.rep((0, y, 0), E)
    b.put((-1, y, 0), STONE)
    assert y == -3, y
    return port


def build():
    b = Parts()
    port = lever_line(b)
    ends = face(b)
    controller(b, ends, 1, 7)
    controller(b, ends, -1, 6)
    return b, port


# --- settling, signals, the layout file ----------------------------------------------------------------------------

def scene_of(b):
    from mcb.scene import Scene
    scene = Scene()
    for p, state in b.b.items():
        scene.set("hidden", p, state)
    for x, y in CELLS:
        scene.set("hidden", (x, y, 0), SHIFTER)
    return scene


def history(b, port, phase=80):
    """Settles the wiring (lever off) and flips the lever on, off, on, off. Returns the settled simulator state and
    every part's on/off history, plus each diode's and torch's input history."""
    from mcb.redstone import Sim
    sim = Sim(scene_of(b))
    if sim.popped:
        sys.exit(f"parts with nothing to stand on: {sim.popped[:5]}")
    sim.run(100)
    settled = {p: (k, dict(props)) for p, (k, props) in sim.parts.items()}
    power = dict(sim.wire_power)
    if sim.powered_shifters() or sim.queue:
        sys.exit(f"not at rest: {sorted(sim.powered_shifters())[:5]}")
    out, inp = {}, {}
    port_on = []
    for flip in range(4):
        sim.set_input(port, flip % 2 == 0)
        for _ in range(phase):
            sim.run(1)
            port_on.append(flip % 2 == 0)
            for p, (k, props) in sim.parts.items():
                if k == "redstone_wire":
                    on = sim.wire_power[p] > 0
                elif k in ("repeater", "phase_shifter"):
                    on = props.get("powered") == "true"
                    if k == "repeater":
                        inp.setdefault(p, []).append(sim.repeater_input(p) > 0)
                elif k in ("redstone_torch", "redstone_wall_torch"):
                    on = props.get("lit", "true") == "true"
                    inp.setdefault(p, []).append(not sim.torch_should_be_lit(p))
                else:
                    continue
                out.setdefault(p, []).append(on)
    out[port] = port_on
    return settled, power, {p: tuple(v) for p, v in out.items()}, {p: tuple(v) for p, v in inp.items()}


def signals(port, out, inp, phase=80):
    """Names each part's signal by its history: L the lever; R<k> eighth k's (its shifters'); any other by when it
    switches, in redstone ticks after the lever: a<on>c<off> (on a ticks after the lever goes on, off c ticks after
    it goes off), with an n in front for one that is on while the lever is off. A diode or torch is out<in."""
    names = {out[port]: "L"}
    for x, y in CELLS:
        names.setdefault(out[(x, y, 0)], f"R{ring(x, y)}")

    def name(h):
        if h not in names:
            rest = h[-1]
            change = [i for i in range(1, len(h)) if h[i] != h[i - 1]]
            if not change and h[0] == rest:
                names[h] = "on" if rest else "off"
            else:
                a = next(i for i in range(phase) if h[i] != rest)
                c = next(i for i in range(phase, 2 * phase) if h[i] == rest) - phase
                base = f"{'n' if rest else ''}a{(a + 1) // 2}c{(c + 1) // 2}"
                if len(change) != 4 or h[:2 * phase] != h[2 * phase:]:
                    base += f"g{len(names)}"          # switches more than once a flip
                names[h] = base
        return names[h]

    tags = {p: name(h) for p, h in out.items()}
    for p, h in inp.items():
        tags[p] = f"{tags[p]}<{name(h)}"
    return tags


def header(ox, oy, oz, opens):
    return [
        "The mountain door (MountainDoorGameTests, examples station 24). Written by tools/gen_mountain.py: do not",
        "edit, change the generator and run it again; check with: python3 tools/redstone_check.py <this file>",
        "",
        f"An arched door, 7 wide and 9 high, in a mountain's face at z = {oz}, x = {ox - 3}..{ox + 3}, y = {oy}..{oy + 8}.",
        "One visible lever opens it like a clock hand sweeping round: from twelve o'clock, clockwise as you face",
        f"it, an eighth every {STEP} redstone ticks, the first {opens} redstone ticks after the lever; it closes",
        "winding back. Every door block is held by a hidden phase shifter at its own spot; the wiring stays within",
        "the door's width, behind it and in front of it (see the generator).",
        "Signals: L the lever; R<k> eighth k; a<n>c<m> on n redstone ticks after the lever goes on and off m",
        "after it goes off (n..: on while the lever is off); a repeater or torch is <out>@<in>; named by the",
        "generator from a simulated run.",
    ]


def joins(b, settled, tags, out):
    """allow lines for the wires where signals join on purpose (the ORs, and the NOR of two torches): a diode or
    torch feeding a wire of another signal is allowed when it is never on while the wire is off (nested)."""
    allow = {}
    step = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
    for w, state in b.b.items():
        if state != WIRE:
            continue
        for d in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, 1, 0), (0, -1, 0)):
            q = (w[0] + d[0], w[1] + d[1], w[2] + d[2])
            k, props = settled.get(q, (None, {}))
            if k == "repeater":
                f = step[props["facing"]]
                if (q[0] - f[0], q[1] - f[1], q[2] - f[2]) != w:
                    continue              # it does not point into w
            elif k != "redstone_wall_torch":
                continue
            mine, theirs = tags[w], tags[q].split("<")[0]
            if mine != theirs and all(a <= c for a, c in zip(out[q], out[w])):
                allow.setdefault(mine, set()).add(theirs)
    return allow


def write(b, port, settled, power, tags, opens, allow):
    import gen_door
    from mcb.report import hidden_size
    out = ROOT / "src/gametest/resources/contraptions/mountain_door.txt"
    structure = ROOT / "src/gametest/resources/data/phasecraft-test/gametest/structure/mountain_door.snbt"
    order = list(b.b) + [(x, y, 0) for x, y in CELLS]
    g = gen_door.Grid()
    for p in order:
        k, props = settled[p]
        g.parts[p] = [("phasecraft:" if k in ("phase_port", "phase_shifter") else "minecraft:") + k,
                      {key: v for key, v in props.items() if not key.startswith("_")}, tags.get(p), None]
        g.order.append(p)
    gen_door.shapes(g)
    for p, v in g.parts.items():
        if v[0] == WIRE and power.get(p):
            v[1]["power"] = str(power[p])
    (mx0, my0, mz0), (mx1, my1, mz1) = (X0, -1, Z0), (X1, 34, Z1)
    xs, ys, zs = ([p[i] for p in order] + [lo, hi] for i, lo, hi in ((0, mx0, mx1), (1, my0, my1), (2, mz0, mz1)))
    ox, oy, oz = -min(xs), -min(ys), -min(zs)
    size = (max(xs) + ox + 1, max(ys) + oy + 1, max(zs) + oz + 1)

    def at(p):
        return f"{p[0] + ox} {p[1] + oy} {p[2] + oz}"

    lines = [f"# {line}" if line else "#" for line in header(ox, oy, oz, opens)] + [
        f"size {size[0]} {size[1]} {size[2]}", f"lever {at(LEVER)}", f"door {DOOR}"]
    lines += [f"allow {sig} {' '.join(sorted(also))}" for sig, also in sorted(allow.items())]
    lines += visible(at)
    lines.append(f"visible {at(LEVER)} minecraft:lever[face=floor,facing=north,powered=false]")
    for p in order:
        name, props, tag, _ = g.parts[p]
        state = name + ("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else "")
        lines.append(f"hidden {at(p)} {state}" + (f" @{tag}" if tag else ""))
    out.write_text("\n".join(lines) + "\n")
    structure.write_text("{\n    DataVersion: 2730,\n    size: [%d, %d, %d],\n    data: [],\n    palette: []\n}\n" % size)
    print(f"wrote {out.relative_to(ROOT)}: area {size[0]}x{size[1]}x{size[2]}; "
          + hidden_size([(p, g.parts[p][0]) for p in order]) + f"; first eighth {opens} redstone ticks after the lever")
    return out, (ox, oy, oz)


def simulate(path, offset):
    """The written layout in the simulator: closed at rest and while settling; each eighth opens at its tick, STEP
    apart, the first at `opens`, and closes in the reverse order; twice."""
    from mcb.redstone import Sim
    from mcb.scene import Scene
    sim = Sim(Scene.from_layout(path))
    if sim.popped:
        sys.exit(f"parts with nothing to stand on: {sim.popped[:5]}")
    sim.run(40)
    if sim.events or sim.powered_shifters():
        sys.exit(f"moved while settling: {sim.events[:5]}")
    ox, oy, oz = offset
    door = {(x + ox, y + oy, oz): (x, y) for x, y in CELLS}
    port = next(p for p, (k, _) in sim.parts.items() if k == "phase_port")
    opens = None
    for cycle in range(2):
        for on in (True, False):
            sim.events.clear()
            t0 = sim.tick
            sim.set_input(port, on)
            sim.run(120)
            times = {}
            for t, p, _, v in sim.events:
                if p not in door or v != on or p in times:
                    sys.exit(f"shifter at {p} switched wrongly ({v} at {t - t0})")
                times[p] = t - t0
            if set(times) != set(door):
                sys.exit(f"{len(times)} of {len(door)} switched")
            by = {}
            for p, t in times.items():
                by.setdefault(ring(*door[p]), set()).add(t)
            first = min(min(v) for v in by.values())
            opens = opens or first // 2
            want = {k: {2 * opens + 2 * STEP * (k if on else TOP - k)} for k in range(SECTORS)}
            if by != want:
                sys.exit(f"{'opening' if on else 'closing'}: {by}, expected {want}")
    print(f"simulated: eighths open at {[2 * opens + 2 * STEP * k for k in range(SECTORS)]} game ticks after the "
          "lever, close in reverse; twice")
    return opens


if __name__ == "__main__":
    parts, port = build()
    settled, power, out, inp = history(parts, port)
    tags = signals(port, out, inp)
    first = min(i for i, on in enumerate(out[(0, CY + 3, 0)]) if on)   # an eighth-0 block
    path, offset = write(parts, port, settled, power, tags, (first + 1) // 2, joins(parts, settled, tags, out))
    simulate(path, offset)
