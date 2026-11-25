#!/usr/bin/env python3
"""The castle tower (examples station 28, CastleTowerGameTests): a square stone tower that comes down block by
block from the top when its lever is flipped, and builds itself up again from the ground when it is flipped back,
on line shifters (one under each column of the tower; see the line shifter in docs/ARCHITECTURE.md).

Usage: python3 tools/gen_tower.py && python3 tools/redstone_check.py src/gametest/resources/contraptions/castle_tower.txt

The tower: 5 x 5, one-block walls, courses y = 0..7 (the top one battlements), a doorway in the north wall (the side
a visitor comes from) and barred windows (iron bars, so that every column is one unbroken line); 118 blocks.

Generator space: the tower's middle at x = z = 0, its courses at y = 0..7, the ground at y = -1. North is -z.

Order: a helix. Its 16 columns in turn, clockwise seen from above from the north-west corner: column k = 4 f + c
(face f = north, east, south, west; c = 0..3 along the face, each corner the first of its face) starts one redstone
tick after column k - 1, and in a column a block goes every STEP redstone ticks (the line shifters' delay). Coming
down (lever on), the block at course y goes at k + STEP (7 - y) redstone ticks after the first: the top comes off
in a spiral, a course lower every face. Going up (lever off), it comes back at k + STEP y + STEP: from the ground,
the same spiral the other way.

The wiring, all hidden:
- Each column's line shifter (delay STEP) sits under the column's lowest block, facing up: under the ground for
  15 columns, in the doorway (just under its lintel) for the doorway's. It takes its column from the top and puts
  it back from the bottom, one block every STEP redstone ticks.
- A battlement gap (a crenel) makes a column one block shorter at the top: coming down it must start STEP later
  than a full column (on later), going up at the same moment (off at the same time). A line shifter takes from its
  power's coming and puts back from its going, so: ring A gives column k a signal at k (S_k), ring B one at k + 4
  (S_k+4). A full column's shifter takes both (on at k, off at k + 4); a crenel's takes ring B's only (on and off
  at k + 4). Going up, every column so starts STEP after its turn; that STEP is the + STEP above.
- Rings: square rings round the tower, ring A at y = 0 (in the visible air beside the tower's lowest course, and
  inside its walls), ring B at y = -2 (under the shifters). On each face, a wire FOUR blocks out from the wall feeds
  a repeater three blocks out (delay c + 1) that points into a stone at the column: above the shifter (ring A, full
  columns only) or under it (ring B). Between faces a repeater adds 4 redstone ticks.
- The doorway's column starts at its lintel (course 2): it takes at k = 2 like a full column, and puts back at
  k + 2 STEP + STEP = 14 = S_14, the signal of the west face's middle column. A wire from that column's ring A stone
  runs through the tower's inside up onto the doorway's shifter.
- The lever powers a phase port under it. A repeater takes ring A's signal from the port (1 redstone tick), two
  take ring B's (5), so ring B runs 4 redstone ticks behind ring A everywhere.
"""

import sys

import gen_door
from mcb.redstone import Sim
from mcb.report import hidden_size
from mcb.scene import Scene

ROOT = gen_door.ROOT
NAME = "castle_tower"

TOP = 7                       # courses 0..TOP
HALF = 2                      # the tower is 2 * HALF + 1 wide
STEP = 4                      # redstone ticks a block in a column: the line shifters' delay
LEVER = (-2, 0, -7)           # beside the path to the door, in front of the tower (north)
PORT = (LEVER[0], LEVER[1] - 1, LEVER[2])
DOOR = (0, -HALF)             # the doorway's column
DOOR_LOW = 2                  # its lowest block (the lintel)
A_Y, B_Y = 0, -2              # the rings' heights

