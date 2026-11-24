#!/usr/bin/env python3
"""A generator for doors of phase shifters that switch in rings: the iris door, the waterfall gate...

A door is a set of cells (x, y) in the plane z = 0, each in a ring (0 switches first when opening, the highest
first when closing), and a hidden phase shifter at every cell. One visible lever (a phase port under it) drives it:
rings switch STEP redstone ticks apart. Every repeater's delay and every part's resting state are computed, and the
written layout is checked in tools/mcb's redstone simulator. A door is described by a Door (see gen_iris.py and
gen_waterfall.py); `build_door(door)` writes its layout file and its GameTest area.

How each cell gets its ring's signal, all hidden:
- Each shifter is powered through a stone right behind it (z = +-1) that a repeater powers: the shifter itself is
  never strongly powered, so it passes nothing on to its neighbours (shifters are solid blocks).
- Even rows are fed from z > 0, odd rows from z < 0, so every repeater has a free row under it for the glass it
  stands on.
- In a row, a run of one ring's cells shares a wire (a "U"); a redstone torch tower right behind it brings that
  ring's signal up from under the floor. Towers of upper rows stand deeper than lower rows' wiring, which they pass.
- Under the floor, each ring has a plane with a wire along x = BUS_X (the bus) and a branch to each tower.
- The controller: the lever's signal L goes down a wire staircase; in each plane two repeater lines of different
  delay merge (OR): the ring rises with the short line and falls with the long one. A ring that must rise late and
  fall early does this on NOT L and is turned over on the way up (an AND). So opening runs ring 0 first and closing
  the highest ring first.
- A door's polarity says what a powered shifter means: "open" (it holds its block: the iris), or "closed" (it holds
  its block while the door is closed and puts it back to open it: the waterfall's gates).
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import redstone_check  # noqa: E402

SHORT = 1                                 # the controller's short line
BUS_X = 9
STAIR_X = 12                              # the staircase's first dust, at y = -1, z = the lever's z
D = None                                  # the Door being built


class Door:
    """What to build. name: the layout's name (contraptions/<name>.txt, structure <name>.snbt); cells: [(x, y)];
    ring(x, y): its ring; step: redstone ticks between rings; planes: ring -> height of its plane (< 0); lever: the
    visible lever's spot (on a floor at y - 1); invert: True if a powered shifter means closed; visible(at): the
    layout's visible lines (at(pos) gives a position's text); header: comment lines; margins: ((x0, y0, z0),
    (x1, y1, z1)) of generator space the layout must include besides the hidden parts; door_block: for the
    layout's door line."""

    def __init__(self, name, cells, ring, step, planes, lever, visible, header, margins, door_block, invert=False):
        self.name, self.cells, self.ring, self.step, self.planes = name, list(cells), ring, step, planes
        self.lever, self.visible, self.header, self.margins = lever, visible, header, margins
        self.door_block, self.invert = door_block, invert
        self.out = ROOT / f"src/gametest/resources/contraptions/{name}.txt"
        self.structure = ROOT / f"src/gametest/resources/data/phasecraft-test/gametest/structure/{name}.snbt"
        self.top = max(planes)            # the highest ring

    def inverted(self, k):
        """Whether ring k's plane works on NOT L (it must rise late and fall early: an AND)."""
        return k * self.step > (self.top - k) * self.step


SUPPORT = "minecraft:glass"
STONE = "minecraft:stone"
WIRE = "minecraft:redstone_wire"
REPEATER = "minecraft:repeater"
TORCH = "minecraft:redstone_torch"
WALL_TORCH = "minecraft:redstone_wall_torch"
OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}


def ring_of(x, y):
    return D.ring(x, y)


def cells():
    return D.cells


class Rep:
    """A repeater whose delay the solver sets (1..4); a flexible one may also be plain wire (0)."""

    def __init__(self, pos, flex=False):
        self.pos, self.flex, self.delay = pos, flex, 1


def lowest(parts):
    return sum(0 if p.flex else 1 for p in parts)


def fill(parts, target):
    """Sets delays so the parts add up to target redstone ticks: wire where flexible, then repeaters, slowest last."""
    if not lowest(parts) <= target <= 4 * len(parts):
        raise ValueError(f"cannot make {target} from {len(parts)} parts (at least {lowest(parts)})")
    for p in parts:
        p.delay = 0 if p.flex else 1
    need = target - lowest(parts)
    for p in parts:
        if p.flex and need:
            p.delay, need = 1, need - 1
    for p in parts:
        add = min(4 - p.delay, need)
        p.delay, need = p.delay + add, need - add


