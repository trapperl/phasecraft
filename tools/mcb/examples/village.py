"""A demo of mcb.shapes: hills, a lake, a small village of timber houses, a watchtower, paths and a forest.

    python3 -m mcb.examples.village OUT.png       a picture
    python3 -m mcb.examples.village --place X Y Z  onto the dev server (both layers' structures; corner at X Y Z)
"""

import sys

from .. import render, server, shapes
from ..scene import Scene

SIZE = (64, 64)


def build(seed=4):
    s = Scene()
    shapes.terrain(s, (0, 0, 0), SIZE, seed=seed, water=7, amplitude=16, scale=28)
    lots = [((14, 18), (22, 25), "south"), ((30, 16), (38, 22), "south"), ((18, 36), (25, 44), "north"),
            ((34, 34), (42, 41), "west")]
    for (a, b, door) in lots:
        y = shapes.pad(s, a, b)
        shapes.house(s, (a[0], y, a[1]), (b[0] - a[0] + 1, b[1] - a[1] + 1), door=door)
    ty = shapes.pad(s, (46, 18), (52, 24))
    shapes.tower(s, (49, ty, 21), 3, 12)
    plaza = (28, 28)
    shapes.path(s, [(18, 26), (18, 29), plaza, (34, 23)], width=2)
    shapes.path(s, [plaza, (22, 35)], width=2)
    shapes.path(s, [plaza, (38, 33)], width=2)
    shapes.path(s, [plaza, (45, 22)], width=2)
    shapes.forest(s, ((2, 2), (61, 61)), 45, seed=seed, avoid=[(a, b) for a, b, _ in lots] + [((44, 16), (54, 26))])
    return s


def main(argv):
    scene = build()
    if argv and argv[0] == "--place":
        print(server.place(scene, tuple(int(v) for v in argv[1:4]), "village"))
    else:
        out = argv[0] if argv else "village.png"
        render.iso(scene, out, tile=6, corner=0)
        print(out)


if __name__ == "__main__":
    main(sys.argv[1:])
