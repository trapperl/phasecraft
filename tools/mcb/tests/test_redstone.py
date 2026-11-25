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

    def step(self, block, lever_below):
        """A wire step: a lower wire at (1, 1), an upper wire on `block` at (2, 2); a lever beside one of them.
        Returns (lower, upper) strengths with the lever on."""
        scene = Scene()
        for x in range(4):
            scene.set("hidden", (x, 0, 0), "minecraft:glass")
        scene.set("hidden", (1, 1, 0), "minecraft:redstone_wire")
        scene.set("hidden", (2, 1, 0), block)
        scene.set("hidden", (2, 2, 0), "minecraft:redstone_wire")
        scene.set("hidden", (3, 1, 0), "minecraft:glass")
        lever = (0, 1, 0) if lever_below else (3, 2, 0)
        scene.set("hidden", lever, LEVER)
        sim = Sim(scene)
        sim.set_input(lever, True)
        sim.run(4)
        return sim.wire_power[(1, 1, 0)], sim.wire_power[(2, 2, 0)]

    def test_a_step_on_glass_carries_a_signal_up_only(self):
        # measured on the dev server with mcb.verify (2026-11-24): up 15 -> 14, down nothing
        self.assertEqual(self.step("minecraft:glass", lever_below=True), (15, 14))
        self.assertEqual(self.step("minecraft:glass", lever_below=False), (0, 15))

    def test_a_step_on_stone_carries_a_signal_both_ways(self):
        self.assertEqual(self.step("minecraft:stone", lever_below=True), (15, 14))
        self.assertEqual(self.step("minecraft:stone", lever_below=False), (14, 15))

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

    def test_a_copper_bulb_toggles_each_time_its_power_comes_on(self):
        sim = Sim(line(LEVER, WIRE, "minecraft:copper_bulb"))
        lit = []
        for on in (True, False, True, False):
            sim.set_input((0, 1, 0), on)
            sim.run(2)
            lit.append(sim.prop((2, 1, 0), "lit"))
        self.assertEqual(lit, ["true", "true", "false", "false"])

    def test_a_comparator_reads_a_bulb_behind_it_and_through_a_block(self):
        for between in ([], ["minecraft:stone"]):
            parts = ["minecraft:copper_bulb[lit=true]", *between, "minecraft:comparator[facing=west]", WIRE]
            sim = Sim(line(*parts))
            sim.run(4)
            self.assertEqual(sim.wire_power[(len(parts) - 1, 1, 0)], 15, f"through {between}")
            sim.parts[(0, 1, 0)][1]["lit"] = "false"
            sim.react()
            sim.run(4)
            self.assertEqual(sim.wire_power[(len(parts) - 1, 1, 0)], 0, f"bulb off, through {between}")

    def side(self, mode, back, side):
        """A comparator (facing west) fed `back` from behind and `side` from a wire at its side: its output."""
        scene = line("minecraft:redstone_block", *[WIRE] * (16 - back), "minecraft:comparator[facing=west,mode=" + mode + "]", WIRE)
        c = (17 - back, 1, 0)
        scene.set("hidden", (c[0], 0, 1), "minecraft:glass")
        scene.set("hidden", (c[0], 1, 1), "minecraft:redstone_wire[north=side,south=side]")
        if side:
            for z in range(2, 2 + 15 - side):
                scene.set("hidden", (c[0], 0, z), "minecraft:glass")
                scene.set("hidden", (c[0], 1, z), "minecraft:redstone_wire[north=side,south=side]")
            scene.set("hidden", (c[0], 1, 2 + 15 - side), "minecraft:redstone_block")
        sim = Sim(scene)
        sim.run(6)
        return sim.wire_power[(c[0] + 1, 1, 0)]

    def test_a_comparator_compares_or_subtracts_its_side(self):
        self.assertEqual(self.side("compare", 12, 0), 12)
        self.assertEqual(self.side("compare", 12, 12), 12, "as strong as the side: on")
        self.assertEqual(self.side("compare", 12, 13), 0, "weaker than the side: off")
        self.assertEqual(self.side("subtract", 12, 5), 7)
        self.assertEqual(self.side("subtract", 12, 12), 0)

    def test_dust_connects_to_a_comparator_from_any_side_and_a_repeater_along_its_axis(self):
        # found by mcb.verify: a wire beside a comparator's side bends into it (vanilla: any signal source but a
        # repeater), so it no longer points on into the block across
        scene = Scene()
        for x in range(3):
            scene.set("hidden", (x, 0, 0), "minecraft:glass")
        scene.set("hidden", (0, 1, 0), "minecraft:stone")
        scene.set("hidden", (1, 1, 0), "minecraft:redstone_wire")
        for diode, connects in (("comparator", True), ("repeater", False)):
            scene.set("hidden", (2, 1, 0), f"minecraft:{diode}[facing=north]")
            sim = Sim(scene)
            self.assertEqual(sim.wire_connects((1, 1, 0), "east"), connects, diode)

    def test_an_observer_pulses_two_ticks_two_ticks_after_a_change(self):
        # a lever beside a wire the observer faces (the observer's back powers the wire behind it)
        scene = line(LEVER, WIRE, "minecraft:observer[facing=west]", WIRE)
        sim = Sim(scene)
        sim.set_input((0, 1, 0), True)
        seen = []
        for _ in range(6):
            sim.run(1)
            seen.append(sim.wire_power[(3, 1, 0)])
        self.assertEqual(seen, [0, 15, 15, 0, 0, 0])

    def test_an_observer_sees_a_repeater_being_locked(self):
        # found by mcb.verify: locking changes the repeater's state, which an observer facing it sees
        scene = line(LEVER, WIRE, "minecraft:repeater[delay=1,facing=south]")
        scene.set("hidden", (2, 1, 0), "minecraft:repeater[delay=1,facing=west]")      # into the side of (3, 1, 0)
        scene.set("hidden", (3, 0, 0), "minecraft:glass")
        scene.set("hidden", (3, 1, 0), "minecraft:repeater[delay=1,facing=north]")
        scene.set("hidden", (3, 1, 1), "minecraft:observer[facing=north]")            # faces it, out to the south
        scene.set("hidden", (3, 0, 2), "minecraft:glass")
        scene.set("hidden", (3, 1, 2), "minecraft:redstone_wire[north=side,south=side]")
        sim = Sim(scene)
        sim.set_input((0, 1, 0), True)
        seen = []
        for _ in range(8):
            sim.run(1)
            seen.append(sim.wire_power[(3, 1, 2)])
        self.assertEqual(sim.prop((3, 1, 0), "locked"), "true")
        self.assertEqual(seen, [0, 0, 0, 15, 15, 0, 0, 0], "locked at 2, the observer on 2 later, for 2")

    def test_a_comparator_misses_an_observers_pulse(self):
        # found by mcb.verify: the observer schedules its off tick before its neighbours hear it is on, so a
        # comparator woken by the pulse ticks after it ends (BUILD_RULES R6)
        scene = line(LEVER, WIRE, "minecraft:observer[facing=west]", WIRE, "minecraft:comparator[facing=west]", WIRE)
        sim = Sim(scene)
        sim.set_input((0, 1, 0), True)
        on = []
        for _ in range(10):
            sim.run(1)
            on.append(sim.prop((4, 1, 0), "powered", "false"))
        self.assertEqual(sim.wire_power[(3, 1, 0)], 0)
        self.assertNotIn("true", on)

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
