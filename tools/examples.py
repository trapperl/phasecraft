#!/usr/bin/env python3
"""Build (or rebuild) the PhaseCraft examples area on the local dev server.

Usage:
    ./gradlew runServer            # in another terminal, RCON enabled
    python3 tools/examples.py

    python3 tools/examples.py --signs-only    # rewrite only the signs, keep the rest

    python3 tools/examples.py --print-commands > build/examples-commands.txt
    PHASECRAFT_EXAMPLES_COMMANDS=$PWD/build/examples-commands.txt ./gradlew runClientGameTest
                                   # screenshots of every station, overlay on
                                   # and off, in build/run/clientGameTest/screenshots

Builds a row of example stations near spawn on a flat world (ground at
y=-61, stations at y=-60), each with a sign explaining it. The hidden layer is
a real dimension at the same coordinates (phasecraft:hidden_overworld); the
script puts a hidden block of ground under hidden blocks that need one, since
hidden redstone follows vanilla rules. With PhaseCraft on the client, press H
(Creative or Spectator) to see the hidden layer as an overlay; or inspect it
with /phasecraft get, or go there with /phasecraft layer hidden. After building, every hidden cell is read
back over RCON and the script exits 1 if any does not match.

Add a station here whenever a milestone adds something worth seeing.
"""

import importlib.util
import pathlib
import sys
import time

_rcon_spec = importlib.util.spec_from_file_location("rcon", pathlib.Path(__file__).with_name("rcon.py"))
rcon = importlib.util.module_from_spec(_rcon_spec)
_rcon_spec.loader.exec_module(rcon)

Y = -60          # first air block above the flat world's grass
SIGN_Z = -2      # signs stand north of each station, readable from the north

# Each station: title, visible blocks, hidden cells, sign lines (max ~15 chars each).
# Positions are (x, y, z). Hidden cells are (dimension, pos, block[, expected after placing]).
STATIONS = [
    {
        "x": 0,
        "sign": ["1. Same spot", "You see stone.", "Hidden inside:", "redstone wire"],
        "visible": [((0, Y, 0), "minecraft:stone")],
        "hidden": [("minecraft:overworld", (0, Y, 0), "minecraft:redstone_wire")],
    },
    {
        "x": 6,
        "sign": ["2. Exact copy", "Hidden repeater", "(delay 3) next", "to a real one"],
        "visible": [((6, Y, 1), "minecraft:repeater[facing=east,delay=3]")],
        "hidden": [("minecraft:overworld", (6, Y, 0), "minecraft:repeater[facing=east,delay=3]")],
    },
    {
        "x": 12,
        "sign": ["3. Own layer", "A hidden piston", "can't move real", "blocks (see 9)"],
        "visible": [((12, Y, 0), "minecraft:glass"), ((12, Y + 1, 0), "minecraft:sand")],
        "hidden": [("minecraft:overworld", (12, Y, 0), "minecraft:piston[facing=up]")],
    },
    {
        "x": 18,
        "sign": ["4. Break me", "Break planks:", "hidden block", "stays (press H)"],
        "visible": [((18, Y, 0), "minecraft:oak_planks")],
        "hidden": [("minecraft:overworld", (18, Y, 0), "minecraft:redstone_block")],
    },
    {
        "x": 24,
        "sign": ["5. Each world", "has its own", "hidden layer.", "Nether: 24 64 0"],
        "visible": [((24, Y, 0), "minecraft:polished_andesite")],
        "hidden": [
            ("minecraft:overworld", (24, Y, 0), "minecraft:lever[face=floor,facing=north]"),
            ("minecraft:the_nether", (24, 64, 0), "minecraft:glowstone"),
        ],
    },
    {
        "x": 30,
        "sign": ["6. Hidden power", "Hidden redstone", "block lights a", "hidden lamp"],
        "visible": [],
        "hidden": [
            # Placed unlit; the check expects lit=true, so it passes only if vanilla
            # redstone in the hidden layer lit it.
            ("minecraft:overworld", (30, Y, 0), "minecraft:redstone_lamp[lit=false]", "minecraft:redstone_lamp[lit=true]"),
            ("minecraft:overworld", (30, Y, 1), "minecraft:redstone_block"),
        ],
    },
    {
        "x": 36,
        "sign": ["7. Phase port", "Lever's power", "goes down to a", "hidden lamp"],
        "visible": [((36, Y, 0), "minecraft:lever[face=floor,facing=north,powered=true]")],
        "hidden": [
            ("minecraft:overworld", (36, Y, 1), "minecraft:redstone_lamp[lit=false]", "minecraft:redstone_lamp[lit=true]"),
            ("minecraft:overworld", (36, Y, 0), "phasecraft:phase_port", "phasecraft:phase_port[power=15]"),
        ],
    },
    {
        "x": 42,
        "sign": ["8. Power down", "Flip the lever:", "power goes down", "a hidden piston"],
        "visible": [((42, Y, 0), "minecraft:lever[face=floor,facing=north,powered=false]")],
        "hidden": [
            ("minecraft:overworld", (42, Y, 2), "minecraft:oak_planks"),
            ("minecraft:overworld", (42, Y, 1), "minecraft:piston[facing=south]", "minecraft:piston[extended=false,facing=south]"),
            ("minecraft:overworld", (42, Y, 0), "phasecraft:phase_port", "phasecraft:phase_port[power=0]"),
        ],
    },
    {
        "x": 48,
        "sign": ["9. Phase piston", "Flip the lever:", "hidden piston", "moves the brick"],
        "visible": [
            ((48, Y, 0), "minecraft:lever[face=floor,facing=north,powered=false]"),
            ((49, Y, 1), "minecraft:stone_bricks"),
        ],
        "hidden": [
            ("minecraft:overworld", (48, Y, 0), "phasecraft:phase_port", "phasecraft:phase_port[power=0]"),
            ("minecraft:overworld", (49, Y, 0), "phasecraft:sticky_phase_piston[facing=south]",
             "phasecraft:sticky_phase_piston[extended=false,facing=south]"),
        ],
    },
]

HIDDEN_OVERWORLD = "phasecraft:hidden_overworld"

# Station 14: phased light. A dark hut; one brick of its back wall is a secret
# lever, and a redstone lamp hidden in the wall beside it, standing on a hidden
# phase block, lights the hut when the lever is on.
STATIONS.append({
    "x": 115,
    "sign": ["14. Hidden lamp", "Dark hut: find", "the brick that", "turns it on"],
    "visible": [],
    "hidden": [
        ("minecraft:overworld", (118, Y + 1, 5), "phasecraft:phase_block"),
        ("minecraft:overworld", (118, Y + 1, 4), "minecraft:lever[face=wall,facing=north]",
         "minecraft:lever[face=wall,facing=north,powered=false]"),
        ("minecraft:overworld", (119, Y, 4), "phasecraft:phase_block"),
        ("minecraft:overworld", (119, Y + 1, 4), "minecraft:redstone_lamp", "minecraft:redstone_lamp[lit=false]"),
    ],
})

