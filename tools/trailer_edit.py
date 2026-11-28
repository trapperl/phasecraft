#!/usr/bin/env python3
"""The trailer's edit: rendered frames and a timeline in, a cut, graded, titled and scored MP4 out.

    python3 tools/trailer_edit.py build/trailer                 # writes build/trailer/phasecraft-trailer.mp4
    python3 tools/trailer_edit.py build/trailer --music my.wav  # someone else's track (beats detected, or --bpm)
    python3 tools/trailer_edit.py build/trailer --contact-only  # only the contact sheet (edit-contact.png)

In: out_dir/frames/NNNNN.png, optionally out_dir/frames_hidden/NNNNN.png (the same frames with the hidden layer's
machinery shown) and out_dir/timeline.json:

    {"fps": 60, "size": [1920, 1080],
     "shots": [{"name": "iris", "caption": "A round door that opens from the middle", "start": 0, "frames": 180,
                "action": 45, "hidden": false}]}

`action` is the frame within the shot where the thing happens; the cut puts it on a beat. An optional "role" per
shot ("hook", "reveal") overrides where it goes: otherwise the first shot with hidden frames opens the trailer and
is the first one revealed, and the others make the montage in order.

The structure, in beats: a hook (slow motion into the first action), the title, a montage that gets faster, a flurry
of flashes, a hit and silence, the reveal (a scanner line wipes from the frames to the hidden ones, the letterbox
opens), the end card.

The music is made here, by `synth_music` (numpy and scipy only: no samples, no one else's track), on the same
beats, with impacts on the cuts and on each action. The font is Montserrat (SIL Open Font License 1.1), fetched
into build/trailer-fonts from its own repository at a pinned commit. Nothing of Mojang's is drawn: no logo, no font,
no texture beyond what the game itself rendered into the frames.
"""

import argparse
import hashlib
import json
import math
import multiprocessing
import pathlib
import shutil
import os
import subprocess
import sys
import time
import urllib.request

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
FONT_DIR = ROOT / "build/trailer-fonts"
FONT_COMMIT = "555facfb2a18c72c3c0380f0d9c0f060453a9058"      # JulietaUla/Montserrat, master on 2026-03-27
FONT_URL = "https://raw.githubusercontent.com/JulietaUla/Montserrat/" + FONT_COMMIT + "/"
FONT_FILES = {                                                  # name: (path in the repository, sha256)
    "extrabold": ("fonts/ttf/Montserrat-ExtraBold.ttf",
                  "d3ac6a843d3ba6d5cafd44cf39e437055c8aed7e261010f595f57d3c7b3e2c1b"),
    "semibold": ("fonts/ttf/Montserrat-SemiBold.ttf",
                 "b4e1563393d73fdff491a869441245aef31add2ec03d9c97a6dae4de07c52fd0"),
    "medium": ("fonts/ttf/Montserrat-Medium.ttf",
               "dae47428bb041f9716604e0e07b5b0c8585b3bdd8183362f75c69fe7bb3cfaf4"),
    "licence": ("OFL.txt", "8b7141c03fa4f8d44e6345d5d4931709290f0f67875e452e95ac1fd3a027802e"),
}
FALLBACK_FONTS = {"extrabold": "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                  "semibold": "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
                  "medium": "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"}
SR = 48000
PHASE = np.array([0.30, 0.92, 1.00], np.float32)              # the colour of the hidden layer in the graphics
WHITE = np.array([1.0, 1.0, 1.0], np.float32)
TITLE = "PhaseCraft"
SUBTITLE = "A hidden layer of blocks, right under your world"
HOOK_LINES = ("No pistons.", "No redstone.", "So what moved it?")
REVEAL_LINE = "All of it runs in a hidden layer"
REVEAL_EXTRA = ("Same blocks. Same coordinates. Out of sight.", "Real redstone, one layer down",
                "Only builders see it")
END_TAGLINE = "The machine is there. You just can't see it."
END_MOD = "A Fabric mod for Minecraft 26.3"
END_DISCLAIMER = "Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft."
DEFAULT_URL = "github.com/trapperl/phasecraft"
DEFAULT_JOBS = 8        # worker processes: few on purpose, the machine may be running the game (about 0.4 GB each at 1080p)
NICE = 15               # and at a low priority, ffmpeg included


def ffmpeg():
    found = shutil.which("ffmpeg") or str(pathlib.Path.home() / "bin/ffmpeg")
    if not pathlib.Path(found).exists():
        sys.exit("ffmpeg not found (on PATH or in ~/bin)")
    return found


def clamp(x, lo=0.0, hi=1.0):
    return lo if x < lo else hi if x > hi else x


def ease(x):
    x = clamp(x)
    return x * x * (3 - 2 * x)


def ease_out(x):
    x = clamp(x)
    return 1 - (1 - x) ** 3


# --------------------------------------------------------------------------------------------------------------
# The plan: which frames go where, on which beat
# --------------------------------------------------------------------------------------------------------------

