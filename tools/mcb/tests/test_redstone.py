"""The redstone simulator against known behaviour (vanilla rules, and the iris door as its GameTest measured it)."""

import pathlib
import unittest

from mcb.redstone import Sim
from mcb.scene import Scene

ROOT = pathlib.Path(__file__).resolve().parents[3]
IRIS = ROOT / "src/gametest/resources/contraptions/iris_door.txt"


def line(*parts):
    """A row of parts along +x at y = 1 on glass, from x = 0."""
    scene = Scene()
    for x, state in enumerate(parts):
        scene.set("hidden", (x, 0, 0), "minecraft:glass")
        if state:
            scene.set("hidden", (x, 1, 0), state)
    return scene


LEVER = "minecraft:lever[face=floor,facing=north,powered=false]"
WIRE = "minecraft:redstone_wire[east=side,west=side]"


def ring(p):
    d2 = (p[0] - 6) ** 2 + (p[1] - 13) ** 2
    return 0 if d2 == 0 else 1 if d2 <= 2 else 2 if d2 <= 6 else 3


class RedstoneTest(unittest.TestCase):
    def test_wire_fades_one_per_block(self):
        sim = Sim(line(LEVER, *[WIRE] * 17))
        sim.set_input((0, 1, 0), True)
        self.assertEqual([sim.wire_power[(x, 1, 0)] for x in range(1, 18)], list(range(15, 0, -1)) + [0, 0])

    def test_a_tripwire_hook_powers_its_block_strongly_and_wire_beside_it(self):
        scene = Scene()
        scene.set("hidden", (0, 1, 0), "minecraft:stone")                                       # the hook's block
        scene.set("hidden", (1, 1, 0), "minecraft:tripwire_hook[attached=true,facing=east,powered=false]")
        for x, z in ((0, 1), (1, 1)):                                    # wire beside the block, and beside the hook
            scene.set("hidden", (x, 0, z), "minecraft:glass")
            scene.set("hidden", (x, 1, z), "minecraft:redstone_wire[north=side,south=side]")
        scene.set("hidden", (1, 0, 1), "minecraft:glass")
        sim = Sim(scene)
        self.assertEqual(sim.popped, [])
        self.assertEqual(sim.wire_power[(0, 1, 1)], 0)
        sim.set_input((1, 1, 0), True)
        self.assertEqual(sim.wire_power[(0, 1, 1)], 15, "beside the strongly powered block")
        self.assertEqual(sim.wire_power[(1, 1, 1)], 15, "beside the hook")

    def test_repeater_delay_in_game_ticks(self):
        sim = Sim(line(LEVER, WIRE, "minecraft:repeater[delay=3,facing=west]", WIRE))
        sim.set_input((0, 1, 0), True)
        for _ in range(5):
            sim.run(1)
            self.assertEqual(sim.wire_power[(3, 1, 0)], 0)
        sim.run(1)
        self.assertEqual(sim.wire_power[(3, 1, 0)], 15, "a delay-3 repeater turns on 6 game ticks later")

    def test_torch_inverts_after_two_ticks(self):
        scene = line(LEVER, WIRE, "minecraft:stone", None)
        scene.set("hidden", (3, 1, 0), "minecraft:redstone_wall_torch[facing=east]")
        scene.set("hidden", (4, 0, 0), "minecraft:glass")
        scene.set("hidden", (4, 1, 0), WIRE)
        sim = Sim(scene)
        self.assertEqual(sim.wire_power[(4, 1, 0)], 15)
        sim.set_input((0, 1, 0), True)
        sim.run(1)
        self.assertEqual(sim.wire_power[(4, 1, 0)], 15)
        sim.run(1)
        self.assertEqual(sim.wire_power[(4, 1, 0)], 0)

    def test_a_powered_repeater_from_the_side_locks(self):
        scene = line(LEVER, WIRE, "minecraft:repeater[delay=1,facing=west]", WIRE)
        scene.set("hidden", (2, 0, 1), "minecraft:glass")
        scene.set("hidden", (2, 1, 1), "minecraft:repeater[delay=1,facing=south,powered=true]")
        scene.set("hidden", (2, 0, 2), "minecraft:glass")
        scene.set("hidden", (2, 1, 2), "minecraft:redstone_block")
        sim = Sim(scene)
        sim.set_input((0, 1, 0), True)
        sim.run(10)
        self.assertEqual(sim.wire_power[(3, 1, 0)], 0, "locked: stays off")

    def test_iris_rings_as_the_gametest_measured(self):
        sim = Sim(Scene.from_layout(IRIS))
        sim.run(40)
        self.assertEqual(sim.powered_shifters(), set(), "closed at rest")
        port = next(p for p, (k, _) in sim.parts.items() if k == "phase_port")
        for on, order in ((True, [0, 1, 2, 3]), (False, [3, 2, 1, 0])):
            sim.events.clear()
            t0 = sim.tick
            sim.set_input(port, on)
            sim.run(100)
            times = {}
            for t, p, _, v in sim.events:
                times.setdefault(ring(p), set()).add(t - t0)
            self.assertEqual([times[r] for r in order], [{44}, {52}, {60}, {68}])

    def test_a_bus_without_repeaters_fades_and_front_rings_stand_open(self):
        scene = Scene.from_layout(IRIS)
        for p, (state, _) in list(scene.layers["hidden"].items()):
            if "repeater" in state and p[0] == 15 and ("facing=north" in state or "facing=south" in state):
                scene.set("hidden", p, "minecraft:redstone_wire[north=side,south=side]")
        sim = Sim(scene)
        sim.run(60)
        self.assertTrue({ring(p) for p in sim.powered_shifters()} >= {3})


if __name__ == "__main__":
    unittest.main()