# Station 13: the phase block. A secret button (the top-middle brick of a low
# wall) and a secret pressure plate (in front of a lamp), each hanging from or
# standing on a hidden phase block. Their power comes up into the visible world
# through hidden phase ports next to the phase blocks, which light the lamps.
# Nothing of the hidden layer shows.
STATIONS.append({
    "x": 105,
    "sign": ["13. Phase block", "Click the top", "middle brick or", "stand at lamp"],
    "visible": [((x, y, 0), "minecraft:stone_bricks") for x in (107, 108, 109) for y in (Y, Y + 1)] + [
        ((108, Y + 1, 2), "minecraft:stone_bricks"),
        ((108, Y + 2, 2), "minecraft:redstone_lamp"),
        ((111, Y, 0), "minecraft:redstone_lamp"),
    ],
    "hidden": [
        ("minecraft:overworld", (108, Y + 1, 1), "phasecraft:phase_block"),
        ("minecraft:overworld", (108, Y + 1, 0), "minecraft:stone_button[face=wall,facing=north]",
         "minecraft:stone_button[face=wall,facing=north,powered=false]"),
        ("minecraft:overworld", (108, Y + 1, 2), "phasecraft:phase_port", "phasecraft:phase_port[up=0]"),
        ("minecraft:overworld", (111, Y - 1, -1), "phasecraft:phase_block"),
        ("minecraft:overworld", (111, Y, -1), "minecraft:stone_pressure_plate", "minecraft:stone_pressure_plate[powered=false]"),
        ("minecraft:overworld", (111, Y - 1, 0), "phasecraft:phase_port", "phasecraft:phase_port[up=0]"),
    ],
})

# Station 19: phased watchers (phase block, third step). Three hidden watchers,
# each on a hidden phase block, each lighting a visible lamp through a hidden
# phase port; nothing of the hidden layer shows.
#   x 180: a phased comparator reads the chest behind it: put anything in the
#          chest and the lamp on the brick behind it lights.
#   x 184..188: a 1-wide doorway; hidden tripwire hooks on phase blocks inside
#          the pillars, hidden string in the gap: walk through and the lamp
#          on the left pillar lights.
#   x 192: a phased observer watches the spot over the chiseled brick: place
#          or break a block there and the lamp at the end of the low wall
#          toggles (the observer's pulse toggles a hidden copper bulb, which a
#          hidden comparator reads).
WATCH_CHEST = (180, Y, 0)
WATCH_CHEST_LAMP = (180, Y + 1, 2)
WATCH_STRING = (186, Y, 0)
WATCH_DOOR_LAMP = (184, Y + 2, 0)
WATCH_SPOT = (192, Y, 0)
WATCH_SPOT_LAMP = (192, Y + 1, 4)
STATIONS.append({
    "x": 178,
    "sign": ["19. Watchers", "Fill the chest,", "walk the door,", "build on mark"],
    "visible": [
        (WATCH_CHEST, "minecraft:chest[facing=north]"),
        ((180, Y, 1), "minecraft:stone_bricks"),
        ((180, Y, 2), "minecraft:stone_bricks"),
        (WATCH_CHEST_LAMP, "minecraft:redstone_lamp"),
    ] + [((x, y, 0), "minecraft:stone_bricks") for x in (184, 185, 187, 188) for y in (Y, Y + 1)] + [
        ((x, Y + 2, 0), "minecraft:stone_bricks") for x in (185, 186, 187, 188)] + [
        (WATCH_DOOR_LAMP, "minecraft:redstone_lamp"),
        ((192, Y - 1, 0), "minecraft:chiseled_stone_bricks"),
    ] + [((192, Y, z), "minecraft:stone_bricks") for z in (1, 2, 3, 4)] + [
        (WATCH_SPOT_LAMP, "minecraft:redstone_lamp"),
    ],
    "hidden": [
        # The chest: comparator on a phase block, reading north, into a port under the lamp.
        ("minecraft:overworld", (180, Y - 1, 1), "phasecraft:phase_block"),
        ("minecraft:overworld", (180, Y, 1), "minecraft:comparator[facing=north]", "minecraft:comparator[facing=north,powered=false]"),
        ("minecraft:overworld", (180, Y, 2), "phasecraft:phase_port", "phasecraft:phase_port[up=0]"),
        # The doorway: hooks on phase blocks inside the pillars, string in the gap, a port under the lamp.
        ("minecraft:overworld", (184, Y, 0), "phasecraft:phase_block"),
        ("minecraft:overworld", (188, Y, 0), "phasecraft:phase_block"),
        ("minecraft:overworld", (185, Y, 0), "minecraft:tripwire_hook[facing=east]",
         "minecraft:tripwire_hook[attached=true,facing=east,powered=false]"),
        ("minecraft:overworld", (187, Y, 0), "minecraft:tripwire_hook[facing=west]",
         "minecraft:tripwire_hook[attached=true,facing=west,powered=false]"),
        ("minecraft:overworld", WATCH_STRING, "minecraft:tripwire", "minecraft:tripwire[attached=true,powered=false]"),
        ("minecraft:overworld", (184, Y + 1, 0), "phasecraft:phase_port", "phasecraft:phase_port[up=0]"),
        # The mark: observer on a phase block watching north, a copper bulb behind it,
        # a comparator (on plain hidden stone) reading the bulb, a port under the lamp.
        ("minecraft:overworld", (192, Y - 1, 1), "phasecraft:phase_block"),
        ("minecraft:overworld", (192, Y, 1), "minecraft:observer[facing=north]", "minecraft:observer[facing=north,powered=false]"),
        ("minecraft:overworld", (192, Y, 2), "minecraft:waxed_copper_bulb", "minecraft:waxed_copper_bulb[lit=false]"),
        ("minecraft:overworld", (192, Y - 1, 3), "minecraft:stone"),
        ("minecraft:overworld", (192, Y, 3), "minecraft:comparator[facing=north]", "minecraft:comparator[facing=north,powered=false]"),
        ("minecraft:overworld", (192, Y, 4), "phasecraft:phase_port", "phasecraft:phase_port[up=0]"),
    ],
})

WELCOME = ["PhaseCraft", "H: see hidden", "J: edit hidden", "[ ] = opacity"]

# Hidden blocks that need something under them (vanilla rules: wire, repeaters
# and floor levers break without support) get one hidden block of ground.
HIDDEN_SUPPORTS = [(0, Y - 1, 0), (6, Y - 1, 0), (24, Y - 1, 0)]


def coords(pos):
    return f"{pos[0]} {pos[1]} {pos[2]}"


SIGN_LINE_MAX = 15  # characters that fit on a sign line at the default font, roughly


def sign(pos, lines):
    too_long = [line for line in lines if len(line) > SIGN_LINE_MAX]
    if too_long or len(lines) > 4:
        raise ValueError(f"sign text does not fit (4 lines of {SIGN_LINE_MAX}): {lines}")
    messages = ",".join('"' + line.replace('"', "'") + '"' for line in (lines + ["", "", "", ""])[:4])
    return f"setblock {coords(pos)} minecraft:oak_sign[rotation=8]{{front_text:{{messages:[{messages}]}}}} replace"


def in_dimension(dimension, command):
    return f"execute in {dimension} run {command}"


