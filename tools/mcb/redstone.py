"""A redstone simulator for one layer of a Scene, following vanilla's rules (Minecraft 26.3 sources), tick by tick.

What it knows: wire (strength, how it connects, up and down steps), repeaters (delay, locking, the order vanilla
ticks them in), torches (standing and on walls), levers, redstone blocks, phase ports (inputs: set them on or off)
and phase shifters (outputs: when each was powered). Blocks are redstone conductors unless they are see-through or
redstone parts (see `conductor`). Not yet: comparators, observers, pistons, buttons, pressure plates, copper bulbs,
torch burnout.

    sim = Sim(scene)                     # the "hidden" layer by default
    sim.run(10)                          # let it settle
    sim.set_input(port_pos, True)        # a phase port (or a lever) on
    sim.run(100)
    sim.events                           # [(tick, pos, "shifter", True), ...]

Directions follow vanilla's signal queries: emit(src, d) is what the part at src sends to the cell at src - d, d being
the direction from that cell to src (SignalGetter.getSignal(pos.relative(d), d)).
"""

import heapq

from .structure import parse_state

DIRS = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}
HORIZONTAL = ("north", "south", "west", "east")
OPPOSITE = {"north": "south", "south": "north", "west": "east", "east": "west", "up": "down", "down": "up"}
SIDES = {"north": ("east", "west"), "south": ("east", "west"), "east": ("north", "south"), "west": ("north", "south")}
# vanilla TickPriority
EXTREMELY_HIGH, VERY_HIGH, HIGH, NORMAL = -3, -2, -1, 0

NOT_CONDUCTORS = ("air", "glass", "pane", "redstone_wire", "repeater", "comparator", "torch", "lever", "button",
                  "pressure_plate", "piston", "slab", "stairs", "leaves", "ice", "fence", "wall", "door", "trapdoor",
                  "lantern", "chain", "water", "lava", "phase_port", "redstone_block", "glowstone", "sea_lantern",
                  "observer", "hopper", "carpet", "rail", "sign", "ladder", "bars", "scaffolding", "barrier", "light",
                  "tripwire", "flower", "sapling", "grass", "fern", "snow", "cactus", "bulb", "daylight_detector")


def add(p, d):
    v = DIRS[d]
    return (p[0] + v[0], p[1] + v[1], p[2] + v[2])


def conductor(name):
    base = name.split(":")[-1]
    if base in ("redstone_lamp", "target", "note_block", "dispenser", "dropper", "phase_shifter", "phase_block"):
        return True
    return not any(n in base for n in NOT_CONDUCTORS)