class Grid:
    """Hidden parts: pos -> [name, props, tag, owner], in placement order."""

    def __init__(self, other=None):
        self.parts = {p: list(v) for p, v in other.parts.items()} if other else {}
        self.order = list(other.order) if other else []
        self.allow = dict(other.allow) if other else {}
        self.flex = set(other.flex) if other else set()   # repeater spots that may become wire

    def free(self, pos):
        return pos not in self.parts

    def put(self, pos, name, tag=None, owner=None, lonely=False, **props):
        """Places a part. A lonely one may not touch, sideways, another group's part that would leak into it or
        out of it: a wire no other group's wire, torch or block; a torch or tower block no other group's wire."""
        if pos in self.parts:
            raise ValueError(f"{pos} taken by {self.parts[pos][0]} of {self.parts[pos][3]} (placing {name} for {owner})")
        if lonely and not self.alone(pos, owner, wire=name == WIRE):
            raise ValueError(f"{name} for {owner} at {pos} would touch another group")
        self.parts[pos] = [name, props, tag, owner]
        self.order.append(pos)

    def alone(self, pos, owner, wire):
        for d in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1)):
            q = (pos[0] + d[0], pos[1], pos[2] + d[2])
            part = self.parts.get(q)
            if part is None or part[3] == owner:
                continue
            if part[0] == WIRE or q in self.flex:
                return False
            if wire and part[0] in (TORCH, WALL_TORCH, STONE, "phasecraft:phase_port"):
                return False
        return True

    def stand(self, pos, name, tag=None, owner=None, lonely=False, **props):
        """A part that needs a block under it: glass, unless something solid is there already."""
        below = (pos[0], pos[1] - 1, pos[2])
        if below in self.parts:
            if self.parts[below][0] not in (SUPPORT, STONE):
                raise ValueError(f"no support under {pos}: {self.parts[below][0]} of {self.parts[below][3]}")
        else:
            self.put(below, SUPPORT, owner=owner)   # first: a wire placed on air breaks at the next update
        self.put(pos, name, tag, owner, lonely, **props)


def place_unit(g, name, k, y, s, members, u, tx):
    """A run of one ring's blocks in one row: their chains, a wire joining them (the U), a top torch if the
    signal needs turning over, the tower behind, at column tx, and its branch to the bus. Raises ValueError
    if it does not fit."""
    rep_in = "south" if s == 1 else "north"     # a chain repeater's input side (away from the door)
    to_door = OPPOSITE[rep_in]
    base_y = D.planes[k]
    torch_base = (y - base_y) % 2 == 1
    torches = (y - base_y + 1) // 2 if torch_base else (y - base_y) // 2
    # turned over at the top if the tower, the bus (NOT ring on an inverted plane) and the door's polarity
    # (a powered shifter meaning closed) leave the signal upside down
    need_top = ((torches % 2 == 1) != D.inverted(k)) != D.invert
    sig = name + "b"                             # what the branch carries
    tower_sigs = [f"{name}t{i}" for i in range(torches)]
    top_sig = tower_sigs[-1]
    u_sig = name + "U" if need_top else top_sig
    chain = {}
    for x in members:
        g.put((x, y, s), STONE, owner=name)
        reps = []
        run = len(members)
        for d in range(u - 1, 1, -1):
            pos = (x, y, s * d)
            # every other spot may be wire, alternating by ring (neighbouring runs are neighbouring rings, so
            # their wires never meet), where it touches no other group's wire, torch or block; the repeater right
            # behind the stone stays (a wire there may join its neighbours and stop pointing into the stone)
            flex = d > 2 and run < 12 and (k + d) % 2 == 0 and g.alone(pos, name, wire=True)
            run = run + 1 if flex else 0
            if flex:
                g.flex.add(pos)
            g.stand(pos, REPEATER, u_sig, name, facing=rep_in)
            reps.append(Rep(pos, flex))
        chain[x] = reps
    for x in members:
        g.stand((x, y, s * u), WIRE, u_sig, name, lonely=True)
    tz = s * (u + (2 if need_top else 1))
    if need_top:
        g.put((tx, y, s * (u + 1)), WALL_TORCH, f"{u_sig}<{top_sig}", name, lonely=True, facing=to_door)
    # the tower, bottom up: torches and blocks alternate, the top block (at the row) last.
    # A torch base is a wall torch on a stone fed from the east; a block base is a stone fed from the east.
    h = base_y
    branch = []
    if torch_base:
        g.put((tx + 1, h, tz), STONE, owner=name, lonely=True)
        g.put((tx, h, tz), WALL_TORCH, f"{tower_sigs[0]}<{sig}", name, lonely=True, facing="west")
        first, n, torch_next = tx + 2, 1, False
    else:
        g.put((tx, h, tz), STONE, owner=name, lonely=True)
        first, n, torch_next = tx + 1, 0, True
    for hh in range(base_y + 1, y + 1):
        if torch_next:
            g.put((tx, hh, tz), TORCH, f"{tower_sigs[n]}<{tower_sigs[n - 1] if n else sig}", name, lonely=True)
            n += 1
        else:
            g.put((tx, hh, tz), STONE, owner=name, lonely=True)
        torch_next = not torch_next
    assert n == torches and torch_next, (name, n, torches)   # the last one placed is the top block
    if first > BUS_X - 1:
        raise ValueError("no room for a branch")
    for x in range(first, BUS_X):
        pos = (x, h, tz)
        flex = x < BUS_X - 1 and g.alone(pos, name, wire=True)
        if flex:
            g.flex.add(pos)
        g.stand(pos, REPEATER, sig if x < BUS_X - 1 else f"{sig}<B{k}", name, facing="east")
        branch.append(Rep(pos, flex))
    g.stand((BUS_X, h, tz), WIRE, f"B{k}", "bus", lonely=True)
    return {"name": name, "ring": k, "row": y, "side": s, "members": members, "chain": chain,
            "branch": branch, "plane": h, "tz": tz, "need_top": need_top, "torch_base": torch_base,
            "torches": torches + (1 if need_top else 0),
            "tower": [(tx, hh, tz) for hh in range(base_y, y)], "top": (tx, y, tz)}


