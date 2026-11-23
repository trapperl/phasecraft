#!/usr/bin/env python3
"""Send commands to the local PhaseCraft dev server over RCON.

Usage:
    python3 tools/rcon.py "phasecraft get 10 64 10" ["another command" ...]

Reads host, port and password from run/server.properties (written by
./gradlew runServer; enable-rcon=true is required). Prints each reply.
Exit code 1 if the server cannot be reached or rejects the password.
"""

import pathlib
import socket
import struct
import sys

PROPERTIES = pathlib.Path(__file__).resolve().parent.parent / "run" / "server.properties"
LOGIN, COMMAND, RESPONSE = 3, 2, 0


def read_properties(path):
    props = {}
    for line in path.read_text().splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def send(sock, request_id, kind, body):
    payload = struct.pack("<ii", request_id, kind) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(payload)) + payload)


def receive(sock):
    def exactly(count):
        data = b""
        while len(data) < count:
            chunk = sock.recv(count - len(data))
            if not chunk:
                raise ConnectionError("server closed the RCON connection")
            data += chunk
        return data

    (length,) = struct.unpack("<i", exactly(4))
    request_id, kind = struct.unpack("<ii", exactly(8))
    body = exactly(length - 8)[:-2].decode("utf-8", errors="replace")
    return request_id, kind, body


def main(commands):
    if not commands:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    props = read_properties(PROPERTIES)
    if props.get("enable-rcon") != "true":
        print(f"RCON is not enabled in {PROPERTIES}", file=sys.stderr)
        return 1
    host = props.get("server-ip") or "127.0.0.1"
    port = int(props.get("rcon.port", "25575"))
    try:
        with socket.create_connection((host, port), timeout=10) as sock:
            send(sock, 1, LOGIN, props.get("rcon.password", ""))
            request_id, _, _ = receive(sock)
            if request_id == -1:
                print("RCON login rejected (check rcon.password)", file=sys.stderr)
                return 1
            for number, command in enumerate(commands, start=2):
                send(sock, number, COMMAND, command.removeprefix("/"))
                _, _, body = receive(sock)
                print(f"> {command}\n{body}" if len(commands) > 1 else body)
    except (OSError, ConnectionError) as error:
        print(f"Cannot reach RCON at {host}:{port}: {error} (is ./gradlew runServer running?)", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
