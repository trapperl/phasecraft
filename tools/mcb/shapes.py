"""Shapes for buildings and scenery, drawn into a Scene layer.

Every function takes the scene, a layer ("visible" by default), positions and a block, which can be a state string
or a Palette (a weighted mix, for texture: "minecraft:stone_bricks" 6, "minecraft:cracked_stone_bricks" 1...).
All are deterministic: a Palette or terrain is seeded.

    from mcb.scene import Scene
    from mcb import shapes
    s = Scene()
    shapes.terrain(s, (0, 0, 0), (63, 63), seed=4)
    shapes.house(s, (20, shapes.ground(s, 20, 20) + 1, 20), (9, 7))
"""

import math
import random

STAIRS_OUT = {"north": "south", "south": "north", "east": "west", "west": "east"}
NATURAL = ("grass_block", "dirt", "sand", "gravel", "coarse_dirt", "podzol")


class Palette:
    """A weighted mix of blocks, picked per position (the same position always gets the same block)."""

    def __init__(self, weights, seed=0):
        self.items = list(weights.items()) if isinstance(weights, dict) else list(weights)
        self.total = sum(w for _, w in self.items)
        self.seed = seed

    def at(self, pos):
        r = random.Random(hash((self.seed,) + tuple(pos)) & 0xFFFFFFFF).random() * self.total
        for block, w in self.items:
            r -= w
            if r < 0:
                return block
        return self.items[-1][0]


def pick(block, pos):
    return block.at(pos) if isinstance(block, Palette) else block


def put(scene, pos, block, layer="visible"):
    scene.set(layer, pos, pick(block, pos))


# --- solids ---
def box(scene, a, b, block, layer="visible", hollow=False):
    """A cuboid between corners a and b; hollow: only its faces."""
    lo = [min(a[i], b[i]) for i in range(3)]
    hi = [max(a[i], b[i]) for i in range(3)]
    for x in range(lo[0], hi[0] + 1):
        for y in range(lo[1], hi[1] + 1):
            for z in range(lo[2], hi[2] + 1):
                if hollow and lo[0] < x < hi[0] and lo[1] < y < hi[1] and lo[2] < z < hi[2]:
                    continue
                put(scene, (x, y, z), block, layer)


def walls(scene, a, b, block, layer="visible"):
    """The four walls of a cuboid (no floor or ceiling)."""
    lo = [min(a[i], b[i]) for i in range(3)]
    hi = [max(a[i], b[i]) for i in range(3)]
    for x in range(lo[0], hi[0] + 1):
        for y in range(lo[1], hi[1] + 1):
            for z in range(lo[2], hi[2] + 1):
                if x in (lo[0], hi[0]) or z in (lo[2], hi[2]):
                    put(scene, (x, y, z), block, layer)


def cylinder(scene, base, radius, height, block, layer="visible", hollow=False):
    """An upright cylinder from base (its centre at the bottom) upward."""
    cx, cy, cz = base
    r2 = (radius + 0.5) ** 2
    inner = (radius - 0.5) ** 2
    for x in range(-radius, radius + 1):
        for z in range(-radius, radius + 1):
            d2 = x * x + z * z
            if d2 <= r2 and (not hollow or d2 > inner):
                for y in range(height):
                    put(scene, (cx + x, cy + y, cz + z), block, layer)


def sphere(scene, centre, radius, block, layer="visible", hollow=False, upper=False):
    """A sphere (or its upper half: a dome) around centre."""
    cx, cy, cz = centre
    r2 = (radius + 0.5) ** 2
    inner = (radius - 0.5) ** 2
    for x in range(-radius, radius + 1):
        for y in range(0 if upper else -radius, radius + 1):
            for z in range(-radius, radius + 1):
                d2 = x * x + y * y + z * z
                if d2 <= r2 and (not hollow or d2 > inner):
                    put(scene, (cx + x, cy + y, cz + z), block, layer)