class Sim:
    def __init__(self, scene, layer="hidden"):
        self.parts = {}
        for pos, (state, _) in scene.layers[layer].items():
            name, props = parse_state(state)
            base = name.split(":")[-1]
            if base in ("air", "cave_air", "void_air"):
                continue
            self.parts[pos] = [base, dict(props)]
        self.tick = 0
        self.queue = []          # (time, priority, seq, pos)
        self.scheduled = set()
        self.seq = 0
        self.events = []         # (tick, pos, kind, on)
        self.wires_signal = True
        self.conductive = {p: conductor(v[0]) for p, v in self.parts.items()}
        self.popped = self.drop_unsupported()
        self.wire_power = {p: int(v[1].get("power", 0)) for p, v in self.parts.items() if v[0] == "redstone_wire"}
        self.react()

    def adopt(self, scene, layer="hidden"):
        """Takes the states of the parts (powered, lit, wire strength) from a scene, as exported from the server
        once it has settled; nothing is left scheduled."""
        for p, (state, _) in scene.layers[layer].items():
            if p in self.parts:
                props = parse_state(state)[1]
                for key in ("powered", "lit"):
                    if key in props:
                        self.parts[p][1][key] = props[key]
                if self.parts[p][0] == "redstone_wire":
                    self.wire_power[p] = int(props.get("power", 0))
        self.queue, self.scheduled = [], set()
        self.react()

    # --- what can stand where ---
    def sturdy(self, p):
        """Whether the block at p can hold a wire, repeater, torch or lever (a full block)."""
        k = self.kind(p)
        return self.is_conductor(p) or k in ("glass", "tinted_glass", "redstone_block", "glowstone", "sea_lantern",
                                             "observer", "phase_port", "barrier") or k.endswith("_glass") and "pane" not in k

    def support(self, p):
        k = self.kind(p)
        if k in ("redstone_wire", "repeater", "comparator", "redstone_torch"):
            return add(p, "down")
        if k == "redstone_wall_torch":
            return add(p, OPPOSITE[self.prop(p, "facing")])
        if k == "lever" or "button" in k:
            face = self.prop(p, "face", "wall")
            return add(p, {"floor": "down", "ceiling": "up"}.get(face) or OPPOSITE[self.prop(p, "facing")])
        return None

    def drop_unsupported(self):
        """Parts with nothing to stand or hang on break, as they do when a layout is placed. Returns where."""
        gone = []
        while True:
            lost = [p for p in self.parts if self.support(p) and not self.sturdy(self.support(p))]
            if not lost:
                return gone
            for p in lost:
                del self.parts[p]
                self.conductive.pop(p, None)
            gone += lost

    # --- what is where ---
    def kind(self, p):
        part = self.parts.get(p)
        return part[0] if part else "air"

    def prop(self, p, key, default=None):
        return self.parts[p][1].get(key, default)

    def is_on(self, p, key):
        """A boolean property; a torch is lit unless the state says otherwise, as in vanilla's default state."""
        default = "true" if key == "lit" else "false"
        return self.parts[p][1].get(key, default) == "true"

    def is_conductor(self, p):
        return self.conductive.get(p, False)

    # --- wires ---
    def wire_connects(self, p, d):
        """Whether the wire at p connects towards horizontal d (RedStoneWireBlock.getConnectingSide)."""
        q = add(p, d)
        k = self.kind(q)
        if k == "redstone_wire":
            return True
        if k in ("repeater", "comparator"):
            return self.prop(q, "facing") in (d, OPPOSITE[d])
        if k in ("redstone_torch", "redstone_wall_torch", "lever", "redstone_block", "phase_port", "observer") \
                or "button" in k or "pressure_plate" in k:
            return k != "observer" or self.prop(q, "facing") == d
        if self.kind(add(q, "up")) == "redstone_wire" and not self.is_conductor(add(p, "up")):
            return True
        if self.kind(add(q, "down")) == "redstone_wire" and not self.is_conductor(q):
            return True
        return False

    def wire_points(self, p):
        conn = [d for d in HORIZONTAL if self.wire_connects(p, d)]
        if not conn:
            props = self.parts[p][1]
            dot = all(props.get(d, "none") == "none" for d in HORIZONTAL)
            return set() if dot else set(HORIZONTAL)
        if len(conn) == 1:
            return {conn[0], OPPOSITE[conn[0]]}
        return set(conn)

    # --- signals ---
    def emit(self, src, d):
        """What the part at src sends towards src - d (weak or strong)."""
        k = self.kind(src)
        if k == "air":
            return 0
        if k == "redstone_wire":
            if not self.wires_signal or d == "down":
                return 0
            power = self.wire_power.get(src, 0)
            if power == 0:
                return 0
            return power if d == "up" or OPPOSITE[d] in self.wire_points(src) else 0
        if k in ("repeater", "comparator"):
            return 15 if self.is_on(src, "powered") and self.prop(src, "facing") == d else 0
        if k == "redstone_torch":
            return 15 if self.is_on(src, "lit") and d != "up" else 0
        if k == "redstone_wall_torch":
            return 15 if self.is_on(src, "lit") and self.prop(src, "facing") != d else 0
        if k == "lever":
            return 15 if self.is_on(src, "powered") else 0
        if k == "redstone_block":
            return 15
        if k == "phase_port":
            return 15 if self.parts[src][1].get("_on") else 0
        return 0

    def direct(self, src, d):
        """The strong signal the part at src sends into the block at src - d."""
        k = self.kind(src)
        if k == "redstone_wire":
            return self.emit(src, d)
        if k in ("repeater", "comparator"):
            return self.emit(src, d)
        if k in ("redstone_torch", "redstone_wall_torch"):
            return self.emit(src, d) if d == "down" else 0
        if k == "lever":
            face = self.prop(src, "face", "wall")
            connected = {"floor": "up", "ceiling": "down"}.get(face, self.prop(src, "facing"))
            return 15 if self.is_on(src, "powered") and d == connected else 0
        return 0

    def direct_to(self, p):
        return max(self.direct(add(p, d), d) for d in DIRS)

    def get_signal(self, q, d):
        """SignalGetter.getSignal(q, d): a conductor passes on only the strong signals into it."""
        own = self.emit(q, d)
        return max(own, self.direct_to(q)) if self.is_conductor(q) else own

    def neighbor_signal(self, p):
        return max(self.get_signal(add(p, d), d) for d in DIRS)

    def wire_targets(self):
        """Every wire's strength: the best signal from its non-wire neighbours, or a connected wire's minus one."""
        self.wires_signal = False
        base = {w: self.neighbor_signal(w) for w in self.wire_power}
        self.wires_signal = True
        links = {}
        for w in self.wire_power:
            out = []
            for d in HORIZONTAL:
                q = add(w, d)
                out.append(q)
                if self.is_conductor(q) and not self.is_conductor(add(w, "up")):
                    out.append(add(q, "up"))
                elif not self.is_conductor(q):
                    out.append(add(q, "down"))
            links[w] = [q for q in out if q in self.wire_power]
        power = dict(base)
        heap = [(-v, w) for w, v in base.items() if v > 0]
        heapq.heapify(heap)
        while heap:
            v, w = heapq.heappop(heap)
            v = -v
            if v < power[w]:
                continue
            for q in links[w]:
                if v - 1 > power[q]:
                    power[q] = v - 1
                    heapq.heappush(heap, (-(v - 1), q))
        return power

    # --- parts ---
    def repeater_input(self, p):
        f = self.prop(p, "facing")
        q = add(p, f)
        signal = self.get_signal(q, f)
        if signal < 15 and self.kind(q) == "redstone_wire":
            signal = max(signal, self.wire_power.get(q, 0))
        return signal

    def locked(self, p):
        if self.kind(p) != "repeater":
            return False
        for side in SIDES[self.prop(p, "facing")]:
            q = add(p, side)
            if self.kind(q) in ("repeater", "comparator") and self.emit(q, side) > 0:
                return True
        return False

    def torch_block(self, p):
        return add(p, "down") if self.kind(p) == "redstone_torch" else add(p, OPPOSITE[self.prop(p, "facing")])

    def torch_should_be_lit(self, p):
        q = self.torch_block(p)
        d = "down" if self.kind(p) == "redstone_torch" else OPPOSITE[self.prop(p, "facing")]
        return self.get_signal(q, d) == 0

    def schedule(self, p, delay, priority):
        if p in self.scheduled:
            return
        self.scheduled.add(p)
        self.seq += 1
        heapq.heappush(self.queue, (self.tick + delay, priority, self.seq, p))

    def set(self, p, key, value):
        """Changes a part's state and lets everything react, as a block update would."""
        self.parts[p][1][key] = value
        self.react()

    def react(self):
        """Wires take their strength at once; repeaters and torches that should change schedule their tick;
        shifters follow their power at once."""
        changed = True
        while changed:
            changed = False
            target = self.wire_targets()
            if target != self.wire_power:
                self.wire_power = target
                changed = True
            for p, (k, props) in self.parts.items():
                if k == "phase_shifter":
                    on = self.neighbor_signal(p) > 0
                    if on != (props.get("powered") == "true"):
                        props["powered"] = "true" if on else "false"
                        self.events.append((self.tick, p, "shifter", on))
                        changed = True
        for p, (k, props) in self.parts.items():
            if k == "repeater" and p not in self.scheduled and not self.locked(p):
                on = props.get("powered") == "true"
                should = self.repeater_input(p) > 0
                if on != should:
                    f = props["facing"]
                    front = add(p, OPPOSITE[f])
                    prioritize = self.kind(front) in ("repeater", "comparator") and self.prop(front, "facing") != OPPOSITE[f]
                    priority = EXTREMELY_HIGH if prioritize else VERY_HIGH if on else HIGH
                    self.schedule(p, 2 * int(props.get("delay", 1)), priority)
            elif k in ("redstone_torch", "redstone_wall_torch") and p not in self.scheduled:
                if (props.get("lit", "true") == "true") != self.torch_should_be_lit(p):
                    self.schedule(p, 2, NORMAL)

    def run_tick(self, p):
        k, props = self.parts[p]
        if k == "repeater":
            if self.locked(p):
                return
            on = props.get("powered") == "true"
            should = self.repeater_input(p) > 0
            if on and not should:
                self.set(p, "powered", "false")
            elif not on:
                self.set(p, "powered", "true")
                if not should:
                    self.schedule(p, 2 * int(props.get("delay", 1)), VERY_HIGH)
        elif k in ("redstone_torch", "redstone_wall_torch"):
            lit = props.get("lit", "true") == "true"
            should = self.torch_should_be_lit(p)
            if lit != should:
                self.set(p, "lit", "true" if should else "false")

    # --- driving it ---
    def set_input(self, p, on):
        """A phase port (as its visible block powered or not) or a lever, switched now."""
        if self.kind(p) == "phase_port":
            self.parts[p][1]["_on"] = on
            self.react()
        else:
            self.set(p, "powered", "true" if on else "false")

    def run(self, ticks):
        """Runs game ticks: each one, the scheduled ticks due, by priority, then in order of scheduling."""
        end = self.tick + ticks
        while self.tick < end:
            self.tick += 1
            while self.queue and self.queue[0][0] <= self.tick:
                _, _, _, p = heapq.heappop(self.queue)
                self.scheduled.discard(p)
                self.run_tick(p)
        return self

    def powered_shifters(self):
        return {p for p, (k, props) in self.parts.items() if k == "phase_shifter" and props.get("powered") == "true"}