def units(y):
    """The row's runs of one ring, inner rings first."""
    row = [x for x, yy in cells() if yy == y]
    runs = []
    for x in row:
        if runs and ring_of(runs[-1][-1], y) == ring_of(x, y):
            runs[-1].append(x)
        else:
            runs.append([x])
    return sorted(runs, key=lambda r: (ring_of(r[0], y), r[0]))


def rows():
    g = Grid()
    groups = []
    ys = sorted({y for _, y in cells()})
    for y in [y for y in ys if y % 2 == 0] + [y for y in ys if y % 2 == 1]:
        s = 1 if y % 2 == 0 else -1
        for members in units(y):
            k = ring_of(members[0], y)
            name = f"r{y}x{members[0]}"
            # the fastest fit among the first few depths that fit: fewest repeaters that must stay
            best = None
            first = None
            for u in range(3, 60):
                if first is not None and u > first + 5:
                    break
                for tx in sorted(members, key=lambda x: (abs(x), -x)):
                    trial = Grid(g)
                    try:
                        placed = place_unit(trial, name, k, y, s, members, u, tx)
                    except ValueError:
                        continue
                    first = u if first is None else first
                    cost = lowest(placed["branch"]) + lowest(placed["chain"][members[0]])
                    if best is None or cost < best[0]:
                        best = (cost, trial, placed)
            if best is None:
                raise ValueError(f"no room for {name}")
            _, g, placed = best
            groups.append(placed)
    return g, groups