def arch(scene, centre, radius, depth, block, layer="visible", axis="x"):
    """Clears a round-topped opening (doorway, window) in a wall: a rectangle up to centre, a half circle above.
    axis: the direction the opening runs along ("x": a wall facing x... the opening's width is along z)."""
    cx, cy, cz = centre
    for w in range(-radius, radius + 1):
        for up in range(-radius - 1, radius + 1):
            if up > 0 and w * w + up * up > (radius + 0.3) ** 2:
                continue
            for d in range(depth):
                p = (cx + d, cy + up, cz + w) if axis == "x" else (cx + w, cy + up, cz + d)
                put(scene, p, block, layer)


# --- roofs ---
def gable_roof(scene, a, b, y, stairs, layer="visible", ridge="x", overhang=1, fill=None):
    """A gable roof of stairs over the rectangle a..b (x, z corners), its eaves at height y, the ridge along x or z.
    fill: a block for the gable ends (the triangles under the roof)."""
    x0, x1 = sorted((a[0], b[0]))
    z0, z1 = sorted((a[1], b[1]))
    if ridge == "x":
        span_lo, span_hi = z0 - overhang, z1 + overhang
        along = range(x0 - overhang, x1 + overhang + 1)
        mk = lambda s, t: (t, s)   # (x, z) from (across, along)
        out_lo, out_hi = "north", "south"
    else:
        span_lo, span_hi = x0 - overhang, x1 + overhang
        along = range(z0 - overhang, z1 + overhang + 1)
        mk = lambda s, t: (s, t)
        out_lo, out_hi = "west", "east"
    level = 0
    lo, hi = span_lo, span_hi
    while lo <= hi:
        for t in along:
            for s, out in ((lo, out_lo), (hi, out_hi)):
                x, z = mk(s, t)
                if lo == hi:
                    put(scene, (x, y + level, z), slab_of(stairs), layer)
                else:
                    put(scene, (x, y + level, z), f"{stairs}[facing={STAIRS_OUT[out]},half=bottom]", layer)
        if fill is not None and level > 0:
            for s in range(lo + 1, hi):
                for t in (along[0] + overhang, along[-1] - overhang):
                    x, z = mk(s, t)
                    put(scene, (x, y + level - 1, z), fill, layer)
        lo, hi, level = lo + 1, hi - 1, level + 1
    return y + level


def slab_of(stairs):
    """The slab that goes with a stairs block (minecraft:oak_stairs -> minecraft:oak_slab)."""
    name = stairs.split("[")[0]
    return name.replace("_stairs", "_slab").replace("bricks_slab", "brick_slab")


def hip_roof(scene, a, b, y, stairs, layer="visible", overhang=1):
    """A hipped roof (sloping on all four sides) of stairs over the rectangle a..b."""
    x0, x1 = sorted((a[0], b[0]))
    z0, z1 = sorted((a[1], b[1]))
    x0, x1, z0, z1 = x0 - overhang, x1 + overhang, z0 - overhang, z1 + overhang
    level = 0
    while x0 <= x1 and z0 <= z1:
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if x not in (x0, x1) and z not in (z0, z1):
                    continue
                if x0 == x1 or z0 == z1:
                    put(scene, (x, y + level, z), slab_of(stairs), layer)
                    continue
                out = "north" if z == z0 else "south" if z == z1 else "west" if x == x0 else "east"
                shape = ""
                if x in (x0, x1) and z in (z0, z1):
                    shape = ",shape=outer_" + ("left" if (x == x0) == (z == z0) else "right")
                put(scene, (x, y + level, z), f"{stairs}[facing={STAIRS_OUT[out]},half=bottom{shape}]", layer)
        x0, x1, z0, z1, level = x0 + 1, x1 - 1, z0 + 1, z1 - 1, level + 1
    return y + level


