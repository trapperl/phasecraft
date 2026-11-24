"""The local dev server (./gradlew runServer, run/): commands over RCON, and the phasecraft-devtools commands
/mcb place and /mcb export, which move whole regions of both layers through structure files in one tick."""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import rcon  # noqa: E402

from .scene import Scene  # noqa: E402


def world():
    props = rcon.read_properties(rcon.PROPERTIES)
    return ROOT / "run" / props.get("level-name", "world")


def structure_dir(name):
    return world() / "generated" / "mcb" / "structure" / name


def command(*commands):
    """Replies to the commands, in order (one RCON connection)."""
    return rcon.send_commands(list(commands))


def coords(pos):
    return " ".join(str(v) for v in pos)


def place(scene, at, name="scene", strict=False):
    """Writes the scene's structures and places both layers with the scene's corner at `at`, in one tick.
    strict: every block exactly as written, no updates (parts written in their settled state)."""
    scene.to_structures(structure_dir(name))
    reply = command(f"mcb place {name} {coords(at)}" + (" strict" if strict else ""))[0]
    if not reply.startswith("placed"):
        raise RuntimeError(f"/mcb place: {reply}")
    return reply


def export(a, b, name="snapshot"):
    """Both layers of the region between corners a and b, as a Scene (positions relative to the lower corner)."""
    reply = command(f"mcb export {coords(a)} {coords(b)} {name}")[0]
    if not reply.startswith("exported"):
        raise RuntimeError(f"/mcb export: {reply}")
    return Scene.from_structures(structure_dir(name))
