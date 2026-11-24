#!/usr/bin/env python3
"""Writes the mountain door (examples station 24, MountainDoorGameTests): contraptions/mountain_door.txt.

Usage: python3 tools/gen_mountain.py && python3 tools/redstone_check.py src/gametest/resources/contraptions/mountain_door.txt

An arched door, 7 wide and 9 high (57 blocks), at the back of a gorge cut into a mountain. Flip the lever: the door
goes like a clock hand sweeping round, from twelve o'clock clockwise (as you face it), one eighth every STEP redstone
ticks; flip it back and it winds shut the other way. Behind it a tunnel leads to a vault. Every door block is held by a
hidden phase shifter at its own spot. The wiring: tools/gen_door.py.

Generator space: the door in the mountain's face at z = 0, x = -3..3, y = 0..8, the gorge's floor at y = -1 in front
(z < 0); the player faces it looking south, so the player's right is west (-x). The mountain rises from y = -1.
"""

import math
import random

import gen_door
from gen_door import Door
from mcb.shapes import _noise

STEP = 2
CY = 4                                    # the door's middle row: the clock's centre is (0, CY)
DOOR = "minecraft:polished_blackstone_bricks"
FRAME = "minecraft:stone_bricks"
CELLS = [(x, y) for y in range(9) for x in range(-3, 4) if y <= 5 or x * x + (y - 5) ** 2 <= 12.25]
SECTORS = 8


def ring(x, y):
    """The eighth of the clock the cell is in: 0 from twelve o'clock, clockwise as seen from the front (to the
    player's right, -x). Whole numbers only, so the GameTest computes exactly the same."""
    u, v = -x, y - CY
    if u == 0 and v == 0:
        return 0
    if u >= 0 and v > 0:
        return 0 if u < v else 1
    if u > 0 and v <= 0:
        return 2 if -v < u else 3
    if u <= 0 and v < 0:
        return 4 if -u < -v else 5
    return 6 if v < -u else 7


# the deepest planes for the rings reaching least high (their torch towers are shortest); of rings as high, the
# later ones shallower (the fastest of the orders tried: 24 redstone ticks to the first eighth, against 28)
_tops = {k: max(y for x, y in CELLS if ring(x, y) == k) for k in range(SECTORS)}
PLANES = {k: -3 - 2 * i for i, k in enumerate(sorted(range(SECTORS), key=lambda k: (-_tops[k], -k)))}

# --- the mountain ---
X0, X1, Z0, Z1 = -21, 21, -9, 28          # its footprint
GORGE = 6                                 # the gorge in front of the door: |x| <= GORGE, z < 0, floor at y = -1
TUNNEL = range(1, 9)                      # z of the tunnel behind the door (the door's shape)
VAULT = ((-5, 0, 9), (5, 5, 15))          # the room at the tunnel's end
FACE = {"minecraft:stone": 6, "minecraft:andesite": 2, "minecraft:cobblestone": 1, "minecraft:tuff": 1}


def height(x, z):
    """The mountain's top at (x, z): a cone round a peak behind the door, roughened; the gorge cut out in front."""
    if abs(x) <= GORGE and z < 0:
        return -1
    noise = _noise(7)
    r = math.hypot(x, (z - 9) * 1.1)
    h = 32 - 1.5 * r + 3 * (noise(x, z, 6) - 0.5) + 1.5 * (noise(x + 50, z, 3) - 0.5)
    if abs(x) <= GORGE + 1 and 0 <= z <= 3:
        h = max(h, 11)                    # the cliff over the door
    return max(-1, int(round(h)))


def pick(weights, pos):
    r = random.Random(hash(pos) & 0xFFFF).random() * sum(weights.values())
    for block, w in weights.items():
        r -= w
        if r < 0:
            return block
    return next(iter(weights))


