#!/usr/bin/env python3
"""The castle tower (examples station 28, CastleTowerGameTests): a square stone tower that comes down block by
block from the top when its lever is flipped, and builds itself up again from the ground when it is flipped back.

Usage: python3 tools/gen_tower.py && python3 tools/redstone_check.py src/gametest/resources/contraptions/castle_tower.txt

The tower: 5 x 5, one-block walls, courses y = 0..7 (the top one battlements), a doorway in the north wall (the side a visitor comes from), arrow
slits; 112 blocks, each held by a hidden phase shifter at its own spot. Order: course by course, and in each course
clockwise seen from above, from the north-west corner: block i of the build (i = 16 y + 4 f + c, face f = north, east,
south, west, column c = 0..3 along the face) comes STEP redstone ticks after block i - 1; coming down, course 7 goes
first (i = 16 (7 - y) + 4 f + c). Blocks missing from the shape (doorway, slits, gaps of the battlements) keep their
turn, so the rhythm stays even.

Generator space: the tower's middle at x = z = 0, its courses at y = 0..7, the ground at y = -1. North is -z.

The wiring, all hidden:
- Faces as a pinwheel: the north face feeds its columns x = -2..1 from the north, the east face z = -2..1 from the
  east, the south face x = 2..-1 from the south, the west face z = 2..-1 from the west (each corner belongs to one
  face), so every block has a free side facing out, with the free quadrants between the faces.
- Channels: each block's course is fed by a straight channel out from its face, at the block's own height,
  alternating stone and repeater along it and from course to course (a repeater always stands on the stone of the
  channel below; a stone powers the next repeater in, and the shifter if it touches it). Course y's channel is
  depth(y) = 9 - y blocks long: each course up one block shorter, a staircase. Its last repeater reads a stone
  (the course's input) under a wire.
- Rings: course y's wire (y + 1 up) runs over its four faces' input stones, a square ring round the tower, open in
  the north-west, where its feed comes in. Between faces the ring has repeaters adding FACE redstone ticks, and each
  channel's outermost repeater adds its column's c, so block i lights i redstone ticks after the ring's start. A
  stone above every ring cuts it from the ring above, one block in and up (wire joins a diagonal step).
- Timing: a delay line from the lever with eight taps, COURSE redstone ticks apart (L0..L7). Coming down (lever on),
  course y goes at tap 7 - y; going up (lever off), at tap y. So course y's shifters follow L(7-y) OR L(y) for the
  top four courses (on at the earlier tap, off at the later) and L(y) AND L(7-y) for the bottom four (on at the
  later, off at the earlier): one tap pair per two courses. The AND is a torch fed by the two taps' torches.
- The router (tools/mcb/router.py) lays the lines from the taps to the courses' gates, each with the delay that
  makes every block's turn exact, and the lever's line to the first tap.
"""

import itertools
import pathlib
import sys

import gen_door
from mcb import router
from mcb.redstone import SIDES, Sim, add
from mcb.report import hidden_size
from mcb.scene import Scene

ROOT = gen_door.ROOT
NAME = "castle_tower"

TOP = 7                       # courses 0..TOP
HALF = 2                      # the tower is 2 * HALF + 1 wide
D_TOP = 2                     # the top course's channel length
COURSE, FACE = 16, 4          # redstone ticks per course, per face (a block a redstone tick)
ZG = 15                       # the feeds' ends at z = -ZG, the gates just north of them
ZL = 26                       # the delay line along z = -ZL
LEVER = (3, 0, -5)            # beside the path to the door, in front of the tower (north)

STONE, GLASS, WIRE = "minecraft:stone", "minecraft:glass", "minecraft:redstone_wire"
SHIFTER, PORT = "phasecraft:phase_shifter", "phasecraft:phase_port"
WALL = "minecraft:stone_bricks"
WALL_MIX = {"minecraft:stone_bricks": 6, "minecraft:mossy_stone_bricks": 1, "minecraft:cracked_stone_bricks": 1}
VEC = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
OPP = {"north": "south", "south": "north", "west": "east", "east": "west"}
FACES = [("north", [(x, -HALF) for x in (-2, -1, 0, 1)]),
         ("east", [(HALF, z) for z in (-2, -1, 0, 1)]),
         ("south", [(x, HALF) for x in (2, 1, 0, -1)]),
         ("west", [(-HALF, z) for z in (2, 1, 0, -1)])]