STONE, GLASS, WIRE = "minecraft:stone", "minecraft:glass", "minecraft:redstone_wire"
SHIFTER = f"phasecraft:line_shifter[delay={STEP},facing=up,powered=false]"
WALL_MIX = {"minecraft:stone_bricks": 6, "minecraft:mossy_stone_bricks": 1, "minecraft:cracked_stone_bricks": 1}
BARS = "minecraft:iron_bars"
DIR = {(0, -1): "north", (0, 1): "south", (-1, 0): "west", (1, 0): "east"}
# Faces in turn: their columns in order along the face (a pinwheel, each corner the first of a face), the way out.
FACES = [((0, -1), [(x, -HALF) for x in (-2, -1, 0, 1)]),
         ((1, 0), [(HALF, z) for z in (-2, -1, 0, 1)]),
         ((0, 1), [(x, HALF) for x in (2, 1, 0, -1)]),
         ((-1, 0), [(-HALF, z) for z in (2, 1, 0, -1)])]


def columns():
    """[(k, (x, z), out)] for the 16 columns in turn."""
    return [(4 * f + c, col, out) for f, (out, cols) in enumerate(FACES) for c, col in enumerate(cols)]


def present(y, x, z):
    """Whether the tower has a block at (x, y, z) of its wall: battlements on top, a doorway."""
    if y == TOP:
        return (x + z) % 2 == 0
    if (x, z) == DOOR and y < DOOR_LOW:
        return False
    return True


def window(y, x, z):
    """Barred windows: courses 3 and 4 in the middle of the east, south and west walls."""
    return y in (3, 4) and (x, z) in ((0, HALF), (HALF, 0), (-HALF, 0))


def full(col):
    """Whether a column reaches the top course (a merlon above it) rather than stopping one below (a crenel)."""
    return present(TOP, col[0], col[1])


def blocks():
    """[(pos, y, k)] of every block of the tower."""
    return [((x, y, z), y, k) for k, (x, z), _ in columns() for y in range(TOP + 1) if present(y, x, z)]


def turn(y, k, up):
    """Redstone ticks after the first block: going up (lever off) or coming down (lever on)."""
    return k + STEP * y + STEP if up else k + STEP * (TOP - y)


def shifter_at(col):
    low = DOOR_LOW if col == DOOR else 0
    return (col[0], low - 1, col[1])


class Parts:
    """Hidden parts by position; the same part twice is fine, two different ones are a mistake."""

    def __init__(self):
        self.b = {}

    def put(self, p, state):
        p = tuple(p)
        if p in self.b and self.b[p] != state:
            raise ValueError(f"clash at {p}: {self.b[p]} vs {state}")
        self.b[p] = state

    def support(self, p):
        below = (p[0], p[1] - 1, p[2])
        if below not in self.b:
            self.put(below, GLASS)

    def rep(self, p, facing, delay=1):
        """A repeater whose input is on its `facing` side."""
        self.support(p)
        self.put(p, f"minecraft:repeater[delay={delay},facing={facing}]")

    def wire(self, p):
        self.support(p)
        self.put(p, WIRE)


def ring(b, y, wanted):
    """A ring of face wires four blocks out, a branch repeater (delay c + 1) three blocks out into a stone at each
    wanted column (at height y), and a 4-redstone-tick repeater from each face's wire to the next face's. Returns
    the north face's first wire (where the ring's signal comes in)."""
    for f, (out, cols) in enumerate(FACES):
        ox, oz = out
        along = (cols[1][0] - cols[0][0], cols[1][1] - cols[0][1])
        for c, (x, z) in enumerate(cols):
            b.wire((x + 2 * ox, y, z + 2 * oz))
            if (x, z) in wanted:
                b.rep((x + ox, y, z + oz), DIR[out], c + 1)
                b.put((x, y, z), STONE)
        if f == len(FACES) - 1:
            break
        x, z = cols[-1][0] + 2 * ox, cols[-1][1] + 2 * oz          # the face's last wire
        r = (x + along[0], z + along[1])
        b.rep((r[0], y, r[1]), DIR[(-along[0], -along[1])], 4)
        corner = [(r[0] + along[0], r[1] + along[1]), (r[0] + 2 * along[0], r[1] + 2 * along[1])]
        corner.append((corner[-1][0] - ox, corner[-1][1] - oz))
        for cx, cz in corner:
            b.wire((cx, y, cz))
    out, cols = FACES[0]
    return (cols[0][0] + 2 * out[0], y, cols[0][1] + 2 * out[1])


