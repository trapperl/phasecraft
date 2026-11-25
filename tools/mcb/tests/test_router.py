"""mcb.router: routes reach their target, on time, through clutter, without touching anything else (checked in
the redstone simulator)."""

import unittest

from mcb import router
from mcb.redstone import Sim
from mcb.scene import Scene

LEVER = "minecraft:lever[face=floor,facing=north,powered=false]"
BOX = ((-3, 0, -3), (30, 12, 20))


def bench():
    """A lever on stone at the west end, a shifter (the target) in the east."""
    s = Scene()
    s.set("hidden", (0, 0, 0), "minecraft:stone")
    s.set("hidden", (0, 1, 0), LEVER)
    s.set("hidden", (20, 1, 6), "phasecraft:phase_shifter")
    return s


def fire(scene, target=(20, 1, 6), ticks=200):
    sim = Sim(scene)
    sim.run(5)
    assert not sim.popped, sim.popped
    on_before = target in sim.powered_shifters()
    sim.set_input((0, 1, 0), True)
    sim.run(ticks)
    when = next((t for t, p, _, v in sim.events if p == target and v), None)
    return on_before, when, sim


class RouterTest(unittest.TestCase):
    def test_reaches_the_target(self):
        s = bench()
        r = router.route(s, start=(1, 1, 0), start_from="west", end=(19, 1, 6), end_into="east", box=BOX).apply(s)
        before, when, _ = fire(s)
        self.assertFalse(before)
        self.assertEqual(when, 5 + 2 * r.delay, "arrives after the line's delay")

    def test_goes_around_a_wall_and_over_a_ridge(self):
        s = bench()
        for z in range(-3, 15):
            for y in range(0, 13):
                s.set("hidden", (8, y, z), "minecraft:stone")   # a wall, open only at the south end
        for x in range(12, 16):
            for y in range(0, 4):
                s.set("hidden", (x, y, 15), "minecraft:stone")
        r = router.route(s, start=(1, 1, 0), start_from="west", end=(19, 1, 6), end_into="east", box=BOX).apply(s)
        self.assertTrue(any(c[2] >= 15 for c in r.cells), "went round the wall's end")
        _, when, _ = fire(s)
        self.assertIsNotNone(when)

    def test_a_long_line_gets_repeaters_and_still_arrives(self):
        s = bench()
        s.set("hidden", (20, 1, 6), "minecraft:air")
        s.set("hidden", (28, 1, 18), "phasecraft:phase_shifter")
        r = router.route(s, start=(1, 1, 0), start_from="west", end=(27, 1, 18), end_into="east", box=BOX).apply(s)
        self.assertGreater(len(r.cells), 30)
        _, when, _ = fire(s, target=(28, 1, 18))
        self.assertEqual(when, 5 + 2 * r.delay)

    def test_exact_delay(self):
        s = bench()
        r = router.route(s, start=(1, 1, 0), start_from="west", end=(19, 1, 6), end_into="east", box=BOX,
                         delay=17).apply(s)
        self.assertEqual(r.delay, 17)
        _, when, _ = fire(s)
        self.assertEqual(when, 5 + 34)

    def test_crosses_a_busy_area_without_touching_another_circuit(self):
        s = bench()
        # another circuit across the way: a powered line feeding its own shifter
        s.set("hidden", (10, 0, -2), "minecraft:stone")
        s.set("hidden", (10, 1, -2), "minecraft:redstone_block")
        for z in range(-1, 14):
            s.set("hidden", (10, 0, z), "minecraft:glass")
            s.set("hidden", (10, 1, z), "minecraft:redstone_wire[north=side,south=side]")
        s.set("hidden", (10, 1, 14), "phasecraft:phase_shifter")
        r = router.route(s, start=(1, 1, 0), start_from="west", end=(19, 1, 6), end_into="east", box=BOX).apply(s)
        before, when, sim = fire(s)
        self.assertFalse(before)
        self.assertIsNotNone(when, "the route crossed (over or under) and arrived")
        self.assertIn((10, 1, 14), sim.powered_shifters(), "the other circuit still works")
        sim.set_input((0, 1, 0), False)
        sim.run(100)
        self.assertNotIn((20, 1, 6), sim.powered_shifters(), "and our target goes off: nothing of theirs leaks in")

    def test_wire_joins_diagonally_so_routes_keep_off_diagonals(self):
        # the rule's reason: a wire one step down and across joins (here: powers) another
        s = Scene()
        s.set("hidden", (0, 1, 0), "minecraft:stone")
        s.set("hidden", (0, 2, 0), "minecraft:redstone_wire")
        s.set("hidden", (1, 0, 0), "minecraft:glass")
        s.set("hidden", (1, 1, 0), "minecraft:redstone_wire")
        s.set("hidden", (-1, 2, 0), "minecraft:redstone_block")
        self.assertGreater(Sim(s).wire_power[(1, 1, 0)], 0)
        # so a cell diagonal to another circuit's wire is refused, though nothing touches it face to face
        space = router._Space(s, "hidden", BOX)
        self.assertFalse(router._clear_around(space, (2, 2, 0), ()))
        self.assertTrue(router._clear_around(space, (3, 3, 0), ()))

    def test_no_route_when_walled_in(self):
        s = bench()
        for x in range(-3, 31):
            for y in range(0, 13):
                s.set("hidden", (x, y, 3), "minecraft:stone")
        with self.assertRaises(router.NoRoute):
            router.route(s, start=(1, 1, 0), start_from="west", end=(19, 1, 6), end_into="east", box=BOX)