def surface(x, z, h):
    """The top block: snow up high, grass where the slope is gentle, bare rock where it is steep."""
    steep = max(abs(h - height(x + dx, z + dz)) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
    if h >= 22:
        return "minecraft:snow_block"
    if steep <= 1:
        return "minecraft:grass_block"
    return pick({"minecraft:stone": 3, "minecraft:andesite": 1, "minecraft:gravel": 1}, (x, z))


def inside(x, y, z):
    """The hollow behind the door: the tunnel, in the door's shape, and the vault."""
    (vx0, vy0, vz0), (vx1, vy1, vz1) = VAULT
    return (z in TUNNEL and (x, y) in CELLS) or (vx0 <= x <= vx1 and vy0 <= y <= vy1 and vz0 <= z <= vz1)


def visible(at):
    lines = []
    blocks = {}                           # single blocks, after the columns
    for x in range(X0, X1 + 1):
        for z in range(Z0, Z1 + 1):
            h = height(x, z)
            if h > -1:
                lines.append(f"fill visible {at((x, -1, z))} {at((x, h - 1, z))} minecraft:stone")
            blocks[(x, h, z)] = surface(x, z, h) if h > -1 else "minecraft:grass_block"
    # the cliff face round the door and the gorge's walls: rough stone
    for x in range(X0, X1 + 1):
        for z in range(Z0, 4):
            for y in range(0, height(x, z)):
                b = pick(FACE, (x, y, z))
                if b != "minecraft:stone" and any(height(x + dx, z + dz) < y for dx, dz in ((1, 0), (-1, 0), (0, -1))):
                    blocks.setdefault((x, y, z), b)     # (stone is there already)
    # the frame round the door, flush in the face; a keystone on top
    for x, y in CELLS:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (1, 1), (-1, 1)):
            p = (x + dx, y + dy)
            if p not in CELLS:
                blocks[(p[0], p[1], 0)] = FRAME
    blocks[(0, 9, 0)] = "minecraft:chiseled_stone_bricks"
    # the tunnel and the vault: hollow, lined with bricks, a polished floor
    hollow = [(x, y, z) for x in range(-5, 6) for y in range(0, 9) for z in range(1, 16) if inside(x, y, z)]
    for x, y, z in hollow:
        for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, 0, 1)):
            q = (x + d[0], y + d[1], z + d[2])
            if not inside(*q) and q[2] > 0:
                blocks[q] = pick({FRAME: 4, "minecraft:mossy_stone_bricks": 1, "minecraft:cracked_stone_bricks": 1}, q)
        blocks[(x, -1, z)] = "minecraft:polished_andesite"
    for p in hollow:
        blocks[p] = "minecraft:air"
    # light: lanterns hanging in the tunnel, on the vault's floor; the treasure
    for z in (3, 7):
        blocks[(0, 8, z)] = "minecraft:lantern[hanging=true]"
    for x in (-4, 4):
        blocks[(x, 0, 14)] = "minecraft:lantern[hanging=false]"
        blocks[(x, 0, 10)] = "minecraft:lantern[hanging=false]"
    blocks[(0, 0, 14)] = "minecraft:chest[facing=north]"
    for x in (-2, -1, 1, 2):
        blocks[(x, 0, 15 if abs(x) == 1 else 14)] = "minecraft:gold_block"
    blocks[(1, 1, 15)] = "minecraft:gold_block"
    # in the gorge: lanterns on posts either side of the door, a path to it
    for x in (-5, 5):
        blocks[(x, 0, -1)] = "minecraft:stone_brick_wall"
        blocks[(x, 1, -1)] = "minecraft:lantern[hanging=false]"
    for z in range(Z0, 0):
        for x in (-1, 0, 1):
            blocks[(x, -1, z)] = "minecraft:dirt_path"
    # spruces on the lower slopes, clear of the gorge
    rng = random.Random(3)
    trunks = []
    for _ in range(60):
        x, z = rng.randint(X0 + 2, X1 - 2), rng.randint(Z0 + 2, Z1 - 2)
        h = height(x, z)
        if abs(x) <= GORGE + 3 and z < 6 or not 0 <= h <= 9 or surface(x, z, h) != "minecraft:grass_block":
            continue
        if any(abs(x - a) + abs(z - b) < 5 for a, b in trunks):
            continue
        trunks.append((x, z))
        tall = rng.randint(5, 7)
        blocks[(x, h, z)] = "minecraft:dirt"
        for y in range(h + 1, h + tall + 1):
            blocks[(x, y, z)] = "minecraft:spruce_log[axis=y]"
        for level in range(2, tall + 2):
            r = max(0, (tall + 1 - level) // 2)
            for dx in range(-r, r + 1):
                for dz in range(-r, r + 1):
                    q = (x + dx, h + level, z + dz)
                    if (dx or dz) and abs(dx) + abs(dz) <= r + 1 and q[1] > height(q[0], q[2]):
                        blocks.setdefault(q, "minecraft:spruce_leaves[persistent=true]")
        blocks[(x, h + tall + 1, z)] = "minecraft:spruce_leaves[persistent=true]"
    # the door's own blocks, which the shifters take when they are placed if the door is to be open
    for x, y in CELLS:
        blocks[(x, y, 0)] = DOOR
    lines += [f"visible {at(p)} {b}" for p, b in blocks.items()]
    return lines


def header(ox, oy, oz, opens):
    return [
        "The mountain door (MountainDoorGameTests, examples station 24). Written by tools/gen_mountain.py: do not",
        "edit, change the generator and run it again; check with: python3 tools/redstone_check.py <this file>",
        "",
        f"An arched door, 7 wide and 9 high, in a mountain's face at z = {oz}, x = {ox - 3}..{ox + 3}, y = {oy}..{oy + 8}.",
        "One visible lever opens it like a clock hand sweeping round: from twelve o'clock, clockwise as you face",
        f"it, an eighth every {STEP} redstone ticks, the first {opens} redstone ticks after the lever; it closes",
        "winding back. Every door block is held by a hidden phase shifter at its own spot.",
        "Signals: L the lever; P<k>.. eighth k's controller; B<k> its bus; r<row>x<x>.. a run of its blocks in a row.",
    ]


MOUNTAIN = Door(name="mountain_door", cells=CELLS, ring=ring, step=STEP, planes=PLANES,
                lever=(5, 0, -3),          # in the gorge, left of the path as you face the door
                visible=visible, header=header,
                margins=((X0, -1, Z0), (X1, 34, Z1)), door_block=DOOR)

if __name__ == "__main__":
    gen_door.build_door(MOUNTAIN)