# Station 10: the hidden 2x2 flush door (same layout as HiddenDoorGameTests).
DOOR_ORIGIN = (58, -61, -4)   # test-relative (0, 0, 0); relative y = 1 is the station level
DOOR_BLOCK = "minecraft:smooth_stone"


def door_pos(x, y, z):
    ox, oy, oz = DOOR_ORIGIN
    return (ox + x, oy + y, oz + z)


def door_commands():
    hidden = lambda pos, block: in_dimension(HIDDEN_OVERWORLD, f"setblock {coords(door_pos(*pos))} {block}")
    visible = lambda pos, block: f"setblock {coords(door_pos(*pos))} {block}"
    out = []
    ox, oy, oz = DOOR_ORIGIN
    out.append(f"fill {ox - 2} {oy + 1} {oz - 2} {ox + 9} {oy + 6} {oz + 9} minecraft:air")
    out.append(in_dimension(HIDDEN_OVERWORLD, f"fill {ox - 2} {oy} {oz - 2} {ox + 9} {oy + 6} {oz + 9} minecraft:air"))
    out.append(f"fill {ox} {oy} {oz} {ox + 7} {oy} {oz + 7} minecraft:stone")
    for x in range(8):
        for y in range(1, 4):
            for z in range(2, 5):
                passage = x in (3, 4) and y <= 2
                pocket = z == 3 and y <= 2 and x in (2, 5)
                if not (passage or pocket):
                    out.append(visible((x, y, z), "minecraft:stone_bricks"))
    for x in (3, 4):
        for y in (1, 2):
            out.append(visible((x, y, 4), DOOR_BLOCK))
    out.append(visible((7, 2, 6), "minecraft:stone"))
    out.append(visible((7, 3, 6), "minecraft:lever[face=floor,facing=north,powered=false]"))
    out.append(sign(door_pos(2, 1, 7), ["10. Hidden door", "Flip the lever", "on the pillar.", "H shows how"]))
    # Hidden machinery.
    out.append(hidden((7, 3, 6), "phasecraft:phase_port"))
    for z in range(3, 6):
        out.append(hidden((7, 2, z), "minecraft:stone"))
        out.append(hidden((7, 3, z), "minecraft:redstone_wire"))
    for x in (3, 4):
        for y in (1, 2):
            out.append(hidden((x, y, 5), "phasecraft:sticky_phase_piston[facing=north]"))
        out.append(hidden((x, 3, 5), "minecraft:redstone_wire"))
    for y in (1, 2):
        out.append(hidden((3, y, 3), "phasecraft:sticky_phase_piston[facing=east]"))
        out.append(hidden((4, y, 3), "phasecraft:sticky_phase_piston[facing=west]"))
    out.append(hidden((3, 3, 3), "minecraft:redstone_wire"))
    out.append(hidden((4, 3, 3), "minecraft:redstone_wire"))
    for pos in [(5, 3, 5), (5, 3, 3), (6, 2, 5), (6, 2, 3), (5, 2, 4)]:
        out.append(hidden(pos, "minecraft:stone"))
    out.append(hidden((6, 3, 5), "minecraft:repeater[facing=east,delay=1]"))
    out.append(hidden((6, 3, 3), "minecraft:repeater[facing=east,delay=4]"))
    out.append(hidden((5, 3, 4), "minecraft:repeater[facing=north,delay=4]"))
    return out


def door_state():
    """'open', 'closed' or a picture of the door blocks (D = door, . = air)."""
    cells = [(x, y, z) for z in (3, 4) for y in (1, 2) for x in (2, 3, 4, 5)]
    replies = rcon.send_commands([f"phasecraft get {coords(door_pos(*c))}" for c in cells])
    seen = {c: (DOOR_BLOCK in r.split("visible: ", 1)[1].split(" |")[0]) for c, r in zip(cells, replies)}
    door_at = {c for c, is_door in seen.items() if is_door}
    if door_at == {(x, y, 4) for x in (3, 4) for y in (1, 2)}:
        return "closed"
    if door_at == {(x, y, 3) for x in (2, 5) for y in (1, 2)}:
        return "open"
    return "".join("D" if seen[c] else "." for c in cells)


def lever(on):
    rcon.send_commands([f"setblock {coords(door_pos(7, 3, 6))} minecraft:lever[face=floor,facing=north,powered={str(on).lower()}]"])


# Station 11: the hidden staircase (same layout as HiddenStaircaseGameTests).
STAIR_ORIGIN = (76, -61, -4)   # test-relative (0, 0, 0); the walking surface is relative y = 5
STAIR_STEP = "minecraft:polished_andesite"
STAIR_BODY = "minecraft:stone_bricks"
STAIR_LEVER = (9, 6, 2)


def stair_pos(x, y, z):
    ox, oy, oz = STAIR_ORIGIN
    return (ox + x, oy + y, oz + z)


def stair_air(x, y, z):
    """Visible air inside the platform: the pit under the hidden floor, the cellar, the wall pockets."""
    return (x in (3, 4) and ((z == 2 and y == 4) or (z == 3 and y == 4) or (z == 4 and y in (3, 4))
                             or (5 <= z <= 7 and 2 <= y <= 4))) or (y == 4 and z in (3, 4) and x in (2, 5))


