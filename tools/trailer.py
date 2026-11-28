#!/usr/bin/env python3
"""The PhaseCraft trailer's pictures: the hero builds of the examples area set down in ordinary land (a default
world from a fixed seed), filmed by a scripted camera and rendered frame by frame by the game itself
(TrailerClientGameTest in the run runClientGameTestTrailer: Iris and the Complementary Reimagined shader pack, on the
virtual display; on a graphics card where there is one under WSL, else in software).

    python3 tools/trailer.py                  # script, render, edit: build/trailer/
    python3 tools/trailer.py --shots iris,tower   # render only these shots again (their frames keep their numbers)
    python3 tools/trailer.py --step 6         # a quick look: every 6th frame only
    python3 tools/trailer.py --no-edit        # render only
    python3 tools/trailer.py --edit           # only edit again, from the frames already rendered
    python3 tools/trailer.py --software       # never the graphics card
    python3 tools/trailer.py --scout X0 Z0 X1 Z1   # the land's heights there, to choose where builds go (heights.txt)

What it leaves in build/trailer/ (the editor's input, tools/trailer_edit.py):
    frames/NNNNN.png          every frame, numbered from 0 across all shots, 1920x1080, 60 a second
    frames_hidden/NNNNN.png   the same frames with the hidden layer's overlay on, for the shots marked "hidden"
    timeline.json             {"fps", "size", "shots": [{"name", "caption", "start", "frames", "action", "hidden"}]}
    contact.png               a frame of every shot, to look the render over

The world runs a game tick every third frame and each frame is drawn at its own place between two ticks, so the
camera, carts and piston arms move 60 times a second; a block a shifter takes is there or gone, as in the game.
The edit (titles, music, cuts) is tools/trailer_edit.py's; without it a plain cut with captions is written.
"""

import importlib.util
import json
import math
import os
import pathlib
import re
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "build/trailer"
FPS = 60
PER_TICK = 3               # frames a game tick
W, H = 1920, 1080
SEED = "phasecraft"
VIEW = 20                  # render distance in chunks
HIDDEN = "phasecraft:hidden_overworld"
FLAT_GROUND = -61          # the flat examples world's grass
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
# The shader pack's settings (Complementary Reimagined's own names): its higher qualities, the rest as it comes.
PACK_OPTIONS = ["shadowDistance=224.0", "SHADOW_QUALITY=3", "CLOUD_QUALITY=3", "LIGHTSHAFT_QUALI_DEFINE=3",
                "DETAIL_QUALITY=3", "BLOCK_REFLECT_QUALITY=3", "ENTITY_SHADOW=1",
                # The builds face north: the sun's path leans that way, so their fronts are lit. Water a little
                # less clear, so falling water shows against rock.
                "SUN_ANGLE=40", "WATER_ALPHA_MULT=300", "WATER_FOG_MULT=200"]

spec = importlib.util.spec_from_file_location("examples", ROOT / "tools/examples.py")
ex = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ex)


# ---------------------------------------------------------------------------------------------------------------
# Where the builds stand. The examples' commands are written for the flat world; each build's commands are moved
# to its place in the land: (x, z) is where the middle of what it shows goes, y the grass it stands on there (found
# with --scout; the seed is fixed, so the land is the same every time).

SITES = {
    # a meadow valley between two snowy peaks
    "mountain": (-590, 94, -255),
    "tower": (-520, 87, -250),
    "falls": (-470, 100, -290),
    "bridge": (-560, 91, -190),
    "iris": (-455, 98, -235),
    "ocean": (-600, 86, -90),
    # a plain by the sea
    "track": (-470, 69, 208),
    "cottage": (-420, 69, 205),
    "pixels": (-370, 70, 215),
}
LATE = {"ocean": lambda: ex.ocean_corner_commands()}      # sent a second after the build (see examples.py)
PAD_MARGIN = 4
PAD_NORTH = {"falls": 14, "cottage": 12}     # more level ground in front of a build, where its camera flies low
PAD_SKIRT = 10

