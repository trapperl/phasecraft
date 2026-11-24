#!/usr/bin/env python3
"""Static check of a hidden-layer contraption layout: which signals reach each piston.

Usage:
    python3 tools/redstone_check.py src/gametest/resources/contraptions/<name>.txt

A layout file (see contraptions/README.md) lists blocks per layer, and every
hidden redstone part and piston carries an @signal name: the signal it is meant
to carry or be driven by. This tool follows vanilla's power rules, roughly, and
reports every place a part or piston receives a signal other than its own:

- a wire is powered by connected wires, by diodes and torches pointing into it,
  by sources next to it, and by strongly powered blocks next to it (above and
  below included);
- a block is strongly powered by a wire on top of it or pointing into it, a
  repeater pointing into it, or a torch under it;
- a piston is powered from any side but its front, and also (quasi-
  connectivity) by anything powering the cell above it, from any side but
  below;
- a phase shifter is powered from any side, like a copper bulb; a torch
  goes out only when its block has a direct signal (a wire on or pointing
  into it, a repeater pointing into it, a torch under it);
- a button or pressure plate is a source like a lever, and strongly powers
  the block it hangs on or stands on; a copper bulb is powered from any side; a comparator is a diode like a
  repeater, and one reading a copper bulb carries the bulb's signal.

It is a model, not Minecraft: GameTests decide. It exists to catch signals
leaking into the wrong part before a test run, which in tight 3D layouts is
most of the work. Nested signals are fine (a piston that is also powered by a
signal that is only ever on while its own is on): list them with
"allow <signal> <also-allowed>..." lines.
"""

import sys
from collections import defaultdict

DIRS = {
    "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0),
    "up": (0, 1, 0), "down": (0, -1, 0),
}
HORIZONTAL = ["north", "south", "west", "east"]
OPPOSITE = {"north": "south", "south": "north", "west": "east", "east": "west", "up": "down", "down": "up"}


def add(p, d):
    return (p[0] + DIRS[d][0], p[1] + DIRS[d][1], p[2] + DIRS[d][2])


def parse_state(text):
    name, _, props = text.partition("[")
    return name, dict(kv.split("=") for kv in props.rstrip("]").split(",") if kv)


def load(path):
    """Hidden parts by position, plus allowed signal pairs. Visible lines and fills are skipped."""
    parts, allow, inputs = {}, defaultdict(set), {}
    for raw in open(path):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        words = line.split()
        if words[0] == "allow":
            allow[words[1]].update(words[2:])
            continue
        if words[0] != "hidden":
            continue
        pos = tuple(int(v) for v in words[1:4])
        name, props = parse_state(words[4])
        tag = next((w[1:] for w in words[5:] if w.startswith("@")), None)
        signal, _, source = (tag or "").partition("<")
        parts[pos] = (name.split(":")[-1], props, signal or None)
        if source:
            inputs[pos] = source
    return parts, allow, inputs


class Layout:
    def __init__(self, parts):
        self.parts = parts

    def kind(self, p):
        part = self.parts.get(p)
        return part[0] if part else "air"

    def is_conductor(self, p):
        k = self.kind(p)
        return k not in ("air", "redstone_wire", "repeater", "comparator", "redstone_torch", "redstone_wall_torch",
                         "phase_port", "lever", "glass") and "piston" not in k and "button" not in k and "bulb" not in k \
            and "pressure_plate" not in k

    def wire_connects(self, p, d):
        """Whether the wire at p connects towards horizontal direction d (same level, up or down a step)."""
        q = add(p, d)
        k = self.kind(q)
        if k == "redstone_wire":
            return True
        if k == "repeater":
            facing = self.parts[q][1].get("facing")
            return facing in (d, OPPOSITE[d])
        if k in ("redstone_torch", "redstone_wall_torch", "phase_port", "lever", "comparator") or "button" in k \
                or "pressure_plate" in k:
            return True
        if self.kind(add(q, "up")) == "redstone_wire" and not self.is_conductor(add(p, "up")):
            return True
        if self.kind(add(q, "down")) == "redstone_wire" and not self.is_conductor(q):
            return True
        return False

    def wire_points(self, p):
        """Horizontal directions a wire powers: its connections; one connection points both ways; none: all
        for a cross (sides set, as a player places one), nothing for a dot (no sides set, as /setblock places one)."""
        conn = [d for d in HORIZONTAL if self.wire_connects(p, d)]
        if not conn:
            props = self.parts[p][1]
            return set(HORIZONTAL) if any(props.get(d, "none") != "none" for d in HORIZONTAL) else set()
        if len(conn) == 1:
            return {conn[0], OPPOSITE[conn[0]]}
        return set(conn)

    def emits(self, src, d):
        """Signals the part at src sends into the cell src - d (i.e. towards direction OPPOSITE[d] from src)."""
        part = self.parts.get(src)
        if not part:
            return set()
        k, props, signal = part
        # d is the direction from the receiving cell to src, as in vanilla's hasSignal(pos.relative(d), d).
        if k == "redstone_wire":
            if d == "down":
                return set()
            if d == "up" or OPPOSITE[d] in self.wire_points(src):
                return {signal}
            return set()
        if k in ("repeater", "comparator"):
            return {signal} if props.get("facing") == d else set()
        if k == "redstone_torch":
            return set() if d == "up" else {signal}
        if k == "redstone_wall_torch":
            return set() if props.get("facing") == d else {signal}
        if k in ("phase_port", "lever") or "button" in k or "pressure_plate" in k:
            return {signal}
        if self.is_conductor(src):
            return self.strong(src)
        return set()

    def strong(self, block):
        """Signals strongly powering a conductor."""
        out = set()
        for d in DIRS:
            src = add(block, d)
            part = self.parts.get(src)
            if not part:
                continue
            k, props, signal = part
            toward = OPPOSITE[d]  # direction from src to block
            if k == "redstone_wire" and (toward == "down" or toward in self.wire_points(src)):
                out.add(signal)
            elif k in ("repeater", "comparator") and props.get("facing") == d:
                out.add(signal)
            elif "button" in k and props.get("face") == "wall" and toward == OPPOSITE[props.get("facing")]:
                out.add(signal)
            elif "pressure_plate" in k and toward == "down":
                out.add(signal)
            elif "button" in k and props.get("face") in ("floor", "ceiling") and toward == {"floor": "down", "ceiling": "up"}[props["face"]]:
                out.add(signal)
            elif k in ("redstone_torch", "redstone_wall_torch") and toward == "up":
                out.add(signal)
        return out

    def into(self, cell, exclude=()):
        """Signals reaching cell from its neighbours (excluding directions in exclude)."""
        out = set()
        for d in DIRS:
            if d not in exclude:
                out |= self.emits(add(cell, d), d)
        return out

    def wire_inputs(self, p):
        """Signals a wire picks up: from neighbours other than wires, and from connected wires."""
        out = set()
        for d in DIRS:
            q = add(p, d)
            if self.kind(q) == "redstone_wire":
                continue
            out |= self.emits(q, d) if not self.is_conductor(q) else self.strong(q)
        for d in HORIZONTAL:
            if self.wire_connects(p, d):
                for q in (add(p, d), add(add(p, d), "up"), add(add(p, d), "down")):
                    if self.kind(q) == "redstone_wire":
                        out.add(self.parts[q][2])
        return out

    def piston_inputs(self, p):
        facing = self.parts[p][1]["facing"]
        direct = self.into(p, exclude=(facing,))
        quasi = self.into(add(p, "up"), exclude=("down",))
        return direct, quasi


