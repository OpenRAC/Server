#!/usr/bin/env python3
"""Plays a client's first steps against a running hub and game server, as docker compose starts them.

It says HELLO to the hub, logs in (registering a fresh account), waits until REGIONS lists the game
server, then says HELLO to that server and proves the login with ACCOUNT TOKEN. The wire format is
docs/protocol.md's. Exits 0 when every step got the answer it should.

Usage:
    scripts/smoke_test.py [--hub localhost:7177] [--server-host localhost] [--wait 90]
"""

import argparse
import secrets
import socket
import struct
import sys
import time

# Packet ids and opcodes: ordinals of Packets and Opcodes in :common.
HELLO, ERROR, UDP, REGIONS, ACCOUNT = 0, 4, 6, 8, 13
LOGIN, LOGGED_IN, TOKEN = 0, 1, 2

ERRORS = ["PARTY_NOT_FOUND", "PARTY_FULL", "ALREADY_IN_PARTY", "NOT_IN_PARTY", "NOT_HOST", "RESUME_REFUSED", "OTHER_GAME", "REGION_KEY",
          "HUB_FULL", "WRONG_PASSWORD", "INVALID_ACCOUNT", "SESSION_INVALID", "TRY_LATER", "NOT_LOGGED_IN"]


class Reader:
    """Reads the fields of one packet's payload."""

    def __init__(self, data):
        self.data = data
        self.offset = 0

    def take(self, size):
        """The next bytes of the payload."""
        chunk = self.data[self.offset:self.offset + size]

        if len(chunk) != size: raise ValueError(f"packet ended {size - len(chunk)} bytes early")

        self.offset += size

        return chunk

    def byte(self):
        """An unsigned byte."""
        return self.take(1)[0]

    def int(self):
        """A big-endian int."""
        return struct.unpack(">i", self.take(4))[0]

    def long(self):
        """A big-endian long."""
        return struct.unpack(">q", self.take(8))[0]

    def string(self):
        """A 2-byte length, then UTF-8; -1 is the empty string."""
        length = struct.unpack(">h", self.take(2))[0]

        return "" if length < 0 else self.take(length).decode("utf-8")

    def remaining(self):
        """Bytes left in the payload."""
        return len(self.data) - self.offset


def string(value):
    """Writes a string the way the protocol does."""
    data = value.encode("utf-8")

    return struct.pack(">h", len(data) if data else -1) + data


class Connection:
    """One TCP connection that sends and reads framed packets."""

    def __init__(self, host, port, timeout=10):
        self.socket = socket.create_connection((host, port), timeout=timeout)
        self.where = f"{host}:{port}"

    def send(self, packet, *fields):
        """Frames a packet id, its opcode if any, and its encoded fields."""
        body = bytes([packet]) + b"".join(fields)

        self.socket.sendall(struct.pack(">i", len(body)) + body)

    def read_exactly(self, size):
        """Reads size bytes or fails on a closed connection."""
        data = b""

        while len(data) < size:
            chunk = self.socket.recv(size - len(data))

            if not chunk: raise ConnectionError(f"{self.where} closed the connection")

            data += chunk

        return data

    def receive(self, expected):
        """The payload of the next packet, which must be the one expected."""
        length = struct.unpack(">i", self.read_exactly(4))[0]
        payload = Reader(self.read_exactly(length))
        packet = payload.byte()

        if packet == ERROR and expected != ERROR:
            reason = payload.byte()

            raise RuntimeError(f"{self.where} answered ERROR {ERRORS[reason] if reason < len(ERRORS) else reason}")

        if packet != expected: raise RuntimeError(f"{self.where} sent packet {packet}, expected {expected}")

        return payload

    def hello(self, name):
        """Says hello and reads the session id and the UDP offer."""
        self.send(HELLO, string("smoke"), string(name), bytes([0]))

        session = self.receive(HELLO).int()

        self.receive(UDP)

        return session

    def close(self):
        """Closes the connection."""
        self.socket.close()


def connect(host, port, deadline):
    """Connects and says hello, retrying until the deadline while the service starts.

    Docker publishes a port before the service listens on it, so a connection can open and then be
    reset; that counts as not up yet.
    """
    while True:
        connection = None

        try:
            connection = Connection(host, port)

            return connection, connection.hello("Smoke")
        except OSError as error:
            if connection: connection.close()

            if time.monotonic() > deadline: raise RuntimeError(f"nothing answers on {host}:{port}: {error}") from error

            time.sleep(1)


def regions(hub):
    """Asks the hub for the game servers and reads them."""
    hub.send(REGIONS, string("rac1-60-1"))

    payload = hub.receive(REGIONS)
    listed = [{"region": payload.string(), "host": payload.string(), "port": payload.int(), "players": payload.int()} for _ in range(payload.byte())]

    # The names are appended after the list.
    for server in listed:
        server["name"] = payload.string() if payload.remaining() else ""

    return listed


def main():
    """Runs the steps, printing each, and fails on the first that goes wrong."""
    parser = argparse.ArgumentParser(description="Smoke test for a running hub and game server.")
    parser.add_argument("--hub", default="localhost:7177", help="the hub's host:port")
    parser.add_argument("--server-host", default="localhost", help="where the game server's listed port is reached")
    parser.add_argument("--wait", type=int, default=90, help="seconds to wait for the services to come up")
    args = parser.parse_args()

    hub_host, hub_port = args.hub.rsplit(":", 1)
    deadline = time.monotonic() + args.wait
    name = "Smoke" + secrets.token_hex(4)
    password = secrets.token_hex(8)

    hub, session = connect(hub_host, int(hub_port), deadline)
    print(f"hub: HELLO, session {session}")

    hub.send(ACCOUNT, bytes([LOGIN]), string(name), string(password))
    login = hub.receive(ACCOUNT)

    if login.byte() != LOGGED_IN: raise RuntimeError("the hub did not answer LOGGED_IN")

    account_id, account_name, token, created = login.long(), login.string(), login.string(), login.byte()
    print(f"hub: ACCOUNT LOGIN, account {account_id} ({account_name}), created {created}")

    if not token or created != 1: raise RuntimeError("a first login must register and hand out a token")

    # The game server registers on its own schedule, so the list may start empty.
    listed = regions(hub)

    while not listed:
        if time.monotonic() > deadline: raise RuntimeError("no game server registered with the hub")

        time.sleep(2)
        listed = regions(hub)

    server = listed[0]
    print(f"hub: REGIONS, {len(listed)} listed, first {server}")
    hub.close()

    game, session = connect(args.server_host, server["port"], deadline)
    print(f"server: HELLO, session {session}")

    game.send(ACCOUNT, bytes([TOKEN]), string(token))
    verified = game.receive(ACCOUNT)

    if verified.byte() != LOGGED_IN: raise RuntimeError("the game server did not answer LOGGED_IN")

    verified_id, verified_name = verified.long(), verified.string()
    print(f"server: ACCOUNT TOKEN, account {verified_id} ({verified_name})")

    if verified_id != account_id or verified_name != account_name: raise RuntimeError("the game server named another account")

    game.close()
    print("Smoke test passed.")

    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, ValueError, OSError) as error:
        print(f"Smoke test failed: {error}", file=sys.stderr)
        sys.exit(1)
