#!/usr/bin/env python3
"""Writes the giant iris door (examples station 22, IrisDoorGameTests): src/gametest/resources/contraptions/iris_door.txt.

Usage: python3 tools/gen_iris.py && python3 tools/redstone_check.py src/gametest/resources/contraptions/iris_door.txt

A round 7x7 door (37 blocks) in a wall, flush with it. One visible lever opens it from the centre outward, ring by
ring, and closes it from the outside in (rings by distance from the centre: 1, 8, 12 and 16 blocks, 4 redstone
ticks apart). Every door block is held by a hidden phase shifter. The wiring: tools/gen_door.py.
"""

import gen_door
from gen_door import Door

DOOR = "minecraft:polished_deepslate"
WALL = "minecraft:stone_bricks"
FLOOR = "minecraft:stone"
STEP = 4


def ring(x, y):
    d2 = x * x + (y - 3) * (y - 3)
    return 0 if d2 == 0 else 1 if d2 <= 2 else 2 if d2 <= 6 else 3


CELLS = [(x, y) for y in range(7) for x in range(-3, 4) if x * x + (y - 3) * (y - 3) <= 12]


def visible(at):
    return [
        f"fill visible {at((-6, -1, -7))} {at((8, -1, 7))} {FLOOR}",
        f"fill visible {at((-5, 0, 0))} {at((5, 7, 0))} {WALL}",
    ] + [f"visible {at((x, y, 0))} {DOOR}" for x, y in CELLS]


def header(ox, oy, oz, opens):
    return [
        "The giant iris door (IrisDoorGameTests, examples station 22). Written by tools/gen_iris.py: do not edit,",
        "change the generator and run it again; check with: python3 tools/redstone_check.py <this file>",
        "",
        f"A round 7x7 door (37 blocks) in a wall at z = {oz}, centre ({ox}, {oy + 3}, {oz}). One visible lever",
        "opens it from the centre outward and closes it from the outside in: every door block is held by a",
        f"hidden phase shifter at its own spot; rings switch {STEP} redstone ticks apart, the first {opens}",
        "redstone ticks after the lever. Signals: L the lever; P<k>.. ring k's controller; B<k> ring k's bus;",
        "r<row>x<x>.. a run of one ring's blocks in a row, from column x (b branch, t tower, U the blocks' feed).",
    ]


IRIS = Door(name="iris_door", cells=CELLS, ring=ring, step=STEP,
            planes={3: -3, 2: -5, 1: -7, 0: -9},     # outer rings climb most
            lever=(6, 0, -2), visible=visible, header=header,
            margins=((-6, 0, -7), (8, 8, 7)), door_block=DOOR)

if __name__ == "__main__":
    gen_door.build_door(IRIS)