def timeline_from_shots(shots, fps, size):
    """A timeline for tools/trailer.py's first Shot objects (caption, frames, events, overlay_at), for renders
    that came without a timeline.json."""
    out, start = [], 0
    for i, s in enumerate(shots):
        events = [f for f in getattr(s, "events", {}) if f > 0]
        overlay = getattr(s, "overlay_at", None)
        shot = {"name": f"shot{i}", "caption": s.caption, "start": start, "frames": s.frames,
                "action": overlay if overlay is not None else (min(events) if events else s.frames // 5),
                "hidden": False}
        if overlay is not None:
            shot["role"] = "reveal"
        out.append(shot)
        start += s.frames
    return {"fps": fps, "size": list(size), "shots": out}


def shot_keys(shot, t0, t_action, t1, fps_src):
    """Time to source frame for a clip of `shot` on screen from t0 to t1 whose action lands at t_action: never
    faster than life before it (slow motion when the shot has little lead), then quick and settling after it."""
    a = float(min(max(shot.get("action", 0), 0), shot["frames"] - 1))
    lead = max(0.0, t_action - t0)
    keys = []
    if lead > 0:
        keys.append((t0, a - min(a, lead * fps_src)))
    post = max(1e-6, t1 - t_action)
    avail = shot["frames"] - 1 - a
    speed = min(3.0, max(0.5, avail / (post * fps_src)))
    total = speed * post * fps_src
    for k in range(9):
        u = k / 8
        keys.append((t_action + u * post, a + total * u * (1.5 - 0.5 * u)))
    return keys


def key_pos(keys, t):
    """The source frame at time t, and how fast the source runs there (frames a second)."""
    for (ta, pa), (tb, pb) in zip(keys, keys[1:]):
        if t < tb:
            break
    slope = (pb - pa) / max(1e-9, tb - ta)
    return pa + (t - ta) * slope, slope


def build_plan(tl, bpm=120.0, fps=None, url=DEFAULT_URL):
    fps_src = float(tl["fps"])
    width, height = tl["size"]
    fps = float(fps or fps_src)
    beat = 60.0 / bpm
    while beat < 0.375:
        beat *= 2
    while beat > 0.75:
        beat /= 2
    b = beat
    shots = tl["shots"]
    if not shots:
        sys.exit("the timeline has no shots")
    hidden = [i for i, s in enumerate(shots) if s.get("hidden")]
    hooks = [i for i, s in enumerate(shots) if s.get("role") == "hook"]
    hook = hooks[0] if hooks else hidden[0] if hidden else 0
    late = [i for i, s in enumerate(shots) if s.get("role") == "reveal" and not s.get("hidden")]
    montage = [i for i in range(len(shots)) if i != hook and i not in late]
    reveal = ([hook] if hook in hidden or not hidden else []) + [i for i in hidden if i != hook]
    reveal = reveal[:4] + late[:1]
    # how long: the first shots of the montage get two bars, the rest one, and the whole stays near a minute
    fixed = 8 + 8 + 4 + 4 + 16 + 8 * (len(reveal) - 1) + 16
    longs = round(len(montage) * 0.4)
    while longs > 0 and (fixed + 8 * longs + 4 * (len(montage) - longs)) * b > 72:
        longs -= 1
    while len(montage) > 4 and (fixed + 4 * len(montage)) * b > 75:
        print(f"trailer_edit: no room for shot {shots[montage[-1]].get('name')}, left out")
        montage = montage[:-1]

    clips, texts = [], []
    ev = {"cuts": [], "actions": [], "whoosh": [], "wipes": [], "ticks": [], "blips": []}
    sections = {}

    def clip(kind, t0, t1, trans="cut", **more):
        c = dict(kind=kind, t0=t0, t1=t1, trans=trans, **more)
        clips.append(c)
        if trans == "whip":
            ev["whoosh"].append(t0)
        return c

    # the hook: slow motion into the first action, which lands on the second bar
    h = shots[hook]
    keys = shot_keys(h, 0.0, 4 * b, 8 * b, fps_src)
    clip("shot", 0.0, 8 * b, shot=hook, keys=keys, punch=[4 * b], push=0.03)
    texts.append(dict(style="big", text=HOOK_LINES[0], t0=0.5 * b, t1=2.3 * b))
    texts.append(dict(style="big", text=HOOK_LINES[1], t0=2.4 * b, t1=3.9 * b))
    texts.append(dict(style="big", text=HOOK_LINES[2], t0=5.0 * b, t1=7.8 * b))
    ev["cuts"].append((4 * b, "impact"))
    sections["hook"] = (0.0, 8 * b)
    # the title, over what the hook shot does next
    p_end = key_pos(keys, 8 * b)[0]
    clip("title", 8 * b, 16 * b, trans="flash", flash=0.9,
         bg=dict(shot=hook, hidden=False, keys=[(8 * b, p_end), (16 * b, p_end + 0.5 * 8 * b * fps_src)]))
    ev["cuts"].append((8 * b, "impact"))
    ev["blips"] = [8 * b + 0.3 + 0.085 * i for i in range(len(TITLE))]
    sections["title"] = (8 * b, 16 * b)
    t = 16 * b
    # the montage
    m0 = t
    for j, si in enumerate(montage):
        long = j < longs
        dur = (8 if long else 4) * b
        ta = t + (2 if long else 1) * b
        if j == 0:
            trans = "whip"
        elif long:
            trans = ("flash", "dissolve", "whip")[j % 3]
        else:
            trans = "whip" if (j - longs) % 4 == 3 else "flash"
        clip("shot", t, t + dur, trans=trans, flash=0.5 if long else 0.3, shot=si,
             keys=shot_keys(shots[si], t, ta, t + dur, fps_src), punch=[ta], push=0.03)
        if shots[si].get("caption"):
            texts.append(dict(style="caption", text=shots[si]["caption"], t0=t + 0.12, t1=t + dur - 0.08))
        if j > 0:
            ev["cuts"].append((t, "boom" if long else "soft"))
        ev["actions"].append(ta)
        t += dur
        if j == longs - 1:
            sections["montage1"] = (m0, t)
            m0 = t
    if "montage1" not in sections:
        sections["montage1"] = (m0, m0)
    sections["montage2"] = (m0, t)
    # the flurry: eight flashes of what was just seen, a half beat each
    pool = (montage + [hook]) if montage else [hook]
    picks = [pool[int(k * len(pool) / 8) % len(pool)] for k in range(8)]
    for k, si in enumerate(picks):
        s = shots[si]
        a = min(s.get("action", 0), s["frames"] - 1)
        p0 = a + (0.25 + 0.5 * (k % 3) / 3) * (s["frames"] - 1 - a)
        clip("shot", t, t + b / 2, trans="flash", flash=0.35, shot=si, zoom=1.06 + 0.025 * k,
             keys=[(t, p0), (t + b / 2, p0 + 1.5 * b / 2 * fps_src)])
        ev["ticks"].append(t)
        t += b / 2
    sections["recap"] = (t - 4 * b, t)
    # the hit, and silence
    clip("black", t, t + 4 * b, trans="flash", flash=1.0)
    ev["cuts"].append((t, "big"))
    sections["silence"] = (t, t + 4 * b)
    t += 4 * b
    # the reveal
    r0 = t
    extra_lines = list(REVEAL_EXTRA)
    last_bg = None
    for k, si in enumerate(reveal):
        s = shots[si]
        dur = (16 if k == 0 else 8) * b
        ta = t + (2 if k == 0 else 1) * b
        wipe = None
        if s.get("hidden"):
            wipe = (t + 4 * b, t + 10 * b) if k == 0 else (t + 2 * b, t + 6 * b)
            ev["wipes"].append(wipe)
        keys = shot_keys(s, t, ta, t + dur, fps_src)
        clip("shot", t, t + dur, trans="flash" if k == 0 else "whip", flash=0.9, shot=si, keys=keys,
             punch=[] if wipe else [ta], push=0.0 if wipe else 0.03, wipe=wipe)
        ev["cuts"].append((t, "drop" if k == 0 else "boom"))
        ev["actions"].append(ta)
        if k == 0:
            if wipe:
                if s.get("caption") and si == hook:
                    texts.append(dict(style="caption", text=s["caption"], t0=t + 0.3, t1=wipe[0] + 0.5 * b))
                texts.append(dict(style="caption", text=REVEAL_LINE, t0=wipe[0] + 2 * b, t1=t + dur - 0.08))
            else:
                texts.append(dict(style="caption", text=REVEAL_LINE, t0=t + 0.3, t1=t + dur - 0.08))
        else:
            line = s.get("caption", "") if si in late else ""
            if not line or line == REVEAL_LINE:
                line = extra_lines.pop(0) if extra_lines else ""
            if line:
                texts.append(dict(style="caption", text=line, t0=t + 0.12, t1=t + dur - 0.08))
        t += dur
        last_bg = dict(shot=si, hidden=bool(wipe), keys=keys[-2:])
    sections["reveal"] = (r0, t)
    # the end card
    tail = 1.0
    clip("end", t, t + 16 * b + tail, trans="dissolve", bg=last_bg, url=url)
    ev["cuts"].append((t, "impact"))
    sections["end"] = (t, t + 16 * b + tail)
    e0 = t
    t += 16 * b + tail
    return dict(fps=fps, fps_src=fps_src, size=[width, height], beat=b, duration=t, clips=clips, texts=texts,
                events=ev, sections=sections, shots=shots, how=(sections["silence"][0] + b, r0 - 0.08),
                bars=[(0.0, 1.0), (r0, 1.0), (r0 + 1.0, 0.0), (e0 - 0.1, 0.0), (e0 + 0.7, 1.0)],
                n_frames=int(round(t * fps)))


# --------------------------------------------------------------------------------------------------------------
# The music
# --------------------------------------------------------------------------------------------------------------

CHORDS = {"Dm": (38, (50, 57, 62, 65)), "Bb": (34, (46, 53, 58, 62)), "F": (41, (53, 57, 60, 65)),
          "C": (36, (48, 55, 60, 64)), "A": (33, (45, 52, 57, 61))}
PROGRESSION = ("Dm", "Bb", "F", "C")
MELODY = ((69, 74, 77), (77, 74, 70), (72, 77, 81), (79, 76, 72))      # three notes a bar: 3 + 3 + 2 eighths


def hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)


