"""tools/redstone_check.py's power rules on small layouts (each also checked on the dev server or in vanilla's code)."""

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2]))
import redstone_check  # noqa: E402


def problems(lines):
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.write("\n".join(lines) + "\n")
    parts, allow, inputs = redstone_check.load(f.name)
    layout = redstone_check.Layout(parts)
    return {p: layout.wire_inputs(p) - {parts[p][2], None}
            for p, (k, _, _) in parts.items() if k == "redstone_wire"}


class CheckTest(unittest.TestCase):
    def step(self, block):
        """A lower wire (@low) at (1, 1, 0) and an upper wire (@up) on `block` at (2, 2, 0)."""
        return problems([
            "hidden 1 0 0 minecraft:glass", "hidden 1 1 0 minecraft:redstone_wire @low",
            f"hidden 2 1 0 {block}", "hidden 2 2 0 minecraft:redstone_wire @up"])

    def test_a_glass_step_carries_up_only(self):
        # the upper wire reads the lower one; the lower one does not read the upper (mcb.verify on the server)
        self.assertEqual(self.step("minecraft:glass"), {(1, 1, 0): set(), (2, 2, 0): {"low"}})

    def test_a_stone_step_carries_both_ways(self):
        self.assertEqual(self.step("minecraft:stone"), {(1, 1, 0): {"up"}, (2, 2, 0): {"low"}})

    def test_a_wire_does_not_pick_up_a_block_powered_by_wire_alone(self):
        # wire @a points into the stone; wire @b beside the stone: vanilla turns wires' signals off while a wire
        # works out its strength, so the stone gives @b nothing
        got = problems([
            "hidden 0 0 0 minecraft:glass", "hidden 0 1 0 minecraft:redstone_wire[east=side,west=side] @a",
            "hidden 1 1 0 minecraft:stone",
            "hidden 1 0 1 minecraft:glass", "hidden 1 1 1 minecraft:redstone_wire[east=side,west=side] @b"])
        self.assertEqual(got[(1, 1, 1)], set())

    def test_a_wire_picks_up_a_block_a_repeater_powers(self):
        got = problems([
            "hidden 0 0 0 minecraft:glass", "hidden 0 1 0 minecraft:repeater[facing=west] @a",
            "hidden 1 1 0 minecraft:stone",
            "hidden 1 0 1 minecraft:glass", "hidden 1 1 1 minecraft:redstone_wire[east=side,west=side] @b"])
        self.assertEqual(got[(1, 1, 1)], {"a"})

    def report(self, lines):
        """The checker's printed problems for a layout."""
        import contextlib, io
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
            f.write("\n".join(lines) + "\n")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            redstone_check.check(f.name)
        return [line for line in out.getvalue().splitlines() if not line.endswith("problem(s)")]

    def test_a_comparator_reads_a_container_and_takes_a_redstone_block(self):
        # a comparator reading a hopper carries its own signal; one behind a redstone block carries the block's
        self.assertEqual(self.report([
            "hidden 0 1 0 minecraft:hopper", "hidden 1 0 0 minecraft:glass",
            "hidden 1 1 0 minecraft:comparator[facing=west] @f",
            "hidden 0 1 2 minecraft:redstone_block @on", "hidden 1 0 2 minecraft:glass",
            "hidden 1 1 2 minecraft:comparator[facing=west,mode=subtract] @g<on"]), [])

    def test_a_powered_block_gives_a_comparator_side_nothing(self):
        # vanilla's getControlInputSignal: a redstone block, a wire or a source counts; a powered stone does not
        quiet = self.report([
            "hidden 0 1 1 minecraft:redstone_block @on", "hidden 1 0 1 minecraft:glass",
            "hidden 1 1 1 minecraft:comparator[facing=west,mode=subtract] @g<on",
            "hidden 1 1 0 minecraft:stone", "hidden 1 0 -1 minecraft:glass",
            "hidden 1 1 -1 minecraft:repeater[facing=north] @t"])
        self.assertEqual(quiet, ["UNPOWERED  repeater at (1, 1, -1) (@t): nothing at its input (1, 1, -2)"])
        loud = self.report([
            "hidden 0 1 1 minecraft:redstone_block @on", "hidden 1 0 1 minecraft:glass",
            "hidden 1 1 1 minecraft:comparator[facing=west,mode=subtract] @g<on",
            "hidden 1 0 0 minecraft:glass", "hidden 1 1 0 minecraft:redstone_wire @t"])
        self.assertTrue(any("side input" in line and "'t'" in line for line in loud), loud)


    def gate(self, allowed):
        """An AND of two torches' signals: repeaters @a and @b point into a stone with a torch
        on its side (@q<ab, lit unless the stone is powered); `allowed` the allow line's signals."""
        return self.report([
            f"allow ab {allowed}",
            "hidden 1 0 0 minecraft:glass", "hidden 1 1 0 minecraft:repeater[facing=west] @a<off",
            "hidden 2 0 1 minecraft:glass", "hidden 2 1 1 minecraft:repeater[facing=south] @b<off",
            "hidden 2 1 0 minecraft:stone", "hidden 3 1 0 minecraft:redstone_wall_torch[facing=east] @q<ab"])

    def test_a_gate_torch_takes_the_signals_it_is_made_of(self):
        self.assertEqual([line for line in self.gate("a b") if line.startswith("TORCH")], [])

    def test_a_gate_torch_takes_no_other_signal(self):
        self.assertEqual(len([line for line in self.gate("a") if line.startswith("TORCH")]), 1)

    def fed_twice(self, allowed):
        """A line shifter @s fed by two repeaters, @a from the west and @b from the north (an OR, as the castle
        tower's full columns); `allowed` the allow line's signals."""
        return self.report([
            f"allow s {allowed}",
            "hidden 0 0 1 minecraft:glass", "hidden 0 1 1 minecraft:repeater[facing=west] @a<off",
            "hidden 1 0 0 minecraft:glass", "hidden 1 1 0 minecraft:repeater[facing=north] @b<off",
            "hidden 1 1 1 phasecraft:line_shifter[facing=up] @s"])

    def test_a_shifter_fed_twice_takes_the_signals_it_is_made_of(self):
        self.assertEqual([line for line in self.fed_twice("a b") if "line_shifter" in line], [])

    def test_a_shifter_fed_twice_takes_no_other_signal(self):
        self.assertEqual(len([line for line in self.fed_twice("a") if "line_shifter" in line]), 2)

    def joined(self, allowed):
        """Repeaters @a (from the west) and @b (from the north) power a stone, and a repeater @j reads it (an OR
        on its way on, as the combination lock's J); `allowed` the allow line's signals."""
        return self.report([
            f"allow j {allowed}",
            "hidden 0 0 1 minecraft:glass", "hidden 0 1 1 minecraft:repeater[facing=west] @a<off",
            "hidden 1 0 0 minecraft:glass", "hidden 1 1 0 minecraft:repeater[facing=north] @b<off",
            "hidden 1 0 1 minecraft:glass", "hidden 1 1 1 minecraft:stone",
            "hidden 1 0 2 minecraft:glass", "hidden 1 1 2 minecraft:repeater[facing=north] @j<j"])

    def test_a_repeater_reading_an_or_takes_the_signals_it_is_made_of(self):
        self.assertEqual([line for line in self.joined("a b") if "repeater at (1, 1, 2)" in line], [])

    def test_a_repeater_reading_an_or_takes_no_other_signal(self):
        self.assertEqual(len([line for line in self.joined("a") if "repeater at (1, 1, 2)" in line]), 2)

    def test_a_phased_comparator_reads_the_visible_world(self):
        # a comparator on a phase block reads the visible block behind it (nothing hidden there): an input
        loud = self.report([
            "hidden 1 0 0 phasecraft:phase_block", "hidden 1 1 0 minecraft:comparator[facing=north] @o",
            "hidden 1 0 1 minecraft:glass", "hidden 1 1 1 minecraft:redstone_wire @o"])
        self.assertEqual([line for line in loud if "(1, 1, 0)" in line], [])

    def test_an_unphased_comparator_with_nothing_behind_it_is_unpowered(self):
        loud = self.report([
            "hidden 1 0 0 minecraft:stone", "hidden 1 1 0 minecraft:comparator[facing=north] @o",
            "hidden 1 0 1 minecraft:glass", "hidden 1 1 1 minecraft:redstone_wire @o"])
        self.assertEqual(len([line for line in loud if "UNPOWERED" in line and "(1, 1, 0)" in line]), 1)


if __name__ == "__main__":
    unittest.main()
