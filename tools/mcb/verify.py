"""Checks the redstone simulator against the real game on the dev server, tick by tick.

A circuit (a Scene; its redstone in the hidden layer) is placed on the dev server, with its ticking frozen
(/tick freeze), and in a Sim; then, one game tick at a time, inputs are switched on both sides and the server steps
(/tick step 1) and exports the region. Every repeater, torch, wire and shifter must agree each tick.

    python3 -m mcb.verify [--random N] [--seed S]
"""

import random
import sys
import time

from . import server
from .redstone import Sim
from .scene import Scene
from .structure import parse_state

AT = (300, -40, 100)             # a free spot on the dev server, both layers
HIDDEN = "phasecraft:hidden_overworld"


def observed(scene_or_sim, layer="hidden"):
    """{pos: value} of the parts whose state the check compares."""
    out = {}
    if isinstance(scene_or_sim, Sim):
        for p, (k, props) in scene_or_sim.parts.items():
            if k == "redstone_wire":
                out[p] = scene_or_sim.wire_power.get(p, 0)
            elif k in ("repeater", "phase_shifter", "lever"):
                out[p] = props.get("powered") == "true"
            elif k in ("redstone_torch", "redstone_wall_torch"):
                out[p] = props.get("lit", "true") == "true"
        return out
    for p, (state, _) in scene_or_sim.layers[layer].items():
        name, props = parse_state(state)
        k = name.split(":")[-1]
        if k == "redstone_wire":
            out[p] = int(props.get("power", 0))
        elif k in ("repeater", "phase_shifter", "lever"):
            out[p] = props.get("powered") == "true"
        elif k in ("redstone_torch", "redstone_wall_torch"):
            out[p] = props.get("lit") == "true"
    return out


def check(scene, inputs, ticks, name="verify"):
    """inputs: {tick: [(pos, on)]} (levers or phase ports in the hidden layer). Returns [(tick, pos, real, sim)]."""
    lo, hi = scene.bounds()
    size = tuple(hi[i] - lo[i] + 1 for i in range(3))
    far = tuple(AT[i] + size[i] - 1 for i in range(3))
    server.command(f"forceload add {AT[0] - 1} {AT[2] - 1} {far[0] + 1} {far[2] + 1}",
                   f"execute in {HIDDEN} run forceload add {AT[0] - 1} {AT[2] - 1} {far[0] + 1} {far[2] + 1}",
                   "tick unfreeze")
    # Placing runs half-built redstone (repeaters and torches see passing signals and schedule ticks), in an order
    # the simulator does not copy: let the server settle, then start the simulator from what the server has.
    server.place(scene, AT, name)
    time.sleep(3)
    server.command("tick freeze")
    time.sleep(0.2)
    settled = server.export(AT, far, name + "_snap")
    sim = Sim(scene)
    sim.adopt(settled)
    mismatches = []

    def compare(t):
        real = observed(server.export(AT, far, name + "_snap"))
        mine = observed(sim)
        for p in sorted(set(real) | set(mine)):
            if real.get(p) != mine.get(p):
                mismatches.append((t, p, real.get(p), mine.get(p)))

    compare(0)
    # a circuit that never settles (a clock) has ticks pending that no export shows: not comparable
    server.command("tick step 1")
    time.sleep(0.12)
    if sim.queue or observed(server.export(AT, far, name + "_snap")) != observed(settled):
        server.command("tick unfreeze")
        return None
    sim.run(1)
    for t in range(2, ticks + 1):
        for pos, on in inputs.get(t, []):
            k = scene.get("hidden", pos)
            if "lever" in k:
                name_, props = parse_state(k)
                props["powered"] = str(on).lower()
                state = name_ + "[" + ",".join(f"{a}={b}" for a, b in sorted(props.items())) + "]"
                server.command(f"execute in {HIDDEN} run setblock {' '.join(str(AT[i] + pos[i]) for i in range(3))} {state}")
            sim.set_input(pos, on)
        server.command("tick step 1")
        time.sleep(0.12)
        sim.run(1)
        compare(t)
        if len(mismatches) > 40:
            break
    server.command("tick unfreeze")
    return mismatches


def random_circuit(rng, size=(9, 3, 9)):
    """A random little circuit on a glass floor: wires, repeaters, torches, stone, two levers."""
    scene = Scene(size)
    sx, sy, sz = size
    scene.fill("hidden", (0, 0, 0), (sx - 1, 0, sz - 1), "minecraft:glass")
    facings = ["north", "south", "east", "west"]
    for x in range(sx):
        for z in range(sz):
            r = rng.random()
            if r < 0.40:
                scene.set("hidden", (x, 1, z), "minecraft:redstone_wire[east=side,north=side,south=side,west=side]")
            elif r < 0.58:
                scene.set("hidden", (x, 1, z), f"minecraft:repeater[delay={rng.randint(1, 4)},facing={rng.choice(facings)}]")
            elif r < 0.68:
                scene.set("hidden", (x, 1, z), "minecraft:stone")
                if rng.random() < 0.6:
                    scene.set("hidden", (x, 2, z), "minecraft:redstone_torch")
            elif r < 0.74:
                scene.set("hidden", (x, 1, z), f"minecraft:redstone_wall_torch[facing={rng.choice(facings)}]")
    levers = []
    for _ in range(2):
        x, z = rng.randrange(sx), rng.randrange(sz)
        scene.set("hidden", (x, 1, z), "minecraft:lever[face=floor,facing=north,powered=false]")
        scene.set("hidden", (x, 2, z), "minecraft:air")    # no torch left standing on the lever
        levers.append((x, 1, z))
    # wall torches need their block
    for p, (state, _) in list(scene.layers["hidden"].items()):
        if "wall_torch" in state:
            f = parse_state(state)[1]["facing"]
            back = {"north": (0, 0, 1), "south": (0, 0, -1), "east": (-1, 0, 0), "west": (1, 0, 0)}[f]
            q = (p[0] + back[0], p[1], p[2] + back[2])
            if 0 <= q[0] < sx and 0 <= q[2] < sz:
                scene.set("hidden", q, "minecraft:stone")
            else:
                scene.set("hidden", p, "minecraft:air")
    return scene, levers


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    count = int(argv[argv.index("--random") + 1]) if "--random" in argv else 3
    seed = int(argv[argv.index("--seed") + 1]) if "--seed" in argv else 1
    rng = random.Random(seed)
    bad = 0
    for i in range(count):
        scene, levers = random_circuit(rng)
        inputs = {3: [(levers[0], True)], 15: [(levers[1], True)], 31: [(levers[0], False)], 45: [(levers[1], False)]}
        found = check(scene, inputs, 90, name=f"verify{i}")
        if found is None:
            print(f"circuit {i} (seed {seed}): a clock, skipped")
            continue
        print(f"circuit {i} (seed {seed}): " + ("agrees every tick" if not found else f"{len(found)} differences, first: {found[:4]}"))
        bad += bool(found)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
