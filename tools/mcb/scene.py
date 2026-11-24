"""A build as blocks per layer ("visible", "hidden"), at positions relative to its corner.

Scenes come from PhaseCraft layout files (src/gametest/resources/contraptions), from structures exported from the
dev server, or from code; they go to layout files, structures (for /mcb place) and pictures (render).
"""

import pathlib

from .structure import Structure, normal, parse_state

LAYERS = ("visible", "hidden")
AIR = "minecraft:air"


class Scene:
    def __init__(self, size=None):
        self.layers = {layer: {} for layer in LAYERS}   # pos -> (state, nbt or None)
        self.size = tuple(size) if size else None
        self.lever = None

    # --- building ---
    def set(self, layer, pos, state, data=None):
        self.layers[layer][tuple(pos)] = (normal(state), data)

    def fill(self, layer, a, b, state):
        for x in range(min(a[0], b[0]), max(a[0], b[0]) + 1):
            for y in range(min(a[1], b[1]), max(a[1], b[1]) + 1):
                for z in range(min(a[2], b[2]), max(a[2], b[2]) + 1):
                    self.set(layer, (x, y, z), state)

    def get(self, layer, pos):
        block = self.layers[layer].get(tuple(pos))
        return block[0] if block else AIR

    def bounds(self):
        """(min, max) corners over both layers' non-air blocks, or of the size if one is set."""
        if self.size:
            return (0, 0, 0), tuple(s - 1 for s in self.size)
        points = [p for layer in self.layers.values() for p, (s, _) in layer.items() if s != AIR]
        return (tuple(min(p[i] for p in points) for i in range(3)), tuple(max(p[i] for p in points) for i in range(3)))

    # --- layout files ---
    @classmethod
    def from_layout(cls, path):
        """A PhaseCraft layout file (contraptions/README.md): size, fills and blocks; @signal tags are dropped."""
        scene = cls()
        with open(path) as f:
            lines = f.readlines()
        for raw in lines:
            words = raw.split("#", 1)[0].split()
            if not words:
                continue
            if words[0] == "size":
                scene.size = tuple(int(v) for v in words[1:4])
            elif words[0] == "lever":
                scene.lever = tuple(int(v) for v in words[1:4])
            elif words[0] in LAYERS:
                scene.set(words[0], tuple(int(v) for v in words[1:4]), words[4])
            elif words[0] == "fill":
                scene.fill(words[1], tuple(int(v) for v in words[2:5]), tuple(int(v) for v in words[5:8]), words[8])
        return scene

    # --- structures ---
    def to_structures(self, directory):
        """visible.nbt and hidden.nbt in directory (generated/mcb/structure/<name>), every position of the scene's box
        written, air included, so placing it replaces what was there."""
        directory = pathlib.Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        lo, hi = self.bounds()
        size = tuple(hi[i] - lo[i] + 1 for i in range(3))
        for layer in LAYERS:
            blocks = {}
            for x in range(size[0]):
                for y in range(size[1]):
                    for z in range(size[2]):
                        pos = (lo[0] + x, lo[1] + y, lo[2] + z)
                        blocks[(x, y, z)] = self.layers[layer].get(pos, (AIR, None))
            Structure(size, blocks).save(directory / f"{layer}.nbt")
        return size

    @classmethod
    def from_structures(cls, directory):
        directory = pathlib.Path(directory)
        scene = None
        for layer in LAYERS:
            s = Structure.load(directory / f"{layer}.nbt")
            if scene is None:
                scene = cls(s.size)
            for pos, (state, data) in s.blocks.items():
                if state != AIR:
                    scene.layers[layer][pos] = (state, data)
        return scene

    def diff(self, other, layers=LAYERS):
        """[(layer, pos, mine, theirs)] where the two scenes differ. A property this scene leaves out matches any
        value (layouts leave out defaults such as power=0)."""
        out = []
        for layer in layers:
            for pos in sorted(set(self.layers[layer]) | set(other.layers[layer])):
                a, b = self.get(layer, pos), other.get(layer, pos)
                if not matches(a, b):
                    out.append((layer, pos, a, b))
        return out


def matches(expected, actual):
    """Whether actual is the expected block with at least the expected properties."""
    en, ep = parse_state(expected)
    an, ap = parse_state(actual)
    return en == an and all(ap.get(k) == v for k, v in ep.items())