def staircase_commands():
    visible = lambda pos, block: f"setblock {coords(stair_pos(*pos))} {block}"
    hidden = lambda pos, block: in_dimension(HIDDEN_OVERWORLD, f"setblock {coords(stair_pos(*pos))} {block}")

    def on(pos, block):  # a hidden wire or repeater on a hidden block of stone
        x, y, z = pos
        return [hidden((x, y - 1, z), "minecraft:stone"), hidden(pos, block)]

    def repeater(facing, delay, powered):  # facing = the input side
        return f"minecraft:repeater[facing={facing},delay={delay},powered={str(powered).lower()}]"

    wire = "minecraft:redstone_wire"
    ox, oy, oz = STAIR_ORIGIN
    out = [
        f"fill {ox - 1} {oy + 1} {oz - 8} {ox + 12} {oy + 8} {oz + 10} minecraft:air",
        in_dimension(HIDDEN_OVERWORLD, f"fill {ox - 1} {oy} {oz - 8} {ox + 12} {oy + 8} {oz + 10} minecraft:air"),
        f"fill {ox} {oy} {oz} {ox + 10} {oy} {oz + 8} minecraft:stone",
        f"fill {ox} {oy + 1} {oz} {ox + 10} {oy + 4} {oz + 8} {STAIR_BODY}",
        f"fill {ox} {oy + 5} {oz} {ox + 10} {oy + 5} {oz + 8} {STAIR_STEP}",
    ]
    out += [visible((x, y, z), "minecraft:air")
            for x in range(11) for y in range(1, 6) for z in range(9) if stair_air(x, y, z)]
    for x in (3, 4):
        out.append(visible((x, 3, 3), STAIR_STEP))
        out.append(visible((x, 2, 4), STAIR_STEP))
        out.append(f"fill {coords(stair_pos(x, 1, 5))} {coords(stair_pos(x, 1, 7))} {STAIR_STEP}")
    # Something to find in the cellar, and light to find it by.
    out.append(visible((3, 2, 7), "minecraft:chest[facing=north]"))
    out.append(visible((4, 2, 7), "minecraft:lantern"))
    # Ordinary stairs up to the platform, on the north side by the lever.
    for i, z in enumerate(range(-5, 0)):
        for x in (7, 8):
            if i > 0:
                out.append(f"fill {coords(stair_pos(x, 1, z))} {coords(stair_pos(x, i, z))} {STAIR_BODY}")
            out.append(visible((x, i + 1, z), "minecraft:stone_brick_stairs[facing=south]"))
    out.append(visible(STAIR_LEVER, "minecraft:lever[face=floor,facing=north,powered=false]"))
    out.append(sign(stair_pos(6, 1, -6), ["11. Stairs", "Go up and flip", "the lever: the", "floor opens"]))

    # Hidden machinery, closed, in signal order so every part is placed already settled.
    out.append(hidden((9, 5, 2), "phasecraft:phase_port"))         # under the lever
    out += on((9, 5, 3), repeater("north", 1, False))              # R1: lever -> S1
    out += on((8, 5, 2), repeater("east", 4, False))               # R2: lever -> stage B bus
    out += on((8, 5, 4), repeater("west", 4, False))               # R3: stage B bus -> S1 (hold)
    out.append(hidden((9, 5, 4), "minecraft:stone"))               # S1
    for z in range(0, 5):
        out += on((7, 5, z), wire)
    for x in range(6, -1, -1):
        out += on((x, 5, 0), wire)
    for z in range(1, 5):
        out += on((0, 5, z), wire)
    for z in (3, 4):
        out += on((1, 5, z), repeater("west", 1, False))
        out += on((2, 5, z), repeater("west", 3, False))
        out += on((6, 5, z), repeater("east", 1, False))
        out += on((5, 5, z), repeater("east", 3, False))
    out.append(hidden((9, 5, 5), "minecraft:redstone_wall_torch[facing=south,lit=true]"))
    out += on((9, 4, 5), wire)
    out += on((8, 3, 5), wire)
    out += on((7, 3, 5), repeater("east", 1, True))
    for z in range(5, 0, -1):
        out += on((6, 3, z), wire)
    for x in range(5, 0, -1):
        out += on((x, 3, 1), wire)
    for z in range(2, 5):
        out += on((1, 3, z), wire)
    for z in range(2, 5):
        out += on((2, 3, z), repeater("west", 1, True))
        out += on((5, 3, z), repeater("east", 1, True))
    for z in range(2, 5):
        for x in (3, 4):
            out.append(hidden((x, 3, z), "phasecraft:sticky_phase_piston[facing=up,extended=true]"))
    for z in (3, 4):
        out.append(hidden((3, 4, z), "phasecraft:sticky_phase_piston[facing=east,extended=false]"))
        out.append(hidden((4, 4, z), "phasecraft:sticky_phase_piston[facing=west,extended=false]"))
    return out


def staircase_state():
    """'closed', 'open' or a picture of the moving blocks (S = step block, . = anything else)."""
    cells = [(x, y, z) for z in (2, 3, 4) for y in (5, 4) for x in (2, 3, 4, 5) if y == 4 or x in (3, 4)]
    replies = rcon.send_commands([f"phasecraft get {coords(stair_pos(*c))}" for c in cells])
    step_at = {c for c, r in zip(cells, replies) if STAIR_STEP in r.split("visible: ", 1)[1].split(" |")[0]}
    if step_at == {(x, 5, z) for x in (3, 4) for z in (2, 3, 4)}:
        return "closed"
    if step_at == {(3, 4, 2), (4, 4, 2), (2, 4, 3), (2, 4, 4), (5, 4, 3), (5, 4, 4)}:
        return "open"
    return "".join("S" if c in step_at else "." for c in cells)


def stair_lever(on):
    rcon.send_commands([f"setblock {coords(stair_pos(*STAIR_LEVER))} "
                        f"minecraft:lever[face=floor,facing=north,powered={str(on).lower()}]"])


# Station 12: the 3x3 flush door, built from its layout file (shared with ThreeByThreeDoorGameTests).
DOOR3_LAYOUT = pathlib.Path(__file__).resolve().parent.parent / "src/gametest/resources/contraptions/flush_door_3x3.txt"
DOOR3_ORIGIN = (92, -63, -12)   # layout (0, 0, 0); the tunnel floor (layout y = 2) is the grass level


def read_layout(path):
    """(placements, info): placements are (layer, from, to, block) in file order; info has lever, door, clicks, step."""
    placements, info = [], {}
    for raw in open(path):
        words = raw.split("#", 1)[0].split()
        if not words:
            continue
        if words[0] in ("visible", "hidden"):
            pos = tuple(int(v) for v in words[1:4])
            placements.append((words[0], pos, pos, words[4]))
        elif words[0] == "fill":
            placements.append((words[1], tuple(int(v) for v in words[2:5]), tuple(int(v) for v in words[5:8]), words[8]))
        elif words[0] == "lever":
            info["lever"] = tuple(int(v) for v in words[1:4])
        elif words[0] == "click":
            info.setdefault("clicks", []).append((tuple(int(v) for v in words[1:4]), words[4]))
        elif words[0] == "step":
            info["step"] = tuple(int(v) for v in words[1:4])
        elif words[0] == "door":
            info["door"] = words[1]
    return placements, info


def door3_pos(x, y, z):
    ox, oy, oz = DOOR3_ORIGIN
    return (ox + x, oy + y, oz + z)


def door3_commands():
    ox, oy, oz = DOOR3_ORIGIN
    out = [
        f"fill {ox - 2} {oy + 3} {oz - 1} {ox + 11} {oy + 12} {oz + 14} minecraft:air",   # x - 2: the sign
        in_dimension(HIDDEN_OVERWORLD, f"fill {ox - 1} {oy} {oz - 1} {ox + 11} {oy + 12} {oz + 14} minecraft:air"),
    ]
    placements, _ = read_layout(DOOR3_LAYOUT)
    for layer, a, b, block in placements:
        command = (f"fill {coords(door3_pos(*a))} {coords(door3_pos(*b))} {block}" if a != b
                   else f"setblock {coords(door3_pos(*a))} {block}")
        out.append(in_dimension(HIDDEN_OVERWORLD, command) if layer == "hidden" else command)
    out.append(sign((ox - 2, -60, -2), ["12. 3x3 door", "Walk into the", "tunnel and flip", "the lever"]))
    return out


def door3_state():
    """'closed', 'open' or a picture (D = door block) of the face (z = 6) then the pockets layer (z = 5)."""
    _, info = read_layout(DOOR3_LAYOUT)
    cells = [(x, y, z) for z in (6, 5) for y in range(7, 1, -1) for x in range(3, 8)]
    replies = rcon.send_commands([f"phasecraft get {coords(door3_pos(*c))}" for c in cells])
    at = {c for c, r in zip(cells, replies) if info["door"] in r.split("visible: ", 1)[1].split(" |")[0]}
    if at == {(x, y, 6) for x in (4, 5, 6) for y in (3, 4, 5)}:
        return "closed"
    if at == {(3, y, 5) for y in (3, 4, 5)} | {(7, y, 5) for y in (3, 4, 5)} | {(5, 2, 5), (5, 6, 5), (5, 7, 5)}:
        return "open"
    return "".join("D" if c in at else "." for c in cells)


