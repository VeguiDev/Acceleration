#!/usr/bin/env python3
"""Socket-level 1.13 integration against real proxy processes; no Minecraft EULA required.

The terminal backend is a protocol fixture, NOT Paper. Authentication is intentionally
offline at the upstream. Pass --upstream-jar to exercise an independently built Velocity.
"""
import argparse
import hashlib
import hmac
import json
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import threading
import time
import uuid
import zipfile


def varint(n):
    out = bytearray()
    while n > 127:
        out.append((n & 127) | 128)
        n >>= 7
    return bytes(out) + bytes([n])


def string(s):
    b = s.encode()
    return varint(len(b)) + b


def exact(s, n):
    b = b""
    while len(b) < n:
        chunk = s.recv(n - len(b))
        if not chunk:
            raise EOFError("Connection closed")
        b += chunk
    return b


def readint(s):
    n = 0
    for shift in range(0, 35, 7):
        b = exact(s, 1)[0]
        n |= (b & 127) << shift
        if not b & 128:
            return n
    raise ValueError("Oversized VarInt")


def packet(s):
    return exact(s, readint(s))


def send(s, data):
    s.sendall(varint(len(data)) + data)


def login(port):
    s = socket.create_connection(("127.0.0.1", port), timeout=10)
    send(s, b"\x00" + varint(393) + string("localhost") + struct.pack(">H", port) + b"\x02")
    send(s, b"\x00" + string("ChainTest"))
    return s


def freeport():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Backend:
    def __init__(self):
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.port = self.listener.getsockname()[1]
        self.listener.listen()
        self.identity = None
        self.error = None
        self.done = threading.Event()
        threading.Thread(target=self.run, daemon=True).start()

    def run(self):
        try:
            with self.listener.accept()[0] as s:
                s.settimeout(15)
                packet(s)  # Handshake
                assert packet(s) == b"\x00" + string("ChainTest")
                send(s, b"\x04\x07" + string("velocity:player_info") + b"\x04")
                response = packet(s)
                assert response[:3] == b"\x02\x07\x01", response.hex()
                signature, data = response[3:35], response[35:]
                assert hmac.compare_digest(signature, hmac.digest(b"outgoing", data, "sha256"))
                import io
                stream = io.BytesIO(data)

                class Reader:
                    recv = stream.read

                r = Reader()
                assert readint(r) == 1
                ip = exact(r, readint(r)).decode()
                player_uuid = uuid.UUID(bytes=exact(r, 16))
                name = exact(r, readint(r)).decode()
                self.identity = (ip, player_uuid, name)
                send(s, b"\x02" + string(str(player_uuid)) + string(name))
                # Minecraft 1.13 Join Game; enough to complete both proxies' initial connection.
                send(s, b"\x25" + struct.pack(">iBiBB", 1, 0, 0, 1, 20)
                     + string("default") + b"\x00")
                self.done.wait(15)
        except Exception as exc:
            self.error = exc
        finally:
            self.listener.close()
            self.done.set()


def start_proxy(jar, root, port, target, backend, incoming="incoming", outgoing="outgoing",
                second=None, plugin=None):
    root.mkdir()
    if plugin:
        (root / "plugins").mkdir()
        (root / "plugins" / "chain-fixture.jar").write_bytes(plugin.read_bytes())
    (root / "forwarding.secret").write_text(outgoing)
    (root / "upstream.secret").write_text(incoming)
    (root / "velocity.toml").write_text(f'''config-version = "2.7"
bind = "127.0.0.1:{port}"
online-mode = false
proxy-backend = {str(backend).lower()}
proxy-backend-secret-file = "upstream.secret"
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
[servers]
lobby = "127.0.0.1:{target}"
second = "127.0.0.1:{second or target}"
try = ["lobby"]
[forced-hosts]
"lobby.example.com" = ["lobby"]
[advanced]
compression-threshold = -1
login-ratelimit = 0
[metrics]
enabled = false
''')
    log = (root / "console.log").open("w")
    process = subprocess.Popen(["java", "-jar", str(jar)], cwd=root, stdin=subprocess.PIPE,
                               stdout=log, stderr=subprocess.STDOUT)
    log.close()
    for _ in range(150):
        if process.poll() is not None:
            raise RuntimeError((root / "console.log").read_text())
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=.1):
                return process
        except OSError:
            time.sleep(.1)
    process.terminate()
    raise TimeoutError(str(root))