def synth_music(plan, sfx_only=False):
    """The cue, as float32 stereo at 48 kHz, before the limiter: D minor, one chord a bar."""
    from scipy import signal
    rng = np.random.default_rng(26)
    b = plan["beat"]
    bar = 4 * b
    n = int((plan["duration"] + 0.5) * SR)
    sec, ev = plan["sections"], plan["events"]
    names = ("kick", "drums", "bass", "pad", "arp", "strings", "lead", "fx", "impact")
    bus = {k: np.zeros((n, 2), np.float32) for k in names}
    kicks = []

    def put(name, t, sig, gain=1.0, pan=0.0):
        i = int(round(t * SR))
        if i < 0:
            sig, i = sig[-i:], 0
        m = min(len(sig), n - i)
        if m <= 0:
            return
        if sig.ndim == 1:
            th = (pan + 1) * math.pi / 4
            bus[name][i:i + m, 0] += sig[:m] * (gain * math.cos(th) * 1.4142)
            bus[name][i:i + m, 1] += sig[:m] * (gain * math.sin(th) * 1.4142)
        else:
            bus[name][i:i + m] += sig[:m] * gain

    def tt(dur):
        return np.arange(int(dur * SR), dtype=np.float32) / SR

    def norm(x):
        return (x / max(1e-9, float(np.abs(x).max()))).astype(np.float32)

    def filt(x, kind, fc, order=2):
        sos = signal.butter(order, fc, kind, fs=SR, output="sos")
        return signal.sosfilt(sos, x, axis=0).astype(np.float32)

    def sweep(x, kind, f_of_u, chunk=256):
        """A filter whose cutoff moves: f_of_u(0..1) gives it (one number, or a band's two)."""
        out = np.empty_like(x)
        zi = None
        for i in range(0, len(x), chunk):
            sos = signal.butter(2, f_of_u(i / len(x)), kind, fs=SR, output="sos")
            if zi is None:
                zi = np.zeros((sos.shape[0], 2) + x.shape[1:], np.float64)
            out[i:i + chunk], zi = signal.sosfilt(sos, x[i:i + chunk], axis=0, zi=zi)
        return out

    def saw(f, count, phase=0.0):
        """A sawtooth with its aliasing tamed (a differentiated parabola)."""
        if np.isscalar(f):
            ph = (phase + f * np.arange(count + 1) / SR) % 1.0
            scale = SR / (4 * f)
        else:
            ph = (phase + np.concatenate(([0.0], np.cumsum(f))) / SR) % 1.0
            scale = SR / (4 * f)
        s = 2 * ph - 1
        return (np.diff(s * s) * scale).astype(np.float32)

    def supersaw(f, dur, voices=7, detune=0.010):
        count = int(dur * SR)
        out = np.zeros((count, 2), np.float32)
        for v in range(voices):
            d = (2 * v / (voices - 1) - 1) if voices > 1 else 0.0
            x = saw(f * (1 + detune * d), count, rng.random())
            th = (0.8 * d * (1 if v % 2 else -1) + 1) * math.pi / 4
            out[:, 0] += x * math.cos(th)
            out[:, 1] += x * math.sin(th)
        return out / voices

    def envelope(count, attack, release, shape=1.0):
        e = np.ones(count, np.float32)
        a, r = max(1, int(attack * SR)), max(1, int(release * SR))
        a, r = min(a, count), min(r, count)
        e[:a] = np.linspace(0, 1, a) ** shape
        e[-r:] *= np.linspace(1, 0, r)
        return e

    def noise(count, stereo=False):
        return rng.standard_normal((count, 2) if stereo else count).astype(np.float32)

    # ---- the kit ----
    def make_kick():
        t = tt(0.45)
        f = 46 + 120 * np.exp(-t / 0.028) + 220 * np.exp(-t / 0.005)
        x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.16)
        x[:96] += filt(noise(96), "highpass", 3000) * np.linspace(1, 0, 96) * 0.25
        return norm(np.tanh(1.8 * x) * envelope(len(t), 0.0005, 0.05))

    def make_snare():
        t = tt(0.45)
        tone = np.sin(2 * np.pi * 185 * t) * np.exp(-t / 0.06) * 0.5 + np.sin(2 * np.pi * 331 * t) * np.exp(-t / 0.04) * 0.25
        nz = filt(noise(len(t)), "bandpass", (900, 9000)) * np.exp(-t / 0.11)
        for d in (0.0, 0.011, 0.023):                 # a clap's three hands
            i = int(d * SR)
            nz[i:i + 480] += filt(noise(480), "bandpass", (1200, 6000)) * np.linspace(1, 0, 480) * 0.6
        return norm(np.tanh(1.5 * (tone + nz)) * envelope(len(t), 0.0005, 0.05))

    def make_hat(decay):
        t = tt(decay * 6)
        return norm(filt(noise(len(t)), "highpass", 7500, 4) * np.exp(-t / decay))

    def make_boom():
        t = tt(1.6)
        f = 42 + 50 * np.exp(-t / 0.05)
        x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.42)
        x += filt(noise(len(t)), "lowpass", 320) * np.exp(-t / 0.10) * 0.8
        return norm(np.tanh(1.6 * x) * envelope(len(t), 0.001, 0.2))

    def make_impact():
        t = tt(3.2)
        f = 31 + 55 * np.exp(-t / 0.11)
        sub = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.85)
        nz = sweep(noise(len(t), True), "lowpass", lambda u: 9000 * (160 / 9000) ** min(1.0, u * 2.2))
        nz *= np.exp(-t / 0.45)[:, None]
        metal = sum(np.sin(2 * np.pi * f0 * t + ph) for f0, ph in ((211, 0), (317, 1), (523, 2), (811, 3)))
        metal = metal * np.exp(-t / 0.35) * 0.06
        x = nz * 0.7 + (np.tanh(2.0 * sub) + metal)[:, None]
        return norm(x * envelope(len(t), 0.001, 0.4)[:, None])

    def make_crash():
        t = tt(2.6)
        x = filt(noise(len(t), True), "highpass", 3500, 4) * np.exp(-t / 0.65)[:, None]
        return norm(x * envelope(len(t), 0.002, 0.3)[:, None])

    def riser(dur):
        t = tt(dur)
        u = t / dur
        nz = sweep(noise(len(t), True), "bandpass", lambda v: (220 * 30 ** v, min(20000, 500 * 30 ** v)))
        tone = saw(110 * 4 ** u.astype(np.float64), len(t))
        tone = sweep(tone, "lowpass", lambda v: 600 + 5000 * v * v)
        trem = 0.6 + 0.4 * np.sin(2 * np.pi * np.cumsum(4 + 20 * u * u) / SR)
        x = nz * 1.0 + (tone * trem * 0.35)[:, None]
        return norm(x * (u ** 2.2 * envelope(len(t), 0.01, 0.004))[:, None])

    def swell(dur):
        t = tt(dur)
        x = filt(noise(len(t), True), "highpass", 2500, 4) * np.exp(-(dur - t) / (dur / 4.5))[:, None]
        return norm(x * envelope(len(t), 0.01, 0.006)[:, None])

    def whoosh(dur=0.45):
        t = tt(dur)
        u = t / dur
        x = sweep(noise(len(t)), "bandpass", lambda v: (350 + 2600 * math.sin(math.pi * v) ** 2,
                                                         900 + 6000 * math.sin(math.pi * v) ** 2))
        x = norm(x * np.sin(np.pi * u) ** 2)
        return np.stack([x * np.cos(u * np.pi / 2), x * np.sin(u * np.pi / 2)], 1).astype(np.float32)

    def scanner(dur):
        t = tt(dur)
        u = t / dur
        x = np.zeros(len(t), np.float32)
        for k, f0 in enumerate((1760.0, 2637.0, 3520.0, 5274.0)):
            x += np.sin(2 * np.pi * f0 * t * (1 + 0.06 * u) + 2.5 * np.sin(2 * np.pi * (5 + k) * t)) / (k + 1)
        x = norm(x * (0.55 + 0.45 * np.sin(2 * np.pi * 12 * t)) * np.sin(np.pi * u) ** 0.5)
        th = (0.1 + 0.8 * u) * np.pi / 2
        return np.stack([x * np.cos(th), x * np.sin(th)], 1).astype(np.float32)

    def blip():
        dur = float(rng.uniform(0.03, 0.07))
        t = tt(dur)
        f0 = hz(rng.choice((74, 77, 81, 86, 89, 93)))
        x = np.sign(np.sin(2 * np.pi * f0 * t)) * 0.6 + noise(len(t)) * 0.4
        return norm(filt(x, "bandpass", (1500, 9000)) * envelope(len(t), 0.001, 0.01))

    def tick():
        t = tt(0.06)
        return norm(np.sin(2 * np.pi * 2400 * t) * np.exp(-t / 0.012) + filt(noise(len(t)), "highpass", 5000) * np.exp(-t / 0.004))

    def rim():
        t = tt(0.12)
        x = np.sin(2 * np.pi * 420 * t) * np.exp(-t / 0.03) + filt(noise(len(t)), "bandpass", (1500, 6000)) * np.exp(-t / 0.008)
        return norm(x)

    KICK, SNARE, HAT, OPEN, BOOM, IMPACT, CRASH, TICK, RIM = (make_kick(), make_snare(), make_hat(0.022),
                                                              make_hat(0.11), make_boom(), make_impact(),
                                                              make_crash(), tick(), rim())

    # ---- the instruments ----
    cache = {}

    def bass_note(midi, dur, bright):
        key = ("bass", midi, round(dur, 3), bright)
        if key not in cache:
            count = int(dur * SR)
            f = hz(midi)
            x = saw(f, count) + 0.6 * saw(f * 1.006, count, 0.3)
            x = filt(x, "lowpass", bright) * 0.6 + np.sin(2 * np.pi * f * np.arange(count) / SR) * 0.9
            cache[key] = norm(np.tanh(1.3 * x) * envelope(count, 0.004, min(0.06, dur / 3)))
        return cache[key]

    def pad(chord, dur, bright):
        key = ("pad", chord, round(dur, 3), bright)
        if key not in cache:
            x = sum(supersaw(hz(m), dur + 0.6, 7, 0.011) for m in CHORDS[chord][1])
            x = filt(x, "lowpass", bright)
            cache[key] = norm(x * envelope(len(x), min(0.5, dur / 3), 0.6)[:, None])
        return cache[key]

    def pluck(midi, bright=3500):
        key = ("pluck", midi, bright)
        if key not in cache:
            count = int(0.32 * SR)
            f = hz(midi)
            x = saw(f, count) + 0.5 * saw(f * 2.003, count, 0.5)
            t = np.arange(count) / SR
            cache[key] = norm(filt(x, "lowpass", bright) * np.exp(-t / 0.075) * envelope(count, 0.002, 0.03))
        return cache[key]

    def spiccato(chord):
        key = ("spic", chord)
        if key not in cache:
            x = sum(supersaw(hz(m), 0.22, 5, 0.006) for m in CHORDS[chord][1][:3])
            t = np.arange(len(x)) / SR
            x = filt(x, "lowpass", 3800) * (np.exp(-t / 0.07) * envelope(len(x), 0.008, 0.03))[:, None]
            cache[key] = norm(x)
        return cache[key]

    def stab(chord, dur):
        key = ("stab", chord, round(dur, 3))
        if key not in cache:
            x = sum(supersaw(hz(m + 12), dur, 7, 0.009) for m in CHORDS[chord][1])
            t = np.arange(len(x)) / SR
            x = filt(x, "lowpass", 5200) * (np.exp(-t / (dur * 0.6)) * envelope(len(x), 0.004, 0.04))[:, None]
            cache[key] = norm(x)
        return cache[key]

    def lead(midi, dur):
        f = hz(midi)
        x = supersaw(f, dur, 7, 0.008)
        x[:, 0] += np.sin(2 * np.pi * f / 2 * np.arange(len(x)) / SR) * 0.3
        x[:, 1] += np.sin(2 * np.pi * f / 2 * np.arange(len(x)) / SR) * 0.3
        return norm(filt(x, "lowpass", 6000) * envelope(len(x), 0.012, min(0.15, dur / 3))[:, None])

    def bars_of(name):
        t0, t1 = sec[name]
        return [t0 + k * bar for k in range(int(round((t1 - t0) / bar)))]

    def kick_at(t, gain=0.9):
        put("kick", t, KICK, gain)
        kicks.append(t)

    def groove(t0, chord, level, k):
        """One bar of the band: level 1 the half-time montage, 2 four on the floor, 3 the drop."""
        root, notes = CHORDS[chord]
        if level == 1:
            for x in (0, 1.5) + ((3.5,) if k % 2 else ()):
                kick_at(t0 + x * b)
            put("drums", t0 + 2 * b, SNARE, 0.5)
            for i in range(8):
                put("drums", t0 + i * b / 2, HAT, 0.11 if i % 2 == 0 else 0.07, 0.25)
        else:
            for x in range(4):
                kick_at(t0 + x * b)
            for x in (1, 3):
                put("drums", t0 + x * b, SNARE, 0.55 if level == 3 else 0.48)
            for i in range(16):
                put("drums", t0 + i * b / 4, HAT, (0.11, 0.05, 0.08, 0.05)[i % 4], 0.25)
            for x in range(4):
                put("drums", t0 + (x + 0.5) * b, OPEN, 0.07, -0.25)
        bright = (500, 800, 1100)[level - 1]
        for i in range(8):
            midi = root + (12 if level > 1 and i % 2 else 0)
            put("bass", t0 + i * b / 2, bass_note(midi, b / 2 * 0.92, bright), 0.34 if i % 2 == 0 else 0.28)
        put("pad", t0, pad(chord, bar, (1800, 2600, 3200)[level - 1]), (0.15, 0.15, 0.11)[level - 1])
        order = (0, 2, 1, 3, 2, 3, 1, 2, 0, 2, 1, 3, 2, 3, 1, 3)
        for i in range(16):
            put("arp", t0 + i * b / 4, pluck(notes[order[i]] + 12, 3000 + 800 * level),
                (0.10, 0.12, 0.10)[level - 1] * (1.0 if i % 4 == 0 else 0.75), 0.45 if i % 2 else -0.45)
        if level >= 2:
            for i in range(8):
                put("strings", t0 + i * b / 2, spiccato(chord), (0.17 if level == 2 else 0.13) * (1.0 if i in (0, 3, 6) else 0.55))
        if level == 3:
            at = 0
            for length, midi in zip((3, 3, 2), MELODY[k % 4]):
                put("pad", t0 + at * b / 2, stab(chord, length * b / 2), 0.15)
                put("lead", t0 + at * b / 2, lead(midi, length * b / 2 * 0.95), 0.21)
                at += length

    if not sfx_only:
        # the hook: a drone, a heartbeat, a clock
        t0, t1 = sec["hook"]
        count = int((t1 - t0) * SR)
        drone = filt(saw(hz(26), count) + saw(hz(38) * 1.004, count, 0.4), "lowpass", 170)
        drone *= (0.8 + 0.2 * np.sin(2 * np.pi * 0.9 * np.arange(count) / SR)) * envelope(count, 1.2, 0.5)
        put("bass", t0, norm(drone), 0.22)
        soft_kick = norm(filt(KICK, "lowpass", 140))
        for x in (0, 0.6, 2, 2.6):
            put("kick", t0 + x * b, soft_kick, 0.4 if x in (0, 2) else 0.28)
        for i in range(8):
            put("drums", t0 + i * b / 2, TICK, 0.05, 0.3 if i % 2 else -0.3)
        put("pad", t0 + bar, pad("Dm", bar, 900), 0.13)
        # the title: the chords arrive, the arpeggio starts under them
        for k, t0 in enumerate(bars_of("title")):
            chord = PROGRESSION[k % 4]
            root, notes = CHORDS[chord]
            put("pad", t0, pad(chord, bar, 1500), 0.16)
            put("bass", t0, bass_note(root, bar * 0.95, 400), 0.3)
            order = (0, 2, 1, 3, 2, 3, 1, 2, 0, 2, 1, 3, 2, 3, 1, 3)
            for i in range(16):
                put("arp", t0 + i * b / 4, pluck(notes[order[i]] + 12, 1800), 0.05 + 0.03 * (k + i / 16), 0.45 if i % 2 else -0.45)
        k = 0
        for t0 in bars_of("montage1"):
            groove(t0, PROGRESSION[k % 4], 1, k)
            k += 1
        for t0 in bars_of("montage2"):
            groove(t0, PROGRESSION[k % 4], 2, k)
            k += 1
        # the flurry: the band drops out, a roll on the dominant
        t0, t1 = sec["recap"]
        put("pad", t0, pad("A", t1 - t0, 2800), 0.16)
        steps = [i * b / 2 for i in range(4)] + [2 * b + i * b / 4 for i in range(4)] + [3 * b + i * b / 8 for i in range(8)]
        for i, x in enumerate(steps):
            put("drums", t0 + x, SNARE, 0.16 + 0.34 * i / len(steps))
        for i in range(16):
            put("strings", t0 + i * b / 4, spiccato("A"), 0.08 + 0.12 * i / 16)
        put("impact", t0, BOOM, 0.5)
        for k, t0 in enumerate(bars_of("reveal")):
            groove(t0, PROGRESSION[k % 4], 3, k)
            if k % 4 == 0:
                put("fx", t0, CRASH, 0.26)
        t0, t1 = sec["reveal"]
        for i in range(4):
            put("drums", t1 - b + i * b / 4, SNARE, 0.3 + 0.08 * i)
        # the end: one chord left ringing, the tune's first notes, slower
        t0, t1 = sec["end"]
        for k, chord in enumerate(("Dm", "Bb", "Dm", "Dm")):
            put("pad", t0 + k * bar, pad(chord, bar, 1500 - 250 * k), 0.17 - 0.02 * k)
            put("bass", t0 + k * bar, bass_note(CHORDS[chord][0], bar * 0.95, 350), 0.26 - 0.04 * k)
            for i in range(8):
                put("arp", t0 + k * bar + i * b / 2, pluck(CHORDS[chord][1][(0, 2, 1, 3, 2, 3, 1, 2)[i]] + 12, 2400),
                    max(0.0, 0.10 - 0.028 * (k + i / 8)), 0.45 if i % 2 else -0.45)
        put("lead", t0 + 0 * b, lead(69, 2 * b), 0.10)
        put("lead", t0 + 2 * b, lead(74, 5 * b), 0.10)
        put("lead", t0 + 8 * b, lead(77, 2 * b), 0.08)
        put("lead", t0 + 10 * b, lead(74, 5 * b), 0.08)
        put("impact", t0 + 2 * bar, BOOM, 0.3)

    # ---- what the picture asks for: hits on the cuts and the actions, risers into them ----
    for t, kind in ev["cuts"]:
        if kind == "impact":
            put("impact", t, IMPACT, 0.95)
            put("fx", t, CRASH, 0.2)
        elif kind == "big":
            put("impact", t, IMPACT, 1.15)
            put("impact", t, BOOM, 0.6)
        elif kind == "drop":
            put("impact", t, IMPACT, 0.7)
        elif kind == "boom":
            put("impact", t, BOOM, 0.55)
        else:
            put("impact", t, BOOM, 0.3)
    for t in ev["actions"]:
        put("impact", t, BOOM, 0.42)
        put("drums", t, RIM, 0.3, -0.2)
    for t in ev["whoosh"]:
        put("fx", t - 0.24, whoosh(), 0.26)
    for t in ev["ticks"]:
        put("drums", t, TICK, 0.2)
        put("drums", t, RIM, 0.16, 0.2)
    for t in ev["blips"]:
        put("fx", t - 0.05, blip(), 0.07, float(rng.uniform(-0.7, 0.7)))
    for t0, t1 in ev["wipes"]:
        put("fx", t0, scanner(t1 - t0), 0.055)
    hook_hit = ev["cuts"][0][0]
    put("fx", hook_hit - 4 * b, riser(4 * b), 0.26)
    put("fx", sec["title"][0] - 1.5 * b, swell(1.5 * b), 0.2)
    put("fx", sec["title"][1] - 4 * b, riser(4 * b), 0.2)
    build = min(sec["silence"][0] - sec["montage2"][0], 12 * b)
    put("fx", sec["silence"][0] - build, riser(build), 0.26)
    put("fx", sec["reveal"][0] - 1.5 * b, swell(1.5 * b), 0.24)
    put("fx", sec["end"][0] - 1.5 * b, swell(1.5 * b), 0.2)

    # ---- the mix ----
    # the balance between the groups, set by measuring: with every group at 1 the lows sat 12 dB over the tune
    for name, level in (("kick", 0.75), ("bass", 0.6), ("impact", 0.75), ("drums", 1.3), ("pad", 2.0), ("arp", 2.2),
                        ("strings", 2.0), ("lead", 2.1), ("fx", 1.2)):
        bus[name] *= level
    duck = np.ones(n, np.float32)
    t = tt(0.4)
    dip = 1 - 0.6 * np.exp(-t / 0.11)
    for tk in kicks:
        i = int(round(tk * SR))
        m = min(len(dip), n - i)
        if m > 0:
            duck[i:i + m] = np.minimum(duck[i:i + m], dip[:m])
    for name in ("bass", "pad", "arp", "strings", "lead"):
        bus[name] *= duck[:, None]
    # the arpeggio and the tune echo from side to side
    step = int(0.75 * b * SR)
    for name, amount in (("arp", 0.4), ("lead", 0.35)):
        dry = bus[name].copy()
        for k in range(1, 5):
            echo = dry[:n - k * step] * amount ** k
            bus[name][k * step:] += echo[:, ::-1] if k % 2 else echo
    # a hall for most of it, a bigger one for the impacts
    def hall(dur, bright):
        t = tt(dur)
        ir = noise(len(t), True) * np.exp(-6.9 * t / dur)[:, None]
        ir = filt(filt(ir, "lowpass", bright), "highpass", 250)
        ir[:int(0.012 * SR)] = 0
        return ir / np.sqrt((ir ** 2).sum(0, keepdims=True))

    sends = {"drums": 0.10, "arp": 0.22, "lead": 0.30, "pad": 0.2, "strings": 0.2, "fx": 0.2}
    wet_in = sum(bus[k] * g for k, g in sends.items())
    mix = sum(bus.values())
    for src, ir, gain in ((wet_in, hall(2.2, 6000), 0.9), (bus["impact"], hall(4.0, 3500), 0.30)):
        for ch in range(2):
            mix[:, ch] += signal.fftconvolve(src[:, ch], ir[:, ch])[:n].astype(np.float32) * gain
    return filt(mix, "highpass", 28)