def door3_lever(on):
    _, info = read_layout(DOOR3_LAYOUT)
    rcon.send_commands([f"setblock {coords(door3_pos(*info['lever']))} "
                        f"minecraft:lever[face=floor,facing=north,powered={str(on).lower()}]"])


# Station 15: a flying machine with a hidden engine carrying visible cargo (as FlyingMachineGameTests).
# The engine is vanilla's 6-block two-way "engine A" (Minecraft Wiki), built in the hidden layer,
# flying east; a phase piston stuck to it pushes the visible gold block ahead each cycle. The top
# brick of the post is a secret switch: a hidden lever on a phase block, right above the engine's
# first observer, which starts it. Hidden obsidian stops it at the end of the run.
FLY = (130, -58, 0)   # the engine's north-west slime block
FLY_CARGO = (131, -58, -1)
FLY_SWITCH = (130, -56, 0)


def flying_commands():
    x, y, z = FLY
    hidden = lambda pos, block: in_dimension(HIDDEN_OVERWORLD, f"setblock {coords(pos)} {block}")
    out = [
        "fill 126 -60 -4 144 -53 4 minecraft:air",
        in_dimension(HIDDEN_OVERWORLD, "fill 126 -61 -4 144 -53 4 minecraft:air"),
        # The post (visible): its top brick is the switch.
        f"fill {x} -60 {z} {x} {FLY_SWITCH[1] - 1} {z} minecraft:oak_fence",
        f"setblock {coords(FLY_SWITCH)} minecraft:chiseled_stone_bricks",
        f"setblock {coords(FLY_CARGO)} minecraft:gold_block",
        sign((128, Y, -2), ["15. Flying gold", "Click the top", "of the post: a", "hidden engine"]),
        # The switch (hidden): a lever on a phase block, above the first observer.
        # Placed before the engine: a change in front of an observer starts it.
        hidden((x, FLY_SWITCH[1], z - 1), "phasecraft:phase_block"),
        hidden(FLY_SWITCH, "minecraft:lever[face=wall,facing=south,powered=false]"),
        # The engine (hidden), flying east. Its observers come from flying_observer_commands().
        hidden((x, y, z), "minecraft:slime_block"),
        hidden((x + 1, y, z), "minecraft:sticky_piston[facing=west]"),
        hidden((x, y, z + 1), "minecraft:sticky_piston[facing=east]"),
        hidden((x + 1, y, z + 1), "minecraft:slime_block"),
        hidden((x, y, z - 1), "phasecraft:phase_piston[facing=east]"),
        # The end of the run (hidden).
        in_dimension(HIDDEN_OVERWORLD, f"fill {x + 9} {y} {z - 1} {x + 9} {y + 1} {z + 2} minecraft:obsidian"),
    ]
    return out


# Station 16: the secret room behind a bookshelf wall, built from its layout file (shared with
# SecretRoomGameTests). One bookshelf on each side of the wall hides a phased button; a hidden copper
# bulb remembers the door's state. Here the room also gets walls, a roof and a lantern.
ROOM_LAYOUT = pathlib.Path(__file__).resolve().parent.parent / "src/gametest/resources/contraptions/secret_room.txt"
ROOM_ORIGIN = (146, -61, -12)   # layout (0, 0, 0); the layout's floor (y = 0) replaces the grass


def room_pos(x, y, z):
    ox, oy, oz = ROOM_ORIGIN
    return (ox + x, oy + y, oz + z)


def room_commands():
    a, b = room_pos(-2, 1, -2), room_pos(10, 5, 10)
    out = [
        f"fill {coords(a)} {coords(b)} minecraft:air",
        in_dimension(HIDDEN_OVERWORLD, f"fill {coords(room_pos(-2, 0, -2))} {coords(b)} minecraft:air"),
    ]
    placements, _ = read_layout(ROOM_LAYOUT)
    for layer, a, b, block in placements:
        command = (f"fill {coords(room_pos(*a))} {coords(room_pos(*b))} {block}" if a != b
                   else f"setblock {coords(room_pos(*a))} {block}")
        out.append(in_dimension(HIDDEN_OVERWORLD, command) if layer == "hidden" else command)
    # The room behind the wall (layout z 0..2): walls, a roof over room and wall, a lantern, a chest.
    out += [
        f"fill {coords(room_pos(-1, 0, -1))} {coords(room_pos(9, 0, 2))} minecraft:spruce_planks",
        f"fill {coords(room_pos(-1, 1, -1))} {coords(room_pos(9, 3, -1))} minecraft:bookshelf",
        f"fill {coords(room_pos(-1, 1, 0))} {coords(room_pos(-1, 3, 5))} minecraft:bookshelf",
        f"fill {coords(room_pos(9, 1, 0))} {coords(room_pos(9, 3, 5))} minecraft:bookshelf",
        f"fill {coords(room_pos(-1, 4, -1))} {coords(room_pos(9, 4, 5))} minecraft:spruce_planks",
        f"setblock {coords(room_pos(1, 1, 0))} minecraft:chest[facing=south]",
        f"setblock {coords(room_pos(6, 1, 0))} minecraft:lantern",
        f"setblock {coords(room_pos(2, 1, 0))} minecraft:lectern[facing=south]",
    ]
    out.append(sign(room_pos(1, 1, 8), ["16. Secret room", "One of these", "bookshelves is", "a switch"]))
    return out


def room_state():
    """'closed', 'open' or a picture (D = bookshelf) of the doorway and pocket cells."""
    cells = [(4, y, z) for y in (1, 2) for z in (5, 4)] + [(5, y, 4) for y in (1, 2)]
    replies = rcon.send_commands([f"phasecraft get {coords(room_pos(*c))}" for c in cells])
    at = {c for c, r in zip(cells, replies) if "minecraft:bookshelf" in r.split("visible: ", 1)[1].split(" |")[0]}
    if at == {(4, 1, 5), (4, 2, 5)}:
        return "closed"
    if at == {(5, 1, 4), (5, 2, 4)}:
        return "open"
    return "".join("D" if c in at else "." for c in cells)


def room_press(click):
    """Presses and releases the phased button behind a secret bookshelf. RCON cannot click, so this sets
    the button; a real press also updates what is next to the block the button hangs on, which /setblock
    does not, so the wire on the phase block is placed again to pick up the change."""
    (x, y, z), face = click
    pos = coords(room_pos(x, y, z))
    wire = coords(room_pos(8, 3, 4))
    for powered in ("true", "false"):
        rcon.send_commands([in_dimension(HIDDEN_OVERWORLD, command) for command in (
            f"setblock {pos} minecraft:stone_button[face=wall,facing={face},powered={powered}]",
            f"setblock {wire} minecraft:air",
            f"setblock {wire} minecraft:redstone_wire[north=side,south=side,east=side,west=side]")])
        time.sleep(1)


