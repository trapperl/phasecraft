#!/usr/bin/env python3
"""The sorting machine (examples station 27, SortingMachineGameTests): put items in a chest on top of a wall and
they come out sorted into a row of chests below, one kind per chest, everything else in the last one. Visible:
a wall, the input chest on its top, the output chests in front of it. Everything else is hidden.

Usage: python3 tools/gen_sorter.py && python3 tools/redstone_check.py src/gametest/resources/contraptions/sorting_machine.txt

The redstone community's hopper item sorter, rebuilt in the hidden layer with two changes:

- The classic gate hopper (under the filter, locked by a redstone torch) keeps the last item it lets through until
  the next one of its kind comes: it pulls the item and is locked again before its 8-tick cooldown ends. Here a
  second hopper under the gate (the drain, never locked) pulls it straight out. A gate hopper locked again in time
  never pulls twice, so the filter never loses its own items.
- The lock is a comparator in subtract mode with a redstone block behind it (15, gate locked), not a torch: a full
  stack of one kind toggles the lock 64 times in under 30 seconds, and a torch toggled 8 times in 60 game ticks
  burns out. A repeater into the comparator's side takes it to 0 (gate open).

Logical coordinates: x along the wall (east), y up from the floor (the floor at y = 0), z out from the wall (the
wall at z = -1, the output chests at z = 0, the player at z > 0). Written to the layout as X = x + 1, Y = y + 2,
Z = z + 3. Output chests are three apart (x = 2, 5, 8, 11; overflow 14): a column's wiring uses x + 1 and x + 2.

Per sorted column x:
- The item line: hoppers at y = 3 inside the wall, x = 1..13 pointing east (the last, x = 14, down to the overflow).
  The phased input hopper (x = 0, on a phase block) pulls from the visible chest on the wall's top.
- F, the filter hopper (x, 2, -1), under the line, pointing north at nothing: 18 of its kind and 4 sticks named
  "Filter" (rest 22 items, comparator 1). It pulls only items that stack onto what it holds.
- Q, a comparator reading F (x + 1, 2, -1), two wires to C, a stone (x + 2, 1, -2): C is powered only from 23 items
  up (comparator 2). The repeater Rs reads C and powers the side of S, the subtract comparator (x, 1, -2) with a
  redstone block behind it, which feeds the gate G (x, 1, -1). So G is unlocked while F has an extra item.
- G pulls one item from F (pointing down at the drain); the drain D (x, 0, -1) pulls it out of G and pushes it
  into the phased dropper (x, 0, 0) under the visible chest, facing up into it.
- A clock (two observers watching each other, under the floor at the west end) runs a wire along z = 2, y = -1;
  a repeater from it into every dropper's phase block fires the droppers, so they push out what they hold, and
  wait (keeping it) while a chest is full. An empty phased dropper's click is heard only in the hidden layer.
"""

import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
NAME = "sorting_machine"
KINDS = ["minecraft:cobblestone", "minecraft:dirt", "minecraft:oak_log", "minecraft:iron_ingot"]
COLUMNS = [2 + 3 * i for i in range(len(KINDS))]
OVERFLOW = 2 + 3 * len(KINDS)
FILTER = '18'
JUNK = 'minecraft:stick[custom_name="Filter"]*1'
SIZE = (OVERFLOW + 3, 8, 8)

STONE, GLASS = "minecraft:stone", "minecraft:glass"


def at(x, y, z):
    return x + 1, y + 2, z + 3