def depth(y):
    return D_TOP + TOP - y


def radius(y):
    """Course y's ring: a square this far out from the middle."""
    return HALF + 1 + depth(y)


def feed_x(y):
    """Course y's feed turns north at x = -feed_x(y), its gate north of that."""
    return 5 + 4 * y


def present(y, x, z):
    """Whether the tower has a block at (x, y, z) of its wall: battlements on top, a doorway, arrow slits."""
    if y == TOP:
        return (x + z) % 2 == 0
    if (x, z) == (0, -HALF) and y in (0, 1):
        return False
    if y in (3, 4) and (x, z) in ((0, HALF), (HALF, 0), (-HALF, 0)):
        return False
    return True


def blocks():
    """[(pos, y, f, c)] of every block of the tower."""
    out = []
    for f, (_, cols) in enumerate(FACES):
        for c, (x, z) in enumerate(cols):
            for y in range(TOP + 1):
                if present(y, x, z):
                    out.append(((x, y, z), y, f, c))
    return out


def turn(y, f, c, up):
    """Redstone ticks after the first block: going up (lever off) or coming down (lever on)."""
    return COURSE * (y if up else TOP - y) + FACE * f + c


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


def channels(b):
    """Each block's channel, out from its face at its own height, and the course's input stone at its end."""
    for name, cols in FACES:
        vx, _, vz = VEC[name]
        for c, (x, z) in enumerate(cols):
            for y in range(TOP + 1):
                d_end = depth(y)
                here = present(y, x, z)
                for d in range(1, d_end + 2):
                    p = (x + vx * d, y, z + vz * d)
                    if d == d_end + 1:
                        b.put(p, STONE if here else GLASS)     # the input, under the ring's wire
                    elif not here:
                        continue
                    elif (d + y) % 2:
                        b.rep(p, name, 1 + c if d == d_end else 1)
                    else:
                        b.put(p, STONE)
    # a repeater over a missing block's channel stands on glass
    for p, state in list(b.b.items()):
        if "repeater" in state:
            b.support(p)


def ring_path(y):
    """Course y's ring, in the signal's order: [north row, NE, east row, SE, south row, SW, west row] as (x, z)."""
    r = radius(y)
    return [[(x, -r) for x in range(-2, 2)],
            [(x, -r) for x in range(2, r + 1)] + [(r, z) for z in range(-r + 1, -2)],
            [(r, z) for z in range(-2, 2)],
            [(r, z) for z in range(2, r + 1)] + [(x, r) for x in range(r - 1, 2, -1)],
            [(x, r) for x in (2, 1, 0, -1)],
            [(x, r) for x in range(-2, -r - 1, -1)] + [(-r, z) for z in range(r - 1, 2, -1)],
            [(-r, z) for z in (2, 1, 0, -1)]]