# Station 17: the trapdoor floor, built from its layout file (shared with TrapdoorFloorGameTests).
# A raised corridor: step on the fifth tile and the next two drop away into a pit. A tunnel leads
# out of the pit, and steps lead up to the corridor.
TRAP_LAYOUT = pathlib.Path(__file__).resolve().parent.parent / "src/gametest/resources/contraptions/trapdoor_floor.txt"
TRAP_ORIGIN = (158, -64, -4)   # layout (0, 0, 0); the world's bottom, so the corridor floor (y = 5) is at -59


def trap_pos(x, y, z):
    ox, oy, oz = TRAP_ORIGIN
    return (ox + x, oy + y, oz + z)


def trap_commands():
    out = [
        f"fill {coords(trap_pos(-1, 4, -4))} {coords(trap_pos(11, 9, 13))} minecraft:air",
        in_dimension(HIDDEN_OVERWORLD, f"fill {coords(trap_pos(-1, 0, -4))} {coords(trap_pos(11, 9, 13))} minecraft:air"),
        f"fill {coords(trap_pos(-1, 0, -1))} {coords(trap_pos(9, 3, 12))} minecraft:stone",
    ]
    placements, _ = read_layout(TRAP_LAYOUT)
    for layer, a, b, block in placements:
        command = (f"fill {coords(trap_pos(*a))} {coords(trap_pos(*b))} {block}" if a != b
                   else f"setblock {coords(trap_pos(*a))} {block}")
        out.append(in_dimension(HIDDEN_OVERWORLD, command) if layer == "hidden" else command)
    out += [
        # Steps up to the corridor (north end).
        f"setblock {coords(trap_pos(4, 4, -2))} minecraft:stone_brick_stairs[facing=south]",
        f"setblock {coords(trap_pos(4, 5, -1))} minecraft:stone_brick_stairs[facing=south]",
        # The way out of the pit: a tunnel south under the corridor, and steps up to the grass.
        f"fill {coords(trap_pos(4, 2, 7))} {coords(trap_pos(4, 3, 11))} minecraft:air",
        f"setblock {coords(trap_pos(4, 2, 10))} minecraft:stone_brick_stairs[facing=south]",
        f"setblock {coords(trap_pos(4, 3, 11))} minecraft:stone_brick_stairs[facing=south]",
        f"setblock {coords(trap_pos(4, 4, 7))} minecraft:lantern[hanging=true]",
    ]
    out.append(sign(trap_pos(2, 4, -3), ["17. Trapdoor", "Walk down the", "corridor. Mind", "your step!"]))
    return out


def trap_state():
    """'closed', 'open' or a picture (T = tile) of the trap tiles, the cells under them and the pockets."""
    _, info = read_layout(TRAP_LAYOUT)
    cells = [(x, y, z) for z in (5, 6) for y in (5, 4) for x in (4, 5)]
    replies = rcon.send_commands([f"phasecraft get {coords(trap_pos(*c))}" for c in cells])
    at = {c for c, r in zip(cells, replies) if info["door"] in r.split("visible: ", 1)[1].split(" |")[0]}
    if at == {(4, 5, 5), (4, 5, 6)}:
        return "closed"
    if at == {(5, 4, 5), (5, 4, 6)}:
        return "open"
    return "".join("T" if c in at else "." for c in cells)


def trap_walker(present):
    """A pig with no AI standing on the trigger tile (RCON cannot walk), or none."""
    _, info = read_layout(TRAP_LAYOUT)
    x, y, z = trap_pos(*info["step"])
    commands = ["kill @e[type=minecraft:pig,tag=phasecraft_trap]"]
    if present:
        commands.append(f"summon minecraft:pig {x + 0.5} {y} {z + 0.5} {{NoAI:1b,Tags:[\"phasecraft_trap\"]}}")
    rcon.send_commands(commands)


# Station 18: the phase shifter. A stone-brick wall; the brick right of the
# middle hides a phased lever (on a phase block behind it). The lever powers
# four hidden phase shifters at the same spots as four wall bricks, which take
# those bricks out of the visible world: a doorway appears; click again and the
# bricks come back. Hidden wire runs over the top row and behind the wall.
SHIFT_X = 171                     # the doorway: x 171..172, y Y..Y+1, z 0
SHIFT_LEVER = (173, Y + 1, 0)     # the secret brick


def shifter_commands():
    h = lambda pos, block: in_dimension(HIDDEN_OVERWORLD, f"setblock {coords(pos)} {block}")
    x0 = SHIFT_X
    lx, ly, lz = SHIFT_LEVER
    wire = "minecraft:redstone_wire"
    out = [
        "fill 168 -60 -3 176 -55 3 minecraft:air",
        in_dimension(HIDDEN_OVERWORLD, "fill 168 -61 -3 176 -55 3 minecraft:air"),
        f"fill 169 {Y} 0 174 {Y + 2} 0 minecraft:stone_bricks",
        sign((168, Y, -2), ["18. Shifter", "Click the brick", "right of the", "middle"]),
        # The secret lever on a phase block behind the wall.
        h((lx, ly, lz + 1), "phasecraft:phase_block"),
        h(SHIFT_LEVER, "minecraft:lever[face=wall,facing=north,powered=false]"),
        # The shifters, at the doorway's four bricks.
    ] + [h((x, y, 0), "phasecraft:phase_shifter") for x in (x0, x0 + 1) for y in (Y, Y + 1)] + [
        # Top row: wire over the shifters, from the wire on the phase block.
        h((lx, ly + 1, lz + 1), wire),
        h((x0 + 1, ly, lz + 1), "minecraft:stone"),
        h((x0 + 1, ly + 1, lz + 1), wire),
        h((x0 + 1, ly + 1, 0), wire),
        h((x0, ly + 1, 0), wire),
        # Bottom row: wire under the phase block, round behind, into repeaters facing the shifters.
        h((lx, Y - 1, lz + 1), "minecraft:stone"),
        h((lx, Y, lz + 1), wire),
    ] + [h((x, Y - 1, 2), "minecraft:stone") for x in (x0, x0 + 1, lx)] + [
        h((x, Y, 2), wire) for x in (x0, x0 + 1, lx)] + [
        h((x, Y - 1, 1), "minecraft:stone") for x in (x0, x0 + 1)] + [
        h((x, Y, 1), "minecraft:repeater[facing=south,delay=1]") for x in (x0, x0 + 1)]
    return out


def shifter_state():
    """'closed' (all four doorway bricks there), 'open' (none) or a picture."""
    cells = [(x, y, 0) for y in (Y + 1, Y) for x in (SHIFT_X, SHIFT_X + 1)]
    replies = rcon.send_commands([f"phasecraft get {coords(c)}" for c in cells])
    there = ["minecraft:stone_bricks" in r.split("visible: ", 1)[1].split(" |")[0] for r in replies]
    return "closed" if all(there) else "open" if not any(there) else "".join("B" if t else "." for t in there)