if __name__ == "__main__":
    unittest.main()


def crossing(n, gap=3, height=7):
    """n levers in a row at the west, their shifters in the east in the other order: every line crosses the others."""
    s = Scene()
    nets = []
    for i in range(n):
        s.set("hidden", (0, 0, i * gap), "minecraft:stone")
        s.set("hidden", (0, 1, i * gap), LEVER)
        s.set("hidden", (20, 1, (n - 1 - i) * gap), "phasecraft:phase_shifter")
        nets.append(router.Net((1, 1, i * gap), "west", (19, 1, (n - 1 - i) * gap), "east"))
    return s, nets, ((-1, 0, -3), (22, height, (n - 1) * gap + 3))


class RouteAllTest(unittest.TestCase):
    def test_lines_that_must_cross_each_reach_only_their_target(self):
        n, gap = 4, 3
        s, nets, box = crossing(n, gap)
        for r in router.route_all(s, nets, box):
            r.apply(s)
        for i in range(n):
            sim = Sim(s)
            sim.run(5)
            self.assertEqual(sim.popped, [])
            sim.set_input((0, 1, i * gap), True)
            sim.run(200)
            self.assertEqual(sim.powered_shifters(), {(20, 1, (n - 1 - i) * gap)}, f"lever {i}")

    def test_says_so_when_there_is_no_room(self):
        s, nets, _ = crossing(3, gap=2)
        with self.assertRaises(router.NoRoute):
            router.route_all(s, nets, ((-1, 0, -1), (22, 2, 5)), rounds=8)


