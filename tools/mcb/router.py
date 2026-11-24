"""A wire router: lays redstone from one spot to another through a crowded layer, the way the iris door taught.

    from mcb import router
    route = router.route(scene, start=(0, 1, 0), start_from="west", end=(20, 4, 9), end_into="south",
                         box=((-2, 0, -2), (30, 10, 20)), delay=12)
    route.apply(scene)            # the parts into the scene's layer (hidden by default)

start is where the first part goes; start_from the direction, from there, of what feeds it (a lever's block, a
powered block, another line's end). end is where the last part goes; end_into the direction, from there, of what it
must power. Both ends are repeaters, so the line takes its input only from start_from and powers only end_into: it
never runs backwards and never feeds anything else.

The rules, from building the iris door:
- every wire and repeater stands on a block (glass is placed where there is none, before the part);
- no new part touches another circuit's redstone, even diagonally (wire connects up and down steps), and no new part
  sits next to anything but air, glass or the line itself, so nothing leaks into or out of the line;
- wire climbs and drops one block per step: the upper wire stands on stone (vanilla joins a step only over a
  conductor; glass breaks the line), nothing else touches that stone, and the block above the lower wire stays empty;
- a repeater comes before the signal has faded (every 15 blocks of wire at most);
- with delay= (redstone ticks), repeater delays are set, and repeaters added on straight stretches, to arrive then.
Check a route in mcb.redstone before trusting it.
"""

import heapq

from .structure import parse_state

DIRS = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
OPPOSITE = {"north": "south", "south": "north", "west": "east", "east": "west"}
REDSTONE = ("redstone_wire", "repeater", "comparator", "torch", "lever", "button", "pressure_plate", "observer",
            "piston", "phase_port", "phase_shifter", "redstone_block", "target", "daylight", "bulb", "lamp", "hopper",
            "dropper", "dispenser", "note_block", "tripwire")


def add(p, v):
    return (p[0] + v[0], p[1] + v[1], p[2] + v[2])


class NoRoute(Exception):
    pass


class Route:
    """The parts of a route: [(pos, state)] in placement order (supports first), and its delay in redstone ticks."""

    def __init__(self, parts, delay, cells):
        self.parts, self.delay, self.cells = parts, delay, cells

    def apply(self, scene, layer="hidden"):
        for pos, state in self.parts:
            scene.set(layer, pos, state)
        return self


class _Space:
    def __init__(self, scene, layer, box):
        self.blocks = {p: parse_state(s)[0].split(":")[-1] for p, (s, _) in scene.layers[layer].items()}
        self.lo, self.hi = box

    def inside(self, p):
        return all(self.lo[i] <= p[i] <= self.hi[i] for i in range(3))

    def kind(self, p):
        return self.blocks.get(p, "air")

    def air(self, p):
        return self.kind(p) in ("air", "cave_air")

    def redstone(self, p):
        k = self.kind(p)
        return any(r in k for r in REDSTONE)


def _clear_around(space, c, path, allow=()):
    """Nothing but air, glass or the line's own last cells next to c (sides, above, below), and no redstone anywhere
    in the 3x3x3 around it (wire connects diagonally up and down), the line's earlier cells included: a line passing
    next to itself would join across and skip its repeaters."""
    recent = set(path[-2:])
    older = set(path[:-2])
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                q = (c[0] + dx, c[1] + dy, c[2] + dz)
                if q == c or q in recent or q in allow:
                    continue
                if q in older:
                    return False
                if space.redstone(q):
                    return False
                if abs(dx) + abs(dy) + abs(dz) == 1 and not (space.air(q) or space.kind(q).endswith("glass")):
                    return False
    return True


def _can_hold(space, c, mine_supports):
    below = (c[0], c[1] - 1, c[2])
    return below in mine_supports or space.air(below) and space.inside(below) or space.kind(below) in (
        "glass", "stone", "smooth_stone", "stone_bricks")