def controller(g):
    """The lever's signal down a staircase, then per plane an OR of a short and a long line, out to the bus."""
    lx, ly, lz = D.lever
    g.put((lx, ly - 1, lz), "phasecraft:phase_port", "L", "ctl")
    route = []
    y0 = ly - 1                              # the port's height: the lever's route runs there
    for x in range(lx + 1, STAIR_X):
        flex = x > lx + 1 and x % 2 == 0
        if flex:
            g.flex.add((x, y0, lz))
        g.stand((x, y0, lz), REPEATER, "L", "ctl", facing="west")
        route.append(Rep((x, y0, lz), flex))
    stair = {}
    for i in range(0, y0 - min(D.planes.values()) + 1):
        pos = (STAIR_X + i, y0 - i, lz)
        g.put((pos[0], pos[1] - 1, lz), STONE, owner="ctl")
        g.put(pos, WIRE, "L", "ctl")
        stair[pos[1]] = pos
    planes = {}
    for k, h in D.planes.items():
        tx, _, tz = stair[h]
        inv = D.inverted(k)
        o = f"P{k}"
        g.stand((tx, h, tz + 1), REPEATER, f"P{k}in<L", o, facing="north")
        r0 = Rep((tx, h, tz + 1))
        z = tz + 2
        d1 = f"P{k}in"
        if inv:
            g.put((tx, h, z), STONE, owner=o)
            g.put((tx, h, z + 1), WALL_TORCH, f"P{k}x<P{k}in", o, facing="south")
            z += 2
            d1 = f"P{k}x"
        g.stand((tx, h, z), WIRE, d1, o)
        lines = {}
        for side, line in ((-1, "s"), (1, "l")):
            sl = f"P{k}{line}"
            parts = []
            short = line == "s"     # the short line may be wire but for its last repeater (the diode into N)
            g.stand((tx + side, h, z), REPEATER, f"{sl}<{d1}", o, facing="east" if side == -1 else "west")
            parts.append(Rep((tx + side, h, z), short))
            g.stand((tx + 2 * side, h, z), WIRE, sl, o)
            for dz in (1, 2, 3):
                g.stand((tx + 2 * side, h, z + dz), REPEATER, sl, o, facing="north")
                parts.append(Rep((tx + 2 * side, h, z + dz), short))
            g.stand((tx + 2 * side, h, z + 4), WIRE, sl, o)
            g.stand((tx + side, h, z + 4), REPEATER, sl, o, facing="west" if side == -1 else "east")
            parts.append(Rep((tx + side, h, z + 4)))
            lines[line] = parts
        g.allow[f"P{k}s"] = [d1]      # the short line's wire joins the input's: one signal
        g.allow[d1] = [f"P{k}s"]
        g.stand((tx, h, z + 4), WIRE, f"P{k}n", o)
        g.allow[f"P{k}n"] = [f"P{k}s", f"P{k}l"]
        g.stand((tx, h, z + 5), REPEATER, f"B{k}<P{k}n", o, facing="north")
        out = [r0, Rep((tx, h, z + 5))]
        g.stand((tx, h, z + 6), WIRE, f"B{k}", o)
        for x in range(tx - 1, BUS_X, -1):
            flex = x > BUS_X + 1     # wire, but for the repeater into the bus (full strength there)
            if flex:
                g.flex.add((x, h, z + 6))
            g.stand((x, h, z + 6), REPEATER, f"B{k}", o, facing="east")
            out.append(Rep((x, h, z + 6), flex))
        planes[k] = {"short": lines["s"], "long": lines["l"], "out": out, "entry": z + 6, "inv": inv, "h": h}
    return route, planes


def buses(g, groups, planes):
    """Each plane's bus along x = BUS_X: dust, with a repeater (facing away from the entry) every 12 blocks.
    Returns, per ring, the repeaters' delay up to each z."""
    delays = {}
    for k, plane in planes.items():
        h, entry = plane["h"], plane["entry"]
        junctions = {gr["tz"] for gr in groups if gr["ring"] == k}
        lo, hi = min(junctions | {entry}), max(junctions | {entry})
        at = {entry: 0}
        if g.free((BUS_X, h, entry)):
            g.stand((BUS_X, h, entry), WIRE, f"B{k}", "bus")
        for direction in (-1, 1):
            run, count, z = 0, 0, entry + direction
            while lo <= z <= hi:
                pos = (BUS_X, h, z)
                if g.free(pos) and run >= 12:
                    g.stand(pos, REPEATER, f"B{k}", "bus", facing="north" if direction == 1 else "south")
                    plane.setdefault("bus_reps", []).append(pos)
                    run, count = 0, count + 1
                else:
                    if g.free(pos):
                        g.stand(pos, WIRE, f"B{k}", "bus")
                    run += 1
                at[z] = count
                z += direction
        delays[k] = at
    return delays