def master(x, plan, loud=None, ceiling=0.79):       # -2 dBFS: AAC overshoots by about 1 dB
    """Level, a soft knee, a look-ahead limiter, the fade at the end. Returns the audio and what the limiter did."""
    from scipy import ndimage
    if loud is None:
        t0, t1 = plan["sections"]["reveal"]
        loud = x[int(t0 * SR):int(t1 * SR)]
    rms = float(np.sqrt(np.mean(loud ** 2))) or 1.0
    x = x * (0.19 / rms)
    knee = 0.8
    over = np.abs(x) > knee
    x = np.where(over, np.sign(x) * (knee + (1.6 - knee) * np.tanh((np.abs(x) - knee) / (1.6 - knee))), x)
    peak = np.abs(x).max(axis=1)
    need = np.minimum(1.0, ceiling / np.maximum(peak, 1e-9))
    look = int(0.005 * SR)
    gain = ndimage.minimum_filter1d(need, 2 * look + 1)
    gain = ndimage.uniform_filter1d(gain, look)
    gain = np.minimum(gain, ndimage.minimum_filter1d(need, 3))
    x = (x * gain[:, None]).astype(np.float32)
    n = min(len(x), int(plan["duration"] * SR))
    x = x[:n]
    fade = int(2.2 * SR)
    x[-fade:] *= (np.linspace(1, 0, fade) ** 1.5)[:, None].astype(np.float32)
    return x, 20 * math.log10(max(1e-9, float(gain.min())))