def layout():
    b = Parts()
    for k, col, _ in columns():
        b.put(shifter_at(col), SHIFTER)
    a_start = ring(b, A_Y, {col for _, col, _ in columns() if full(col)})
    b_start = ring(b, B_Y, {col for _, col, _ in columns() if col != DOOR})
    # the doorway: ring A's stone is under its shifter (in the doorway's floor); S_14 comes from the west face's
    # middle column's ring A stone, through the tower's inside, onto the shifter
    west_middle = FACES[3][1][2]
    assert (west_middle, 14) == ((-HALF, 0), next(k for k, col, _ in columns() if col == west_middle))
    for p in [(-1, 0, 0), (-1, 1, -1), (-1, 2, -2), (0, DOOR_LOW, -HALF)]:
        b.wire(p)
    # the lever's port: ring A through one repeater, ring B through two (5 redstone ticks)
    px, py, pz = PORT
    b.put(PORT, "phasecraft:phase_port")
    b.rep((px, py, pz + 1), "north", 1)
    b.put((px, py, pz + 2), STONE)
    b.put((px, py + 1, pz + 2), WIRE)                                    # on the stone, onto ring A's north wire
    assert (px, py + 1, pz + 3) == a_start
    b.rep((px - 1, py, pz), "east", 4)
    b.rep((px - 2, py, pz), "east", 1)
    b.put((px - 3, py, pz), STONE)
    for p in [(px - 3, B_Y, pz), (px - 3, B_Y, pz + 1), (px - 3, B_Y, pz + 2), (px - 3, B_Y, pz + 3),
              (px - 2, B_Y, pz + 3), (px - 1, B_Y, pz + 3)]:
        b.wire(p)
    assert (px, B_Y, pz + 3) == b_start
    return b


def scene_of(b):
    scene = Scene()
    for p, state in b.b.items():
        scene.set("hidden", p, state)
    return scene


def history(b, phase):
    """Settles the wiring (lever off), flips the lever on and off; every part's on/off history, each diode's input
    history, and the settled state."""
    sim = Sim(scene_of(b))
    if sim.popped:
        sys.exit(f"parts with nothing to stand on: {sim.popped[:5]}")
    sim.run(300)
    if sim.events or sim.queue:
        sys.exit(f"not at rest: {sim.events[:5]}")
    settled = {p: (k, dict(props)) for p, (k, props) in sim.parts.items()}
    power = dict(sim.wire_power)
    out, inp, port_on = {}, {}, []
    for flip in range(2):
        sim.set_input(PORT, flip == 0)
        for _ in range(phase):
            sim.run(1)
            port_on.append(flip == 0)
            for p, (k, props) in sim.parts.items():
                if k == "redstone_wire":
                    on = sim.wire_power[p] > 0
                elif k in ("repeater", "line_shifter"):
                    on = props.get("powered") == "true"
                    if k == "repeater":
                        inp.setdefault(p, []).append(sim.repeater_input(p) > 0)
                else:
                    continue
                out.setdefault(p, []).append(on)
    out[PORT] = port_on
    return settled, power, {p: tuple(v) for p, v in out.items()}, {p: tuple(v) for p, v in inp.items()}


def signals(out, inp, phase):
    """Each part's signal, named by its history: L the lever; C<k> column k's shifter; any other a<on>c<off>: on a
    redstone ticks after the lever goes on, off c after it goes off. A diode is out<in."""
    names = {out[PORT]: "L"}
    for k, col, _ in columns():
        names.setdefault(out[shifter_at(col)], f"C{k}")

    def name(h):
        if h not in names:
            change = [i for i in range(1, len(h)) if h[i] != h[i - 1]]
            if not change and h[0] == h[-1]:
                names[h] = "on" if h[-1] else "off"
            elif len(change) == 2 and change[0] < phase <= change[1] and h[0] == h[-1]:
                a, c = change[0], change[1] - phase
                names[h] = f"{'n' if h[-1] else ''}a{(a + 1) // 2}c{(c + 1) // 2}"
            else:
                names[h] = f"{'n' if h[-1] else ''}e{'x'.join(str((i + 1) // 2) for i in change)}g{len(names)}"
        return names[h]

    tags = {p: name(h) for p, h in out.items()}
    for p, h in inp.items():
        tags[p] = f"{tags[p]}<{name(h)}"
    return tags


