#!/usr/bin/env python3
"""The PhaseCraft trailer: a scripted camera through the examples area, rendered frame by frame by the game itself
(TrailerClientGameTest, on the virtual display), then titled, captioned and encoded to an MP4 with ffmpeg.

    python3 tools/trailer.py             # script, render (about 10 minutes), edit, encode: build/trailer/phasecraft-trailer.mp4
    python3 tools/trailer.py --edit      # only edit and encode again, from the frames already rendered

No players and no sound: the camera is a spectator's, and music is left to whoever publishes it.
"""

import importlib.util
import math
import os
import pathlib
import shutil
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "build/trailer"
FPS = 20                   # a frame a game tick
W, H = 1280, 720
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

spec = importlib.util.spec_from_file_location("examples", ROOT / "tools/examples.py")
ex = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ex)


def lever_at(layout, pos_of, facing="north"):
    _, info = ex.read_layout(layout)
    return f"setblock {ex.coords(pos_of(*info['lever']))} minecraft:lever[face=floor,facing={facing},powered=true]"


class Shot:
    """A camera move from one place to another over `frames` frames (eased), commands at given frames, a caption."""

    def __init__(self, caption, frames, start, end, events=(), follow=None, overlay_at=None):
        self.caption, self.frames, self.start, self.end = caption, frames, start, end
        self.events = dict(events)
        self.follow = follow               # a command placing the camera by something moving, instead of start to end
        self.overlay_at = overlay_at       # the frame the overlay comes on (a caption's second line from then)

    def at(self, i):
        t = i / max(1, self.frames - 1)
        t = 0.5 - 0.5 * math.cos(math.pi * t)
        return tuple(a + (b - a) * t for a, b in zip(self.start, self.end))


def shots():
    track_x0 = 716.5                      # the minecart track's west start rail (station 35)
    cart = "@e[type=minecraft:minecart,tag=trailer_cart,limit=1]"
    return [
        Shot("A round door that opens from the middle", 120, (228.5, -54, -13.5, 0, 0), (228.5, -54, -9.5, 0, 0),
             {10: lever_at(ex.IRIS_LAYOUT, ex.iris_pos)}),
        Shot("A waterfall that parts", 100, (262.5, -56, -16.5, 0, -5), (262.5, -56, -13.5, 0, -5),
             {5: lever_at(ex.FALLS_LAYOUT, ex.falls_pos)}),
        Shot("A door into the mountain", 110, (321.5, -58, 7.5, 0, -10), (321.5, -59, 3.5, 0, -10),
             {5: lever_at(ex.MOUNTAIN_LAYOUT, ex.mountain_pos)}),
        Shot("A castle tower, taken down block by block", 120, (545.5, -56, -4.5, 0, -10), (545.5, -56, -2.0, 0, -6),
             {15: lever_at(ex.TOWER_LAYOUT, ex.tower_pos)}),
        Shot("A cottage that vanishes, chest and all, and comes back", 140, (572.5, -56, -6.5, 0, 14),
             (572.5, -56, -3.5, 0, 16),
             {20: lever_at(ex.COTTAGE_LAYOUT, ex.cottage_pos),
              80: lever_at(ex.COTTAGE_LAYOUT, ex.cottage_pos).replace("powered=true", "powered=false")}),
        Shot("A dry hole in the sea", 140, (593.5, -48, -2.5, 0, 48), (593.5, -47, 0.5, 0, 56),
             {5: lever_at(ex.OCEAN_LAYOUT, ex.ocean_pos, "south")}),
        Shot("A picture drawn in the wall", 100, (656.5, -50, -6.5, 0, 0), (656.5, -50, -3.5, 0, 0),
             {5: lever_at(ex.PIXELS_LAYOUT, ex.pixels_pos)}),
        Shot("A track laid ahead of the cart", 110, (track_x0 - 2, -49, 12.5, -12, 30), (track_x0 - 2, -49, 12.5, -12, 30),
             {0: f"summon minecraft:minecart {track_x0} -54 20.5 "
                 '{Tags:["trailer_cart"],Motion:[0.4d,0.0d,0.0d],Passengers:[{id:"minecraft:pig",NoAI:1b,Silent:1b}]}'},
             follow=f"execute at {cart} run tp @a ~-3 ~5 ~-7 -25 30"),
        Shot("All of it runs in a hidden layer", 120, (240.5, -50, -12.5, 40, 20), (238.5, -50, -10.5, 45, 22),
             overlay_at=45),
    ]