def spots(y):
    """Where the repeaters go in each of course y's corner stretches (indexes; all four alike) and their delays: as
    few as keep every stretch of wire under 14, as even as can be. The corner itself (index r - 2) cannot hold one."""
    r = radius(y)
    n = 2 * r - 4
    allowed = [i for i in range(n) if i != r - 2]
    best = None
    for m in (1, 2, 3):
        delays = {1: [FACE], 2: [FACE // 2, FACE - FACE // 2], 3: [1, 1, FACE - 2]}[m]

        for pos in itertools.combinations(allowed, m):
            runs = [4 + pos[0]]                                     # from the feed's last repeater
            runs += [pos[k + 1] - pos[k] - 1 for k in range(m - 1)]
            runs.append(n - 1 - pos[-1] + 4 + pos[0])               # across a row to the next stretch
            worst = max(runs)
            if worst <= 13 and (best is None or worst < best[0]):
                best = (worst, pos, delays)
        if best:
            return best[1], best[2]
    raise ValueError(f"no repeater spots for course {y}")


def facing_from(a, b):
    """The direction from b back to a, both (x, z) (a repeater at b fed from a has this facing)."""
    d = (a[0] - b[0], a[1] - b[1])
    return next(k for k, v in VEC.items() if (v[0], v[2]) == d)


GATE_Y = 1                    # the gates' height: each feed climbs from there to its course


def feed_path(y):
    """Course y's feed in the signal's order, as (x, y, z): its end E at the gates' height (z = -ZG), south and up a
    step a block (on glass: a one-way step, up) to its ring's height, on to z = -r - 1; then the corner and east
    along z = -r to (-3, -r), its last repeater, which feeds the ring's start."""
    h, r, fx = y + 1, radius(y), feed_x(y)
    cells, level = [], GATE_Y
    for i, z in enumerate(range(-ZG, -r)):
        if i >= 2 and level < h:
            level += 1
        cells.append((-fx, level, z))
    if level != h:
        raise ValueError(f"course {y}'s feed cannot climb to {h} by z = {-r - 1}")
    cells += [(x, h, -r) for x in range(-fx, -2)]
    return cells


def feed_repeaters(cells, delay=None):
    """{index: delay} for a feed: its last cell, and one before the signal fades (never on E, a step or a turn);
    with delay= (redstone ticks), delays raised, then repeaters added on spare straight cells, to make it."""
    n = len(cells)

    def straight(i):
        a, b, c = cells[i - 1], cells[i], cells[i + 1]
        return a[1] == b[1] == c[1] and (b[0] - a[0], b[2] - a[2]) == (c[0] - b[0], c[2] - b[2])
    ok = [i for i in range(1, n - 1) if straight(i)]
    reps = {n - 1: 1}
    run, cand = 0, None
    for i in range(n - 1):
        run += 1
        if i in ok:
            cand = i
        if run >= 13:
            reps[cand] = 1
            run = i - cand
            cand = None
    if delay is not None:
        need = delay - len(reps)
        spare = [i for i in ok if i not in reps and i - 1 not in reps and i + 1 not in reps]
        while need > 0:
            room = [i for i in sorted(reps) if reps[i] < 4]
            if room:
                reps[room[0]] += 1
            elif spare:
                reps[spare.pop(len(spare) // 2)] = 1
            else:
                raise ValueError(f"no room for {delay} redstone ticks in a feed of {n} cells")
            need -= 1
        if need < 0:
            raise ValueError(f"a feed of {n} cells needs at least {len(reps)} redstone ticks, more than {delay}")
    return reps


def rings(b, feed_delays=None):
    """Each course's ring over its input stones, with the cutting stones above it, and its feed from the gates.
    Returns {course: (E, the feed's delay)}."""
    ends = {}
    for y in range(TOP + 1):
        h = y + 1
        parts = ring_path(y)
        pos, delays = spots(y)
        feed = feed_path(y)
        reps = feed_repeaters(feed, (feed_delays or {}).get(y))
        for i, p in enumerate(feed):
            if i in reps:
                prev = feed[i - 1]
                b.rep(p, facing_from((prev[0], prev[2]), (p[0], p[2])), reps[i])
            else:
                b.wire(p)
            if p[1] == h and y < TOP and p[2] == -radius(y):
                b.put((p[0], h + 1, p[2]), STONE)                   # the run: cut from the feed above
        ring = [c for part in parts for c in part]
        at = {}
        for k, part_i in enumerate((1, 3, 5)):
            first = sum(len(parts[j]) for j in range(part_i))
            for q, dl in zip(pos, delays):
                at[first + q] = dl
        for i, (x, z) in enumerate(ring):
            p = (x, h, z)
            prev = ring[i - 1] if i else (feed[-1][0], feed[-1][2])
            if i in at:
                b.rep(p, facing_from(prev, (x, z)), at[i])
            else:
                b.wire(p)
            if y < TOP:
                b.put((x, h + 1, z), STONE)                         # cuts the step to the ring above
        ends[y] = (feed[0], sum(reps.values()))
    return ends


def gates(b, ends):
    """North of each feed's end E: an OR (a stone the two taps' lines point into) for the top courses; for the bottom
    ones an AND: a stone the two taps' torches' lines point into, and a torch on it lighting E. {course: stone}"""
    into = {}
    for y in range(TOP + 1):
        (x, h, z), _ = ends[y]
        if y > TOP // 2:
            g = (x, h, z - 1)
            b.put(g, STONE)
        else:
            g = (x, h, z - 2)
            b.put(g, STONE)
            b.put((x, h, z - 1), "minecraft:redstone_wall_torch[facing=south]")
        into[y] = g
    return into


def tap_x(j):
    return -4 - 5 * j


def delay_line(b):
    """The lever's delay line west along z = -ZL at the gates' height: tap stones T0..T7 with four repeaters (COURSE
    ticks) between; each tap's own signal goes out south (a repeater), its inverse north (a torch on the tap)."""
    taps = {}
    for j in range(TOP + 1):
        t = (tap_x(j), GATE_Y, -ZL)
        b.put(t, STONE)
        taps[j] = t
        b.put((t[0], GATE_Y, -ZL - 1), "minecraft:redstone_wall_torch[facing=north]")
        # four repeaters to the next tap; after the last, four more, so that the lock opens a course after the last
        # tap, once the bottom course has finished
        for k in range(1, 5):
            b.rep((t[0] - k, GATE_Y, -ZL), "east", COURSE // 4)
    return taps


def lock(b):
    """The delay line's input I, locked while a move is under way (L0 and L7 differ), so that the lever is read again
    only once the tower has finished moving: a flip during a move waits for its end, and a quick flick makes one whole
    cycle. The lever's line powers P; I (3 redstone ticks, more than the lock takes to close) feeds S0 (L0), and
    wire and a repeater on to T0. Two comparators in subtract mode point into I's sides: C1 = L0 - L7 (L0 behind it,
    L7 at its side), C2 = L7 - L0. Returns (where the lever's line ends, where L7's lines end) as router ends."""
    y, z = GATE_Y, -ZL
    b.rep((4, y, z), "east", 1)                                               # the lever's line ends here
    b.put((3, y, z), STONE)                                                   # P
    b.rep((2, y, z), "east", 3)                                               # I
    b.put((1, y, z), STONE)                                                   # S0: L0
    for x in (0, -1, -2):
        b.wire((x, y, z))
    b.rep((-3, y, z), "east", 1)                                              # into T0
    b.support((2, y, z - 1))
    b.put((2, y, z - 1), "minecraft:comparator[facing=north,mode=subtract]")  # C1, into I's north side
    b.rep((1, y, z - 1), "south", 1)                                          # L0 round to C1's back
    b.wire((1, y, z - 2))
    b.wire((2, y, z - 2))
    b.support((2, y, z + 1))
    b.put((2, y, z + 1), "minecraft:comparator[facing=south,mode=subtract]")  # C2, into I's south side
    b.wire((1, y, z + 1))                                                     # L0 at C2's side
    return (5, y, z), [((2, y, z + 2), "north"), ((3, y, z - 1), "west")]


def layout(feed_delays=None):
    b = Parts()
    channels(b)
    ends = rings(b, feed_delays)
    into = gates(b, ends)
    taps = delay_line(b)
    lever_end, l7_ends = lock(b)
    lx, ly, lz = LEVER
    port = (lx, ly - 1, lz)
    b.put(port, PORT)
    return b, ends, into, taps, port, lever_end, l7_ends


def channel_delay(y):
    """Redstone ticks through course y's channel for column 0 (each column c adds c)."""
    return sum(1 for d in range(1, depth(y) + 1) if (d + y) % 2)


def torches(y):
    """Torches on the way from a tap to course y: the tap's and the gate's for an AND, none for an OR."""
    return 2 if y <= TOP // 2 else 0


def nets(into, taps, port, lever_end, l7_ends, delays):
    """Each tap to its two courses' gates (its own signal to the OR, its inverse to the AND), and the lever's port
    to the first tap. [(course or None, Net)]"""
    out = []
    for j in range(TOP + 1):
        for inverse in (False, True):
            y = min(j, TOP - j) if inverse else max(j, TOP - j)
            g = into[y]
            side = "north" if j == min(j, TOP - j) else "west"
            v = VEC[side]
            end = (g[0] + v[0], g[1], g[2] + v[2])
            t = taps[j]
            start, start_from = ((t[0], t[1], -ZL - 2), "south") if inverse else ((t[0], t[1], -ZL + 1), "north")
            out.append((y, router.Net(start, start_from, end, OPP[side], delay=delays.get(y),
                                      name=f"{'not ' if inverse else ''}L{j} to course {y}")))
    out.append((None, router.Net((port[0], port[1], port[2] - 1), "south", lever_end, "west", name="lever to the lock")))
    t7 = taps[TOP]
    out.append((None, router.TreeNet((t7[0] - 5, t7[1], t7[2]), "east", l7_ends, name="L7 to the lock")))
    return out


BOX = ((-48, -4, -34), (16, 14, 16))


def route(b, into, taps, port, lever_end, l7_ends, delays):
    scene = Scene()
    for p, s in b.b.items():
        scene.set("hidden", p, s)
    pairs = nets(into, taps, port, lever_end, l7_ends, delays)
    routes = router.route_all(scene, [n for _, n in pairs], BOX)
    for r in routes:
        for p, s in r.parts:
            b.put(p, s)
    return [(y, r) for (y, _), r in zip(pairs, routes)]


def wire_up():
    """Routes once with the fewest repeaters, to learn how many redstone ticks each course's lines need at least;
    then sets every feed so that tap to block takes the same time for all, and routes again, exactly."""
    b, ends, into, taps, port, lever_end, l7_ends = layout()
    first = route(b, into, taps, port, lever_end, l7_ends, {})
    need = {}
    for y, r in first:
        if y is not None:
            need[y] = max(need.get(y, 0), r.delay)
    feed_min = {y: d for y, (_, d) in ends.items()}
    for _ in range(20):
        total = max(need[y] + channel_delay(y) + torches(y) + feed_min[y] for y in need)
        feeds = {y: total - need[y] - channel_delay(y) - torches(y) for y in need}
        b, ends, into, taps, port, lever_end, l7_ends = layout(feeds)
        try:
            routes = route(b, into, taps, port, lever_end, l7_ends, need)
            break
        except router.NoRoute as e:
            text = str(e)
            if "needs at least" not in text and "cannot make" not in text:
                raise
            y = int(text.split(" to course ")[1].split(":")[0])
            need[y] += 1 if "needs at least" in text else -1
    else:
        raise router.NoRoute("no timing found for every line")
    return b, port, total, routes


def scene_of(b):
    scene = Scene()
    for p, state in b.b.items():
        scene.set("hidden", p, state)
    for p, *_ in blocks():
        scene.set("hidden", p, SHIFTER)
    return scene


def history(b, port, phase):
    """Settles the wiring (lever off), flips the lever on and off; every part's on/off history, each diode's and
    torch's input history, and the settled state."""
    sim = Sim(scene_of(b))
    if sim.popped:
        sys.exit(f"parts with nothing to stand on: {sim.popped[:5]}")
    sim.run(300)
    if sim.powered_shifters() or sim.queue:
        sys.exit(f"not at rest: {sorted(sim.powered_shifters())[:5]}")
    settled = {p: (k, dict(props)) for p, (k, props) in sim.parts.items()}
    power = dict(sim.wire_power)
    out, inp, side, port_on = {}, {}, {}, []
    for flip in range(2):
        sim.set_input(port, flip == 0)
        for _ in range(phase):
            sim.run(1)
            port_on.append(flip == 0)
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
                elif k == "comparator":
                    on = props.get("powered") == "true"
                    inp.setdefault(p, []).append(sim.comparator_input(p) > 0)
                    side.setdefault(p, []).append(max(sim.control(add(p, d), d) for d in SIDES[props["facing"]]) > 0)
                else:
                    continue
                out.setdefault(p, []).append(on)
    out[port] = port_on
    for p, h in side.items():
        out[("side",) + p] = h
    return settled, power, {p: tuple(v) for p, v in out.items()}, {p: tuple(v) for p, v in inp.items()}


def signals(port, out, inp, phase):
    """Each part's signal, named by its history: L the lever; Y<y>K<k> a block's shifter (course y, k-th of the
    course); any other a<on>c<off>: on a redstone ticks after the lever goes on, off c after it goes off (with an n in
    front if it is on while the lever is off). A diode or torch is out<in."""
    names = {out[port]: "L"}
    for p, y, f, c in blocks():
        names.setdefault(out[p], f"Y{y}K{FACE * f + c}")

    def name(h):
        if h not in names:
            rest = h[-1]
            change = [i for i in range(1, len(h)) if h[i] != h[i - 1]]
            if not change and h[0] == rest:
                names[h] = "on" if rest else "off"
            elif len(change) == 2 and change[0] < phase <= change[1] and h[0] == rest:
                a, c = change[0], change[1] - phase
                names[h] = f"{'n' if rest else ''}a{(a + 1) // 2}c{(c + 1) // 2}"
            else:                                     # any other: its changes, in redstone ticks from the start
                names[h] = f"{'n' if rest else ''}e{'x'.join(str((i + 1) // 2) for i in change)}g{len(names)}"
        return names[h]

    tags = {p: name(h) for p, h in out.items()}
    for p, h in inp.items():
        tags[p] = f"{tags[p]}<{name(h)}"
    return tags


def comparator_sides(tags):
    """allow lines for the lock's comparators: each takes the other tap's signal at its side, on purpose."""
    allow = {}
    for key, tag in tags.items():
        if key[0] == "side":
            allow.setdefault(tags[key[1:]].split("<")[0], set()).add(tag)
    return allow


def joins(b, settled, tags):
    """allow lines for the gates, where two lines point into one stone on purpose: whatever reads the stone (an OR's
    feed wire, an AND's torch) carries both lines' signals (the simulator checks what it makes of them)."""
    allow = {}
    for g, state in b.b.items():
        if state != STONE:
            continue
        into = set()
        for d in VEC.values():
            q = (g[0] + d[0], g[1], g[2] + d[2])
            k, props = settled.get(q, (None, {}))
            if k == "repeater":
                f = VEC[props["facing"]]
                if (q[0] - f[0], q[1], q[2] - f[2]) == g:
                    into.add(tags[q].split("<")[0])
        if len(into) < 2:
            continue
        for d in list(VEC.values()) + [(0, 1, 0)]:
            r = (g[0] + d[0], g[1] + d[1], g[2] + d[2])
            k, props = settled.get(r, (None, {}))
            if k == "redstone_wire":
                allow.setdefault(tags[r].split("<")[0], set()).update(into)
            elif k == "redstone_wall_torch" and VEC[props["facing"]] == d:
                allow.setdefault(tags[r].split("<")[1], set()).update(into)
    return allow


def visible(at):
    """The ground, a path to the door, the lever, the tower (stone bricks, a few mossy and cracked)."""
    from mcb.shapes import Palette
    lines = []
    lo, hi = -9, 9
    lines.append(f"fill visible {at((lo, -3, lo))} {at((hi, -2, hi))} minecraft:dirt")
    lines.append(f"fill visible {at((lo, -1, lo))} {at((hi, -1, hi))} minecraft:grass_block")
    lines.append(f"fill visible {at((-1, -1, -1))} {at((1, -1, 1))} minecraft:cobblestone")
    for z in range(lo, -HALF):
        lines.append(f"visible {at((0, -1, z))} minecraft:dirt_path")
    mix = Palette(WALL_MIX)
    for p, *_ in blocks():
        lines.append(f"visible {at(p)} {mix.at(p)}")
    lines.append(f"visible {at(LEVER)} minecraft:lever[face=floor,facing=north,powered=false]")
    return lines


def write(b, settled, power, tags, allow, first):
    g = gen_door.Grid()
    tower = [p for p, *_ in blocks()]
    hidden = [p for p in b.b if p not in set(tower)]
    plain = [p for p in hidden if b.b[p] in (STONE, GLASS)]                 # supports before what stands on them
    order = plain + [p for p in hidden if b.b[p] not in (STONE, GLASS)] + tower
    for p in order:
        if p in settled:
            k, props = settled[p]
            name = ("phasecraft:" if k in ("phase_port", "phase_shifter") else "minecraft:") + k
            props = {key: v for key, v in props.items() if not key.startswith("_")}
        else:
            name, props = b.b[p], {}
        g.parts[p] = [name, props, tags.get(p), None]
        g.order.append(p)
    gen_door.shapes(g)
    for p, v in g.parts.items():
        if v[0] == WIRE and power.get(p):
            v[1]["power"] = str(power[p])
    xs, ys, zs = ([p[i] for p in order] + [-9, 9] for i in range(3))
    ys = [p[1] for p in order] + [-3]
    ox, oy, oz = -min(xs), -min(ys), -min(zs)
    size = (max(xs) + ox + 1, max(ys) + oy + 1, max(zs) + oz + 1)

    def at(p):
        return f"{p[0] + ox} {p[1] + oy} {p[2] + oz}"

    header = [f"# {line}".rstrip() for line in __doc__.strip().splitlines()]
    header.insert(0, "# Written by tools/gen_tower.py; change the generator and run it again.")
    header.append(f"# The tower's middle at {ox} {oy} {oz} (its lowest course); the first block moves {first} redstone "
                  "ticks after the lever.")
    header.append("# Signals: L the lever; Y<y>K<k> course y's k-th block; a<n>c<m> on n redstone ticks after the "
                  "lever goes on and off m after it goes off (n..: on while the lever is off); a diode or torch "
                  "<out><<in>; named from a simulated run.")
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
          + hidden_size([(p, g.parts[p][0]) for p in hidden])
          + f"; first block {first} redstone ticks after the lever")
    return out, (ox, oy, oz)


def simulate(path, offset):
    """The written layout in the simulator: standing at rest and while settling; coming down and going up again,
    each block at its turn, twice."""
    sim = Sim(Scene.from_layout(path))
    if sim.popped:
        sys.exit(f"parts with nothing to stand on: {sim.popped[:5]}")
    sim.run(60)
    if sim.events or sim.powered_shifters():
        sys.exit(f"moved while settling: {sim.events[:5]}")
    ox, oy, oz = offset
    where = {(p[0] + ox, p[1] + oy, p[2] + oz): (y, f, c) for p, y, f, c in blocks()}
    port = next(p for p, (k, _) in sim.parts.items() if k == "phase_port")
    first = None
    for cycle in range(2):
        for on in (True, False):
            sim.events.clear()
            t0 = sim.tick
            sim.set_input(port, on)
            sim.run(2 * (COURSE * (TOP + 1) + 60))
            times = {}
            for t, p, _, v in sim.events:
                if p not in where or v != on or p in times:
                    sys.exit(f"shifter at {p} switched wrongly ({v} at {t - t0})")
                times[p] = t - t0
            if set(times) != set(where):
                sys.exit(f"{len(times)} of {len(where)} switched")
            first = first or min(times.values())
            wrong = {where[p]: t for p, t in times.items() if t != first + 2 * turn(*where[p], up=not on)}
            if wrong:
                sys.exit(f"{'coming down' if on else 'going up'}: {len(wrong)} blocks off their turn, e.g. "
                         f"{sorted(wrong.items())[:6]} (first {first})")
    # the lock: flipped back during a move (or flicked), the tower finishes coming down at the same turns, then
    # builds itself up again; nothing moves twice
    for back in (120, 4):
        sim.events.clear()
        t0 = sim.tick
        sim.set_input(port, True)
        sim.run(back)
        sim.set_input(port, False)
        sim.run(4 * (COURSE * (TOP + 1) + 60))
        down, up = {}, {}
        for t, p, _, v in sim.events:
            if p not in where or p in (down if v else up) or (not v and p not in down):
                sys.exit(f"flipped back after {back} game ticks: shifter at {p} switched wrongly ({v} at {t - t0})")
            (down if v else up)[p] = t - t0
        if set(down) != set(where) or set(up) != set(where) or sim.powered_shifters():
            sys.exit(f"flipped back after {back} game ticks: {len(down)} went, {len(up)} came back")
        late = {where[p]: t for p, t in down.items() if t != first + 2 * turn(*where[p], up=False)}
        start = min(up.values())
        early = {where[p]: t for p, t in up.items() if t != start + 2 * turn(*where[p], up=True)}
        if late or early or start <= max(down.values()):
            sys.exit(f"flipped back after {back} game ticks: off their turns: {sorted(late.items())[:4]} "
                     f"{sorted(early.items())[:4]}")
    print(f"simulated: down from the top and up from the ground, a block every redstone tick, {len(where)} blocks, "
          f"the first {first} game ticks after the lever; twice; flipped back during a move or flicked, it finishes "
          "coming down, then goes up")
    return first


def main():
    b, port, total, _ = wire_up()
    phase = 2 * (COURSE * (TOP + 1) + 60)
    settled, power, out, inp = history(b, port, phase)
    tags = signals(port, out, inp, phase)
    first_block = next(p for p, y, f, c in blocks() if y == TOP and f == 0 and c == 0)
    first = (out[first_block].index(True) + 1) // 2
    allow = joins(b, settled, tags)
    for sig, also in comparator_sides(tags).items():
        allow.setdefault(sig, set()).update(also)
    path, offset = write(b, settled, power, tags, allow, first)
    simulate(path, offset)


if __name__ == "__main__":
    main()