def feeds(b, tags):
    """allow lines for the shifters fed twice on purpose (a full column: rings A and B; the doorway: ring A and
    S_14): each takes the signals of what feeds it."""
    allow = {}
    for k, col, _ in columns():
        s = shifter_at(col)
        got = set()
        for d in ((0, 1, 0), (0, -1, 0)):
            q = (s[0], s[1] + d[1], s[2])
            if b.b.get(q) == STONE:                     # a ring's stone: the repeater pointing into it
                for v, name in DIR.items():
                    r = (q[0] + v[0], q[1], q[2] + v[1])
                    if b.b.get(r, "").startswith("minecraft:repeater") and f"facing={name}" in b.b[r]:
                        got.add(tags[r].split("<")[0])
            elif b.b.get(q) == WIRE:
                got.add(tags[q])
        if len(got) > 1:
            allow[f"C{k}"] = got
    return allow


def visible(at):
    """The ground, a path to the door, the lever, the tower (stone bricks, a few mossy and cracked; iron bars in the
    windows)."""
    from mcb.shapes import Palette
    lines = []
    lo, hi = -9, 9
    lines.append(f"fill visible {at((lo, -3, lo))} {at((hi, -2, hi))} minecraft:dirt")
    lines.append(f"fill visible {at((lo, -1, lo))} {at((hi, -1, hi))} minecraft:grass_block")
    lines.append(f"fill visible {at((-1, -1, -1))} {at((1, -1, 1))} minecraft:cobblestone")
    for z in range(lo, -HALF):
        lines.append(f"visible {at((0, -1, z))} minecraft:dirt_path")
    mix = Palette(WALL_MIX)
    for p, y, _ in blocks():
        if window(y, p[0], p[2]):                   # joined to the wall on both sides, as placed bars would be
            sides = "east=true,west=true" if abs(p[2]) == HALF else "north=true,south=true"
            lines.append(f"visible {at(p)} {BARS}[{sides}]")
        else:
            lines.append(f"visible {at(p)} {mix.at(p)}")
    lines.append(f"visible {at(LEVER)} minecraft:lever[face=floor,facing=north,powered=false]")
    return lines


