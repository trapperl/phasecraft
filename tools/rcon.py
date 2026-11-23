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


class RconError(Exception):
    pass


def send_commands(commands, properties=PROPERTIES):
    """Send commands in one RCON session; return the reply to each, in order."""
    props = read_properties(properties)
    if props.get("enable-rcon") != "true":
        raise RconError(f"RCON is not enabled in {properties}")
    host = props.get("server-ip") or "127.0.0.1"
    port = int(props.get("rcon.port", "25575"))
    replies = []
    try:
        with socket.create_connection((host, port), timeout=10) as sock:
            send(sock, 1, LOGIN, props.get("rcon.password", ""))
            request_id, _, _ = receive(sock)
            if request_id == -1:
                raise RconError("RCON login rejected (check rcon.password)")
            for number, command in enumerate(commands, start=2):
                send(sock, number, COMMAND, command.removeprefix("/"))
                replies.append(receive(sock)[2])
    except (OSError, ConnectionError) as error:
        raise RconError(f"Cannot reach RCON at {host}:{port}: {error} (is ./gradlew runServer running?)") from error
    return replies


def main(commands):
    if not commands:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    try:
        replies = send_commands(commands)
    except RconError as error:
        print(error, file=sys.stderr)
        return 1
    for command, body in zip(commands, replies):
        print(f"> {command}\n{body}" if len(commands) > 1 else body)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
