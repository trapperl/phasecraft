#!/usr/bin/env python3
"""Writes the pixel wall (examples station 33, PixelWallGameTests): src/gametest/resources/contraptions/pixel_wall.txt.

Usage: python3 tools/gen_pixels.py && python3 tools/redstone_check.py src/gametest/resources/contraptions/pixel_wall.txt

A shifter display: a lime wall 10 wide and 10 high with black behind it. One visible lever draws a creeper's face on
it, the eyes first, then the nose, the mouth, the mouth's corners (4 redstone ticks apart): a hidden phase shifter at
each of the face's 20 pixels takes the lime block there, and the black behind shows through. The lever back takes
the face away in the opposite order. The wiring: tools/gen_door.py (a door of phase shifters switching in rings).
"""

import gen_door
from gen_door import Door

FACE = "minecraft:lime_concrete"
BACK = "minecraft:black_concrete"
FLOOR = "minecraft:stone"
STEP = 4

# The face, top row first (as drawn); X a pixel the shifters take.
PICTURE = [
    "........",
    "........",
    ".XX..XX.",
    ".XX..XX.",
    "...XX...",
    "..XXXX..",
    "..XXXX..",
    "..X..X..",
]
CELLS = [(x - 4, 7 - row) for row, line in enumerate(PICTURE) for x, c in enumerate(line) if c == "X"]


def ring(x, y):
    """Eyes, nose, mouth, the mouth's corners."""
    return 0 if y >= 4 else 1 if y == 3 else 2 if y >= 1 else 3


def visible(at):
    return [
        f"fill visible {at((-7, -1, -7))} {at((7, -1, 7))} {FLOOR}",
        f"fill visible {at((-5, 0, 0))} {at((4, 9, 0))} {FACE}",
        f"fill visible {at((-5, 0, 1))} {at((4, 9, 1))} {BACK}",
    ]


def header(ox, oy, oz, opens):
    return [
        "The pixel wall (PixelWallGameTests, examples station 33). Written by tools/gen_pixels.py: do not edit,",
        "change the generator and run it again; check with: python3 tools/redstone_check.py <this file>",
        "",
        f"A lime wall at z = {oz}, black behind it. One visible lever draws a creeper's face: a hidden phase shifter",
        "at each of its 20 pixels takes the lime block there and the black shows through; eyes, nose, mouth and",
        f"the mouth's corners, {STEP} redstone ticks apart, the first {opens} redstone ticks after the lever; the",
        "lever back takes the face away in the opposite order. Signals: L the lever; P<k>.. ring k's controller;",
        "B<k> ring k's bus; r<row>x<x>.. a run of one ring's blocks in a row (b branch, t tower, U the feed).",
    ]


PIXELS = Door(name="pixel_wall", cells=CELLS, ring=ring, step=STEP,
              planes={3: -3, 2: -5, 1: -7, 0: -9},
              lever=(6, 0, -3), visible=visible, header=header,
              margins=((-7, 0, -7), (7, 11, 7)), door_block=FACE)

if __name__ == "__main__":
    gen_door.build_door(PIXELS)