def shifter_lever(on):
    """Flips the hidden lever (RCON cannot click), then places the wire next to its phase block again:
    /setblock does not update what is next to the block a lever hangs on, as a real flip does."""
    lx, ly, lz = SHIFT_LEVER
    commands = [f"setblock {coords(SHIFT_LEVER)} minecraft:lever[face=wall,facing=north,powered={str(on).lower()}]"]
    for pos in ((lx, ly + 1, lz + 1), (lx, Y, lz + 1)):
        commands += [f"setblock {coords(pos)} minecraft:air", f"setblock {coords(pos)} minecraft:redstone_wire"]
    rcon.send_commands([in_dimension(HIDDEN_OVERWORLD, c) for c in commands])


def lamp_lit(pos):
    """Whether the visible redstone lamp at pos is lit."""
    reply = rcon.send_commands([f"phasecraft get {coords(pos)}"])[0]
    return "lit=true" in reply.split("visible: ", 1)[1].split(" |")[0]


def watchers_check():
    """Station 19, driven over RCON: (name, ok, steps) for the chest, the doorway and the mark."""
    results = []
    cx, cy, cz = WATCH_CHEST
    steps = [lamp_lit(WATCH_CHEST_LAMP)]
    rcon.send_commands([f'data merge block {cx} {cy} {cz} {{Items:[{{Slot:0b,id:"minecraft:cobblestone",count:64}}]}}'])
    time.sleep(1); steps.append(lamp_lit(WATCH_CHEST_LAMP))
    rcon.send_commands([f"data merge block {cx} {cy} {cz} {{Items:[]}}"])
    time.sleep(1); steps.append(lamp_lit(WATCH_CHEST_LAMP))
    results.append(("chest lamp (empty, filled, emptied)", steps == [False, True, False], steps))
    sx, sy, sz = WATCH_STRING
    # Items trip tripwire too (vanilla): clear any left in the doorway, and send the
    # pig out of the world below rather than killing it there, where it would drop some.
    rcon.send_commands([f"kill @e[type=minecraft:item,x={sx + 0.5},y={sy},z={sz + 0.5},distance=..3]"])
    time.sleep(1)
    steps = [lamp_lit(WATCH_DOOR_LAMP)]
    rcon.send_commands([f"summon minecraft:pig {sx + 0.5} {sy} {sz + 0.5} {{NoAI:1b,Tags:[\"phasecraft_watch\"]}}"])
    time.sleep(1); steps.append(lamp_lit(WATCH_DOOR_LAMP))
    rcon.send_commands([f"tp @e[type=minecraft:pig,tag=phasecraft_watch] {sx + 0.5} -300 {sz + 0.5}"])
    time.sleep(2); steps.append(lamp_lit(WATCH_DOOR_LAMP))
    results.append(("doorway lamp (before, pig in the door, pig gone)", steps == [False, True, False], steps))
    first = lamp_lit(WATCH_SPOT_LAMP)
    rcon.send_commands([f"setblock {coords(WATCH_SPOT)} minecraft:oak_planks"])
    time.sleep(1); placed = lamp_lit(WATCH_SPOT_LAMP)
    rcon.send_commands([f"setblock {coords(WATCH_SPOT)} minecraft:air"])
    time.sleep(1); broken = lamp_lit(WATCH_SPOT_LAMP)
    steps = [first, placed, broken]
    results.append(("mark lamp (before, block placed, block broken)", placed != first and broken == first, steps))
    return results


def flying_observer_commands():
    """The engine's two observers, sent a second after flying_commands(): placed in the same
    batch, the engine starts on its own. They are placed "strict" because a plain setblock
    updates the new block from its neighbours, which fires an observer."""
    x, y, z = FLY
    return [in_dimension(HIDDEN_OVERWORLD, f"setblock {coords(pos)} minecraft:observer[facing=up] strict")
            for pos in ((x, y + 1, z), (x + 1, y + 1, z + 1))]


def flying_cargo_x():
    """Where the visible gold block is on the cargo's row, or None."""
    replies = rcon.send_commands([f"phasecraft get {xx} {FLY_CARGO[1]} {FLY_CARGO[2]}" for xx in range(FLY_CARGO[0], FLY[0] + 12)])
    for xx, reply in zip(range(FLY_CARGO[0], FLY[0] + 12), replies):
        if "minecraft:gold_block" in reply.split("visible: ", 1)[1].split(" |")[0]:
            return xx
    return None


def build_commands():
    commands = [
        "setworldspawn 0 -60 -6",
        "gamerule advance_time false",
        "time set noon",
        "weather clear 1000000",
        "forceload add -8 -16 200 8",
        in_dimension(HIDDEN_OVERWORLD, "forceload add -8 -16 200 8"),
        # Clear the area (visible) and lay a path of andesite under the stations.
        "fill -4 -60 -8 54 -55 4 minecraft:air",
        "fill -4 -61 -8 54 -61 4 minecraft:grass_block",
        "fill -2 -61 -1 50 -61 1 minecraft:polished_andesite",
        # The hidden layer: clear it (older builds laid hidden ground everywhere),
        # then hidden ground only under the hidden blocks that need it.
        in_dimension(HIDDEN_OVERWORLD, "fill -4 -61 -8 54 -40 4 minecraft:air"),
        "fill 104 -60 -4 112 -55 3 minecraft:air",
        in_dimension(HIDDEN_OVERWORLD, "fill 104 -61 -4 112 -55 3 minecraft:air"),
        # Station 14's hut: solid, hollowed out, a doorway in front; dark inside.
        "fill 114 -60 -4 124 -54 7 minecraft:air",
        in_dimension(HIDDEN_OVERWORLD, "fill 114 -61 -4 124 -54 7 minecraft:air"),
        # Station 19's area.
        "fill 177 -60 -3 196 -55 5 minecraft:air",
        "fill 177 -61 -3 196 -61 5 minecraft:grass_block",   # ground (the mark sits in it)
        in_dimension(HIDDEN_OVERWORLD, "fill 177 -61 -3 196 -55 5 minecraft:air"),
        "fill 116 -60 -1 122 -56 5 minecraft:stone_bricks",
        "fill 117 -60 0 121 -57 3 minecraft:air",
        "fill 119 -60 -1 119 -59 -1 minecraft:air",
        sign((0, Y, -6), WELCOME),
    ]
    commands += [in_dimension(HIDDEN_OVERWORLD, f"setblock {coords(pos)} minecraft:smooth_stone") for pos in HIDDEN_SUPPORTS]
    commands += door_commands()
    commands += staircase_commands()
    commands += door3_commands()
    commands += flying_commands()
    commands += room_commands()
    commands += trap_commands()
    commands += shifter_commands()
    for station in STATIONS:
        for dimension, pos, *_ in station["hidden"]:
            commands.append(in_dimension(dimension, f"phasecraft clear {coords(pos)}"))
        for pos, block in station["visible"]:
            commands.append(f"setblock {coords(pos)} {block} replace")
        for dimension, pos, block, *_ in station["hidden"]:
            commands.append(in_dimension(dimension, f"phasecraft set {coords(pos)} {block}"))
        commands.append(sign((station["x"], Y, SIGN_Z), station["sign"]))
    return commands


