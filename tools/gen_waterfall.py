#!/usr/bin/env python3
"""Writes the parting waterfall (examples station 23, WaterfallGameTests): contraptions/waterfall.txt.

Usage: python3 tools/gen_waterfall.py && python3 tools/redstone_check.py src/gametest/resources/contraptions/waterfall.txt

A pool on a cliff top spills over the edge in five ribbons of water, 14 high, between rock ribs, with a cave behind
the middle. Flip the lever on the cliff top: the middle ribbon stops first, then the two beside it, then the outer
two, STEP redstone ticks apart, each draining from the top down, and the cave appears; flip it back and they pour
again from the outside in. Each ribbon is fed from the pool through a gate spot on the cliff's edge; a hidden phase
shifter there holds a stone while the water runs and puts it back to stop it (a put-back block replaces the water).
Why ribbons, one wide, between ribs: water takes its level from the water beside it, falling water counting as full.
In one 9-wide fall the gated columns stayed wet beside running ones; a 3-wide stream drained, but slowly (each level
only after the one above, its columns topping each other up: about 25 s). One column between ribs drains in about
3.5 s. The wiring: tools/gen_door.py.

Generator space: the gate row at y = 0 along the cliff's front edge (z = 0); the cliff fills z >= 0 below y = 0;
the water falls at z = -1 into a basin at y = -14; the pool is behind the gates, the hidden wiring under and behind.
"""

import random

import gen_door
from gen_door import Door

STEP = 3
GATES = [(x, 0) for x in (-4, -2, 0, 2, 4)]
RIBS = (-5, -3, -1, 1, 3, 5)      # the outer two frame the fall: nothing may run beside a ribbon
GATE = "minecraft:stone"
WATER = "minecraft:water"
FACE = {"minecraft:stone": 6, "minecraft:andesite": 2, "minecraft:cobblestone": 1, "minecraft:mossy_cobblestone": 1}


def ring(x, y):
    return abs(x) // 2


def face_block(pos):
    """A natural-looking cliff face: mostly stone, some andesite and cobble, the same each time."""
    r = random.Random(hash(pos) & 0xFFFF).random() * sum(FACE.values())
    for block, w in FACE.items():
        r -= w
        if r < 0:
            return block
    return "minecraft:stone"


def visible(at):
    lines = [
        # the cliff, with its top at y = -1, and the ground in front
        f"fill visible {at((-7, -15, 0))} {at((8, -1, 6))} minecraft:stone",
        f"fill visible {at((-8, -15, -8))} {at((8, -15, -1))} minecraft:stone",
        f"fill visible {at((-8, -14, -8))} {at((8, -14, -7))} minecraft:grass_block",
        f"fill visible {at((-8, -14, -6))} {at((-7, -14, -1))} minecraft:grass_block",
        f"fill visible {at((7, -14, -6))} {at((8, -14, -1))} minecraft:grass_block",
        # the basin the water falls into
        f"fill visible {at((-6, -14, -6))} {at((6, -14, -1))} {WATER}",
        # the cave behind the middle of the fall, with a lantern
        f"fill visible {at((-1, -14, 0))} {at((1, -12, 4))} minecraft:air",
        f"fill visible {at((-1, -15, 0))} {at((1, -15, 4))} minecraft:gravel",
        f"visible {at((0, -14, 4))} minecraft:lantern[hanging=false]",
        # the pool on the cliff top: a rim, water, and the front row the water spills over
        f"fill visible {at((-6, 0, 0))} {at((6, 0, 6))} minecraft:mossy_cobblestone",
        f"fill visible {at((-5, 0, 1))} {at((5, 0, 5))} {WATER}",
        f"fill visible {at((-4, 0, 0))} {at((4, 0, 0))} {WATER}",     # the front row the ribbons spill from
    ]
    # the rock ribs between the ribbons, from the basin (or, either side of the cave, from above its entrance) to
    # the pool's edge, standing out from the face
    for x in RIBS:
        bottom = -11 if abs(x) == 1 else -14
        lines.append(f"fill visible {at((x, bottom, -1))} {at((x, 0, -1))} minecraft:stone")
        if abs(x) < 5:      # the rib's top on the front row (the outer two are the rim's already)
            lines.append(f"visible {at((x, 0, 0))} minecraft:mossy_cobblestone")
        if abs(x) >= 3:
            lines.append(f"fill visible {at((x, -14, -2))} {at((x, -4 if abs(x) == 5 else -8, -2))} minecraft:stone")
    # texture on the cliff face
    for x in range(-7, 9):
        for y in range(-14, 0):
            b = face_block((x, y))
            if b != "minecraft:stone" and not (-1 <= x <= 1 and -14 <= y <= -12):
                lines.append(f"visible {at((x, y, 0))} {b}")
    # the gates: stone the shifters take when they are placed (powered at rest), so the water runs
    lines += [f"visible {at((x, y, 0))} {GATE}" for x, y in GATES]
    return lines


def header(ox, oy, oz, opens):
    return [
        "The parting waterfall (WaterfallGameTests, examples station 23). Written by tools/gen_waterfall.py: do not",
        "edit, change the generator and run it again; check with: python3 tools/redstone_check.py <this file>",
        "",
        f"A pool on a cliff spills in five ribbons; their gates are at y = {oy}, z = {oz}, x = {ox - 4}..{ox + 4} by 2.",
        "Lever on: a hidden phase shifter at each gate puts a stone back, the middle first, then outward,",
        f"{STEP} redstone ticks apart, the first {opens} redstone ticks after the lever; each ribbon drains",
        "and the cave behind appears. Lever off: the stones are taken again, the outer ribbons first.",
        "Signals: L the lever; P<k>.. ring k's controller; B<k> ring k's bus; r<row>x<x>.. a gate's wiring.",
    ]


WATERFALL = Door(name="waterfall", cells=GATES, ring=ring, step=STEP,
                 planes={2: -3, 1: -5, 0: -7},
                 lever=(6, 1, 3),         # on the pool's rim, beside the water
                 visible=visible, header=header,
                 margins=((-8, -15, -8), (8, 1, 6)), door_block=GATE, invert=True)

if __name__ == "__main__":
    gen_door.build_door(WATERFALL)
