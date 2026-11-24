"""mcb.shapes: shapes come out as they should, and the demo village needs nothing fixed by the game."""

import unittest

from mcb import shapes
from mcb.examples import village
from mcb.scene import Scene


class ShapesTest(unittest.TestCase):
    def test_palette_is_the_same_each_time(self):
        p = shapes.Palette({"minecraft:stone": 1, "minecraft:cobblestone": 1}, seed=3)
        picks = [p.at((x, 0, 0)) for x in range(50)]
        self.assertEqual(picks, [p.at((x, 0, 0)) for x in range(50)])
        self.assertEqual(set(picks), {"minecraft:stone", "minecraft:cobblestone"})

    def test_gable_roof_slopes_face_up_the_roof(self):
        s = Scene()
        top = shapes.gable_roof(s, (0, 0), (4, 4), 10, "minecraft:oak_stairs", ridge="x", overhang=0)
        self.assertIn("facing=south", s.get("visible", (2, 10, 0)))   # north slope: climbs towards the south
        self.assertIn("facing=north", s.get("visible", (2, 10, 4)))
        self.assertEqual(s.get("visible", (2, 12, 2)), "minecraft:oak_slab")   # the ridge
        self.assertEqual(top, 13)

    def test_cylinder_and_sphere_sizes(self):
        s = Scene()
        shapes.cylinder(s, (0, 0, 0), 3, 2, "minecraft:stone")
        self.assertEqual(len(s.layers["visible"]), 2 * 37)
        s = Scene()
        shapes.sphere(s, (0, 0, 0), 2, "minecraft:stone", hollow=True)
        self.assertNotIn((0, 0, 0), s.layers["visible"])
        self.assertIn((2, 0, 0), s.layers["visible"])

    def test_terrain_fills_water_below_its_line(self):
        s = Scene()
        h = shapes.terrain(s, (0, 0, 0), (32, 32), seed=4, water=7, amplitude=16)
        low = min(h, key=h.get)
        self.assertLess(h[low], 7)
        self.assertEqual(s.get("visible", (low[0], 7, low[1])), "minecraft:water")
        self.assertEqual(shapes.ground(s, *low), 7)

    def test_path_keeps_off_floors(self):
        s = Scene()
        shapes.terrain(s, (0, 0, 0), (16, 16), seed=1, water=0, amplitude=0)
        y = shapes.ground(s, 5, 5)
        s.set("visible", (5, y, 5), "minecraft:oak_planks")
        shapes.path(s, [(0, 5), (15, 5)])
        self.assertEqual(s.get("visible", (5, y, 5)), "minecraft:oak_planks")
        self.assertEqual(s.get("visible", (4, y, 5)), "minecraft:dirt_path")

    def test_village_needs_nothing_fixed(self):
        self.assertEqual(shapes.problems(village.build()), [])


if __name__ == "__main__":
    unittest.main()