def check(path):
    parts, allow, inputs = load(path)
    layout = Layout(parts)
    problems = 0
    for p, (k, props, signal) in sorted(parts.items()):
        if signal is None and (k in ("redstone_wire", "repeater", "comparator") or "piston" in k or "bulb" in k):
            print(f"no @signal: {k} at {p}")
            problems += 1
            continue
        ok = {signal, None} | allow.get(signal, set())
        if "piston" in k:
            direct, quasi = layout.piston_inputs(p)
            bad = (direct | quasi) - ok
            got = (direct | quasi) - {None}
            if signal not in got:
                print(f"UNPOWERED  {k} at {p}: expects @{signal}, gets nothing of it")
                problems += 1
            if bad:
                print(f"LEAK       {k} at {p} (@{signal}): also powered by {sorted(bad)}"
                      f" (direct {sorted(direct - {None})}, quasi {sorted(quasi - {None})})")
                problems += 1
        elif k == "redstone_wire":
            bad = layout.wire_inputs(p) - ok
            if bad:
                print(f"LEAK       wire at {p} (@{signal}): picks up {sorted(bad)}")
                problems += 1
        elif "bulb" in k or k == "phase_shifter":
            got = layout.into(p)
            bad = got - ok
            if signal not in got:
                print(f"UNPOWERED  {k} at {p}: expects @{signal}, gets nothing of it")
                problems += 1
            if bad:
                print(f"LEAK       {k} at {p} (@{signal}): also powered by {sorted(bad)}")
                problems += 1
        elif k in ("repeater", "comparator"):
            facing = props["facing"]
            src = add(p, facing)
            got = layout.emits(src, facing) if not layout.is_conductor(src) else layout.strong(src)
            if layout.kind(src) == "redstone_wire" or (k == "comparator" and "bulb" in layout.kind(src)):
                got = {parts[src][2]}
            if k == "comparator":
                for side in HORIZONTAL:
                    if side not in (facing, OPPOSITE[facing]):
                        q = add(p, side)
                        side_in = layout.emits(q, side) if not layout.is_conductor(q) else layout.strong(q)
                        if layout.kind(q) == "redstone_wire":
                            side_in = {parts[q][2]}
                        if side_in - {None}:
                            print(f"LEAK       comparator at {p} (@{signal}): side input {q} carries {sorted(side_in - {None})}")
                            problems += 1
            want = inputs.get(p, signal)
            bad = got - {want, None} - allow.get(want, set())
            if bad:
                print(f"LEAK       repeater at {p} (@{signal}<{want}): input carries {sorted(bad)}")
                problems += 1
            if want not in got:
                print(f"UNPOWERED  repeater at {p} (@{signal}): nothing at its input {src}")
                problems += 1
        elif k in ("redstone_torch", "redstone_wall_torch"):
            held = add(p, OPPOSITE[props["facing"]]) if k == "redstone_wall_torch" else add(p, "down")
            # vanilla: a torch goes out when its block has a direct signal (SignalGetter.getSignal of a
            # conductor is getDirectSignalTo): a torch or a powered block beside that block does not count
            got = layout.strong(held) if layout.is_conductor(held) else set()
            want = inputs.get(p)
            bad = got - {want, None} - allow.get(want, set())
            if bad or want not in got:
                print(f"TORCH      at {p} (@{signal}<{want}): its block gets {sorted(got - {None})}")
                problems += 1
    print(f"{len(parts)} hidden parts, {problems} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(check(sys.argv[1]))
