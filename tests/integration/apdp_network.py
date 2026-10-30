#!/usr/bin/env python3
"""Four real Acceleration JVMs, zero players/Paper/EULA/accounts; stdlib only.

Usage: python3 tests/integration/apdp_network.py <acceleration-all.jar>
All state is isolated in /tmp/opencode; no existing testnet is modified.
"""
import base64
import json
import pathlib
import re
import socket
import subprocess
import sys
import tempfile
import time


def port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait(check, timeout=35):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.1)
    raise AssertionError("APDP processes did not converge")


class Proxy:
    def __init__(self, jar, directory, name, leaves=False):
        self.directory = directory
        directory.mkdir()
        self.name = name
        self.logfile = directory / "console.log"
        self.minecraft_port = port()
        servers = ('public = "127.0.0.1:9"\nprivate = "127.0.0.1:10"' if leaves
                   else 'lobby = "127.0.0.1:9"')
        (directory / "velocity.toml").write_text(f'''config-version = "2.7"
bind = "127.0.0.1:{self.minecraft_port}"
online-mode = false
player-info-forwarding-mode = "none"
[servers]
{servers}
try = ["{'public' if leaves else 'lobby'}"]
[forced-hosts]
"lobby.example.com" = ["{'public' if leaves else 'lobby'}"]
"factions.example.com" = ["{'public' if leaves else 'lobby'}"]
"minigames.example.com" = ["{'public' if leaves else 'lobby'}"]
[advanced]
log-command-executions = true
[metrics]
enabled = false
''')
        if name != "head":
            (directory / "apdp.toml").write_text(f'''name = "{name}"
listen = "127.0.0.1:0"
publication = "{'exposed' if leaves else 'transparent'}"
exposed = ["public"]
proxy-destinations = ["lobby"]
''')
        self.jar = jar
        self.start()

    def start(self):
        with self.logfile.open("a") as log:
            self.process = subprocess.Popen(
                ["java", "-Xmx128m", "-jar", str(self.jar)], cwd=self.directory,
                stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT)
        def ready():
            if self.process.poll() is not None:
                raise AssertionError(self.logfile.read_text())
            try:
                with socket.create_connection(("127.0.0.1", self.minecraft_port), timeout=.1):
                    return True
            except OSError:
                return False
        wait(ready)

    def command(self, text):
        self.process.stdin.write((text + "\n").encode())
        self.process.stdin.flush()

    def log(self):
        return re.sub(r"\x1b\[[0-9;]*[mK]", "", self.logfile.read_text())

    def topology(self):
        before = len(self.log())
        self.command("apdp topology")
        time.sleep(.25)
        return self.log()[before:]

    def stop(self):
        if self.process.poll() is None:
            self.command("shutdown")
            try:
                self.process.wait(20)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                self.process.wait(10)
        self.process.stdin.close()


def enroll(parent, child, delegate):
    handoff = child.directory / "handoff.token"
    parent.command(f"apdp invite-file {handoff} 600{' delegate' if delegate else ''}")
    wait(handoff.exists)
    code = handoff.read_text()
    child.command(f"apdp join-file {handoff}")
    wait(lambda: "APDP enrollment complete" in child.log())
    handoff.unlink()  # Explicit administrator-owned handoff input, not APDP persisted state.
    assert code not in parent.log() and code not in child.log(), "Bearer leaked to console"


def main():
    jar = pathlib.Path(sys.argv[1]).resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="apdp-network-", dir="/tmp/opencode") as temporary:
        directory = pathlib.Path(temporary)
        processes = []
        try:
            for name in ("head", "survival", "group", "shard"):
                processes.append(Proxy(jar, directory / name, name, leaves=name == "shard"))
            head, first, second, third = processes
            head.command("apdp init network")
            wait(lambda: "APDP head initialized" in head.log())
            config = head.directory / "apdp.toml"
            config.write_text(config.read_text().replace('"local"', '"transparent"'))
            enroll(head, first, True)
            enroll(first, second, True)
            enroll(second, third, False)
            wait(lambda: "survival.group.shard.public MINECRAFT_SERVER depth=4" in head.topology())
            initial = head.topology()
            assert "survival.group.shard BACKEND_PROXY depth=3" in initial
            assert "survival.group.shard.private" not in initial
            third.command("apdp resync")
            wait(lambda: "survival.group.shard.public" in third.topology())
            assert "survival.group.shard.private" not in third.topology()
            first.command("apdp routes")
            wait(lambda: "nextHop=" in first.log())
            third.stop()
            wait(lambda: "survival.group.shard" not in head.topology())
            third.start()
            wait(lambda: "survival.group.shard.public" in head.topology())
            # Manual publication reload; ancestors cannot reconstruct the hidden leaf.
            config = third.directory / "apdp.toml"
            config.write_text(config.read_text().replace('"exposed"', '"local"'))
            wait(lambda: "survival.group.shard.public" not in head.topology()
                 and "survival.group.shard BACKEND_PROXY" in head.topology())
            local = json.loads((third.directory / "apdp-state/local.json").read_text())
            body = local["member"]["body"]
            member = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))
            head.command(f"apdp node revoke {member['node']}")
            wait(lambda: "Membership policy updated" in head.log())
            wait(lambda: "survival.group.shard" not in head.topology())
            def revoked_status():
                third.command("apdp status")
                return "APDP revoked" in third.log()
            wait(revoked_status)
            assert all("APDP1." not in process.log() for process in processes)
            print("PASS: four JVMs, zero players, depth 3, recursive public/private filtering,")
            print("      snapshots, routes, publication reload, withdrawal, restart and revocation")
        except Exception:
            for process in processes:
                print(f"--- {process.name}: last diagnostics ---", file=sys.stderr)
                print(process.log()[-5000:], file=sys.stderr)
            raise
        finally:
            for process in reversed(processes):
                process.stop()


if __name__ == "__main__":
    main()
