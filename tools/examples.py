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
    """(placements, info): placements are (layer, from, to, block) in file order; info has lever, door."""
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
        elif words[0] == "door":
            info["door"] = words[1]
    return placements, info


def door3_pos(x, y, z):
    ox, oy, oz = DOOR3_ORIGIN
    return (ox + x, oy + y, oz + z)


def door3_commands():
    ox, oy, oz = DOOR3_ORIGIN
    out = [
        f"fill {ox - 1} {oy + 3} {oz - 1} {ox + 11} {oy + 12} {oz + 14} minecraft:air",
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


def build_commands():
    commands = [
        "setworldspawn 0 -60 -6",
        "gamerule advance_time false",
        "time set noon",
        "weather clear 1000000",
        "forceload add -8 -16 104 8",
        in_dimension(HIDDEN_OVERWORLD, "forceload add -8 -16 104 8"),
        # Clear the area (visible) and lay a path of andesite under the stations.
        "fill -4 -60 -8 54 -55 4 minecraft:air",
        "fill -4 -61 -8 54 -61 4 minecraft:grass_block",
        "fill -2 -61 -1 50 -61 1 minecraft:polished_andesite",
        # The hidden layer: clear it (older builds laid hidden ground everywhere),
        # then hidden ground only under the hidden blocks that need it.
        in_dimension(HIDDEN_OVERWORLD, "fill -4 -61 -8 54 -40 4 minecraft:air"),
        sign((0, Y, -6), WELCOME),
    ]
    commands += [in_dimension(HIDDEN_OVERWORLD, f"setblock {coords(pos)} minecraft:smooth_stone") for pos in HIDDEN_SUPPORTS]
    commands += door_commands()
    commands += staircase_commands()
    commands += door3_commands()
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
        print("\n".join(build_commands()))
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
    print("Examples built and verified." if ok else "Some examples did not build as expected.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
