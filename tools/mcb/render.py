"""PNG pictures of a Scene: isometric (from any of the four corners), top-down, or straight from a side.

Colours come from the game's own textures: for each block, the average colour of the texture its model shows (the
top for top faces), read from the Minecraft client jar in Gradle's cache and kept in build/mcb/colors.json. Nothing
from the jar is copied into the repository.

Hidden blocks are drawn see-through and tinted purple, as the in-game overlay shows them; pass layers=("visible",)
to see what a player sees without it.
"""

import io
import json
import pathlib
import zipfile

from PIL import Image, ImageDraw

from .structure import parse_state

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
CACHE = ROOT / "build" / "mcb" / "colors.json"
JARS = [pathlib.Path.home() / ".gradle/caches/fabric-loom/26.3/minecraft-client.jar"]
HIDDEN_TINT = (170, 90, 230)

# shapes: (height of the top face, inset of the sides), in block units
FLAT = ("redstone_wire", "rail", "carpet", "pressure_plate", "lily_pad", "tripwire", "snow")
LOW = ("repeater", "comparator", "daylight_detector", "slab", "trapdoor")
THIN = ("torch", "fence", "wall", "pane", "bars", "rod", "button", "lever", "sign", "flower", "sapling", "grass",
        "fern", "bush", "chain", "lantern", "candle", "tripwire_hook", "dead_bush", "sugar_cane", "vine", "ladder",
        "door", "glass_pane")


def shape(name):
    base = name.split(":")[-1]
    if base in ("short_grass", "tall_grass") or any(base.endswith(s) or s == base for s in ("_torch", "torch")):
        return 0.6, 0.4
    if any(f in base for f in FLAT):
        return 0.07, 0.0
    if any(f in base for f in LOW):
        return 0.5, 0.0
    if any(t in base for t in THIN):
        return 0.9, 0.35
    return 1.0, 0.0