def write_wav(path, x):
    from scipy.io import wavfile
    rng = np.random.default_rng(1)
    dither = (rng.random(x.shape) - rng.random(x.shape)) / 32768.0
    wavfile.write(str(path), SR, np.clip((x + dither) * 32767.0, -32768, 32767).astype(np.int16))


def read_audio(path, work):
    """Any audio file as float32 stereo at 48 kHz (through ffmpeg)."""
    from scipy.io import wavfile
    tmp = work / "music-in.wav"
    subprocess.run([ffmpeg(), "-y", "-loglevel", "error", "-i", str(path), "-ar", str(SR), "-ac", "2",
                    "-c:a", "pcm_s16le", str(tmp)], check=True)
    _, data = wavfile.read(str(tmp))
    return data.astype(np.float32) / 32768.0


def onset_envelope(x, hop=480):
    """How much new sound starts in each 10 ms: the rise of the spectrum, summed."""
    from scipy import signal
    mono = x.mean(axis=1) if x.ndim == 2 else x
    _, _, spec = signal.stft(mono, SR, nperseg=1920, noverlap=1920 - hop, padded=False, boundary=None)
    mag = np.log1p(40 * np.abs(spec))
    return np.maximum(0, np.diff(mag, axis=1)).sum(axis=0), hop / SR


def detect_beats(x):
    """Tempo and the time of the first beat of a track."""
    env, dt = onset_envelope(x)
    env = env - env.mean()
    ac = np.correlate(env, env, "full")[len(env) - 1:]
    lags = np.arange(len(ac))
    lo, hi = int(60 / 180 / dt), int(60 / 70 / dt)
    prior = np.exp(-0.5 * (np.log2(np.maximum(lags, 1) * dt / 0.5) / 0.6) ** 2)
    lag = lo + int(np.argmax((ac * prior)[lo:hi]))
    if 0 < lag < len(ac) - 1:                                    # a parabola through the peak
        y0, y1, y2 = ac[lag - 1], ac[lag], ac[lag + 1]
        lag = lag + 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2 + 1e-12)
    period = lag * dt
    best, first = -1.0, 0.0
    for k in range(int(period / dt)):
        idx = np.round((k * dt + np.arange(0, len(env) * dt - k * dt, period)) / dt).astype(int)
        score = env[idx[idx < len(env)]].sum()
        if score > best:
            best, first = score, k * dt + 0.03
    first %= period
    return 60.0 / period, 0.0 if period - first < 0.02 else first


def sync_report(plan, audio):
    """Where the sound's attacks fall against the picture's cuts and actions."""
    env, dt = onset_envelope(audio)
    fps = plan["fps"]
    rows = []
    for label, times in (("cut", [t for t, _ in plan["events"]["cuts"]]), ("action", plan["events"]["actions"])):
        for t in times:
            i = int(round(t / dt))
            lo, hi = max(0, i - 5), min(len(env), i + 6)
            j = lo + int(np.argmax(env[lo:hi]))
            strong = env[j] > 2.0 * np.median(env[max(0, i - 200):i + 200] + 1e-9)
            rows.append((label, t, round(t * fps), (j + 1) * dt + 0.02 - t, bool(strong)))   # a window is 40 ms
    return rows


# --------------------------------------------------------------------------------------------------------------
# The picture
# --------------------------------------------------------------------------------------------------------------

G = {}          # what a worker needs: the plan, the folders, the fonts, small caches


def ensure_fonts():
    """Montserrat, from its repository at the pinned commit, checked against the hashes here."""
    FONT_DIR.mkdir(parents=True, exist_ok=True)
    paths = {}
    try:
        for name, (rel, sha) in FONT_FILES.items():
            path = FONT_DIR / pathlib.Path(rel).name
            if not path.exists():
                with urllib.request.urlopen(FONT_URL + rel, timeout=30) as r:
                    data = r.read()
                if hashlib.sha256(data).hexdigest() != sha:
                    raise OSError(f"{rel}: not the file this tool was written against")
                path.write_bytes(data)
            elif hashlib.sha256(path.read_bytes()).hexdigest() != sha:
                raise OSError(f"{path}: not the file this tool was written against (delete it to fetch it again)")
            paths[name] = str(path)
    except OSError as e:
        print(f"trailer_edit: no Montserrat ({e}); using DejaVu Sans instead", file=sys.stderr)
        return dict(FALLBACK_FONTS)
    return paths


def font(weight, size):
    key = (weight, int(round(size)))
    fonts = G.setdefault("font_cache", {})
    if key not in fonts:
        fonts[key] = ImageFont.truetype(G["fonts"][weight], key[1])
    return fonts[key]


def layout(text, f, tracking):
    """Where each character starts, kerned, with extra tracking; and the whole width."""
    xs, x = [], 0.0
    for i, c in enumerate(text):
        xs.append(x)
        x += (f.getlength(text[i:i + 2]) - f.getlength(text[i + 1:i + 2]) if i + 1 < len(text) else f.getlength(c)) + tracking
    return xs, max(0.0, x - tracking)


def text_patch(text, weight, size, tracking=0.0):
    """The text as an alpha patch (and a blurred one for its shadow), cached."""
    key = (text, weight, int(round(size)), round(tracking * 2) / 2)
    patches = G.setdefault("patch_cache", {})
    if key not in patches:
        if len(patches) > 300:
            patches.clear()
        f = font(weight, size)
        xs, width = layout(text, f, key[3])
        asc, desc = f.getmetrics()
        padding = int(size * 0.45) + 2
        im = Image.new("L", (int(math.ceil(width)) + 2 * padding, asc + desc + 2 * padding), 0)
        d = ImageDraw.Draw(im)
        for c, x in zip(text, xs):
            d.text((padding + x, padding), c, font=f, fill=255)
        shadow = im.filter(ImageFilter.GaussianBlur(size * 0.16))
        patches[key] = (np.asarray(im, np.float32) / 255, np.asarray(shadow, np.float32) / 255, padding, width,
                        asc + desc)
    return patches[key]


def comp(img, alpha, x, y, color, opacity=1.0):
    """Lay an alpha patch over the picture in a colour."""
    if opacity <= 0:
        return
    h_img, w_img = img.shape[:2]
    h, w = alpha.shape
    x, y = int(round(x)), int(round(y))
    x0, y0, x1, y1 = max(0, x), max(0, y), min(w_img, x + w), min(h_img, y + h)
    if x1 <= x0 or y1 <= y0:
        return
    a = alpha[y0 - y:y1 - y, x0 - x:x1 - x, None] * opacity
    region = img[y0:y1, x0:x1]
    region += (np.asarray(color, np.float32) - region) * a


def draw_text(img, text, weight, size, x, y, tracking=0.0, color=WHITE, opacity=1.0, anchor="l", shadow=0.6):
    """Text with its top-left (or top-centre, anchor "c") at x, y. Returns its width."""
    alpha, soft, padding, width, _ = text_patch(text, weight, size, tracking)
    if anchor == "c":
        x -= width / 2
    if shadow:
        comp(img, soft, x - padding, y - padding + size * 0.05, (0, 0, 0), opacity * shadow)
    comp(img, alpha, x - padding, y - padding, color, opacity)
    return width