def solve(groups, route, planes, bus):
    """Every repeater's delay: rings open STEP apart from the centre out and close STEP apart from the outside in."""
    # downstream of the bus entry, per group: bus repeaters, torches, branch and chain; equal within a ring
    delta = {}
    for k in planes:
        paths = []
        for gr in groups:
            if gr["ring"] != k:
                continue
            # the members' chains are set alike: a flexible spot is flexible only if it is in every member's chain
            chains = [gr["chain"][x] for x in gr["members"]]
            for spots in zip(*chains):
                if not all(r.flex for r in spots):
                    for r in spots:
                        r.flex = False
            paths.append((gr, bus[k][gr["tz"]] + gr["torches"], gr["branch"] + chains[0]))
        delta[k] = max(fixed + lowest(reps) for _, fixed, reps in paths)
        for gr, fixed, reps in paths:
            fill(reps, delta[k] - fixed)
            for x in gr["members"][1:]:
                for a, b in zip(gr["chain"][gr["members"][0]], gr["chain"][x]):
                    b.delay = a.delay
    fill(route, lowest(route))
    # Ring k opens at O + k * STEP and closes at O + (top - k) * STEP. Its two lines differ by
    # |k - (top - k)| * STEP; an ordinary plane opens with the short line, an inverted one (on NOT L, one torch more)
    # with the long one. O: the earliest every plane can make; each plane's out line makes up the rest.
    fixed = {}
    for k, p in planes.items():
        gap = abs(k - (D.top - k)) * D.step
        p["long_d"] = max(SHORT + gap, lowest(p["long"]))
        p["short_d"] = p["long_d"] - gap
        fixed[k] = delta[k] + (1 + p["long_d"] if p["inv"] else p["short_d"])
    base = max(lowest(p["out"]) + fixed[k] - k * D.step for k, p in planes.items())
    for k, p in planes.items():
        fill(p["out"], base + k * D.step - fixed[k])
        fill(p["short"], p["short_d"])
        fill(p["long"], p["long_d"])
    return lowest(route) + base


def settle(g, groups, planes):
    """The resting state (lever off, door closed): powered repeaters and lit torches. At rest every ring is off; an
    inverted plane carries NOT ring (on); the shifters are powered if the door's polarity says closed = powered."""
    on = set()
    lit = set()
    for p in planes.values():
        if p["inv"]:   # NOT L is on at rest, and with it this ring's bus
            on |= {r.pos for r in p["short"] + p["long"] + p["out"][1:]} | set(p.get("bus_reps", []))
    for gr in groups:
        v = D.inverted(gr["ring"])           # the bus at rest
        if v:
            on |= {r.pos for r in gr["branch"]}
        for pos in sorted(gr["tower"], key=lambda q: q[1]):
            if g.parts[pos][0] in (TORCH, WALL_TORCH):
                v = not v
                if v:
                    lit.add(pos)
        u = v                                 # the top block, powered by the last torch
        if gr["need_top"]:
            tx, ty, tz = gr["top"]
            w = (tx, ty, tz - gr["side"])
            u = not v
            if u:
                lit.add(w)
        assert u == D.invert, (gr["name"], u)
        if u:
            on |= {r.pos for reps in gr["chain"].values() for r in reps}
    for pos, part in g.parts.items():
        name, props = part[0], part[1]
        if name == REPEATER:
            props["powered"] = "true" if pos in on else "false"
        elif name in (TORCH, WALL_TORCH):
            owner = part[3]
            if owner.startswith("P"):
                props["lit"] = "true"     # NOT L, lever off
            else:
                props["lit"] = "true" if pos in lit else "false"


def shapes(g):
    """Wire shapes as a player's wire would take them (the checker's connection rules)."""
    parts = {p: (v[0].split(":")[-1], v[1], v[2]) for p, v in g.parts.items()}
    lay = redstone_check.Layout(parts)
    for p, v in g.parts.items():
        if v[0] != WIRE:
            continue
        conn = [d for d in redstone_check.HORIZONTAL if lay.wire_connects(p, d)]
        props = {}
        for d in redstone_check.HORIZONTAL:
            up = redstone_check.add(redstone_check.add(p, d), "up")
            props[d] = ("up" if lay.kind(up) == "redstone_wire" else "side") if d in conn else "none"
        if len(conn) == 1:
            props[OPPOSITE[conn[0]]] = "side"
        v[1] = props