# --- buildings ---
def house(scene, corner, size, walls_block=None, frame="minecraft:stripped_oak_log", roof="minecraft:spruce_stairs",
          floor="minecraft:oak_planks", layer="visible", door="south", storeys=1):
    """A timber-framed house: corner is the floor's lowest corner, size (x, z) its outside. Windows on every wall,
    a door, a gable roof. Returns its top height."""
    walls_block = walls_block or Palette({"minecraft:white_terracotta": 4, "minecraft:smooth_sandstone": 1}, seed=7)
    x0, y0, z0 = corner
    sx, sz = size
    x1, z1 = x0 + sx - 1, z0 + sz - 1
    h = 4 * storeys
    box(scene, (x0, y0, z0), (x1, y0, z1), floor, layer)
    walls(scene, (x0, y0 + 1, z0), (x1, y0 + h, z1), walls_block, layer)
    for x, z in ((x0, z0), (x0, z1), (x1, z0), (x1, z1)):
        box(scene, (x, y0 + 1, z), (x, y0 + h, z), frame, layer)
    for s in range(storeys):
        yb = y0 + 4 * s + 4
        beam = lambda axis: frame if "[" in frame else f"{frame}[axis={axis}]"
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                put(scene, (x, yb, z), beam("x"), layer)
        for z in range(z0, z1 + 1):
            for x in (x0, x1):
                put(scene, (x, yb, z), beam("z"), layer)
        # windows: every third block along each wall, at eye level
        wy = y0 + 4 * s + 2
        for x in range(x0 + 2, x1 - 1, 3):
            for z in (z0, z1):
                put(scene, (x, wy, z), "minecraft:glass_pane", layer)
        for z in range(z0 + 2, z1 - 1, 3):
            for x in (x0, x1):
                put(scene, (x, wy, z), "minecraft:glass_pane", layer)
    dx = (x0 + x1) // 2
    dz = z1 if door == "south" else z0
    put(scene, (dx, y0 + 1, dz), f"minecraft:oak_door[facing={door},half=lower]", layer)
    put(scene, (dx, y0 + 2, dz), f"minecraft:oak_door[facing={door},half=upper]", layer)
    ridge = "x" if sx >= sz else "z"
    return gable_roof(scene, (x0, z0), (x1, z1), y0 + h + 1, roof, layer, ridge=ridge, fill=walls_block)


def tower(scene, base, radius, height, block=None, roof="minecraft:dark_oak_stairs", layer="visible"):
    """A round stone tower with arrow slits and a conical roof."""
    block = block or Palette({"minecraft:stone_bricks": 6, "minecraft:mossy_stone_bricks": 2,
                              "minecraft:cracked_stone_bricks": 1}, seed=3)
    cx, cy, cz = base
    cylinder(scene, base, radius, height, block, layer, hollow=True)
    cylinder(scene, base, radius - 1, 1, "minecraft:spruce_planks", layer)
    for y in range(cy + 3, cy + height - 1, 4):
        for dx, dz in ((radius, 0), (-radius, 0), (0, radius), (0, -radius)):
            put(scene, (cx + dx, y, cz + dz), "minecraft:air", layer)
            put(scene, (cx + dx, y + 1, cz + dz), "minecraft:air", layer)
    # a cone of the roof's slabs and planks
    top = cy + height
    for level in range(radius + 2):
        r = radius + 1 - level
        cylinder(scene, (cx, top + level, cz), r, 1, slab_of(roof) if r == 0 else roof.replace("_stairs", "_planks"),
                 layer, hollow=r > 0)
    return top + radius + 2


# --- scenery ---
def _noise(seed):
    """Smooth 2D value noise in [0, 1): a function of (x, z) and a scale."""
    def lattice(ix, iz):
        return random.Random(hash((seed, ix, iz)) & 0xFFFFFFFF).random()

    def smooth(t):
        return t * t * (3 - 2 * t)

    def at(x, z, scale):
        fx, fz = x / scale, z / scale
        ix, iz = math.floor(fx), math.floor(fz)
        tx, tz = smooth(fx - ix), smooth(fz - iz)
        a = lattice(ix, iz) + (lattice(ix + 1, iz) - lattice(ix, iz)) * tx
        b = lattice(ix, iz + 1) + (lattice(ix + 1, iz + 1) - lattice(ix, iz + 1)) * tx
        return a + (b - a) * tz

    return at