TRIPLE = r"(-?\d+(?:\.\d+)?) (-?\d+(?:\.\d+)?) (-?\d+(?:\.\d+)?)"
PLACE = re.compile(r"\b(setblock|fill|phasecraft set|phasecraft clear|item replace block|if block|summon \S+|tp \S+) "
                   + TRIPLE + r"(?: " + TRIPLE + r")?")
SELECTOR = re.compile(r"\bx=(-?\d+),y=(-?\d+),z=(-?\d+)")


def number(value, by):
    moved = float(value) + by
    return str(int(moved)) if "." not in value else f"{moved:.3f}".rstrip("0").rstrip(".")


def moved(command, offset):
    """A command of the flat examples world, for a build that stands `offset` away from there."""
    dx, dy, dz = offset

    def place(match):
        out = f"{match.group(1)} {number(match.group(2), dx)} {number(match.group(3), dy)} {number(match.group(4), dz)}"
        if match.group(5) is not None:
            out += f" {number(match.group(5), dx)} {number(match.group(6), dy)} {number(match.group(7), dz)}"
        return out
    command = PLACE.sub(place, command)
    return SELECTOR.sub(lambda m: f"x={int(m.group(1)) + dx},y={int(m.group(2)) + dy},z={int(m.group(3)) + dz}", command)


def boxes(commands):
    """(all, shown): the boxes round every position the commands name, and round the visible blocks they place."""
    every, shown = [], []
    for command in commands:
        for match in PLACE.finditer(command):
            points = [tuple(float(v) for v in match.groups()[1:4])]
            if match.group(5) is not None:
                points.append(tuple(float(v) for v in match.groups()[4:7]))
            every += points
            if HIDDEN not in command and not command.rstrip().endswith("minecraft:air"):
                shown += points

    def box(points):
        return tuple(int(min(p[i] for p in points)) for i in range(3)), tuple(int(max(p[i] for p in points)) for i in range(3))
    return box(every), box(shown)


class Site:
    def __init__(self, name):
        self.name = name
        flat = [c for c in getattr(ex, name + "_commands")() if "_sign[" not in c and "#minecraft:signs" not in c]
        (self.lo, self.hi), (shown_lo, shown_hi) = boxes(flat)
        x, y, z = SITES[name]
        self.offset = (x - (shown_lo[0] + shown_hi[0]) // 2, y - FLAT_GROUND, z - (shown_lo[2] + shown_hi[2]) // 2)
        self.flat = flat
        self.pad = (shown_lo[0] - PAD_MARGIN, shown_lo[2] - PAD_MARGIN - PAD_NORTH.get(name, 0),
                    shown_hi[0] + PAD_MARGIN, shown_hi[2] + PAD_MARGIN)

    def at(self, x, y, z):
        """A flat-world position, where this build stands."""
        return (x + self.offset[0], y + self.offset[1], z + self.offset[2])

    def command(self, flat_command):
        return moved(flat_command, self.offset)

    def build(self):
        """(script lines, late script lines) that level the ground and build this."""
        dx, dy, dz = self.offset
        x0, z0, x1, z1 = self.pad
        lines = [f"pad {x0 + dx} {z0 + dz} {x1 + dx} {z1 + dz} {FLAT_GROUND + dy} {PAD_SKIRT}"]
        fx0, fz0, fx1, fz1 = self.lo[0] + dx, self.lo[2] + dz, self.hi[0] + dx, self.hi[2] + dz
        for dimension in ("minecraft:overworld", HIDDEN):
            lines.append(f"run execute in {dimension} run forceload add {fx0 - 8} {fz0 - 8} {fx1 + 8} {fz1 + 8}")
        lines.append(f"load {fx0 - 8} {fz0 - 8} {fx1 + 8} {fz1 + 8}")
        for command in self.flat:
            # Clearing reaches as far as a build's hidden wiring, which is wider than what shows: only the pad
            # is cleared, or the land around would be cut away with it.
            match = re.fullmatch(r"fill (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) minecraft:air", command)
            if match:
                ax, ay, az, bx, by, bz = (int(v) for v in match.groups())
                ax, bx, az, bz = max(min(ax, bx), x0), min(max(ax, bx), x1), max(min(az, bz), z0), min(max(az, bz), z1)
                if ax > bx or az > bz:
                    continue
                command = f"fill {ax} {ay} {az} {bx} {by} {bz} minecraft:air"
            lines.append("run " + self.command(command))
        late = ["run " + self.command(c) for c in LATE.get(self.name, lambda: [])()]
        return lines, late


# ---------------------------------------------------------------------------------------------------------------
# The camera: where it is, what it looks at and how wide it sees, each a smooth curve through a few key points.

def catmull(points, u):
    """A point on the Catmull-Rom curve through `points` (tuples), u from 0 to 1 along it."""
    if len(points) == 1:
        return points[0]
    span = u * (len(points) - 1)
    i = min(int(span), len(points) - 2)
    t = span - i
    p0, p1, p2, p3 = points[max(i - 1, 0)], points[i], points[i + 1], points[min(i + 2, len(points) - 1)]
    return tuple(0.5 * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t * t + (3 * b - a - 3 * c + d) * t ** 3)
                 for a, b, c, d in zip(p0, p1, p2, p3))


