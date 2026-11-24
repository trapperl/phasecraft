"""Command line: python3 -m mcb (run from tools/).

  export X1 Y1 Z1 X2 Y2 Z2 NAME        both layers of a region of the dev server into structures (generated/mcb)
  place SOURCE X Y Z [--strict]       place a layout file or an exported NAME, its corner at X Y Z, in one tick
  render SOURCE OUT.png [--view iso|top|bottom|north|south|east|west] [--corner 0-3] [--visible] [--tile N]
                                      a picture of a layout file or an exported NAME
"""

import argparse
import pathlib
import sys

from . import render, server
from .scene import Scene


def load(source):
    path = pathlib.Path(source)
    if path.suffix == ".txt" and path.exists():
        return Scene.from_layout(path)
    return Scene.from_structures(server.structure_dir(source))


def main(argv=None):
    parser = argparse.ArgumentParser(prog="mcb", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    e = sub.add_parser("export")
    e.add_argument("coords", type=int, nargs=6)
    e.add_argument("name")
    p = sub.add_parser("place")
    p.add_argument("source")
    p.add_argument("at", type=int, nargs=3)
    p.add_argument("--strict", action="store_true")
    p.add_argument("--name", default=None)
    r = sub.add_parser("render")
    r.add_argument("source")
    r.add_argument("out")
    r.add_argument("--view", default="iso", choices=["iso", "top", "bottom", "north", "south", "east", "west"])
    r.add_argument("--corner", type=int, default=0)
    r.add_argument("--visible", action="store_true", help="the visible layer only")
    r.add_argument("--tile", type=int, default=10)
    args = parser.parse_args(argv)
    if args.command == "export":
        scene = server.export(args.coords[:3], args.coords[3:], args.name)
        print(f"{args.name}: {scene.size}, " + ", ".join(f"{k} {len(v)} blocks" for k, v in scene.layers.items()))
    elif args.command == "place":
        name = args.name or pathlib.Path(args.source).stem
        print(server.place(load(args.source), args.at, name, strict=args.strict))
    elif args.command == "render":
        scene = load(args.source)
        layers = ("visible",) if args.visible else ("visible", "hidden")
        if args.view == "iso":
            render.iso(scene, args.out, layers=layers, corner=args.corner, tile=args.tile)
        else:
            axis, flip = {"top": ("y", False), "bottom": ("y", True), "south": ("z", False), "north": ("z", True),
                          "east": ("x", False), "west": ("x", True)}[args.view]
            render.plan(scene, args.out, layers=layers, axis=axis, flip=flip, tile=args.tile)
        print(args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