def draw_caption(img, e, t, bar):
    """A caption coming in word by word from the left, beside a bar in the layer's colour."""
    w_img, h_img = G["plan"]["size"]
    size = h_img / 23
    tracking = size * 0.06
    f = font("semibold", size)
    space = f.getlength(" ") + tracking
    lines, line, x = [], [], 0.0
    for word in e["text"].upper().split():
        w = layout(word, f, tracking)[1]
        if line and x + w > 0.56 * w_img:
            lines.append(line)
            line, x = [], 0.0
        line.append((word, x))
        x += w + space
    lines.append(line)
    dt = t - e["t0"]
    out = clamp((e["t1"] - t) / 0.2)
    line_h = size * 1.4
    x0 = 0.065 * w_img
    y0 = h_img - bar - 0.065 * h_img - len(lines) * line_h
    grow = ease_out(dt / 0.3)
    bw = max(3, round(w_img * 0.0032))
    bx = int(x0 - w_img * 0.014)
    by0 = int(y0 + size * 0.12)
    by1 = int(by0 + grow * (len(lines) * line_h - size * 0.2))
    if by1 > by0:
        img[by0:by1, bx:bx + bw] += (PHASE - img[by0:by1, bx:bx + bw]) * out
    i = 0
    for row, words in enumerate(lines):
        for word, x in words:
            k = ease_out((dt - 0.05 - 0.045 * i) / 0.35)
            if k > 0:
                draw_text(img, word, "semibold", size, x0 + x + (1 - k) * 0.03 * w_img - (1 - out) * 0.01 * w_img,
                          y0 + row * line_h, tracking, opacity=k * out)
            i += 1


def draw_big(img, e, t):
    """A hook line: centred, tracking in from wide."""
    w_img, h_img = G["plan"]["size"]
    size = h_img / 10
    dt = t - e["t0"]
    k = ease_out(dt / 0.6)
    out = clamp((e["t1"] - t) / 0.18)
    tracking = size * (0.10 + 0.45 * (1 - k))
    draw_text(img, e["text"].upper(), "extrabold", size, w_img / 2, h_img * 0.5 - size * 0.6, tracking,
              opacity=ease(dt / 0.25) * out, anchor="c", shadow=0.7)


SCRAMBLE = "ABCDEFGHKMNPRSTXYZ0123456789#%&$"


