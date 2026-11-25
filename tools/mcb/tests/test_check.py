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


if __name__ == "__main__":
    unittest.main()
