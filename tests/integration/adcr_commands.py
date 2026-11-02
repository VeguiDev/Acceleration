#!/usr/bin/env python3
"""Real Minecraft 1.13 client through independently running APDP/ADCR JVMs."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import zipfile
import threading
import proxy_chain as mc
from apdp_network import wait, enroll, temporary_root


class Proxy:
    def __init__(self, jar, directory, name, target, backend, plugin, second=None):
        self.directory, self.name = directory, name
        self.minecraft_port = mc.freeport()
        self.process = mc.start_proxy(jar, directory, self.minecraft_port, target,
                                      backend, incoming="outgoing", plugin=plugin, second=second)
        self.logfile = directory / "console.log"

    def command(self, text):
        self.process.stdin.write((text + "\n").encode())
        self.process.stdin.flush()

    def log(self):
        return self.logfile.read_text()

    def stop(self):
        self.process.communicate(b"shutdown\n", timeout=25)


def plugin(jar, root):
    classes = root / "classes"
    classes.mkdir()
    subprocess.run(["javac", "-proc:none", "-cp", str(jar), "-d", str(classes),
                    str(Path(__file__).with_name("AdcrFixturePlugin.java"))], check=True)
    result = root / "fixture.jar"
    with zipfile.ZipFile(result, "w") as output:
        for file in classes.rglob("*.class"):
            output.write(file, file.relative_to(classes))
        output.writestr("velocity-plugin.json", json.dumps({"id": "adcrfixture", "name": "ADCR fixture",
                            "version": "1", "main": "AdcrFixturePlugin"}))
    return result


def main():
    jar = Path(sys.argv[1]).resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="adcr-commands-", dir=temporary_root()) as temporary:
        root = Path(temporary)
        fixture = mc.Backend(commands=True, lifetime=60)
        second = mc.Backend(commands=True, lifetime=60)
        cross_branch = mc.Backend(commands=True, lifetime=60)
        proxies = []
        client = None
        try:
            artifact = plugin(jar, root)
            child = Proxy(jar, root / "child", "child", fixture.port, True, artifact, second.port)
            proxies.append(child)
            sibling = Proxy(jar, root / "sibling", "sibling", cross_branch.port, True, artifact)
            proxies.append(sibling)
            head = Proxy(jar, root / "head", "head", child.minecraft_port, False, artifact,
                         sibling.minecraft_port)
            proxies.append(head)
            head.command("apdp init network")
            wait(lambda: "APDP head initialized" in head.log())
            config = head.directory / "apdp.toml"
            staged = config.with_suffix(".staged")
            staged.write_text(config.read_text().replace('publication = "local"', 'publication = "transparent"')
                              .replace('proxy-destinations = []', 'proxy-destinations = ["lobby", "second"]'))
            staged.replace(config)
            (child.directory / "apdp.toml").write_text('name = "lobby"\npublication = "transparent"\n')
            enroll(head, child, False)
            (sibling.directory / "apdp.toml").write_text('name = "second"\npublication = "transparent"\n')
            enroll(head, sibling, False)
            time.sleep(4)  # Both peers must negotiate the ADCR heartbeat capability.
            client = mc.login(head.minecraft_port)
            assert mc.packet(client)[0] == 2
            while mc.packet(client)[0] != 0x25:
                pass
            assert fixture.error is None, fixture.error
            received = []
            def drain():
                try:
                    while True:
                        packet = mc.packet(client)
                        if packet[0] == 0x21:
                            mc.send(client, b"\x0e" + packet[1:])
                        else:
                            received.append(packet)
                except (OSError, EOFError):
                    pass
            threading.Thread(target=drain, daemon=True).start()
            wait(lambda: any(packet[0] == 0x11 for packet in received), 10)
            tree = next(packet for packet in received if packet[0] == 0x11)
            assert b"ancestor" in tree and b"head:nearest" in tree and b"local:nearest" in tree
            assert b"lobby:nearest" in tree
            assert b"lobby:server" in tree and b"local:server" in tree
            assert mc.string("blocked") not in tree, "Denied nearest owner leaked into tree"
            # Root completion requests must work too, not only argument providers.
            for transaction, prefix, expected in [(43, "/lob", b"lobby:nearest"),
                                                   (44, "/hea", b"head:nearest")]:
                offset = len(received)
                mc.send(client, b"\x05" + mc.varint(transaction) + mc.string(prefix))
                wait(lambda: any(packet[0] == 0x10 and expected in packet
                                 for packet in received[offset:]), 10)
            mc.send(client, b"\x05" + mc.varint(42) + mc.string("/ancestor "))
            wait(lambda: any(b"ancestor-offer" in packet for packet in received), 10)
            time.sleep(.6)  # Respect the normal player tab-completion limiter.
            offset = len(received)
            mc.send(client, b"\x05" + mc.varint(45) + mc.string("/server lobby."))
            wait(lambda: any(packet[0] == 0x10 and b"lobby.second" in packet
                             for packet in received[offset:]), 10)
            for command, owner in [("nearest", child), ("ancestor", head),
                                   ("head:nearest", head), ("local:nearest", child),
                                   ("lobby:nearest", child),
                                   ("raw-owner", head), ("brigadier-owner", head)]:
                marker = f"ADCR_EXECUTED {owner.name} {command.split(':')[-1]}"
                before = owner.log().count(marker)
                mc.send(client, b"\x02" + mc.string("/" + command))
                wait(lambda: owner.log().count(marker) == before + 1, 15)
            mc.send(client, b"\x02" + mc.string("/blocked"))
            time.sleep(1)
            assert "ADCR_EXECUTED head blocked" not in head.log(), "Denial fell through"
            assert "ADCR_EXECUTED child nearest" in child.log()
            assert head.log().count("ADCR_EXECUTED head nearest") == 1, "Ancestor intercepted"
            mc.send(client, b"\x02" + mc.string("/server lobby.second"))
            wait(lambda: second.identity is not None or second.error is not None, 15)
            assert second.error is None, second.error
            assert second.identity == fixture.identity, "Distributed transfer changed identity"
            mc.send(client, b"\x02" + mc.string("/server second.lobby"))
            wait(lambda: cross_branch.identity is not None or cross_branch.error is not None, 15)
            assert cross_branch.error is None, cross_branch.error
            assert cross_branch.identity == fixture.identity, "Cross-branch transfer changed identity"
            wait(lambda: "ADCR_CONNECTED sibling lobby" in sibling.log()
                         and "ADCR_CONNECTED head second" in head.log(), 10)
            wait(lambda: any(packet[0] == 0x11 and b"second:server" in packet
                             and b"second:nearest" in packet for packet in received), 10)
            mc.send(client, b"\x02" + mc.string("/local:nearest"))
            wait(lambda: "ADCR_EXECUTED sibling nearest" in sibling.log(), 15)
            print("PASS: real player connection, deepest owner, ancestor RPC, namespaces,")
            print("      Brigadier/Simple/Raw, denied owner, head-coordinated terminal transfer")
        except Exception:
            for proxy in proxies:
                print(proxy.name, proxy.log()[-10000:], file=sys.stderr)
            raise
        finally:
            if client:
                client.close()
            fixture.done.set()
            second.done.set()
            cross_branch.done.set()
            for proxy in reversed(proxies):
                proxy.stop()


if __name__ == "__main__":
    main()