def build():
    lines = []

    def put(layer, p, block, tag=""):
        lines.append(f"{layer} {' '.join(map(str, at(*p)))} {block}" + (f" {tag}" if tag else ""))

    def hidden(p, block, tag=""):
        put("hidden", p, block, tag)

    def comment(text):
        lines.append(f"# {text}")

    w, h, d = SIZE
    lines.append(f"size {w} {h} {d}")
    comment("The visible build: ground, floor, the wall (three deep, its top a ledge), the chests.")
    lines.append(f"fill visible 0 0 0 {w - 1} 1 {d - 1} {STONE}")
    lines.append(f"fill visible 0 2 0 {w - 1} 2 {d - 1} minecraft:polished_andesite")
    lines.append(f"fill visible 0 3 0 {w - 1} 5 2 minecraft:stone_bricks")
    put("visible", (0, 4, -1), "minecraft:chest[facing=south]")
    for x in COLUMNS + [OVERFLOW]:
        put("visible", (x, 1, 0), "minecraft:chest[facing=south]")

    comment("The clock: two observers watching each other, a wire along z = 2 under the floor.")
    for x in range(2, OVERFLOW + 1):
        hidden((x, -2, 2), STONE)
    hidden((1, -1, 2), "minecraft:observer[facing=west,powered=false]", "@K")
    hidden((0, -1, 2), "minecraft:observer[facing=east,powered=false]", "@K")
    for x in range(2, OVERFLOW + 1):
        sides = "east=side,west=side" + (",north=side" if x in COLUMNS + [OVERFLOW] else "")
        hidden((x, -1, 2), f"minecraft:redstone_wire[{sides}]", "@K")

    comment("Each output: a phased dropper under its chest, facing up, fired from the clock through its phase block.")
    for x in COLUMNS + [OVERFLOW]:
        hidden((x, -2, 1), STONE)
        hidden((x, -1, 1), "minecraft:repeater[facing=south,delay=1,powered=false]", "@K")
        hidden((x, -1, 0), "phasecraft:phase_block")
        hidden((x, 0, 0), "minecraft:dropper[facing=up,triggered=false]")

    comment("The input: a phased hopper under the visible chest on the wall, then the item line.")
    hidden((0, 2, -1), "phasecraft:phase_block")
    hidden((0, 3, -1), "minecraft:hopper[facing=east,enabled=true]")
    for x in range(1, OVERFLOW):
        hidden((x, 3, -1), "minecraft:hopper[facing=east,enabled=true]")
    hidden((OVERFLOW, 3, -1), "minecraft:hopper[facing=down,enabled=true]")

    for i, x in enumerate(COLUMNS):
        n = i + 1
        comment(f"Column {n} ({KINDS[i]}): filter F, its comparator and threshold, the lock S, gate G, drain D.")
        hidden((x, 0, -1), "minecraft:hopper[facing=south,enabled=true]")                       # D, the drain
        hidden((x, 2, -1), "minecraft:hopper[facing=north,enabled=true]")                       # F, the filter
        hidden((x + 1, 1, -1), GLASS)
        hidden((x + 1, 2, -1), "minecraft:comparator[facing=west,mode=compare,powered=false]", f"@F{n}")
        hidden((x + 2, 1, -1), GLASS)
        hidden((x + 2, 2, -1), "minecraft:redstone_wire[west=side,north=side]", f"@F{n}")
        hidden((x + 2, 1, -2), STONE)                                                           # C
        hidden((x + 2, 2, -2), "minecraft:redstone_wire[north=side,south=side]", f"@F{n}")
        hidden((x + 1, 0, -2), STONE)
        hidden((x + 1, 1, -2), "minecraft:repeater[facing=east,delay=1,powered=false]", f"@T{n}<F{n}")
        hidden((x, 1, -3), "minecraft:redstone_block", f"@ON{n}")
        hidden((x, 0, -2), STONE)
        hidden((x, 1, -2), "minecraft:comparator[facing=north,mode=subtract,powered=true]", f"@G{n}<ON{n}")
        # G, the gate: written unlocked, as it is when placed (its lock is not on yet); a block that changes itself
        # as it is placed sends its neighbours no update, and S would never hear of it (C12)
        hidden((x, 1, -1), "minecraft:hopper[facing=down,enabled=true]", f"@G{n}")
        lines.append(f"items hidden {' '.join(map(str, at(x, 2, -1)))} {KINDS[i]}*{FILTER} {' '.join([JUNK] * 4)}")
        lines.append(f"allow G{n} T{n}")

    comment("The overflow: the line's last hopper down a column of hoppers into the last dropper.")
    hidden((OVERFLOW, 2, -1), "minecraft:hopper[facing=down,enabled=true]")
    hidden((OVERFLOW, 1, -1), "minecraft:hopper[facing=down,enabled=true]")
    hidden((OVERFLOW, 0, -1), "minecraft:hopper[facing=south,enabled=true]")
    return lines


def main():
    lines = build()
    header = [f"# {line}".rstrip() for line in __doc__.strip().splitlines()]
    header.insert(0, "# Written by tools/gen_sorter.py; change the generator and run it again.")
    path = ROOT / f"src/gametest/resources/contraptions/{NAME}.txt"
    path.write_text("\n".join(header + lines) + "\n")
    structure = ROOT / f"src/gametest/resources/data/phasecraft-test/gametest/structure/{NAME}.snbt"
    structure.write_text("{\n    DataVersion: 2730,\n    size: [%d, %d, %d],\n    data: [],\n    palette: []\n}\n" % SIZE)
    hidden = sum(1 for line in lines if line.startswith("hidden"))
    print(f"{path.relative_to(ROOT)}: {hidden} hidden blocks, area {SIZE[0]}x{SIZE[1]}x{SIZE[2]}")


if __name__ == "__main__":
    main()