def scenario(jar, upstream, root, chained, bad_secret=False, plugin=None):
    fixture = Backend()
    second = Backend() if chained and not bad_secret else None
    processes = []
    client = None
    try:
        local_port = freeport()
        processes.append(start_proxy(jar, root / "local", local_port, fixture.port, chained,
                                     second=second.port if second else None, plugin=plugin))
        port = local_port
        if chained:
            port = freeport()
            processes.append(start_proxy(upstream, root / "global", port, local_port, False,
                                         outgoing="wrong" if bad_secret else "incoming"))
        client = login(port)
        if bad_secret:
            first = packet(client)
            if first[0] == 2:  # Upstream completes its own login before opening the backend.
                for _ in range(20):
                    try:
                        response = packet(client)
                    except EOFError:
                        break  # A proxy may close before Join Game instead of sending play disconnect.
                    assert response[0] != 0x25, "Unauthenticated client joined"
                    if response[0] == 0x1A:
                        break
                else:
                    raise AssertionError("Expected play disconnect")
            else:
                assert first[0] == 0, "Expected login disconnect"
            assert fixture.identity is None
            return
        assert packet(client)[0] == 2, "Expected login success without encryption"
        for _ in range(30):
            if packet(client)[0] == 0x25:
                break
        else:
            raise AssertionError("No Join Game")
        assert fixture.error is None, fixture.error
        digest = bytearray(hashlib.md5(b"OfflinePlayer:ChainTest").digest())
        digest[6] = (digest[6] & 15) | 48
        digest[8] = (digest[8] & 63) | 128
        assert fixture.identity == ("127.0.0.1", uuid.UUID(bytes=bytes(digest)), "ChainTest")
        if second:
            send(client, b"\x02" + string("/chain-local-switch"))
            deadline = time.monotonic() + 10
            while second.identity is None and second.error is None and time.monotonic() < deadline:
                time.sleep(.05)
            assert second.error is None, second.error
            assert second.identity == fixture.identity, "Local switch lost identity"
            # Process packets on the original global connection: no reconnect is performed.
            assert packet(client)[0] != 0x1A, "Global connection disconnected during switch"
    finally:
        if client:
            client.close()
        fixture.done.set()
        if second:
            second.done.set()
        for process in reversed(processes):
            process.communicate(b"shutdown\n", timeout=20)
    if not bad_secret:
        log = (root / "local" / "console.log").read_text()
        for marker in ["PostLogin", "ChooseInitial", "PreConnect", "Connected lobby", "Disconnect"]:
            assert "CHAIN_EVENT " + marker in log, marker
        if second:
            assert "CHAIN_EVENT Connected second" in log


def build_plugin(upstream, root):
    classes = root / "classes"
    classes.mkdir()
    subprocess.run(["javac", "-proc:none", "-cp", str(upstream), "-d", str(classes),
                    str(Path(__file__).with_name("FixturePlugin.java"))], check=True)
    plugin = root / "fixture.jar"
    with zipfile.ZipFile(plugin, "w") as jar:
        for file in classes.rglob("*.class"):
            jar.write(file, file.relative_to(classes))
        jar.writestr("velocity-plugin.json", json.dumps({"id": "chain-fixture", "name": "ChainFixture",
                     "version": "1", "main": "chain.FixturePlugin"}))
    return plugin


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--upstream-jar", type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="acceleration-chain-") as temp:
        root = Path(temp)
        plugin = build_plugin((args.upstream_jar or args.jar).resolve(), root)
        for name, chained, invalid in [("conventional", False, False),
                                       ("chain", True, False), ("wrong-secret", True, True)]:
            directory = root / name
            directory.mkdir()
            scenario(args.jar.resolve(), (args.upstream_jar or args.jar).resolve(), directory,
                     chained, invalid, plugin)
            print(json.dumps({"scenario": name, "result": "passed"}))


if __name__ == "__main__":
    main()