def ease(u, amount=1.0):
    """Slow in and out (amount 1), or a steady move (0), or between."""
    return u + (u * u * (3 - 2 * u) - u) * amount


class Path:
    """Eye positions and look-at points as curves through key points, and a field of view from `fov[0]` to
    `fov[-1]`; `easing` 0 keeps a steady speed (for shots cut in the middle of a move)."""

    def __init__(self, eye, look, fov=(60,), easing=0.6):
        self.eye, self.look, self.fov, self.easing = list(eye), list(look), list(fov), easing

    def at(self, u):
        u = ease(u, self.easing)
        return catmull(self.eye, u), catmull(self.look, u), catmull([(f,) for f in self.fov], u)[0]


def orbit(centre, radius, height, from_degrees, to_degrees, look=None, fov=(60,), radius_to=None, height_to=None, easing=0.6):
    """Round `centre` (x, y, z): 0 degrees is north of it (looking south, at a build's front), 90 east of it."""
    steps = 9
    eye = []
    for k in range(steps):
        t = k / (steps - 1)
        a = math.radians(from_degrees + (to_degrees - from_degrees) * t)
        r = radius + ((radius_to if radius_to is not None else radius) - radius) * t
        h = height + ((height_to if height_to is not None else height) - height) * t
        eye.append((centre[0] + r * math.sin(a), centre[1] + h, centre[2] - r * math.cos(a)))
    return Path(eye, [look or centre], fov, easing)


def dolly_zoom(look, start, end, width):
    """Towards (or away from) `look` from `start` to `end`, the field of view changing so that something `width`
    across at `look` keeps its size in the picture while everything behind it swells or shrinks."""
    steps = 9
    eye = [tuple(a + (b - a) * k / (steps - 1) for a, b in zip(start, end)) for k in range(steps)]
    fov = [math.degrees(2 * math.atan(width / 2 / math.dist(e, look))) for e in eye]
    return Path(eye, [look], fov)


class Shot:
    """`events`: {tick: [flat-world commands]}; `each`: (first tick, last tick, flat-world command) run every tick
    between; `before`/`after`: flat-world commands with the ticks to wait after them; `action`: the tick at which
    the thing happens (the editor cuts on it); `hidden`: rendered a second time with the overlay on (after
    `again`, the commands and ticks that put the build back as it was); `follow`: ("follow"|"look", entity type,
    dx, dy, dz) for a camera that goes with a cart."""

    def __init__(self, name, caption, site, seconds, path, events=None, action=0, hidden=False, time=11300, before=(), wait=20,
                 after=(), settle=40, again=(), again_wait=100, each=(), follow=None, weather="clear", opacity=0.75):
        self.name, self.caption, self.site, self.frames, self.path = name, caption, site, round(seconds * FPS), path
        self.events, self.action, self.hidden, self.time = dict(events or {}), action, hidden, time
        self.before, self.wait, self.after, self.settle = list(before), wait, list(after), settle
        self.again, self.again_wait, self.each, self.follow = list(again), again_wait, list(each), follow
        self.weather, self.opacity = weather, opacity


def lever(layout, pos_of, on, facing="north"):
    _, info = ex.read_layout(layout)
    return f"setblock {ex.coords(pos_of(*info['lever']))} minecraft:lever[face=floor,facing={facing},powered={str(on).lower()}]"