def route(scene, start, start_from, end, end_into, box, layer="hidden", delay=None, turn_cost=0.6):
    """The shortest safe line of wire from start to end (A*), with repeaters at both ends and where the signal
    would fade, and delays set for `delay` redstone ticks if given. Raises NoRoute."""
    space = _Space(scene, layer, box)
    first_dir = OPPOSITE[start_from]      # the line leaves start this way
    last_dir = end_into                   # and reaches end going this way
    allow_start = {add(start, DIRS[start_from])}
    allow_end = {add(end, DIRS[end_into])}
    for p in (start, end):
        if not space.inside(p) or not space.air(p):
            raise NoRoute(f"{p} is not free")

    def ok(c, prev_cells, extra_allow=()):
        return (space.inside(c) and space.air(c) and _can_hold(space, c, set())
                and _clear_around(space, c, tuple(prev_cells), extra_allow))

    # A* over (cell, heading); the path's cells are checked against the cells before them on that path
    def h(c):
        return abs(c[0] - end[0]) + abs(c[1] - end[1]) + abs(c[2] - end[2])

    first = add(start, DIRS[first_dir])
    if not ok(start, [], allow_start) or not ok(first, [start], allow_start):
        raise NoRoute("no room to leave the start")
    frontier = [(h(first) + 1, 1, first, first_dir, (start, first))]
    best = {}
    while frontier:
        f, g, c, heading, path = heapq.heappop(frontier)
        if c == end:
            if heading == last_dir and path[-2] == add(end, tuple(-v for v in DIRS[last_dir])):
                return _finish(scene, space, list(path), first_dir, last_dir, delay)
            continue
        key = (c, heading)
        if best.get(key, 1e9) <= g:
            continue
        best[key] = g
        if len(path) > 400:
            continue
        for d, v in DIRS.items():
            if d == OPPOSITE[heading]:
                continue
            for dy in (0, 1, -1):
                n = (c[0] + v[0], c[1] + dy, c[2] + v[2])
                if n in path:
                    continue
                if dy == 1 and not space.air((c[0], c[1] + 1, c[2])):      # above the lower wire stays empty
                    continue
                if dy == -1 and not space.air((c[0] + v[0], c[1], c[2] + v[2])):
                    continue
                if dy and (c == start or n == end):                         # the ends are repeaters: level
                    continue
                allow = allow_end if n == end else ()
                if not ok(n, path, allow):
                    continue
                if dy == 1 and not step_support(space, (c[0] + v[0], c[1], c[2] + v[2]), path):
                    continue
                if dy == -1 and not step_support(space, (c[0], c[1] - 1, c[2]), path, mine=True):
                    continue
                cost = 1 + (turn_cost if d != heading else 0) + (0.4 if dy else 0)
                heapq.heappush(frontier, (g + cost + h(n), g + cost, n, d, path + (n,)))
    raise NoRoute(f"no line from {start} to {end} inside {box}")


def step_support(space, s, path, mine=False):
    """The block under a step's upper wire becomes stone, which carries the line's signal: free (or stone already,
    or under the line's own wire), and nothing but air, glass or the line next to it."""
    if s in path:
        return False
    if not (space.air(s) or space.kind(s) in ("stone", "smooth_stone")):
        return False
    for v in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, 1, 0), (0, -1, 0)):
        q = add(s, v)
        if q in path:
            continue
        if space.redstone(q) or not (space.air(q) or space.kind(q).endswith("glass")):
            return False
    return True


def _finish(scene, space, cells, first_dir, last_dir, delay):
    """Parts for the cells: repeaters at the ends and where the signal fades, wire elsewhere, glass under all."""
    n = len(cells)
    heading = [None] * n
    for i in range(1, n):
        a, b = cells[i - 1], cells[i]
        heading[i] = next(d for d, v in DIRS.items() if (b[0] - a[0], b[2] - a[2]) == (v[0], v[2]))
    heading[0] = first_dir

    def straight(i):
        """A repeater fits at i: level with both neighbours, in a straight line."""
        if i == 0 or i == n - 1:
            return True
        a, b, c = cells[i - 1], cells[i], cells[i + 1]
        return a[1] == b[1] == c[1] and heading[i] == heading[i + 1]

    repeaters = {0, n - 1}
    run = 0
    last_ok = None
    for i in range(1, n - 1):
        run += 1
        if straight(i):
            last_ok = i
        if run >= 14:
            if last_ok is None or last_ok in repeaters:
                raise NoRoute("no straight stretch for a repeater before the signal fades")
            repeaters.add(last_ok)
            run = i - last_ok
            last_ok = None
    delays = {i: 1 for i in repeaters}
    if delay is not None:
        need = delay - len(repeaters)
        spare = [i for i in range(1, n - 1) if i not in repeaters and straight(i)]
        while need > 0:
            room = [i for i in sorted(delays) if delays[i] < 4]
            if room:
                delays[room[0]] += 1
                need -= 1
            elif spare:
                i = spare.pop(len(spare) // 2)
                repeaters.add(i)
                delays[i] = 1
                need -= 1
            else:
                raise NoRoute(f"cannot make {delay} redstone ticks on this line")
        if need < 0:
            raise NoRoute(f"the line needs at least {len(repeaters)} redstone ticks, more than {delay}")
    parts = []
    upper = {cells[i] if cells[i][1] > cells[i - 1][1] else cells[i - 1]
             for i in range(1, n) if cells[i][1] != cells[i - 1][1]}
    for i, c in enumerate(cells):
        below = (c[0], c[1] - 1, c[2])
        if space.air(below):
            parts.append((below, "minecraft:stone" if c in upper else "minecraft:glass"))
    for i, c in enumerate(cells):
        if i in repeaters:
            facing = OPPOSITE[heading[i] if i > 0 else first_dir]
            if i == n - 1:
                facing = OPPOSITE[last_dir]
            parts.append((c, f"minecraft:repeater[delay={delays[i]},facing={facing}]"))
        else:
            parts.append((c, "minecraft:redstone_wire[east=side,north=side,south=side,west=side]"))
    return Route(parts, sum(delays.values()), cells)
