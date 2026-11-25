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

route_all routes many lines together so that none touches another (negotiated congestion, PathFinder).
"""

import heapq

from .structure import parse_state

DIRS = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
OPPOSITE = {"north": "south", "south": "north", "west": "east", "east": "west", "up": "down", "down": "up"}
HORIZONTAL = ("north", "south", "west", "east")
MAX_EXPAND = 250000       # search states one route may look at before it gives up
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
        self.facing = {p: parse_state(s)[1].get("facing") for p, (s, _) in scene.layers[layer].items()}
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


def _end_allow(end, end_into):
    """What may be next to a line's last part, a repeater: what it powers, and anything beside or above it (a
    repeater takes its signal only from behind; only a diode pointing into its side could lock it)."""
    out = {add(end, DIRS[end_into]), add(end, (0, 1, 0))}
    for d in HORIZONTAL:
        if d not in (end_into, OPPOSITE[end_into]):
            out.add(add(end, DIRS[d]))
    return out


def _side_diode(space, end, end_into):
    """Whether a repeater or comparator beside end points into its side (it would lock the end's repeater)."""
    for d in HORIZONTAL:
        if d in (end_into, OPPOSITE[end_into]):
            continue
        q = add(end, DIRS[d])
        if space.kind(q) in ("repeater", "comparator") and space.facing.get(q) == d:
            return True             # its output faces end (a diode's facing is its input side)
    return False


def _clear_around(space, c, path, allow=()):
    """Nothing but air, glass or the line's own last cells next to c (sides, above, below), and no redstone where a
    wire at c could join or power it: beside it or a step above or below it, along x or z (wire never connects
    across a corner), the line's earlier cells included: a line passing next to itself would join across and skip
    its repeaters."""
    recent = set(path[-2:])
    older = set(path[:-2])
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx and dz:
                    continue
                q = (c[0] + dx, c[1] + dy, c[2] + dz)
                if q == c or q in recent or q in allow:
                    continue
                if q in older:
                    return False
                if _side_on(space, q, dx, dy, dz):
                    continue
                if space.redstone(q):
                    return False
                if abs(dx) + abs(dy) + abs(dz) == 1 and not (space.air(q) or space.kind(q).endswith("glass")):
                    return False
    return True


def _side_on(space, q, dx, dy, dz):
    """A repeater or comparator at q that a wire at q - (dx, dy, dz) cannot touch: level with it and beside its
    flank (a diode takes and gives its signal only at its ends), or a step above or below it (steps join only
    wire)."""
    if space.kind(q) not in ("repeater", "comparator"):
        return False
    if dy:
        return dx or dz
    facing = space.facing.get(q)
    return (dx == 0) if facing in ("east", "west") else (dz == 0) if facing in ("north", "south") else False


def _can_hold(space, c, mine_supports):
    below = (c[0], c[1] - 1, c[2])
    return below in mine_supports or space.air(below) and space.inside(below) or space.kind(below) in (
        "glass", "stone", "smooth_stone", "stone_bricks")


def route(scene, start, start_from, end, end_into, box, layer="hidden", delay=None, turn_cost=0.6, extra=None,
          space=None):
    """The shortest safe line of wire from start to end (A*), with repeaters at both ends and where the signal
    would fade, and delays set for `delay` redstone ticks if given. Raises NoRoute.
    extra(cell) adds to a cell's cost (route_all: the price of space other lines want)."""
    space = space or _Space(scene, layer, box)
    first_dir = OPPOSITE[start_from]      # the line leaves start this way
    last_dir = end_into                   # and reaches end going this way
    allow_start = {add(start, DIRS[start_from])}
    allow_end = _end_allow(end, end_into)
    for p in (start, end):
        if not space.inside(p) or not space.air(p):
            raise NoRoute(f"{p} is not free")
    if _side_diode(space, end, end_into):
        raise NoRoute(f"a diode beside {end} would lock it")

    def ok(c, prev_cells, extra_allow=()):
        return (space.inside(c) and space.air(c) and _can_hold(space, c, set())
                and _clear_around(space, c, tuple(prev_cells), extra_allow))

    # A* over (cell, heading); the path's cells are checked against the cells before them on that path
    def h(c):
        return abs(c[0] - end[0]) + abs(c[1] - end[1]) + abs(c[2] - end[2])

    first = add(start, DIRS[first_dir])
    if not ok(start, [], allow_start) or not ok(first, [start], allow_start):
        raise NoRoute("no room to leave the start")
    path = _search(space, [((start, first), first_dir)], end, last_dir, allow_end, turn_cost, extra, level={start})
    if path is None:
        raise NoRoute(f"no line from {start} to {end} inside {box}")
    return _finish(scene, space, list(path), first_dir, last_dir, delay)


def _search(space, starts, end, last_dir, allow_end, turn_cost, extra, level=(), since0=None):
    """A* over (cell, heading) from each (path, heading) in starts (a path's last cell is its first new one) to
    end, reached going last_dir. A path's cells are checked against the cells before them on that path. No step
    up or down out of a cell in `level`, or into end (both are repeaters). It keeps count of the cells since the
    last one that could hold a repeater (level, between two cells in a straight line), and never lets the signal
    fade before one: since0 gives each start's count (a repeater start: 0; a tree's wire: 16 - its strength).
    Returns the path, or None."""
    def ok(c, prev_cells, extra_allow=()):
        return (space.inside(c) and space.air(c) and _can_hold(space, c, set())
                and _clear_around(space, c, tuple(prev_cells), extra_allow))

    def h(c):
        return abs(c[0] - end[0]) + abs(c[1] - end[1]) + abs(c[2] - end[2])
    frontier = [(h(path[-1]) + 1, 1, path[-1], heading, path, (since0 or {}).get(path[0], 0) + 1)
                for path, heading in starts]
    heapq.heapify(frontier)
    best = {}
    expanded = 0
    while frontier:
        f, g, c, heading, path, since = heapq.heappop(frontier)
        expanded += 1
        if expanded > MAX_EXPAND:
            return None                    # a way this long to find is not worth it: report no route
        if c == end:
            if heading == last_dir and path[-2] == add(end, tuple(-v for v in DIRS[last_dir])):
                return path
            continue
        # a state no cheaper and no further from fading than one already taken from here is no use
        seen = best.setdefault((c, heading), [])
        if any(g2 <= g and s2 <= since for g2, s2 in seen):
            continue
        seen[:] = [(g2, s2) for g2, s2 in seen if not (g <= g2 and since <= s2)] + [(g, since)]
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
                if dy and (c in level or n == end):                         # the ends are repeaters: level
                    continue
                allow = allow_end if n == end else ()
                if not ok(n, path, allow):
                    continue
                if dy == 1 and not step_support(space, (c[0] + v[0], c[1], c[2] + v[2]), path):
                    continue
                if dy == -1 and not step_support(space, (c[0], c[1] - 1, c[2]), path, mine=True):
                    continue
                # c can hold a repeater if the line runs straight and level through it
                straight = (dy == 0 and d == heading and len(path) >= 2 and path[-2][1] == c[1]
                            and path[-2] not in level)
                nxt = 1 if straight else since + 1
                if nxt >= 14 and n != end:
                    continue                   # would fade before a repeater could go in
                cost = 1 + (turn_cost if d != heading else 0) + (0.4 if dy else 0) + (extra(n) if extra else 0)
                heapq.heappush(frontier, (g + cost + h(n), g + cost, n, d, path + (n,), nxt))
    return None


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


def _finish(scene, space, cells, first_dir, last_dir, delay, start_strength=None):
    """Parts for the cells: repeaters at the ends and where the signal fades, wire elsewhere, glass under all.
    With start_strength, cells[0] is a tree's wire at that strength (a branch): it is not placed again, and the
    first repeater comes before the signal from it fades."""
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

    repeaters = {0, n - 1} if start_strength is None else {n - 1}
    run = 0 if start_strength is None else 16 - start_strength
    last_ok = None
    for i in range(1, n - 1):
        run += 1
        if straight(i):
            last_ok = i
        if run >= 14:
            if last_ok is None or last_ok in repeaters or last_ok == 0:
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
    skip = 0 if start_strength is None else 1           # a branch's first cell is the tree's
    upper = {cells[i] if cells[i][1] > cells[i - 1][1] else cells[i - 1]
             for i in range(1, n) if cells[i][1] != cells[i - 1][1]}
    for i, c in enumerate(cells):
        below = (c[0], c[1] - 1, c[2])
        if i >= skip and space.air(below):
            parts.append((below, "minecraft:stone" if c in upper else "minecraft:glass"))
    for i, c in enumerate(cells):
        if i < skip:
            continue
        if i in repeaters:
            facing = OPPOSITE[heading[i] if i > 0 else first_dir]
            if i == n - 1:
                facing = OPPOSITE[last_dir]
            parts.append((c, f"minecraft:repeater[delay={delays[i]},facing={facing}]"))
        else:
            parts.append((c, "minecraft:redstone_wire[east=side,north=side,south=side,west=side]"))
    route = Route(parts, sum(delays.values()), cells[skip:])
    # each new cell's strength and the redstone ticks to it from the line's (or tree's) start
    route.strength, route.ticks = {}, {}
    run = 0 if start_strength is None else 16 - start_strength
    ticks = 0
    for i in range(skip, n):
        if i in repeaters:
            ticks += delays[i]
            run = 0
            route.strength[cells[i]] = 15
        else:
            run += 1
            route.strength[cells[i]] = 16 - run
        route.ticks[cells[i]] = ticks
    route.repeaters = {cells[i] for i in repeaters if i >= skip}
    return route


# --- trees: one source, many ends ---
class Tree:
    """A source and several ends, joined by one tree of wire: its parts, cells, and per end the redstone ticks from
    the source (ticks)."""

    def __init__(self):
        self.parts, self.cells, self.ends = [], [], {}
        self.strength, self.at, self.repeaters = {}, {}, set()

    @property
    def delay(self):
        return max(self.ends.values())

    def add(self, r, base=0):
        self.parts += r.parts
        self.cells += r.cells
        self.repeaters |= r.repeaters
        self.strength.update(r.strength)
        for c, t in r.ticks.items():
            self.at[c] = base + t

    def apply(self, scene, layer="hidden"):
        for pos, state in self.parts:
            scene.set(layer, pos, state)
        return self


def tree(scene, start, start_from, ends, box, layer="hidden", turn_cost=0.6, extra=None, space=None):
    """As _tree; when an end cannot be reached, starts again with that end first (its earlier branches may have
    walled it in), once per end."""
    ends = list(ends)
    first = []
    for _ in range(len(ends)):
        order = first + [e for e in ends if e not in first]
        try:
            return _tree(scene, start, start_from, order, box, layer, turn_cost, extra, space)
        except NoRoute as e:
            blocked = getattr(e, "end", None)
            if blocked is None or blocked in first:
                raise
            first.insert(0, blocked)
    raise NoRoute(f"no tree from {start} to all {len(ends)} ends")


def _tree(scene, start, start_from, ends, box, layer="hidden", turn_cost=0.6, extra=None, space=None):
    """Routes a tree from start (a repeater fed from start_from) to every (end, end_into) in ends: the first end by
    route(), each next one (nearest first) from the wire already laid, never touching the tree but where it
    branches. Raises NoRoute. Returns a Tree; tree.ends[end] is the redstone ticks from start to that end's
    repeater output."""
    space = space or _Space(scene, layer, box)
    ends = list(ends)
    first_end, first_into = ends[0]
    t = Tree()
    r = route(scene, start, start_from, first_end, first_into, box, layer, None, turn_cost, extra, space)
    t.add(r)
    t.ends[first_end] = t.at[first_end]
    rest = ends[1:]
    while rest:
        laid = dict(space.blocks)
        for pos, state in t.parts:
            laid[pos] = parse_state(state)[0].split(":")[-1]
        sp = _Space.__new__(_Space)
        sp.blocks, sp.lo, sp.hi, sp.facing = laid, space.lo, space.hi, space.facing
        rest.sort(key=lambda e: min(sum(abs(a - b) for a, b in zip(e[0], c)) for c in t.cells))
        end, into = rest.pop(0)
        allow_end = _end_allow(end, into)
        if not sp.inside(end) or not sp.air(end):
            raise NoRoute(f"{end} is not free")
        starts = []
        for j in t.cells:
            if j in t.repeaters or t.strength.get(j, 0) < 12:      # room for a repeater before it fades
                continue
            for d, v in DIRS.items():
                if d in ("up", "down"):
                    continue
                first = add(j, v)
                if (sp.inside(first) and sp.air(first) and _can_hold(sp, first, set())
                        and _clear_around(sp, first, (j,), {j})):
                    starts.append(((j, first), d))
        path = _search(sp, starts, end, into, allow_end, turn_cost, extra, level={p[0][0] for p in starts},
                       since0={p[0][0]: 16 - t.strength[p[0][0]] for p in starts})
        if path is None:
            e = NoRoute(f"no branch to {end} inside {box}")
            e.end = (end, into)
            raise e
        j = path[0]
        try:
            b = _finish(scene, sp, list(path), None, into, None, start_strength=t.strength[j])
        except NoRoute as e:
            e.end = (end, into)
            raise
        t.add(b, base=t.at[j])
        t.ends[end] = t.at[end]
    return t


# --- many lines at once: negotiated congestion (PathFinder, as FPGA routers do it; docs/BUILD_RULES.md, R10) ---
def _halo(c):
    """Where another line's wire would join or touch a wire at c: the same spot, beside it, or a step above or
    below it (wire connects along x and z only, never across a corner)."""
    out = []
    for dy in (-1, 0, 1):
        for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            out.append((c[0] + dx, c[1] + dy, c[2] + dz))
    return out


class TreeNet:
    """One tree to route: as tree()'s arguments."""

    def __init__(self, start, start_from, ends, name=None):
        self.start, self.start_from, self.ends = start, start_from, list(ends)
        self.name = name or f"{start}->{len(self.ends)} ends"


class Net:
    """One line to route: as route()'s arguments."""

    def __init__(self, start, start_from, end, end_into, delay=None, name=None):
        self.start, self.start_from, self.end, self.end_into = start, start_from, end, end_into
        self.delay, self.name = delay, name or f"{start}->{end}"


def route_all(scene, nets, box, layer="hidden", rounds=40, present=0.6, growth=1.6, history=0.4):
    """Routes every net (Net: a line; TreeNet: a tree) so that no two touch (see _halo), negotiating: each round every net is routed again, paying
    for cells other nets use (more each round) and for cells that stayed contested (history), until no cell is
    contested. Returns [Route] in the nets' order; raises NoRoute if a net has no route at all, or if they still
    conflict after `rounds` rounds. Existing blocks are hard obstacles, as in route()."""
    space = _Space(scene, layer, box)
    near = {}                  # cell -> {net: count}: nets whose wire is at, beside, or a step from it (_halo)
    fills = {}                 # cell -> {net: count}: nets with a part (wire, repeater, support) there
    hist = {}
    routes = [None] * len(nets)

    def bump(table, cell, i, sign):
        m = table.setdefault(cell, {})
        m[i] = m.get(i, 0) + sign
        if m[i] == 0:
            del m[i]

    def mark(i, r, sign):
        for c in r.cells:
            for q in _halo(c):
                bump(near, q, i, sign)
        for pos, _ in r.parts:
            bump(fills, pos, i, sign)

    def others(i, c):
        """Nets a wire of net i at c would touch."""
        return len((set(near.get(c, ())) | set(fills.get(c, ()))) - {i})

    def conflicted(i):
        """Net i's cells another net touches: its wires (within another's halo, or on another's part) and its
        supports (on another's part)."""
        r = routes[i]
        bad = [c for c in r.cells if others(i, c)]
        bad += [pos for pos, _ in r.parts if pos not in r.cells and set(fills.get(pos, ())) - {i}]
        return bad

    factor = 0.0
    for rnd in range(rounds):
        for i, net in enumerate(nets):
            if routes[i] is not None and rnd and not conflicted(i):
                continue
            old = routes[i]
            if old is not None:
                mark(i, old, -1)

            def extra(c, i=i):
                return hist.get(c, 0) + factor * others(i, c) * (1 + hist.get(c, 0))
            try:
                if isinstance(net, TreeNet):
                    routes[i] = tree(scene, net.start, net.start_from, net.ends, box, layer, extra=extra, space=space)
                else:
                    routes[i] = route(scene, net.start, net.start_from, net.end, net.end_into, box, layer, net.delay,
                                      extra=extra, space=space)
            except NoRoute as e:
                if old is None:
                    raise NoRoute(f"{net.name}: {e}") from None
                routes[i] = old            # priced out of every way this round: keep last round's, negotiate on
            mark(i, routes[i], +1)
        contested = {c for i in range(len(nets)) for c in conflicted(i)}
        if not contested:
            return routes
        for c in contested:
            hist[c] = hist.get(c, 0) + history
        factor = present if factor == 0 else factor * growth
    raise NoRoute(f"{len(contested)} cells still contested after {rounds} rounds: {sorted(contested)[:6]}")