def shots():
    iris_on, iris_off = lever(ex.IRIS_LAYOUT, ex.iris_pos, True), lever(ex.IRIS_LAYOUT, ex.iris_pos, False)
    falls_on, falls_off = lever(ex.FALLS_LAYOUT, ex.falls_pos, True), lever(ex.FALLS_LAYOUT, ex.falls_pos, False)
    mountain_on, mountain_off = lever(ex.MOUNTAIN_LAYOUT, ex.mountain_pos, True), lever(ex.MOUNTAIN_LAYOUT, ex.mountain_pos, False)
    tower_on, tower_off = lever(ex.TOWER_LAYOUT, ex.tower_pos, True), lever(ex.TOWER_LAYOUT, ex.tower_pos, False)
    cottage_on, cottage_off = lever(ex.COTTAGE_LAYOUT, ex.cottage_pos, True), lever(ex.COTTAGE_LAYOUT, ex.cottage_pos, False)
    ocean_on, ocean_off = lever(ex.OCEAN_LAYOUT, ex.ocean_pos, True, "south"), lever(ex.OCEAN_LAYOUT, ex.ocean_pos, False, "south")
    pixels_on, pixels_off = lever(ex.PIXELS_LAYOUT, ex.pixels_pos, True), lever(ex.PIXELS_LAYOUT, ex.pixels_pos, False)
    bx, by, bz = ex.bridge_pos(0, 1, 0, gen=True)            # over the bridge's first step
    walker = "@e[type=minecraft:villager,tag=trailer_walker]"
    tx, ty, tz = ex.track_pos(-11, 2, 0, gen=True)           # on the plain rails west of the chasm
    cart = "@e[type=minecraft:minecart,tag=trailer_cart]"
    iris_door = (228.5, -50.5, 0.5)
    return [
        # The hook: a whole house, gone.
        Shot("cottage", "A cottage that vanishes, chest and all", "cottage", 5.0,
             dolly_zoom((572.5, -58.0, 6.0), (566.0, -57.0, -16.0), (569.5, -57.6, -7.0), 15.0),
             {30: [cottage_on], 70: [cottage_off]}, action=30, hidden=True, again_wait=60),
        Shot("iris", "A round door that opens from the middle", "iris", 5.0,
             Path([(221.0, -52.0, -15.0), (225.5, -51.5, -11.5), (228.5, -51.2, -7.5)], [iris_door], (62, 52)),
             before=[iris_on], wait=25, action=20, after=[iris_off], settle=80),
        Shot("falls", "A waterfall that parts", "falls", 5.0,
             orbit((262.5, -55.0, 0.0), 21.0, 4.0, -14, 8, look=(262.5, -54.0, 0.0), fov=(62, 56), radius_to=17.0, height_to=2.0),
             before=[falls_on], wait=35, action=30, after=[falls_off], settle=120, time=9000),
        Shot("mountain", "A door into the mountain", "mountain", 6.0,
             Path([(309.0, -52.0, -30.0), (317.0, -56.0, -12.0), (321.5, -57.5, 2.0), (321.5, -57.5, 7.0)],
                  [(321.5, -52.0, 13.0), (321.5, -56.0, 13.0), (321.5, -56.5, 20.0)], (70, 58)),
             {60: [mountain_on]}, action=80, after=[mountain_off], settle=160),
        Shot("tower", "A castle tower, taken down block by block", "tower", 4.5,
             orbit((545.5, -56.0, 8.5), 14.0, 2.0, -35, 30, look=(545.5, -55.5, 8.5), fov=(64,), height_to=4.0, easing=0.3),
             {10: [tower_on]}, action=14, after=[tower_off], settle=600),
        Shot("ocean", "A dry hole in the sea", "ocean", 6.0,
             Path([(593.5, -45.0, -7.0), (593.5, -42.0, -1.0), (593.5, -40.0, 3.5)], [(593.5, -57.0, 7.5)], (66, 60)),
             before=[ocean_on], wait=30, action=30, after=[ocean_off], settle=200),
        Shot("pixels", "A picture drawn in the wall", "pixels", 4.5,
             Path([(650.5, -51.5, -3.0), (654.0, -51.3, 0.5), (657.0, -51.2, 3.0)], [(657.0, -51.0, 12.0)], (60, 54)),
             before=[pixels_on], wait=22, action=20, after=[pixels_off], settle=80, hidden=True, again=[pixels_off], again_wait=80),
        Shot("track", "A track laid ahead of the cart", "track", 5.5,
             Path([(5.5, 2.6, -6.0)], [(0, 0, 0)], (60,)),
             {0: [f"summon minecraft:minecart {tx + 0.5} {ty} {tz + 0.5} "
                  '{Tags:["trailer_cart"],Motion:[0.4d,0.0d,0.0d],Passengers:[{id:"minecraft:pig",NoAI:1b,Silent:1b,Tags:["trailer_cart"]}]}']},
             action=40, follow=("follow", "minecraft:minecart", 0, 0, 0), after=["kill @e[tag=trailer_cart]"]),
        Shot("bridge", "A bridge that is there only under your feet", "bridge", 6.0,
             Path([(370.0, -51.0, -12.0), (377.0, -52.0, -11.0), (385.0, -52.5, -9.0)],
                  [(375.0, -54.0, 0.5), (382.0, -54.0, 0.5), (388.0, -54.0, 0.5)], (58,), easing=0.2),
             {0: [f"summon minecraft:villager {bx - 6.5} {by} {bz + 0.5} "
                  '{NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,Rotation:[-90f,0f],Tags:["trailer_walker"]}']},
             action=40, each=[(1, 119, f"execute as {walker} at @s run tp @s ~0.2 ~ ~")], after=[f"kill {walker}"], settle=60),
        # The reveal: the same door again from above and behind, and what moves it.
        Shot("reveal", "All of it runs in a hidden layer", "iris", 7.0,
             orbit((228.5, -50.5, 2.0), 17.0, 7.0, -25, 115, look=(228.5, -51.5, 4.0), fov=(62, 68), radius_to=22.0, height_to=11.0),
             before=[iris_on], wait=0, action=50, after=[iris_off], settle=80, hidden=True, again=[iris_off], opacity=0.9),
        # The end card's picture: up and away over the valley.
        Shot("end", "PhaseCraft", "tower", 7.0,
             Path([(545.5, -52.0, -8.0), (545.5, -44.0, -22.0), (545.5, -28.0, -44.0)],
                  [(545.5, -52.0, 8.5), (545.5, -50.0, 12.0), (548.0, -46.0, 30.0)], (58, 70), easing=0.5),
             time=11900),
    ]