class Colors:
    """Block name -> ((r, g, b, a) side, (r, g, b, a) top), from the jar's models and textures."""

    def __init__(self):
        self.cache = json.loads(CACHE.read_text()) if CACHE.exists() else {}
        self.jar = None

    def _zip(self):
        if self.jar is None:
            path = next((j for j in JARS if j.exists()), None)
            self.jar = zipfile.ZipFile(path) if path else False
        return self.jar

    def _json(self, path):
        try:
            return json.loads(self._zip().read(path))
        except KeyError:
            return None

    def _model_textures(self, model):
        """The texture variables of a model, its parents' included."""
        textures = {}
        seen = 0
        while model and seen < 10:
            seen += 1
            ns, _, path = model.partition(":") if ":" in model else ("minecraft", ":", model)
            data = self._json(f"assets/{ns}/models/{path}.json")
            if not data:
                break
            for k, v in data.get("textures", {}).items():
                textures.setdefault(k, v)
            model = data.get("parent")
        def resolve(v, depth=0):
            while isinstance(v, str) and v.startswith("#") and depth < 10:
                v, depth = textures.get(v[1:]), depth + 1
            return v
        return {k: resolve(v) for k, v in textures.items()}

    def _average(self, texture):
        if not texture:
            return None
        ns, _, path = texture.partition(":") if ":" in texture else ("minecraft", ":", texture)
        try:
            img = Image.open(io.BytesIO(self._zip().read(f"assets/{ns}/textures/{path}.png"))).convert("RGBA")
        except KeyError:
            return None
        img = img.crop((0, 0, img.width, min(img.height, img.width)))   # first frame of animations
        px = list(img.getdata())
        solid = [p for p in px if p[3] > 0]
        if not solid:
            return None
        r, g, b = (sum(p[i] for p in solid) // len(solid) for i in range(3))
        a = 255 * len(solid) // len(px)
        if "grass_block_top" in path or "leaves" in path or path.endswith("/vine") or "grass" == path.split("/")[-1]:
            r, g, b = r * 124 // 255, g * 189 // 255, b * 107 // 255   # the plains biome tint
        return (r, g, b, a)

    def _lookup(self, name):
        if not self._zip():
            return [(150, 150, 150, 255), (170, 170, 170, 255)]
        ns, _, base = name.partition(":")
        states = self._json(f"assets/{ns}/blockstates/{base}.json") or {}
        model = None
        if "variants" in states:
            v = next(iter(states["variants"].values()))
            model = (v[0] if isinstance(v, list) else v).get("model")
        elif "multipart" in states:
            for part in states["multipart"]:
                a = part.get("apply")
                model = (a[0] if isinstance(a, list) else a).get("model")
                if model:
                    break
        tex = self._model_textures(model) if model else {}
        side = tex.get("side") or tex.get("all") or tex.get("texture") or tex.get("particle") or tex.get("cross") \
            or tex.get("line") or tex.get("torch") or tex.get("pane")
        top = tex.get("top") or tex.get("up") or tex.get("end") or tex.get("all") or side
        side_c = self._average(side) or self._average(f"{ns}:block/{base}") or (150, 150, 150, 255)
        top_c = self._average(top) or side_c
        if base == "redstone_wire":
            side_c = top_c = (200, 20, 10, 255)
        if base in ("water",):
            side_c = top_c = (60, 90, 200, 160)
        if base in ("lava",):
            side_c = top_c = (230, 110, 20, 255)
        return [side_c, top_c]

    def __call__(self, name):
        if name not in self.cache:
            self.cache[name] = self._lookup(name)
        return self.cache[name]

    def save(self):
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        CACHE.write_text(json.dumps(self.cache, indent=0, sort_keys=True))


def _shade(c, f, alpha=None):
    return (min(255, int(c[0] * f)), min(255, int(c[1] * f)), min(255, int(c[2] * f)), c[3] if alpha is None else alpha)


def _tint(c):
    return tuple((c[i] + 2 * HIDDEN_TINT[i]) // 3 for i in range(3)) + (110,)


def _blocks(scene, layers):
    for layer in layers:
        for pos, (state, _) in scene.layers[layer].items():
            name = parse_state(state)[0]
            if name.endswith(":air") or name.endswith("cave_air") or name.endswith("structure_void"):
                continue
            yield layer, pos, name


def iso(scene, path, layers=("visible", "hidden"), corner=0, tile=10, crop=None):
    """An isometric picture from above, seen from the south-east (corner 0), south-west (1), north-west (2) or
    north-east (3). crop: ((x0, y0, z0), (x1, y1, z1)) to draw only part of the scene."""
    colors = Colors()
    blocks = [b for b in _blocks(scene, layers) if not crop or all(crop[0][i] <= b[1][i] <= crop[1][i] for i in range(3))]
    if not blocks:
        raise ValueError("nothing to draw")
    # turn the scene so the viewer always looks from the +x, +z corner
    def turn(p):
        x, y, z = p
        return [(x, y, z), (z, y, -x), (-x, y, -z), (-z, y, x)][corner]
    pts = [(layer, turn(pos), name) for layer, pos, name in blocks]
    w, h = tile, tile // 2
    def project(x, y, z):
        return ((x - z) * w, (x + z) * h - y * tile)
    xs = [project(*p)[0] for _, p, _ in pts] + [project(p[0] + 1, p[1], p[2] + 1)[0] for _, p, _ in pts]
    ys = [project(*p)[1] for _, p, _ in pts]
    x0, x1 = min(xs) - w * 2, max(xs) + w * 2
    y0, y1 = min(ys) - tile * 2, max(ys) + tile * 2
    img = Image.new("RGBA", (int(x1 - x0), int(y1 - y0)), (205, 225, 245, 255))
    layer_img = Image.new("RGBA", img.size, (0, 0, 0, 0))
    # far to near: the viewer is at +x, +z, above; draw smaller x + z first, then lower y first
    pts.sort(key=lambda b: (b[1][0] + b[1][2], b[1][1], b[1][0]))
    draw = ImageDraw.Draw(img, "RGBA")
    hidden_draw = ImageDraw.Draw(layer_img, "RGBA")
    for layer, (x, y, z), name in pts:
        side, top = colors(name)
        height, inset = shape(name)
        if layer == "hidden":
            side, top = _tint(side), _tint(top)
        d = hidden_draw if layer == "hidden" else draw
        a, b = inset / 2, 1 - inset / 2
        def P(px, py, pz):
            sx, sy = project(px, py, pz)
            return (sx - x0, sy - y0)
        yt = y + height
        top_face = [P(x + a, yt, z + a), P(x + b, yt, z + a), P(x + b, yt, z + b), P(x + a, yt, z + b)]
        east = [P(x + b, y, z + a), P(x + b, yt, z + a), P(x + b, yt, z + b), P(x + b, y, z + b)]    # facing +x
        south = [P(x + a, y, z + b), P(x + a, yt, z + b), P(x + b, yt, z + b), P(x + b, y, z + b)]   # facing +z
        d.polygon(east, fill=_shade(side, 0.72))
        d.polygon(south, fill=_shade(side, 0.86))
        d.polygon(top_face, fill=_shade(top, 1.0))
    img = Image.alpha_composite(img, layer_img)
    colors.save()
    img.convert("RGB").save(path)
    return path


def plan(scene, path, layers=("visible", "hidden"), axis="y", tile=8, flip=False):
    """A flat picture straight along an axis: "y" from above (north up), "z" from the south (x to the right),
    "x" from the east. The nearest block of each column is drawn, shaded by how far back it is; hidden blocks are
    tinted. flip looks from the other side (from below, from the north, from the west)."""
    colors = Colors()
    lo, hi = scene.bounds()
    i = "xyz".index(axis)
    u, v = {"x": (2, 1), "y": (0, 2), "z": (0, 1)}[axis]     # the picture's across and up axes
    nearest = {}
    for layer, pos, name in _blocks(scene, layers):
        key = (pos[u], pos[v])
        depth = pos[i] if flip else -pos[i]     # the viewer is on the + side (above, south, east) unless flipped
        best = nearest.get(key)
        if best is None or depth < best[0] or (depth == best[0] and layer == "visible"):
            nearest[key] = (depth, layer, name)
    if not nearest:
        raise ValueError("nothing to draw")
    du = hi[u] - lo[u] + 1
    dv = hi[v] - lo[v] + 1
    img = Image.new("RGBA", (du * tile, dv * tile), (205, 225, 245, 255))
    draw = ImageDraw.Draw(img, "RGBA")
    depths = [d for d, _, _ in nearest.values()]
    dmin, dmax = min(depths), max(depths)
    for (pu, pv), (depth, layer, name) in nearest.items():
        side, top = colors(name)
        c = top if axis == "y" else side
        c = _shade(c, 1.0 - 0.45 * ((depth - dmin) / (dmax - dmin) if dmax > dmin else 0))
        if layer == "hidden":
            c = _tint(c)
        # as the viewer sees it: from the east, north (-z) is on the right; looking from the other side mirrors
        mirror = (axis == "x") != flip and axis != "y"
        px = (hi[u] - pu if mirror else pu - lo[u]) * tile
        py = (pv - lo[v]) * tile if axis == "y" else (hi[v] - pv) * tile   # up is up for side views
        draw.rectangle([px, py, px + tile - 1, py + tile - 1], fill=c)
    colors.save()
    img.convert("RGB").save(path)
    return path