def script(all_shots):
    lines, n = [], 0
    for s in all_shots:
        lines.append("goto " + " ".join(f"{v:.3f}" for v in s.at(0)))
        lines.append("overlay off")
        for i in range(s.frames):
            if i in s.events:
                lines.append("run " + s.events[i])
            if s.overlay_at is not None and i == s.overlay_at:
                lines.append("overlay on")
            if s.follow and i > 0:
                lines.append("framecmd " + s.follow)
            else:
                lines.append("frame " + " ".join(f"{v:.3f}" for v in s.at(i)))
            n += 1
    lines.append("overlay off")
    return lines, n


def render(all_shots):
    OUT.mkdir(parents=True, exist_ok=True)
    frames = OUT / "frames"
    if frames.exists():
        shutil.rmtree(frames)
    lines, n = script(all_shots)
    (OUT / "script.txt").write_text("\n".join(lines) + "\n")
    commands = OUT / "examples-commands.txt"
    commands.write_text("\n".join(ex.build_commands() + ex.flying_observer_commands() + ex.sorter_fill_commands()
                                  + ex.ocean_corner_commands() + ex.drop_cleanup_commands()) + "\n")
    env = dict(os.environ, PHASECRAFT_TRAILER=str(OUT / "script.txt"), PHASECRAFT_EXAMPLES_COMMANDS=str(commands))
    print(f"rendering {n} frames ...", flush=True)
    subprocess.run(["./gradlew", "--no-daemon", "runClientGameTest"], cwd=ROOT, env=env, check=True)
    got = len(list(frames.glob("*.png")))
    if got != n:
        sys.exit(f"rendered {got} frames, expected {n}")


def font(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT, size)


def card(title, subtitle):
    img = Image.new("RGB", (W, H), (12, 12, 16))
    d = ImageDraw.Draw(img)
    for text, f, y in ((title, font(96, True), H // 2 - 70), (subtitle, font(34), H // 2 + 50)):
        w = d.textlength(text, font=f)
        d.text(((W - w) / 2, y), text, font=f, fill=(235, 235, 240))
    return img


def caption(img, text, alpha):
    if alpha <= 0:
        return img
    img = img.convert("RGBA")
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    f = font(36)
    w = d.textlength(text, font=f)
    x, y = 60, H - 110
    d.rounded_rectangle((x - 20, y - 14, x + w + 20, y + 50), 10, fill=(0, 0, 0, int(140 * alpha)))
    d.text((x, y), text, font=f, fill=(255, 255, 255, int(255 * alpha)))
    return Image.alpha_composite(img, layer).convert("RGB")


def faded(img, k):
    """Darkened towards black: k = 1 the image, 0 black."""
    return Image.eval(img, lambda v: int(v * k)) if k < 1 else img


def edit(all_shots):
    frames = OUT / "frames"
    edited = OUT / "edited"
    if edited.exists():
        shutil.rmtree(edited)
    edited.mkdir()
    out_i = 0

    def put(img):
        nonlocal out_i
        img.save(edited / f"{out_i:05d}.png")
        out_i += 1
    title = card("PhaseCraft", "A hidden layer of blocks, right under your world")
    for i in range(60):
        put(faded(title, min(1, i / 10, (59 - i) / 10)))
    n = 0
    for s in all_shots:
        for i in range(s.frames):
            img = Image.open(frames / f"{n:05d}.png").convert("RGB")
            n += 1
            text = s.caption
            if s.overlay_at is not None and i >= s.overlay_at:
                text = "Only builders see it"
            a = min(1, (i - 8) / 8, (s.frames - 10 - i) / 8)
            if s.overlay_at is not None:
                a = min(1, (i - 8) / 8, (s.overlay_at - 4 - i) / 8) if i < s.overlay_at else min(1, (i - s.overlay_at - 6) / 8, (s.frames - 10 - i) / 8)
            img = caption(img, text, max(0, a))
            put(faded(img, min(1, i / 6, (s.frames - 1 - i) / 6)))
    end = card("PhaseCraft", "Fabric mod for Minecraft Java 26.3 · github.com/trapperl/phasecraft")
    for i in range(70):
        put(faded(end, min(1, i / 10, (69 - i) / 10)))
    mp4 = OUT / "phasecraft-trailer.mp4"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(FPS), "-i", str(edited / "%05d.png"),
                    "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                    str(mp4)], check=True)
    print(f"wrote {mp4.relative_to(ROOT)}: {out_i} frames, {out_i / FPS:.1f} s")


def main():
    all_shots = shots()
    if "--edit" not in sys.argv:
        render(all_shots)
    edit(all_shots)


if __name__ == "__main__":
    main()