def draw_glitch(img, text, size, cx, cy, tl, step=0.085, seed=0, opacity=1.0):
    """The title: each letter flickers through other glyphs, then holds; the channels drift apart and close; a
    ghost of the word in the layer's colour slides in from beside it until both sit in the same spot.
    Returns when (in the same clock as tl) the last letter has resolved."""
    w_img, h_img = G["plan"]["size"]
    f = font("extrabold", size)
    xs, width = layout(text, f, size * 0.02)
    asc, desc = f.getmetrics()
    padding = int(size * 0.4)
    h = asc + desc + 2 * padding
    fixed = np.random.default_rng(seed)
    resolve = [0.3 + step * i + float(fixed.uniform(-0.03, 0.03)) for i in range(len(text))]
    done = max(resolve)
    canvas = Image.new("L", (w_img, h), 0)
    d = ImageDraw.Draw(canvas)
    left = cx - width / 2
    for i, c in enumerate(text):
        if tl < resolve[i] - 0.45:
            continue
        if tl < resolve[i]:
            r = np.random.default_rng(seed * 7919 + i * 131 + int(tl * 24))
            ch = SCRAMBLE[int(r.integers(len(SCRAMBLE)))]
            off = (f.getlength(c) - f.getlength(ch)) / 2
            d.text((left + xs[i] + off, padding), ch, font=f, fill=int(255 * r.uniform(0.3, 0.85)))
        else:
            d.text((left + xs[i], padding), c, font=f, fill=255)
    glitch = 1.0 if tl < done else clamp(1 - (tl - done) / 0.25)
    r = np.random.default_rng(seed * 104729 + int(tl * 20))
    layer = np.asarray(canvas, np.float32) / 255
    if glitch > 0:
        layer = layer.copy()
        for _ in range(int(1 + 5 * glitch)):
            ya = int(r.integers(0, h))
            yb = ya + int(r.integers(h // 24 + 1, h // 6 + 2))
            layer[ya:yb] = np.roll(layer[ya:yb], int(r.normal() * 0.02 * w_img * glitch), axis=1)
    split = int(round(glitch * 0.008 * w_img * (0.5 + r.random())))
    y0 = int(cy - h / 2)
    y0 = max(0, min(h_img - h, y0))
    region = img[y0:y0 + h]
    lock = tl - (done + 0.25)                                   # the two layers meet here
    pulse = math.exp(-lock / 0.25) if lock >= 0 else 0.0
    small = canvas.resize((w_img // 4, h // 4), Image.BILINEAR).filter(ImageFilter.GaussianBlur(size * 0.035))
    glow = np.asarray(small.resize((w_img, h), Image.BILINEAR), np.float32) / 255
    region += PHASE * (glow * (0.3 + 0.9 * pulse) * opacity)[:, :, None]
    if lock < 0:
        k = ease(tl / (done + 0.25))
        ghost = np.roll(layer, (int((1 - k) * -0.03 * h_img), int((1 - k) * 0.04 * w_img)), axis=(0, 1))
        region += (PHASE - region) * (ghost * 0.6 * opacity)[:, :, None]
    for ch, shift in ((0, split), (1, 0), (2, -split)):
        a = np.roll(layer, shift, axis=1) if shift else layer
        region[:, :, ch] += (1.0 - region[:, :, ch]) * a * opacity
    return done


def load_frame(index, hidden=False):
    key = (index, hidden)
    frames = G.setdefault("frame_cache", {})
    if key not in frames:
        if len(frames) >= 6:
            frames.pop(next(iter(frames)))
        path = G["dir"] / ("frames_hidden" if hidden else "frames") / f"{index:05d}.png"
        im = Image.open(path).convert("RGB")
        if im.size != tuple(G["plan"]["size"]):
            im = im.resize(tuple(G["plan"]["size"]), Image.LANCZOS)
        frames[key] = np.asarray(im, np.uint8)       # kept as bytes: a worker holds megabytes, not hundreds
    return frames[key].astype(np.float32) * np.float32(1 / 255)


def sample(si, p, rate, hidden=False):
    """A shot's picture at source frame p: blended between frames in slow motion, a few averaged when sped up."""
    s = G["plan"]["shots"][si]
    last = s["frames"] - 1
    p = min(max(p, 0.0), float(last))
    speed = rate / G["plan"]["fps_src"]
    if speed < 0.95:
        i = int(math.floor(p))
        k = p - i
        if k < 0.02 or i >= last:
            return load_frame(s["start"] + i, hidden)
        return load_frame(s["start"] + i, hidden) * (1 - k) + load_frame(s["start"] + i + 1, hidden) * k
    if speed > 1.4 and G["plan"]["fps_src"] >= G["plan"]["fps"]:
        count = min(4, int(round(speed)))
        i = int(round(p))
        return sum(load_frame(s["start"] + min(last, i + j), hidden) for j in range(count)) / count
    return load_frame(s["start"] + int(round(p)), hidden)


def zoomed(img, z, ox=0.0, oy=0.0):
    h, w = img.shape[:2]
    im = Image.fromarray((np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8))
    cx, cy = w / 2, h / 2
    im = im.transform((w, h), Image.AFFINE, (1 / z, 0, cx - (cx + ox) / z, 0, 1 / z, cy - (cy + oy) / z), Image.BILINEAR)
    return np.asarray(im, np.float32) / 255


def grade(x):
    """A film look: an S-curve, cool shadows and warm highlights, a little bloom, a vignette."""
    luma = x @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    x = x + 0.38 * (x * x * (3 - 2 * x) - x)
    shade = (1 - luma) ** 2
    light = luma * luma
    x += shade[:, :, None] * np.array([-0.030, 0.006, 0.034], np.float32)
    x += light[:, :, None] * np.array([0.040, 0.012, -0.036], np.float32)
    grey = x @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    x = grey[:, :, None] + (x - grey[:, :, None]) * 1.08
    h, w = x.shape[:2]
    k = max(1, h // 135)
    small = np.clip(x[::k, ::k] - 0.72, 0, 1) * 1.6
    im = Image.fromarray((np.clip(small, 0, 1) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(h / k / 28))
    x += np.asarray(im.resize((w, h), Image.BILINEAR), np.float32) * (0.5 / 255)
    return x * G["vignette"]


def vignette(w, h, bar):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    r = np.hypot((x - w / 2) / (w / 2), (y - h / 2) / max(1.0, h / 2 - bar))
    return (1 - 0.34 * np.clip(r - 0.35, 0, None) ** 2)[:, :, None].astype(np.float32)


def blurred_background(bg, t, dim):
    """A shot far out of focus and dark, to put a title on."""
    w, h = G["plan"]["size"]
    p, rate = key_pos(bg["keys"], t)
    src = sample(bg["shot"], p, 0.0, bg.get("hidden", False))
    k = 8
    im = Image.fromarray((src[::k, ::k] * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(h / k / 22))
    x = np.asarray(im.resize((w, h), Image.BICUBIC), np.float32) / 255
    return grade(x) * dim * np.array([0.85, 0.97, 1.08], np.float32)


def light_leak(img, u, seed=0, strength=0.4):
    """Warm light passing through the frame, left to right, as u goes 0 to 1."""
    if u <= 0 or u >= 1:
        return img
    h, w = img.shape[:2]
    k = 16
    y, x = np.mgrid[0:h // k, 0:w // k].astype(np.float32)
    x /= w / k
    y /= h / k
    r = np.random.default_rng(seed)
    out = np.zeros((h // k, w // k, 3), np.float32)
    for color, dy, lag in (((1.0, 0.55, 0.18), r.uniform(0.2, 0.5), 0.0), ((1.0, 0.25, 0.42), r.uniform(0.5, 0.8), 0.18)):
        cx = -0.3 + 1.6 * u - lag
        out += np.exp(-(((x - cx) / 0.33) ** 2 + ((y - dy) / 0.55) ** 2))[:, :, None] * np.array(color, np.float32)
    out *= math.sin(math.pi * u) * strength
    im = Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8)).resize((w, h), Image.BILINEAR)
    leak = np.asarray(im, np.float32) / 255
    return 1 - (1 - np.clip(img, 0, 1)) * (1 - leak)


def clip_image(c, t):
    """What a clip shows at time t (which may be a little outside it, for a transition)."""
    plan = G["plan"]
    w, h = plan["size"]
    if c["kind"] == "black":
        img = np.zeros((h, w, 3), np.float32)
        t0, t1 = plan["how"]
        if t0 <= t < t1:
            draw_glitch(img, "HOW?", h * 0.17, w / 2, h / 2, t - t0, step=0.06, seed=3, opacity=clamp((t1 - t) / 0.12))
        return img
    if c["kind"] == "title":
        tl = t - c["t0"]
        img = blurred_background(c["bg"], t, 0.30 * ease(tl / 0.8 + 0.3))
        img = light_leak(img, tl / (c["t1"] - c["t0"]) * 0.9 + 0.05, 5, 0.25) if tl > 0 else img
        done = draw_glitch(img, TITLE, h * 0.19, w / 2, h * 0.455, tl, seed=1)
        k = ease_out((tl - done - 0.25) / 0.7)
        if k > 0:
            size = h / 34
            half = k * 0.19 * w
            y = int(h * 0.575)
            img[y:y + max(2, h // 400), int(w / 2 - half):int(w / 2 + half)] += PHASE * 0.9 * k
            draw_text(img, SUBTITLE.upper(), "medium", size, w / 2, h * 0.60, size * (0.22 + 0.35 * (1 - k)),
                      opacity=k, anchor="c", shadow=0.5)
        return img
    if c["kind"] == "end":
        tl = t - c["t0"]
        img = blurred_background(c["bg"], t, 0.26) if c.get("bg") else np.zeros((h, w, 3), np.float32)
        img = light_leak(img, tl / 5.0, 9, 0.25)
        done = draw_glitch(img, TITLE, h * 0.16, w / 2, h * 0.36, tl - 0.3, step=0.05, seed=2)
        done += 0.3

        def line(text, weight, size, y, tracking, at, color=WHITE, opacity=1.0):
            k = ease_out((tl - at) / 0.7)
            if k > 0:
                draw_text(img, text, weight, size, w / 2, y + (1 - k) * h * 0.012, size * (tracking + 0.3 * (1 - k)),
                          color=color, opacity=k * opacity, anchor="c", shadow=0.5)
        line(END_TAGLINE.upper(), "medium", h / 36, h * 0.485, 0.2, done + 0.3)
        line(END_MOD.upper(), "semibold", h / 30, h * 0.575, 0.16, done + 0.8, PHASE)
        if c.get("url"):
            line(c["url"], "medium", h / 38, h * 0.655, 0.06, done + 1.2, opacity=0.9)
        line(END_DISCLAIMER, "medium", h / 44, h - G["bar"] - h * 0.07, 0.02, done + 0.6, opacity=0.7)
        return img
    # a shot
    p, rate = key_pos(c["keys"], t)
    img = sample(c["shot"], p, rate)
    wipe = c.get("wipe")
    line_x = None
    if wipe and t > wipe[0]:
        u = ease((t - wipe[0]) / (wipe[1] - wipe[0]))
        tilt = -0.2
        line_x = (-0.14 + 1.28 * u) * w + (np.arange(h, dtype=np.float32)[:, None] - h / 2) * tilt
        dist = np.arange(w, dtype=np.float32)[None, :] - line_x          # past the line: still the visible world
        mask = np.clip(0.5 - dist / (0.004 * w), 0, 1)[:, :, None]
        img = img + (sample(c["shot"], p, rate, True) - img) * mask
    z = c.get("zoom", 1.0) + c.get("push", 0.0) * clamp((t - c["t0"]) / (c["t1"] - c["t0"]))
    ox = oy = 0.0
    for tp in c.get("punch", ()):
        if t >= tp:
            z += 0.045 * math.exp(-(t - tp) / 0.22)
            ox += 0.004 * w * math.exp(-(t - tp) / 0.16) * math.sin(2 * math.pi * 17 * (t - tp))
            oy += 0.004 * w * math.exp(-(t - tp) / 0.16) * math.cos(2 * math.pi * 13 * (t - tp))
    if z > 1.002:
        img = zoomed(img, z, ox, oy)
    img = grade(img)
    if line_x is not None and (t - wipe[0]) < (wipe[1] - wipe[0]) + 0.3:
        ad = np.abs(dist)
        rows = np.arange(h, dtype=np.float32)[:, None]
        scan = 0.75 + 0.25 * np.sin(rows * (math.pi / max(2.0, h / 270)) + t * 30)
        glow = np.exp(-(dist / (0.0016 * w)) ** 2) * 1.2 + np.exp(-ad / (0.02 * w)) * 0.45
        glow += np.where(dist < 0, np.exp(dist / (0.10 * w)) * 0.22 * scan, 0)
        img += glow[:, :, None] * PHASE
        img[:, :, :] += (np.exp(-(dist / (0.0016 * w)) ** 2) * 0.5)[:, :, None]
    return img


def whip(img, amount, direction):
    """A camera whipping sideways: smeared and shoved along x."""
    from scipy import ndimage
    w = img.shape[1]
    size = int(amount * w * 0.3)
    if size > 1:
        img = ndimage.uniform_filter1d(img, size, axis=1, mode="wrap")
        img = ndimage.uniform_filter1d(img, max(2, size // 2), axis=1, mode="wrap")
    return np.roll(img, int(direction * amount * w * 0.5), axis=1)


def letterbox(t):
    """The height of each black bar: 2.39:1, opening for the reveal."""
    plan = G["plan"]
    w, h = plan["size"]
    keys = plan["bars"]
    k = keys[-1][1]
    for (ta, ka), (tb, kb) in zip(keys, keys[1:]):
        if t < tb:
            k = ka + (kb - ka) * ease((t - ta) / max(1e-9, tb - ta))
            break
    return max(0.0, (h - w / 2.39) / 2) * k


WHIP, DISSOLVE = 0.13, 0.22       # half of how long each lasts, in seconds


def render_frame(f):
    plan = G["plan"]
    w, h = plan["size"]
    clips = plan["clips"]
    t = f / plan["fps"] + 1e-6
    i = max(k for k, c in enumerate(clips) if c["t0"] <= t)
    c = clips[i]
    bar = letterbox(t)
    G["bar"] = bar
    nxt = clips[i + 1] if i + 1 < len(clips) else None
    since = t - c["t0"]
    until = nxt["t0"] - t if nxt else 1e9
    if nxt and nxt["trans"] == "dissolve" and until < DISSOLVE:
        k = 0.5 * (1 - until / DISSOLVE)
        img = clip_image(c, t) * (1 - k) + clip_image(nxt, t) * k
        img = light_leak(img, 0.5 - until / (4 * DISSOLVE), i + 1)
    elif c["trans"] == "dissolve" and since < DISSOLVE and i > 0:
        k = 0.5 + 0.5 * since / DISSOLVE
        img = clip_image(clips[i - 1], t) * (1 - k) + clip_image(c, t) * k
        img = light_leak(img, 0.5 + since / (4 * DISSOLVE), i)
    elif nxt and nxt["trans"] == "whip" and until < WHIP:
        img = whip(clip_image(c, t), (1 - until / WHIP) ** 2, -1)
    elif c["trans"] == "whip" and since < WHIP:
        img = whip(clip_image(c, t), (1 - since / WHIP) ** 2, 1)
    else:
        img = clip_image(c, t)
        if c["trans"] == "dissolve" and i > 0:
            img = light_leak(img, 0.5 + since / (4 * DISSOLVE), i)
        elif nxt and nxt["trans"] == "dissolve":
            img = light_leak(img, 0.5 - until / (4 * DISSOLVE), i + 1)
    if c["trans"] == "flash" and since < 0.4:
        img += c.get("flash", 0.5) * math.exp(-since / 0.07) * np.array([1.0, 0.96, 0.88], np.float32)
    if nxt and nxt["trans"] == "flash" and until < 0.05:
        img += nxt.get("flash", 0.5) * 0.45 * (1 - until / 0.05) ** 2
    for e in plan["texts"]:
        if e["t0"] <= t < e["t1"]:
            (draw_caption(img, e, t, bar) if e["style"] == "caption" else draw_big(img, e, t))
    # grain, finer than a block's texture, and the same on every clip
    rng = np.random.default_rng(f)
    k = max(1, round(h / 720))
    grain = rng.standard_normal((h // k + 1, w // k + 1), np.float32) * 0.011
    if k > 1:
        grain = np.repeat(np.repeat(grain, k, 0), k, 1)
    img += grain[:h, :w, None]
    fade = min(1.0, ease(t / 0.5 + 0.05), ease((plan["duration"] - t) / 1.2))
    if fade < 1:
        img *= fade
    edge = int(round(bar))
    if edge > 0:
        img[:edge] = 0
        img[h - edge:] = 0
    return (np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8)


def setup(out_dir, plan, fonts):
    G.clear()
    G.update(plan=plan, dir=pathlib.Path(out_dir), fonts=fonts, bar=0.0)
    w, h = plan["size"]
    G["vignette"] = vignette(w, h, max(0.0, (h - w / 2.39) / 2))


def render_chunk(job):
    index, f0, f1, path = job
    plan = G["plan"]
    w, h = plan["size"]
    fps = plan["fps"]
    rate = f"{fps:g}" if abs(fps - round(fps)) > 1e-6 else str(int(round(fps)))
    proc = subprocess.Popen(
        [ffmpeg(), "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", rate,
         "-i", "-", "-vf", "scale=out_color_matrix=bt709:out_range=tv", "-c:v", "libx264", "-preset", "medium",
         "-crf", "18", "-x264-params", "rc-lookahead=20", "-pix_fmt", "yuv420p", "-threads", "2", "-colorspace", "bt709", "-color_primaries", "bt709",
         "-color_trc", "bt709", "-f", "matroska", path], stdin=subprocess.PIPE)
    for f in range(f0, f1):
        proc.stdin.write(render_frame(f).tobytes())
    proc.stdin.close()
    if proc.wait() != 0:
        raise RuntimeError(f"ffmpeg failed on chunk {index}")
    return index


def contact_sheet(out_dir, plan):
    """Sixteen moments of the edit on one page: titles, captions, transitions, the wipe."""
    sec, clips = plan["sections"], plan["clips"]
    b = plan["beat"]
    moments = [("hook: line", 1.2 * b), ("hook: action", 4 * b + 0.06), ("hook: question", 6.5 * b),
               ("title: resolving", sec["title"][0] + 0.75), ("title", sec["title"][0] + 3.0)]
    shots = [c for c in clips if c["kind"] == "shot" and c["t0"] >= sec["montage1"][0]]
    whips = [c for c in shots if c["trans"] == "whip"]
    fades = [c for c in shots if c["trans"] == "dissolve"]
    if whips:
        moments.append(("whip pan", whips[0]["t0"] + 0.04))
    moments.append(("caption coming in", shots[0]["t0"] + 0.3))
    moments.append(("caption", shots[0]["t0"] + 1.5))
    if fades:
        moments.append(("dissolve, light leak", fades[0]["t0"]))
    moments.append(("montage, short shot", sec["montage2"][0] + 1.2))
    moments.append(("flurry", sec["recap"][0] + 0.3 * b))
    moments.append(("silence", plan["how"][0] + 1.0))
    wipes = plan["events"]["wipes"]
    r0 = sec["reveal"][0]
    moments.append(("reveal: bars opening", r0 + 0.5))
    if wipes:
        for label, u in (("wipe, a third", 0.38), ("wipe, two thirds", 0.6)):
            moments.append((label, wipes[0][0] + u * (wipes[0][1] - wipes[0][0])))
        moments.append(("hidden layer", wipes[0][1] + 0.6))
    moments.append(("end card", sec["end"][0] + 4.5))
    w, h = plan["size"]
    tw = 640
    th = round(tw * h / w)
    cols = 4
    rows = math.ceil(len(moments) / cols)
    sheet = Image.new("RGB", (cols * tw, rows * (th + 30)), (16, 16, 20))
    d = ImageDraw.Draw(sheet)
    f = ImageFont.truetype(G["fonts"]["medium"], 18)
    for k, (label, t) in enumerate(moments):
        frame = Image.fromarray(render_frame(int(round(t * plan["fps"])))).resize((tw, th), Image.LANCZOS)
        x, y = (k % cols) * tw, (k // cols) * (th + 30)
        sheet.paste(frame, (x, y))
        d.text((x + 8, y + th + 4), f"{t:6.2f} s  {label}", font=f, fill=(225, 225, 230))
    sheet.save(pathlib.Path(out_dir) / "edit-contact.png")


def edit(out_dir, timeline=None, music=None, bpm=None, fps=None, jobs=None, sfx=False, url=DEFAULT_URL,
         contact_only=False):
    """Edit the frames in out_dir into out_dir/phasecraft-trailer.mp4. `timeline` stands in for a missing
    timeline.json; `music` is a track to use instead of the one made here."""
    started = time.time()
    try:
        os.nice(max(0, NICE - os.nice(0)))
    except OSError:
        pass
    out_dir = pathlib.Path(out_dir)
    if (out_dir / "timeline.json").exists():
        timeline = json.loads((out_dir / "timeline.json").read_text())
    if timeline is None:
        sys.exit(f"no {out_dir}/timeline.json")
    for s in timeline["shots"]:
        if s.get("hidden") and not (out_dir / "frames_hidden" / f"{s['start']:05d}.png").exists():
            print(f"trailer_edit: shot {s.get('name')} has no frames_hidden; shown without them", file=sys.stderr)
            s["hidden"] = False
    work = out_dir / "edit-work"
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    track = offset = None
    if music:
        track = read_audio(music, work)
        found, offset = detect_beats(track)
        if bpm:
            print(f"music: {bpm:g} BPM given (heard {found:.1f}), first beat at {offset:.2f} s")
        else:
            bpm = round(found * 2) / 2
            print(f"music: heard {found:.1f} BPM, cutting at {bpm:g}; first beat at {offset:.2f} s")
    plan = build_plan(timeline, bpm or 120.0, fps, url)
    setup(out_dir, plan, ensure_fonts())
    print(f"edit: {plan['duration']:.1f} s at {plan['fps']:g} fps, {plan['n_frames']} frames, "
          f"{len(plan['clips'])} clips, a beat every {plan['beat']:.3f} s")
    for name, (t0, t1) in plan["sections"].items():
        print(f"  {name:9s} {t0:6.2f} - {t1:6.2f} s")
    contact_sheet(out_dir, plan)
    if contact_only:
        return plan
    # the sound
    if track is None:
        audio, reduction = master(synth_music(plan), plan)
        wav = out_dir / "music.wav"
    else:
        i = int(offset * SR)
        n = int(plan["duration"] * SR)
        audio = np.zeros((n, 2), np.float32)
        part = track[i:i + n]
        audio[:len(part)] = part
        if len(part) < n:
            print(f"trailer_edit: the track ends {(n - len(part)) / SR:.1f} s before the picture", file=sys.stderr)
        if sfx:
            fx = synth_music(plan, sfx_only=True)[:n]
            level = float(np.sqrt(np.mean(audio ** 2))) or 0.1
            audio[:len(fx)] += fx * (level / 0.19) * 0.6
        audio, reduction = master(audio, plan, loud=audio)
        wav = out_dir / "audio.wav"
    write_wav(wav, audio)
    peak = float(np.abs(audio).max())
    print(f"sound: {wav.name}, peak {20 * math.log10(peak):.1f} dBFS, limiter at most {reduction:.1f} dB")
    for name, (t0, t1) in plan["sections"].items():
        part = audio[int(t0 * SR):int(t1 * SR)]
        if len(part):
            print(f"  {name:9s} RMS {20 * math.log10(max(1e-6, float(np.sqrt(np.mean(part ** 2))))):6.1f} dBFS")
    rows = sync_report(plan, audio)
    late = [abs(r[3]) for r in rows]
    print(f"sync: {len(rows)} cuts and actions; the sound's attack is within {max(late) * 1000:.0f} ms of each "
          f"({sum(1 for r in rows if r[4])} of them stand out of the mix)")
    (out_dir / "edit-sync.txt").write_text("".join(
        f"{label:6s} {t:7.3f} s  frame {frame:5d}  sound {off * 1000:+5.0f} ms  {'accent' if strong else 'in the mix'}\n"
        for label, t, frame, off, strong in rows))
    # the picture, in pieces, on every core
    total = plan["n_frames"]
    jobs = jobs or min(DEFAULT_JOBS, multiprocessing.cpu_count())
    size = max(8, min(int(plan["fps"] * 2), math.ceil(total / jobs)))
    chunks = [(k, f0, min(total, f0 + size), str(work / f"{k:04d}.mkv")) for k, f0 in enumerate(range(0, total, size))]
    with multiprocessing.get_context("fork").Pool(jobs) as pool:
        for done, _ in enumerate(pool.imap_unordered(render_chunk, chunks), 1):
            print(f"\rpicture: {done}/{len(chunks)} pieces", end="", flush=True)
    print()
    (work / "list.txt").write_text("".join(f"file '{pathlib.Path(c[3]).name}'\n" for c in chunks))
    mp4 = out_dir / "phasecraft-trailer.mp4"
    subprocess.run([ffmpeg(), "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", str(work / "list.txt"),
                    "-i", str(wav), "-c:v", "copy", "-c:a", "aac", "-b:a", "256k", "-shortest",
                    "-movflags", "+faststart", str(mp4)], check=True)
    shutil.rmtree(work)
    print(f"wrote {mp4}: {plan['duration']:.1f} s, in {time.time() - started:.0f} s")
    return plan


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("out_dir", nargs="?", default=str(ROOT / "build/trailer"))
    ap.add_argument("--music", help="a track to use instead of the generated one (any format ffmpeg reads)")
    ap.add_argument("--bpm", type=float, help="the tempo to cut on (default 120, or detected from --music)")
    ap.add_argument("--sfx", action="store_true", help="with --music: add the generated impacts and risers")
    ap.add_argument("--fps", type=float, help="output frame rate (default: the timeline's)")
    ap.add_argument("--jobs", type=int, default=DEFAULT_JOBS, help="worker processes (default %(default)s)")
    ap.add_argument("--url", default=DEFAULT_URL, help="the address on the end card ('' for none)")
    ap.add_argument("--contact-only", action="store_true", help="only write edit-contact.png")
    a = ap.parse_args()
    edit(a.out_dir, music=a.music, bpm=a.bpm, fps=a.fps, jobs=a.jobs, sfx=a.sfx, url=a.url,
         contact_only=a.contact_only)


if __name__ == "__main__":
    main()
