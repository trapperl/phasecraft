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


if __name__ == "__main__":
    unittest.main()
