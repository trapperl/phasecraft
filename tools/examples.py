#!/usr/bin/env python3
"""Build (or rebuild) the PhaseCraft examples area on the local dev server.

Usage:
    ./gradlew runServer            # in another terminal, RCON enabled
    python3 tools/examples.py

Builds a row of example stations near spawn on a flat world (ground at
y=-61, stations at y=-60), each with a sign explaining it. The hidden layer is
a real dimension at the same coordinates (phasecraft:hidden_overworld); the
script lays hidden ground under the stations, since hidden redstone follows
vanilla rules. Inspect hidden blocks with /phasecraft get, or go and look with
/phasecraft layer hidden (Creative). After building, every hidden cell is read
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
        "sign": ["1. Overlap", "Stone you see;", "redstone hidden", "at the same XYZ"],
        "visible": [((0, Y, 0), "minecraft:stone")],
        "hidden": [("minecraft:overworld", (0, Y, 0), "minecraft:redstone_wire")],
    },
    {
        "x": 6,
        "sign": ["2. Full state", "Hidden repeater", "east, delay 3;", "visible twin S"],
        "visible": [((6, Y, 1), "minecraft:repeater[facing=east,delay=3]")],
        "hidden": [("minecraft:overworld", (6, Y, 0), "minecraft:repeater[facing=east,delay=3]")],
    },
    {
        "x": 12,
        "sign": ["3. Hidden piston", "facing up, under", "the sand. Later:", "it will push it"],
        "visible": [((12, Y, 0), "minecraft:glass"), ((12, Y + 1, 0), "minecraft:sand")],
        "hidden": [("minecraft:overworld", (12, Y, 0), "minecraft:piston[facing=up]")],
    },
    {
        "x": 18,
        "sign": ["4. Break me", "Break the planks", "then /phasecraft", "get: still there"],
        "visible": [((18, Y, 0), "minecraft:oak_planks")],
        "hidden": [("minecraft:overworld", (18, Y, 0), "minecraft:redstone_block")],
    },
    {
        "x": 24,
        "sign": ["5. Dimensions", "Here: a lever.", "The Nether has", "its own: 24 64 0"],
        "visible": [((24, Y, 0), "minecraft:polished_andesite")],
        "hidden": [
            ("minecraft:overworld", (24, Y, 0), "minecraft:lever[face=floor,facing=north]"),
            ("minecraft:the_nether", (24, 64, 0), "minecraft:glowstone"),
        ],
    },
    {
        "x": 30,
        "sign": ["6. Hidden lamp", "lit by hidden", "power. See it:", "/phasecraft layer"],
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
        "sign": ["7. Phase port", "Lever (on) here,", "hidden port at", "its spot: lamp on"],
        "visible": [((36, Y, 0), "minecraft:lever[face=floor,facing=north,powered=true]")],
        "hidden": [
            ("minecraft:overworld", (36, Y, 1), "minecraft:redstone_lamp[lit=false]", "minecraft:redstone_lamp[lit=true]"),
            ("minecraft:overworld", (36, Y, 0), "phasecraft:phase_port", "phasecraft:phase_port[power=15]"),
        ],
    },
    {
        "x": 42,
        "sign": ["8. Hidden piston", "Flip the lever,", "then look with", "/phasecraft layer"],
        "visible": [((42, Y, 0), "minecraft:lever[face=floor,facing=north,powered=false]")],
        "hidden": [
            ("minecraft:overworld", (42, Y, 2), "minecraft:oak_planks"),
            ("minecraft:overworld", (42, Y, 1), "minecraft:piston[facing=south]", "minecraft:piston[extended=false,facing=south]"),
            ("minecraft:overworld", (42, Y, 0), "phasecraft:phase_port", "phasecraft:phase_port[power=0]"),
        ],
    },
    {
        "x": 48,
        "sign": ["9. Phase piston", "Flip the lever:", "a hidden piston", "moves the bricks"],
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

WELCOME = ["PhaseCraft examples", "/phasecraft get", "x -60 0, or go:", "/phasecraft layer"]


def coords(pos):
    return f"{pos[0]} {pos[1]} {pos[2]}"


def sign(pos, lines):
    messages = ",".join('"' + line.replace('"', "'") + '"' for line in (lines + ["", "", "", ""])[:4])
    return f"setblock {coords(pos)} minecraft:oak_sign[rotation=8]{{front_text:{{messages:[{messages}]}}}} replace"


def in_dimension(dimension, command):
    return f"execute in {dimension} run {command}"


def build_commands():
    commands = [
        "setworldspawn 0 -60 -6",
        "gamerule advance_time false",
        "time set noon",
        "weather clear 1000000",
        "forceload add -8 -8 57 8",
        in_dimension(HIDDEN_OVERWORLD, "forceload add -8 -8 57 8"),
        # Clear the area (visible) and lay a path of andesite under the stations.
        "fill -4 -60 -8 54 -55 4 minecraft:air",
        "fill -4 -61 -8 54 -61 4 minecraft:grass_block",
        "fill -2 -61 -1 50 -61 1 minecraft:polished_andesite",
        # The hidden layer: clear it, then hidden ground under the stations.
        in_dimension(HIDDEN_OVERWORLD, "fill -4 -60 -8 54 -40 4 minecraft:air"),
        in_dimension(HIDDEN_OVERWORLD, "fill -4 -61 -8 54 -61 4 minecraft:smooth_stone"),
        sign((0, Y, -6), WELCOME),
    ]
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
    print("Examples built and verified." if ok else "Some examples did not build as expected.")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