def write(b, settled, power, tags, allow, first):
    g = gen_door.Grid()
    hidden = list(b.b)
    plain = [p for p in hidden if b.b[p] in (STONE, GLASS)]                 # supports before what stands on them
    order = plain + [p for p in hidden if b.b[p] not in (STONE, GLASS)]
    for p in order:
        if p in settled:
            k, props = settled[p]
            name = ("phasecraft:" if k in ("phase_port", "line_shifter") else "minecraft:") + k
            props = {key: v for key, v in props.items() if not key.startswith("_")}
        else:
            name, props = b.b[p], {}
        g.parts[p] = [name, props, tags.get(p), None]
        g.order.append(p)
    gen_door.shapes(g)
    for p, v in g.parts.items():
        if v[0] == WIRE and power.get(p):
            v[1]["power"] = str(power[p])
    xs = [p[0] for p in order] + [-9, 9]
    ys = [p[1] for p in order] + [-3, TOP + 2]      # headroom: a GameTest's barrier ceiling would stop a column
    zs = [p[2] for p in order] + [-9, 9]
    ox, oy, oz = -min(xs), -min(ys), -min(zs)
    size = (max(xs) + ox + 1, max(ys) + oy + 1, max(zs) + oz + 1)

    def at(p):
        return f"{p[0] + ox} {p[1] + oy} {p[2] + oz}"

    header = [f"# {line}".rstrip() for line in __doc__.strip().splitlines()]
    header.insert(0, "# Written by tools/gen_tower.py; change the generator and run it again.")
    header.append(f"# The tower's middle at {ox} {oy} {oz} (its lowest course); the first block moves {first} game "
                  "ticks after the lever.")
    header.append("# Signals: L the lever; C<k> column k's line shifter; a<n>c<m> on n redstone ticks after the lever "
                  "goes on and off m after it goes off; a diode <out><<in>; named from a simulated run.")
    lines = header + [f"size {size[0]} {size[1]} {size[2]}", f"lever {at(LEVER)}"]
    lines += [f"allow {sig} {' '.join(sorted(also))}" for sig, also in sorted(allow.items())]
    lines += visible(at)
    for p in order:
        name, props, tag, _ = g.parts[p]
        state = name + ("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else "")
        lines.append(f"hidden {at(p)} {state}" + (f" @{tag}" if tag else ""))
    out = ROOT / f"src/gametest/resources/contraptions/{NAME}.txt"
    out.write_text("\n".join(lines) + "\n")
    structure = ROOT / f"src/gametest/resources/data/phasecraft-test/gametest/structure/{NAME}.snbt"
    structure.write_text("{\n    DataVersion: 2730,\n    size: [%d, %d, %d],\n    data: [],\n    palette: []\n}\n" % size)
    print(f"wrote {out.relative_to(ROOT)}: area {size[0]}x{size[1]}x{size[2]}; "
          + hidden_size([(p, g.parts[p][0]) for p in hidden]))
    return out, (ox, oy, oz)


def moves(on_at, off_at, line):
    """{pos: game tick} a line shifter (delay STEP) moves each block of its line (bottom first): taking from the
    top when powered at on_at, or putting back from the bottom when unpowered at off_at."""
    if on_at is not None:
        return {p: on_at + 2 * STEP * i for i, p in enumerate(reversed(line))}
    return {p: off_at + 2 * STEP * i for i, p in enumerate(line)}


def simulate(path, offset):
    """The written layout in the simulator: still while settling; coming down and going up again, each column's
    shifter switched once, so that each block moves at its turn; twice."""
    sim = Sim(Scene.from_layout(path))
    if sim.popped:
        sys.exit(f"parts with nothing to stand on: {sim.popped[:5]}")
    sim.run(60)
    if sim.events:
        sys.exit(f"moved while settling: {sim.events[:5]}")
    ox, oy, oz = offset

    def moved(p):
        return (p[0] + ox, p[1] + oy, p[2] + oz)

    lines = {moved(shifter_at(col)): [moved(p) for p, _, kk in blocks() if kk == k] for k, col, _ in columns()}
    where = {moved(p): (y, k) for p, y, k in blocks()}
    port = moved(PORT)
    first = None
    for cycle in range(2):
        for on in (True, False):
            sim.events.clear()
            t0 = sim.tick
            sim.set_input(port, on)
            sim.run(2 * (STEP * (TOP + 1) + 60))
            times = {}
            for t, p, kind, v in sim.events:
                if kind != "line_shifter" or p not in lines or v != on or p in times:
                    sys.exit(f"{kind} at {p} switched wrongly ({v} at {t - t0})")
                times[p] = t - t0
            if set(times) != set(lines):
                sys.exit(f"{len(times)} of {len(lines)} line shifters switched")
            at = {}
            for s, t in times.items():
                at.update(moves(t if on else None, None if on else t, lines[s]))
            first = first if first is not None else min(at.values())
            start = min(at.values()) if not on else first
            if on and start != first:
                sys.exit("the first block moved at another time")
            base = first if on else min(at.values()) - 2 * STEP
            wrong = {where[p]: t for p, t in at.items() if t != base + 2 * turn(*where[p], up=not on)}
            if wrong:
                sys.exit(f"{'coming down' if on else 'going up'}: {len(wrong)} blocks off their turn, e.g. "
                         f"{sorted(wrong.items())[:6]} (first {base})")
            if not on and min(at.values()) - 2 * STEP != first:
                sys.exit(f"going up starts at {min(at.values())} game ticks, not {first + 2 * STEP}")
    print(f"simulated: down from the top in a helix and up again from the ground, {len(where)} blocks, the first "
          f"{first} game ticks after the lever; twice")
    return first


def main():
    b = layout()
    phase = 2 * (STEP * (TOP + 1) + 60)
    settled, power, out, inp = history(b, phase)
    tags = signals(out, inp, phase)
    allow = feeds(b, tags)
    path, offset = write(b, settled, power, tags, allow, "?")
    first = simulate(path, offset)
    write(b, settled, power, tags, allow, first)


if __name__ == "__main__":
    main()