def heights(size, seed=0, base=4, amplitude=10, scale=24):
    """A height map {(x, z): height}: a few octaves of noise, gentle hills."""
    noise = _noise(seed)
    raw = {}
    for x in range(size[0]):
        for z in range(size[1]):
            h = 0.0
            for octave, (s, w) in enumerate(((scale, 1.0), (scale / 2, 0.45), (scale / 4, 0.2))):
                h += w * noise(x + 1000 * octave, z, s)
            raw[(x, z)] = h
    lo, hi = min(raw.values()), max(raw.values())   # stretched to the whole range: noise sums bunch in the middle
    return {p: base + int(round(amplitude * (h - lo) / ((hi - lo) or 1))) for p, h in raw.items()}


def terrain(scene, corner, size, seed=0, water=6, layer="visible", amplitude=10, scale=24):
    """Hills of grass over dirt over stone, sand beaches and water below the water line, and the height map in
    scene.ground (for placing things on it)."""
    x0, y0, z0 = corner
    hmap = heights(size, seed, amplitude=amplitude, scale=scale)
    scene.ground = getattr(scene, "ground", {})
    for (x, z), h in hmap.items():
        X, Z = x0 + x, z0 + z
        for y in range(h + 1):
            depth = h - y
            if depth == 0:
                block = "minecraft:sand" if h <= water + 1 else "minecraft:grass_block"
            elif depth < 3:
                block = "minecraft:sand" if h <= water + 1 else "minecraft:dirt"
            else:
                block = "minecraft:stone"
            put(scene, (X, y0 + y, Z), block, layer)
        for y in range(h + 1, water + 1):
            put(scene, (X, y0 + y, Z), "minecraft:water", layer)
        scene.ground[(X, Z)] = y0 + max(h, water)
    return hmap