# ---------------------------------------------------------------------------------------------------------------
# The script for the game.

def angles(eye, look):
    dx, dy, dz = (b - a for a, b in zip(eye, look))
    return math.degrees(math.atan2(dz, dx)) - 90, -math.degrees(math.atan2(dy, math.hypot(dx, dz)))


def shot_lines(shot, site, start, clock, step):
    """The script lines that render one shot (twice, when it shows the hidden layer too)."""
    def flat(commands):
        return ["run " + site.command(c) for c in commands]

    first_eye, first_look, _ = shot.path.at(0)
    if shot.follow:
        first_eye = site.at(*[float(v) for v in re.search(TRIPLE, shot.events[0][0]).groups()])
        first_look = (first_eye[0], first_eye[1], first_eye[2] + 1)
    else:
        first_eye, first_look = site.at(*first_eye), site.at(*first_look)
    yaw, pitch = angles(first_eye, first_look)
    lines = [f"run time set {shot.time}", f"run weather {shot.weather}",
             "goto " + " ".join(f"{v:.3f}" for v in (*first_eye, yaw, pitch))]
    lines += flat(shot.before) + [f"wait {shot.wait}"]

    def take(folder):
        out = [f"run time set {shot.time}"]
        last_yaw = None
        for i in range(shot.frames):
            tick = i // PER_TICK
            if i % PER_TICK == 0:
                out += flat(shot.events.get(tick, []))
                out += flat(c for a, b, c in shot.each if a <= tick <= b)
                out.append("tick")
            if i % step:
                continue
            eye, look, fov = shot.path.at(i / max(1, shot.frames - 1))
            head = f"frame {folder} {start + i} {(i % PER_TICK + 1) / PER_TICK:.4f} {clock + i / FPS:.4f} "
            if shot.follow:
                mode, kind, dx, dy, dz = shot.follow
                yaw, pitch = angles(eye, look)
                out.append(head + " ".join(f"{v:.4f}" for v in (*eye, yaw, pitch, fov)) + f" {mode} {kind} {dx} {dy} {dz}")
                continue
            eye, look = site.at(*eye), site.at(*look)
            yaw, pitch = angles(eye, look)
            if last_yaw is not None:                   # no jump from 179 to -179
                yaw += 360 * round((last_yaw - yaw) / 360)
            last_yaw = yaw
            out.append(head + " ".join(f"{v:.4f}" for v in (*eye, yaw, pitch, fov)))
        return out

    lines += take("frames")
    if shot.hidden:
        lines += flat(shot.again) + [f"wait {shot.again_wait}"] + flat(shot.before) + [f"wait {shot.wait}"]
        lines += [f"opacity {shot.opacity}", "overlay on"] + take("frames_hidden") + ["overlay off"]
    lines += flat(shot.after) + [f"wait {shot.settle}"]
    return lines


