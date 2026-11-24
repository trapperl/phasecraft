"""Vanilla structure files (generated/<ns>/structure/*.nbt): blocks by position, to and from NBT.

A block is a state string as in /setblock ("minecraft:repeater[delay=2,facing=north]") plus, optionally, its block
entity data (a dict, as NBT).
"""

from . import nbt

DATA_VERSION = 5023   # Minecraft 26.3 (version.json world_version)


def parse_state(text):
    """("minecraft:name", {prop: value}) of a state string; a name without namespace gets minecraft:."""
    name, _, props = text.partition("[")
    if ":" not in name:
        name = "minecraft:" + name
    pairs = [kv.split("=", 1) for kv in props.rstrip("]").split(",") if kv]
    return name, {k.strip(): v.strip() for k, v in pairs}


def format_state(name, props):
    return name + ("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else "")


def normal(text):
    """A state string with namespace and properties sorted, so equal states compare equal."""
    return format_state(*parse_state(text))


class Structure:
    """size (x, y, z) and blocks {(x, y, z): (state, nbt or None)}; positions not listed are left as they are."""

    def __init__(self, size, blocks=None):
        self.size = tuple(size)
        self.blocks = dict(blocks or {})

    @classmethod
    def load(cls, path):
        root = nbt.load(path)
        # 26.3 writes {id, properties}; older versions {Name, Properties}
        palette = [format_state(p.get("id", p.get("Name")),
                                {k: str(v) for k, v in p.get("properties", p.get("Properties", {})).items()})
                   for p in root["palette"]]
        blocks = {}
        for b in root["blocks"]:
            blocks[tuple(b["pos"])] = (palette[b["state"]], b.get("nbt"))
        return cls(tuple(root["size"]), blocks)

    def save(self, path):
        palette, index = [], {}
        out = []
        for pos in sorted(self.blocks, key=lambda p: (p[1], p[2], p[0])):
            state, data = self.blocks[pos]
            state = normal(state)
            if state not in index:
                index[state] = len(palette)
                name, props = parse_state(state)
                entry = {"id": name}
                if props:
                    entry["properties"] = props
                palette.append(entry)
            block = {"pos": nbt.List(list(pos), nbt.INT), "state": index[state]}
            if data is not None:
                block["nbt"] = data
            out.append(block)
        root = {
            "DataVersion": DATA_VERSION,
            "size": nbt.List(list(self.size), nbt.INT),
            "palette": nbt.List(palette, nbt.COMPOUND),
            "blocks": nbt.List(out, nbt.COMPOUND),
            "entities": nbt.List([], nbt.COMPOUND),
        }
        nbt.save(path, root)