def pad(scene, a, b, layer="visible", margin=2):
    """Levels the terrain over the rectangle a..b ((x, z) corners) to the height in its middle, blending into the
    hills around it over `margin` blocks. Returns that height (a building's floor goes one above)."""
    x0, x1 = sorted((a[0], b[0]))
    z0, z1 = sorted((a[1], b[1]))
    level = scene.ground[((x0 + x1) // 2, (z0 + z1) // 2)]
    for x in range(x0 - margin, x1 + margin + 1):
        for z in range(z0 - margin, z1 + margin + 1):
            if (x, z) not in scene.ground:
                continue
            out = max(x0 - x, x - x1, z0 - z, z - z1, 0)
            top = scene.ground[(x, z)]
            want = level if out == 0 else round(level + (top - level) * out / (margin + 1))
            if want > top:
                put(scene, (x, top, z), "minecraft:dirt", layer)   # the old surface, now buried
            for y in range(min(top, want) + 1, max(top, want) + 1):
                put(scene, (x, y, z), "minecraft:air" if y > want else "minecraft:dirt", layer)
            put(scene, (x, want, z), "minecraft:grass_block", layer)
            scene.ground[(x, z)] = want
    return level


def ground(scene, x, z):
    """The top of the terrain at (x, z) (water's surface where there is water)."""
    return scene.ground[(x, z)]


def tree(scene, base, kind="oak", seed=0, layer="visible"):
    """A tree standing on base: oak (round), birch (tall, narrow), spruce (a cone)."""
    rng = random.Random(seed)
    x, y, z = base
    log = f"minecraft:{kind}_log[axis=y]"
    leaves = f"minecraft:{kind}_leaves[persistent=true]"
    if kind == "spruce":
        height = rng.randint(7, 10)
        for level in range(height + 1):
            r = max(0, (height - level) // 3) if level > 2 else 0
            if level > 1:
                for dx in range(-r, r + 1):
                    for dz in range(-r, r + 1):
                        if abs(dx) + abs(dz) <= r + (1 if r > 1 else 0):
                            put(scene, (x + dx, y + level, z + dz), leaves, layer)
        put(scene, (x, y + height + 1, z), leaves, layer)
        for level in range(height):
            put(scene, (x, y + level, z), log, layer)
        _dirt_under(scene, (x, y, z), layer)
        return
    height = rng.randint(4, 6) + (2 if kind == "birch" else 0)
    radius = 2 if kind == "oak" else 1
    top = y + height
    for dx in range(-radius, radius + 1):
        for dz in range(-radius, radius + 1):
            for dy in range(-2, 2):
                d = dx * dx + dz * dz + (dy + 0.5) ** 2
                if d <= (radius + 0.6) ** 2 and rng.random() > 0.1:
                    put(scene, (x + dx, top + dy, z + dz), leaves, layer)
    put(scene, (x, top + 1, z), leaves, layer)
    for level in range(height):
        put(scene, (x, y + level, z), log, layer)
    _dirt_under(scene, (x, y, z), layer)


def _dirt_under(scene, pos, layer):
    """Grass under something solid turns to dirt in the game: put dirt there to begin with."""
    below = (pos[0], pos[1] - 1, pos[2])
    if scene.get(layer, below) == "minecraft:grass_block":
        put(scene, below, "minecraft:dirt", layer)


def forest(scene, area, count, seed=0, kinds=("oak", "oak", "birch", "spruce"), layer="visible", avoid=()):
    """Trees scattered over area ((x0, z0), (x1, z1)) on the terrain, not in water, not on avoided rectangles."""
    rng = random.Random(seed)
    (ax, az), (bx, bz) = area
    placed = []
    for _ in range(count * 6):
        if len(placed) >= count:
            break
        x, z = rng.randint(ax, bx), rng.randint(az, bz)
        if any(abs(x - px) + abs(z - pz) < 5 for px, pz in placed):
            continue
        if any(r[0][0] - 4 <= x <= r[1][0] + 4 and r[0][1] - 4 <= z <= r[1][1] + 4 for r in avoid):   # leaves reach 2
            continue
        top = scene.ground.get((x, z))
        if top is None or "grass_block" not in scene.get(layer, (x, top, z)):
            continue
        tree(scene, (x, top + 1, z), rng.choice(kinds), seed=rng.randrange(1 << 30), layer=layer)
        placed.append((x, z))
    return placed


def path(scene, points, block="minecraft:dirt_path", width=1, layer="visible"):
    """A path along the terrain through (x, z) points, width blocks wide, laid into the ground."""
    for (ax, az), (bx, bz) in zip(points, points[1:]):
        steps = max(abs(bx - ax), abs(bz - az)) or 1
        for i in range(steps + 1):
            x = round(ax + (bx - ax) * i / steps)
            z = round(az + (bz - az) * i / steps)
            for dx in range(-(width // 2), width - width // 2):
                for dz in range(-(width // 2), width - width // 2):
                    X, Z = x + dx, z + dz
                    top = scene.ground.get((X, Z))
                    # only natural ground: a path block is not full height, so a door or wall on it breaks
                    if top is not None and scene.get(layer, (X, top, Z)).split(":")[-1] in NATURAL \
                            and scene.get(layer, (X, top + 1, Z)) == "minecraft:air":
                        put(scene, (X, top, Z), block, layer)


# --- checks ---
NOT_SOLID = ("air", "water", "lava", "glass_pane", "leaves", "slab", "stairs", "door", "torch", "flower", "short_grass",
             "fence", "pane", "bars", "lantern", "sign", "carpet", "snow", "vine", "ladder", "lever", "button", "rail")


def problems(scene, layer="visible"):
    """What the game would change on placing the scene: grass or path under a solid block (turns to dirt), doors
    without a full block under them (break off). [(pos, what)]."""
    out = []
    for pos, (state, _) in scene.layers[layer].items():
        base = state.split("[")[0].split(":")[-1]
        above = scene.get(layer, (pos[0], pos[1] + 1, pos[2])).split("[")[0].split(":")[-1]
        solid_above = not any(n in above for n in NOT_SOLID)
        if base in ("grass_block", "dirt_path") and solid_above:
            out.append((pos, f"{base} under {above} turns to dirt"))
        if base.endswith("_door") and "half=lower" in state:
            below = scene.get(layer, (pos[0], pos[1] - 1, pos[2])).split("[")[0].split(":")[-1]
            if below in ("air", "dirt_path", "water") or any(n in below for n in ("slab", "stairs", "leaves", "pane")):
                out.append((pos, f"door on {below} breaks off"))
    return out