def script(all_shots, only, step):
    """(script lines, timeline, frames to expect {folder: [numbers]})."""
    sites = {name: Site(name) for name in SITES}
    used = sorted({s.site for s in all_shots if not only or s.name in only})
    lines = [f"world normal {SEED}", f"view {VIEW}", f"size {W} {H}",
             "run gamerule phasecraft:area_shifters true", "run gamerule phasecraft:survival_access true",
             "run gamerule advance_time true"]
    late = []
    for name in used:
        built, after = sites[name].build()
        lines += built
        late += after
    lines += ["wait 30"] + late + ["wait 120", "run kill @e[type=minecraft:item]",
                                   f"run execute in {HIDDEN} run kill @e[type=minecraft:item]"]
    timeline = {"fps": FPS, "size": [W, H], "shots": []}
    expect = {"frames": [], "frames_hidden": []}
    start = 0
    for index, shot in enumerate(all_shots):
        timeline["shots"].append({"name": shot.name, "caption": shot.caption, "start": start, "frames": shot.frames,
                                  "action": shot.action * PER_TICK, "hidden": shot.hidden})
        if not only or shot.name in only:
            lines += [f"# {shot.name}"] + shot_lines(shot, sites[shot.site], start, 100.0 * (index + 1), step)
            numbers = [start + i for i in range(0, shot.frames, step)]
            expect["frames"] += numbers
            if shot.hidden:
                expect["frames_hidden"] += numbers
        start += shot.frames
    return lines, timeline, expect


def graphics_card():
    """Environment for Mesa's D3D12 driver on a graphics card under WSL, or None where that is not to be had."""
    if not (os.path.exists("/dev/dxg") and os.path.exists("/usr/lib/wsl/lib/libd3d12.so")
            and any(os.path.exists(f"{d}/d3d12_dri.so") or os.path.exists(f"{d}/libgallium_dri.so") or os.path.exists(f"{d}/dri/d3d12_dri.so")
                    for d in ("/usr/lib/x86_64-linux-gnu/dri", "/usr/lib/x86_64-linux-gnu", "/usr/lib64/dri"))):
        return None
    env = {"GALLIUM_DRIVER": "d3d12", "LD_LIBRARY_PATH": "/usr/lib/wsl/lib:" + os.environ.get("LD_LIBRARY_PATH", "")}
    # With two cards the driver takes the first, often the processor's own: name the one wanted (a part of its name).
    env["MESA_D3D12_DEFAULT_ADAPTER_NAME"] = os.environ.get("PHASECRAFT_TRAILER_GPU", "NVIDIA")
    return env