def write(g, groups, opens):
    (mx0, my0, mz0), (mx1, my1, mz1) = D.margins
    xs = [p[0] for p in g.parts] + [mx0, mx1]
    ys = [p[1] for p in g.parts] + [my0, my1]
    zs = [p[2] for p in g.parts] + [mz0, mz1]
    ox, oy, oz = -min(xs), -min(ys), -min(zs)
    size = (max(xs) + ox + 1, max(ys) + oy + 1, max(zs) + oz + 1)
    D.offset = (ox, oy, oz)

    def at(p):
        return f"{p[0] + ox} {p[1] + oy} {p[2] + oz}"

    lines = [f"# {line}" if line else "#" for line in D.header(ox, oy, oz, opens)] + [
        f"size {size[0]} {size[1]} {size[2]}",
        f"lever {at(D.lever)}",
        f"door {D.door_block}",
    ]
    for sig, also in g.allow.items():
        lines.append(f"allow {sig} {' '.join(also)}")
    lines += D.visible(at)
    lines.append(f"visible {at(D.lever)} minecraft:lever[face=floor,facing=north,powered=false]")
    for pos in g.order:
        name, props, tag, _ = g.parts[pos]
        state = name + ("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else "")
        lines.append(f"hidden {at(pos)} {state}" + (f" @{tag}" if tag else ""))
    for x, y in cells():
        gr = next(gr for gr in groups if gr["row"] == y and x in gr["members"])
        feed = g.parts[(x, y, 2 * gr["side"])][2]
        # placed unpowered: a shifter that sees its power arrive takes the visible block at its spot
        lines.append(f"hidden {at((x, y, 0))} phasecraft:phase_shifter @{feed}")
    D.out.write_text("\n".join(lines) + "\n")
    # the GameTest's empty area, sized to the layout (the test builds everything itself)
    D.structure.write_text("{\n    DataVersion: 2730,\n    size: [%d, %d, %d],\n    data: [],\n    palette: []\n}\n" % size)
    hidden = len(g.parts) + len(cells())
    print(f"wrote {D.out.relative_to(ROOT)}: size {size[0]}x{size[1]}x{size[2]}, {hidden} hidden blocks, "
          f"first ring {opens} redstone ticks after the lever")


def build():
    g, groups = rows()
    route, planes = controller(g)
    bus = buses(g, groups, planes)
    opens = solve(groups, route, planes, bus)
    return g, groups, route, planes, opens


def build_door(door):
    """Builds a door: its layout file and GameTest area, checked in the redstone simulator."""
    global BUS_X, STAIR_X, D
    D = door
    for BUS_X, gap in ((x, gap) for x in range(9, 20) for gap in (3, 6, 9)):
        STAIR_X = BUS_X + gap
        try:
            g, groups, route, planes, opens = build()
            break
        except ValueError as e:
            print(f"bus at x = {BUS_X}, staircase at {STAIR_X}: {e}")
    else:
        sys.exit("no layout found")
    for r in route + [r for p in planes.values() for r in p["short"] + p["long"] + p["out"]] + \
            [r for gr in groups for r in gr["branch"] + [c for reps in gr["chain"].values() for c in reps]]:
        if r.delay == 0:
            g.parts[r.pos][0], g.parts[r.pos][1] = WIRE, {}
        else:
            g.parts[r.pos][1]["delay"] = str(r.delay)
    for v in g.parts.values():
        if v[0] == REPEATER:
            v[1].setdefault("delay", "1")
    settle(g, groups, planes)
    shapes(g)
    write(g, groups, opens)
    simulate(opens)


def simulate(opens):
    """Runs the written layout in tools/mcb's redstone simulator: closed at rest, then each ring opens at its tick
    (STEP apart, ring 0 first) and closes in the reverse order."""
    from mcb.redstone import Sim
    from mcb.scene import Scene
    scene = Scene.from_layout(D.out)
    sim = Sim(scene)
    if sim.popped:
        sys.exit(f"parts with nothing to stand on: {sim.popped[:5]}")
    sim.run(40)
    ox, oy, oz = D.offset
    door = {(x + ox, y + oy, oz): (x, y) for x, y in cells()}
    closed_powered = set(door) if D.invert else set()
    if sim.powered_shifters() != closed_powered:
        sys.exit(f"not closed at rest: powered {sorted(sim.powered_shifters())}")
    port = next(p for p, (k, _) in sim.parts.items() if k == "phase_port")
    for on in (True, False):
        sim.events.clear()
        t0 = sim.tick
        sim.set_input(port, on)
        sim.run((D.top + 1) * D.step * 2 + opens * 2 + 40)
        rings = {}
        for t, p, _, v in sim.events:
            rings.setdefault(D.ring(*door[p]), set()).add(t - t0)
            if v != (on != D.invert):
                sys.exit(f"shifter at {p} switched the wrong way")
        want = {k: {2 * (opens + (k if on else D.top - k) * D.step)} for k in D.planes}
        if rings != want:
            sys.exit(f"simulated {'opening' if on else 'closing'}: {rings}, expected {want}")
    print(f"simulated: rings open at {[2 * (opens + k * D.step) for k in sorted(D.planes)]} game ticks after the "
          "lever, close in reverse")


