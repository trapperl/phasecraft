"""Minimal NBT (Named Binary Tag) reading and writing, enough for structure files.

Values map to Python as: TAG_Compound -> dict, TAG_List -> List (a list with an element type), TAG_String -> str,
TAG_Int -> int, and the other numeric tags to the wrappers below (so a round trip keeps each tag's type).
"""

import gzip
import io
import struct

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class Byte(int):
    pass


class Short(int):
    pass


class Long(int):
    pass


class Float(float):
    pass


class Double(float):
    pass


class IntArray(list):
    pass


class LongArray(list):
    pass


class List(list):
    """A TAG_List: elements of one type (the type of an empty list must be given)."""

    def __init__(self, items=(), kind=None):
        super().__init__(items)
        self.kind = kind


def _kind(value):
    if isinstance(value, Byte):
        return BYTE
    if isinstance(value, Short):
        return SHORT
    if isinstance(value, Long):
        return LONG
    if isinstance(value, bool):
        return BYTE
    if isinstance(value, int):
        return INT
    if isinstance(value, Float):
        return FLOAT
    if isinstance(value, float):
        return DOUBLE
    if isinstance(value, (bytes, bytearray)):
        return BYTE_ARRAY
    if isinstance(value, str):
        return STRING
    if isinstance(value, IntArray):
        return INT_ARRAY
    if isinstance(value, LongArray):
        return LONG_ARRAY
    if isinstance(value, list):
        return LIST
    if isinstance(value, dict):
        return COMPOUND
    raise TypeError(f"no NBT type for {type(value)}")


class _Reader:
    def __init__(self, data):
        self.buf = io.BytesIO(data)

    def unpack(self, fmt):
        size = struct.calcsize(fmt)
        return struct.unpack(">" + fmt, self.buf.read(size))[0]

    def string(self):
        n = self.unpack("H")
        return self.buf.read(n).decode("utf-8", "surrogatepass")

    def payload(self, kind):
        if kind == BYTE:
            return Byte(self.unpack("b"))
        if kind == SHORT:
            return Short(self.unpack("h"))
        if kind == INT:
            return self.unpack("i")
        if kind == LONG:
            return Long(self.unpack("q"))
        if kind == FLOAT:
            return Float(self.unpack("f"))
        if kind == DOUBLE:
            return Double(self.unpack("d"))
        if kind == BYTE_ARRAY:
            return bytearray(self.buf.read(self.unpack("i")))
        if kind == STRING:
            return self.string()
        if kind == LIST:
            item = self.unpack("b")
            return List([self.payload(item) for _ in range(self.unpack("i"))], item)
        if kind == COMPOUND:
            out = {}
            while True:
                tag = self.unpack("b")
                if tag == END:
                    return out
                name = self.string()
                out[name] = self.payload(tag)
        if kind == INT_ARRAY:
            n = self.unpack("i")
            return IntArray(struct.unpack(f">{n}i", self.buf.read(4 * n)))
        if kind == LONG_ARRAY:
            n = self.unpack("i")
            return LongArray(struct.unpack(f">{n}q", self.buf.read(8 * n)))
        raise ValueError(f"unknown NBT tag {kind}")


class _Writer:
    def __init__(self):
        self.buf = io.BytesIO()

    def pack(self, fmt, value):
        self.buf.write(struct.pack(">" + fmt, value))

    def string(self, text):
        data = text.encode("utf-8", "surrogatepass")
        self.pack("H", len(data))
        self.buf.write(data)

    def payload(self, kind, value):
        if kind == BYTE:
            self.pack("b", int(value))
        elif kind == SHORT:
            self.pack("h", value)
        elif kind == INT:
            self.pack("i", value)
        elif kind == LONG:
            self.pack("q", value)
        elif kind == FLOAT:
            self.pack("f", value)
        elif kind == DOUBLE:
            self.pack("d", value)
        elif kind == BYTE_ARRAY:
            self.pack("i", len(value))
            self.buf.write(bytes(value))
        elif kind == STRING:
            self.string(value)
        elif kind == LIST:
            item = getattr(value, "kind", None)
            if item is None:
                item = _kind(value[0]) if value else END
            self.pack("b", item)
            self.pack("i", len(value))
            for v in value:
                self.payload(item, v)
        elif kind == COMPOUND:
            for name, v in value.items():
                tag = _kind(v)
                self.pack("b", tag)
                self.string(name)
                self.payload(tag, v)
            self.pack("b", END)
        elif kind == INT_ARRAY:
            self.pack("i", len(value))
            self.buf.write(struct.pack(f">{len(value)}i", *value))
        elif kind == LONG_ARRAY:
            self.pack("i", len(value))
            self.buf.write(struct.pack(f">{len(value)}q", *value))


def loads(data):
    """The root compound of NBT bytes (gzipped or not)."""
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    r = _Reader(data)
    if r.unpack("b") != COMPOUND:
        raise ValueError("NBT root is not a compound")
    r.string()
    return r.payload(COMPOUND)


def dumps(root, compress=True):
    """NBT bytes (gzipped, as structure files are) of a root compound."""
    w = _Writer()
    w.pack("b", COMPOUND)
    w.string("")
    w.payload(COMPOUND, root)
    data = w.buf.getvalue()
    return gzip.compress(data, mtime=0) if compress else data


def load(path):
    with open(path, "rb") as f:
        return loads(f.read())


def save(path, root):
    with open(path, "wb") as f:
        f.write(dumps(root))