def run_game(lines, software):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "script.txt").write_text("\n".join(lines) + "\n")
    env = dict(os.environ, PHASECRAFT_TRAILER=str(OUT / "script.txt"), PHASECRAFT_TRAILER_PACK_OPTIONS=";".join(PACK_OPTIONS))
    card = None if software else graphics_card()
    if card:
        env.update(card)
    else:
        env.setdefault("LP_NUM_THREADS", "12")           # software: leave the machine some processors
    print("rendering " + ("on the graphics card (Mesa D3D12)" if card else "in software (Mesa llvmpipe)") + " ...", flush=True)
    log = OUT / "render.log"
    with open(log, "w") as out:
        # Niced: a render is long, and the machine is somebody's.
        result = subprocess.run(["nice", "-n", "15", "./gradlew", "--no-daemon", "runClientGameTestTrailer"], cwd=ROOT, env=env,
                                stdout=out, stderr=subprocess.STDOUT)
    text = log.read_text(errors="replace")
    for line in text.splitlines():
        if "Trailer:" in line and ("frames in" in line or "Using graphics device" in line):
            print(line.split("Trailer:", 1)[1].strip())
    # The D3D12 driver can crash as the game closes, after the last frame: the frames decide.
    return result.returncode, "Trailer: " in text and " frames in " in text


def render(all_shots, only, step, software):
    lines, timeline, expect = script(all_shots, only, step)
    for folder, numbers in expect.items():
        if not only and (OUT / folder).exists():
            shutil.rmtree(OUT / folder)
        for n in numbers:
            (OUT / folder / f"{n:05d}.png").unlink(missing_ok=True)
    code, finished = run_game(lines, software)

    def missing():
        return [f"{folder}/{n:05d}.png" for folder, numbers in expect.items() for n in numbers
                if not (OUT / folder / f"{n:05d}.png").exists()]
    # A game that died on the way (the D3D12 driver sometimes loses the card) left whole shots behind: the shots
    # with frames missing are rendered again, a few times at most.
    for attempt in range(3):
        if not missing():
            break
        numbers = {int(pathlib.Path(m).stem) for m in missing()}
        again = {s["name"] for s in timeline["shots"] if any(s["start"] <= n < s["start"] + s["frames"] for n in numbers)}
        print(f"{len(numbers)} frames missing (gradle exit {code}): rendering {sorted(again)} again", flush=True)
        (OUT / "render.log").replace(OUT / f"render-failed-{attempt}.log")
        code, finished = run_game(script(all_shots, again, step)[0], software)
    if missing():
        sys.exit(f"the render failed (gradle exit {code}; {len(missing())} frames missing, e.g. {missing()[:3]}): see {OUT / 'render.log'}")
    (OUT / "timeline.json").write_text(json.dumps(timeline, indent=1) + "\n")
    contact(timeline)


