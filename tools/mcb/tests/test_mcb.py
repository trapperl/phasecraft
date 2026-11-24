"""Unit tests for tools/mcb (no server needed): python3 -m unittest discover -s tools/mcb/tests -t tools"""

import pathlib
import tempfile
import unittest

from mcb import nbt, render
from mcb.scene import Scene, matches
from mcb.structure import Structure, normal, parse_state

ROOT = pathlib.Path(__file__).resolve().parents[3]
IRIS = ROOT / "src/gametest/resources/contraptions/iris_door.txt"


class NbtTest(unittest.TestCase):
    def test_round_trip_keeps_every_type(self):
        root = {"b": nbt.Byte(-3), "s": nbt.Short(300), "i": 70000, "l": nbt.Long(1 << 40), "f": nbt.Float(1.5),
                "d": nbt.Double(2.25), "ba": bytearray(b"\x01\x02"), "str": "héllo", "list": nbt.List([1, 2], nbt.INT),
                "empty": nbt.List([], nbt.COMPOUND), "c": {"x": "y"}, "ia": nbt.IntArray([1, -1]),
                "la": nbt.LongArray([5])}
        back = nbt.loads(nbt.dumps(root))
        self.assertEqual(back, root)
        for key in root:
            self.assertIs(type(back[key]), type(root[key]), key)
        self.assertEqual(back["empty"].kind, nbt.COMPOUND)


class StructureTest(unittest.TestCase):
    def test_states_normalise(self):
        self.assertEqual(normal("repeater[facing=north,delay=2]"), "minecraft:repeater[delay=2,facing=north]")
        self.assertEqual(parse_state("phasecraft:phase_port"), ("phasecraft:phase_port", {}))
        self.assertTrue(matches("minecraft:repeater[delay=1]", "minecraft:repeater[delay=1,locked=false]"))
        self.assertFalse(matches("minecraft:repeater[delay=1]", "minecraft:repeater[delay=2]"))

    def test_structure_round_trip(self):
        blocks = {(0, 0, 0): ("minecraft:stone", None), (1, 0, 0): ("minecraft:chest[facing=north]", {"Lock": "x"})}
        with tempfile.TemporaryDirectory() as d:
            Structure((2, 1, 1), blocks).save(pathlib.Path(d) / "s.nbt")
            back = Structure.load(pathlib.Path(d) / "s.nbt")
        self.assertEqual(back.size, (2, 1, 1))
        self.assertEqual(back.blocks[(1, 0, 0)], ("minecraft:chest[facing=north]", {"Lock": "x"}))


class SceneTest(unittest.TestCase):
    def test_layout_to_structures_and_back(self):
        scene = Scene.from_layout(IRIS)
        self.assertEqual(scene.lever, (12, 10, 16))
        with tempfile.TemporaryDirectory() as d:
            size = scene.to_structures(d)
            back = Scene.from_structures(d)
        self.assertEqual(size, scene.size)
        self.assertEqual(scene.diff(back), [])
        self.assertEqual(sum(1 for s, _ in scene.layers["hidden"].values() if "phase_shifter" in s), 37)

    def test_render_draws(self):
        scene = Scene()
        scene.fill("visible", (0, 0, 0), (3, 0, 3), "minecraft:stone")
        scene.set("hidden", (1, 1, 1), "minecraft:redstone_wire")
        with tempfile.TemporaryDirectory() as d:
            for out in (render.iso(scene, pathlib.Path(d) / "a.png"), render.plan(scene, pathlib.Path(d) / "b.png")):
                self.assertGreater(pathlib.Path(out).stat().st_size, 100)


if __name__ == "__main__":
    unittest.main()