class TreeTest(unittest.TestCase):
    def test_one_lever_reaches_every_end_when_the_tree_says(self):
        s = Scene()
        s.set("hidden", (0, 0, 0), "minecraft:stone")
        s.set("hidden", (0, 1, 0), LEVER)
        targets = [(20, 1, 0), (18, 1, 9), (24, 3, -6), (9, 1, 14)]
        for t in targets:
            s.set("hidden", t, "phasecraft:phase_shifter")
        ends = [((x - 1, y, z), "east") for x, y, z in targets]
        tr = router.tree(s, (1, 1, 0), "west", ends, ((-2, 0, -9), (27, 8, 17))).apply(s)
        sim = Sim(s)
        sim.run(5)
        self.assertEqual(sim.popped, [])
        self.assertEqual(sim.powered_shifters(), set())
        sim.set_input((0, 1, 0), True)
        sim.run(200)
        when = {p: t for t, p, _, v in sim.events if v}
        for (end, _), target in zip(ends, targets):
            self.assertEqual(when.get(target), 5 + 2 * tr.ends[end], f"{target}: the tree says {tr.ends[end]}")

    def test_trees_that_must_cross_reach_only_their_own_ends(self):
        s = Scene()
        nets, mine = [], {}
        for i, z in enumerate((0, 8)):
            s.set("hidden", (0, 0, z), "minecraft:stone")
            s.set("hidden", (0, 1, z), LEVER)
            targets = [(20, 1, 8 - z + dz) for dz in (-3, 0, 3)]       # the other lever's side
            for t in targets:
                s.set("hidden", t, "phasecraft:phase_shifter")
            mine[(0, 1, z)] = set(targets)
            nets.append(router.TreeNet((1, 1, z), "west", [((x - 1, y, tz), "east") for x, y, tz in targets]))
        for r in router.route_all(s, nets, ((-1, 0, -5), (22, 8, 13))):
            r.apply(s)
        for lever, targets in mine.items():
            sim = Sim(s)
            sim.run(5)
            self.assertEqual(sim.popped, [])
            sim.set_input(lever, True)
            sim.run(200)
            self.assertEqual(sim.powered_shifters(), targets, f"lever at {lever}")


class FlankTest(unittest.TestCase):
    def test_a_line_may_pass_a_repeaters_flank_without_touching_it(self):
        s = Scene()
        # another circuit: a lever at z = 0 driving a row of repeaters east to a shifter
        s.set("hidden", (0, 0, 0), "minecraft:stone")
        s.set("hidden", (0, 1, 0), LEVER)
        for x in range(1, 9):
            s.set("hidden", (x, 0, 0), "minecraft:glass")
            s.set("hidden", (x, 1, 0), "minecraft:repeater[delay=1,facing=west]")
        s.set("hidden", (9, 1, 0), "minecraft:glass")          # (a shifter there would power ours, beside it)
        # ours: from a lever at z = 1, hemmed in to the row beside the repeaters' flanks
        s.set("hidden", (0, 0, 1), "minecraft:stone")
        s.set("hidden", (0, 1, 1), LEVER)
        s.set("hidden", (9, 1, 1), "phasecraft:phase_shifter")
        r = router.route(s, start=(1, 1, 1), start_from="west", end=(8, 1, 1), end_into="east",
                         box=((0, 0, 1), (9, 2, 1))).apply(s)
        self.assertTrue(all(c[2] == 1 for c in r.cells), "ran beside the repeaters")
        for lever, theirs, ours in (((0, 1, 0), True, set()), ((0, 1, 1), False, {(9, 1, 1)})):
            sim = Sim(s)
            sim.run(20)
            self.assertEqual(sim.popped, [])
            sim.set_input(lever, True)
            sim.run(100)
            self.assertEqual(sim.is_on((8, 1, 0), "powered"), theirs, f"lever {lever}: their last repeater")
            self.assertEqual(sim.powered_shifters(), ours, f"lever {lever}: our shifter")

    def test_but_never_its_ends(self):
        s = Scene()
        s.set("hidden", (5, 1, 5), "minecraft:repeater[delay=1,facing=west]")     # input west, output east
        space = router._Space(s, "hidden", ((0, 0, 0), (10, 5, 10)))
        self.assertTrue(router._clear_around(space, (5, 1, 6), ()), "beside its flank")
        self.assertFalse(router._clear_around(space, (6, 1, 5), ()), "at its output")
        self.assertFalse(router._clear_around(space, (4, 1, 5), ()), "at its input")