def contact(timeline):
    """One frame of every shot (a second after its action), side by side, to look the render over."""
    tiles = []
    for shot in timeline["shots"]:
        for folder in ("frames", "frames_hidden") if shot["hidden"] else ("frames",):
            want = shot["start"] + min(shot["frames"] - 1, shot["action"] + FPS)
            have = sorted(int(p.stem) for p in (OUT / folder).glob("*.png") if shot["start"] <= int(p.stem) < shot["start"] + shot["frames"])
            if have:
                n = min(have, key=lambda v: abs(v - want))
                tiles.append((shot["name"] + (" (hidden)" if folder == "frames_hidden" else ""), OUT / folder / f"{n:05d}.png"))
    if not tiles:
        return
    columns, tw, th = 4, 640, 360
    sheet = Image.new("RGB", (columns * tw, -(-len(tiles) // columns) * th), (0, 0, 0))
    draw = ImageDraw.Draw(sheet)
    for k, (label, path) in enumerate(tiles):
        x, y = (k % columns) * tw, (k // columns) * th
        sheet.paste(Image.open(path).convert("RGB").resize((tw, th), Image.LANCZOS), (x, y))
        draw.rectangle((x, y, x + 12 + draw.textlength(label, font=font(20)), y + 30), fill=(0, 0, 0))
        draw.text((x + 6, y + 4), label, font=font(20), fill=(255, 255, 255))
    sheet.save(OUT / "contact.png")
    print(f"wrote {(OUT / 'contact.png').relative_to(ROOT)}")


def scout(box):
    """The land's heights in a box, for choosing sites: build/trailer/heights.txt (see TrailerClientGameTest)."""
    lines = [f"world normal {SEED}", "view 8", "heights " + " ".join(str(int(v)) for v in box) + " heights.txt"]
    run_game(lines, True)
    print(f"wrote {(OUT / 'heights.txt').relative_to(ROOT)}")


# ---------------------------------------------------------------------------------------------------------------
# The plain edit, for when tools/trailer_edit.py is not there: title, captions, fades, no sound.

def font(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT, size)


def card(title, subtitle):
    img = Image.new("RGB", (W, H), (12, 12, 16))
    d = ImageDraw.Draw(img)
    for text, f, y in ((title, font(144, True), H // 2 - 105), (subtitle, font(50), H // 2 + 75)):
        d.text(((W - d.textlength(text, font=f)) / 2, y), text, font=f, fill=(235, 235, 240))
    return img


def caption(img, text, alpha):
    if alpha <= 0:
        return img
    img = img.convert("RGBA")
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    f = font(54)
    w = d.textlength(text, font=f)
    x, y = 90, H - 165
    d.rounded_rectangle((x - 30, y - 21, x + w + 30, y + 75), 15, fill=(0, 0, 0, int(140 * alpha)))
    d.text((x, y), text, font=f, fill=(255, 255, 255, int(255 * alpha)))
    return Image.alpha_composite(img, layer).convert("RGB")


def faded(img, k):
    """Darkened towards black: k = 1 the image, 0 black."""
    return Image.eval(img, lambda v: int(v * k)) if k < 1 else img


def plain_edit(out):
    timeline = json.loads((out / "timeline.json").read_text())
    fps = timeline["fps"]
    encoder = subprocess.Popen(["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
                                "-framerate", str(fps), "-i", "-", "-c:v", "libx264", "-preset", "medium", "-crf", "17",
                                "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out / "phasecraft-trailer.mp4")],
                               stdin=subprocess.PIPE)
    count = 0

    def put(img):
        nonlocal count
        encoder.stdin.write(img.tobytes())
        count += 1
    title = card("PhaseCraft", "A hidden layer of blocks, right under your world")
    for i in range(2 * fps):
        put(faded(title, min(1, i / 20, (2 * fps - 1 - i) / 20)))
    for shot in timeline["shots"]:
        for i in range(shot["frames"]):
            img = Image.open(out / "frames" / f"{shot['start'] + i:05d}.png").convert("RGB")
            img = caption(img, shot["caption"], max(0, min(1, (i - 20) / 20, (shot["frames"] - 25 - i) / 20)))
            put(faded(img, min(1, i / 12, (shot["frames"] - 1 - i) / 12)))
    end = card("PhaseCraft", "Fabric mod for Minecraft Java 26.3 · github.com/trapperl/phasecraft")
    for i in range(3 * fps):
        put(faded(end, min(1, i / 20, (3 * fps - 1 - i) / 20)))
    encoder.stdin.close()
    if encoder.wait():
        sys.exit("ffmpeg failed")
    print(f"wrote {(out / 'phasecraft-trailer.mp4').relative_to(ROOT)}: {count} frames, {count / fps:.1f} s")


def edit():
    editor = ROOT / "tools/trailer_edit.py"
    if editor.exists():
        editor_spec = importlib.util.spec_from_file_location("trailer_edit", editor)
        trailer_edit = importlib.util.module_from_spec(editor_spec)
        editor_spec.loader.exec_module(trailer_edit)
        trailer_edit.edit(OUT)
    else:
        plain_edit(OUT)


def main():
    args = sys.argv[1:]
    if "--scout" in args:
        at = args.index("--scout")
        return scout(args[at + 1:at + 5])
    all_shots = shots()
    only = set(args[args.index("--shots") + 1].split(",")) if "--shots" in args else set()
    unknown = only - {s.name for s in all_shots}
    if unknown:
        sys.exit(f"no such shots: {sorted(unknown)}; there are {[s.name for s in all_shots]}")
    step = int(args[args.index("--step") + 1]) if "--step" in args else 1
    if "--edit" not in args:
        render(all_shots, only, step, "--software" in args)
    if step == 1 and not only and "--no-edit" not in args:
        edit()


if __name__ == "__main__":
    main()