def expected_hidden(block):
    """The block name and the properties given, as they appear in /phasecraft get output."""
    name, _, props = block.partition("[")
    return name, [p for p in props.rstrip("]").split(",") if p]


def main():
    if "--signs-only" in sys.argv[1:]:
        # Rewrite just the signs on the running server, leaving everything else as it is.
        replies = rcon.send_commands([c for c in build_commands() if "_sign[" in c])
        bad = [r for r in replies if r and "Changed the block" not in r]
        print(f"{len(replies)} signs written" + (f"; problems: {bad}" if bad else ""))
        return 1 if bad else 0
    if "--print-commands" in sys.argv[1:]:
        # For the screenshot tour (ExamplesTourClientGameTest): the build, without RCON.
        print("\n".join(build_commands() + flying_observer_commands()))
        return 0
    props = rcon.read_properties(rcon.PROPERTIES)
    if props.get("pause-when-empty-seconds", "60") not in ("0", ""):
        print("Warning: pause-when-empty-seconds is not 0 in run/server.properties. With nobody online the server"
              " stops ticking, cross-layer updates (one tick) never arrive, and the door check will fail.", file=sys.stderr)
    try:
        replies = rcon.send_commands(build_commands())
        failures = [(c, r) for c, r in zip(build_commands(), replies)
                    if any(word in r for word in ("Unknown", "Incorrect", "Expected", "<--[HERE]", "Could not"))]
        for command, reply in failures:
            print(f"FAILED: {command}\n  {reply}")

        time.sleep(1)  # let cross-layer updates (one tick) settle before checking
        rcon.send_commands(flying_observer_commands())
        checks = [(cell[0], cell[1], cell[-1]) for s in STATIONS for cell in s["hidden"]]
        answers = rcon.send_commands([in_dimension(d, f"phasecraft get {coords(pos)}") for d, pos, _ in checks])
    except rcon.RconError as error:
        print(error, file=sys.stderr)
        return 1

    ok = not failures
    for (dimension, pos, block), answer in zip(checks, answers):
        hidden = answer.split("hidden: ", 1)[-1]
        name, props = expected_hidden(block)
        hidden_name = hidden.split("[", 1)[0]
        good = hidden_name == name and all(p in hidden for p in props)
        ok &= good
        print(f"{'ok ' if good else 'BAD'} {dimension:<20} {coords(pos):<12} {hidden}")
    try:
        steps = [("before", door_state())]
        lever(True); time.sleep(3); steps.append(("lever on", door_state()))
        lever(False); time.sleep(3); steps.append(("lever off", door_state()))
    except rcon.RconError as error:
        print(error, file=sys.stderr)
        return 1
    door_ok = [s for _, s in steps] == ["closed", "open", "closed"]
    ok &= door_ok
    print(f"{'ok ' if door_ok else 'BAD'} hidden door: " + ", ".join(f"{name}: {state}" for name, state in steps))
    try:
        stair_steps = [("before", staircase_state())]
        stair_lever(True); time.sleep(3); stair_steps.append(("lever on", staircase_state()))
        stair_lever(False); time.sleep(3); stair_steps.append(("lever off", staircase_state()))
    except rcon.RconError as error:
        print(error, file=sys.stderr)
        return 1
    stairs_ok = [s for _, s in stair_steps] == ["closed", "open", "closed"]
    ok &= stairs_ok
    print(f"{'ok ' if stairs_ok else 'BAD'} hidden staircase: " + ", ".join(f"{name}: {state}" for name, state in stair_steps))
    try:
        door3_steps = [("before", door3_state())]
        door3_lever(True); time.sleep(4); door3_steps.append(("lever on", door3_state()))
        door3_lever(False); time.sleep(4); door3_steps.append(("lever off", door3_state()))
    except rcon.RconError as error:
        print(error, file=sys.stderr)
        return 1
    door3_ok = [s for _, s in door3_steps] == ["closed", "open", "closed"]
    ok &= door3_ok
    print(f"{'ok ' if door3_ok else 'BAD'} 3x3 door: " + ", ".join(f"{name}: {state}" for name, state in door3_steps))
    try:
        before = flying_cargo_x()
        rcon.send_commands([in_dimension(HIDDEN_OVERWORLD,
                                         f"setblock {coords(FLY_SWITCH)} minecraft:lever[face=wall,facing=south,powered=true]")])
        time.sleep(6)
        after = flying_cargo_x()
        rcon.send_commands(flying_commands())  # ready for another flight
        time.sleep(1)
        rcon.send_commands(flying_observer_commands())
        time.sleep(1)
        reset = flying_cargo_x()
    except rcon.RconError as error:
        print(error, file=sys.stderr)
        return 1
    fly_ok = before == FLY_CARGO[0] and after is not None and after >= FLY_CARGO[0] + 5 and reset == FLY_CARGO[0]
    ok &= fly_ok
    print(f"{'ok ' if fly_ok else 'BAD'} flying cargo: at x={before}, after the switch x={after}, reset to x={reset}")
    try:
        _, room_info = read_layout(ROOM_LAYOUT)
        outside, inside = room_info["clicks"]
        room_steps = [("before", room_state())]
        room_press(outside); time.sleep(3); room_steps.append(("outside shelf", room_state()))
        room_press(inside); time.sleep(3); room_steps.append(("inside shelf", room_state()))
    except rcon.RconError as error:
        print(error, file=sys.stderr)
        return 1
    room_ok = [s for _, s in room_steps] == ["closed", "open", "closed"]
    ok &= room_ok
    print(f"{'ok ' if room_ok else 'BAD'} secret room: " + ", ".join(f"{name}: {state}" for name, state in room_steps))
    try:
        trap_steps = [("before", trap_state())]
        trap_walker(True); time.sleep(3); trap_steps.append(("pig on the trigger", trap_state()))
        trap_walker(False); time.sleep(4); trap_steps.append(("pig gone", trap_state()))
    except rcon.RconError as error:
        print(error, file=sys.stderr)
        return 1
    trap_ok = [s for _, s in trap_steps] == ["closed", "open", "closed"]
    ok &= trap_ok
    print(f"{'ok ' if trap_ok else 'BAD'} trapdoor floor: " + ", ".join(f"{name}: {state}" for name, state in trap_steps))
    try:
        shift_steps = [("before", shifter_state())]
        shifter_lever(True); time.sleep(2); shift_steps.append(("lever on", shifter_state()))
        shifter_lever(False); time.sleep(2); shift_steps.append(("lever off", shifter_state()))
    except rcon.RconError as error:
        print(error, file=sys.stderr)
        return 1
    shift_ok = [s for _, s in shift_steps] == ["closed", "open", "closed"]
    ok &= shift_ok
    print(f"{'ok ' if shift_ok else 'BAD'} phase shifter: " + ", ".join(f"{name}: {state}" for name, state in shift_steps))
    try:
        watch_results = watchers_check()
    except rcon.RconError as error:
        print(error, file=sys.stderr)
        return 1
    for name, good, steps in watch_results:
        ok &= good
        print(f"{'ok ' if good else 'BAD'} watchers, {name}: {steps}")
    print("Examples built and verified." if ok else "Some examples did not build as expected.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
