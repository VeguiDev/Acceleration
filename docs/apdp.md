# APDP v1

APDP is Acceleration's independent encrypted discovery/control plane. It does not
transfer players, synchronize plugins, authorize Minecraft connections or implement
distributed fallback. Conventional installations create no identity or control listener.

## Bootstrap

Head console:
```text
apdp init network
apdp invite-file /private/handoff.token 600 delegate
```

Securely deliver that file to the child, then run on its console:
```text
apdp join-file /private/handoff.token
apdp status
```

Remove the administrator-owned handoff file after enrollment. `delegate` lets the child
invite its own children. Repeat through any authorized parent; no direct connection to
the head is needed. Invitations default to 600 seconds (range 1–86400), admit one
identity, and are atomically consumed alongside membership at the head. Identical
persisted retries recover a committed enrollment even after expiry; other identities fail.

For Docker/Pterodactyl mount a protected token file and set `APDP_JOIN_TOKEN_FILE`.
Persist `apdp-state`. There are no manual keys, certificates or shared network password.
Tokens are out-of-band trust anchors: complete substitution also substitutes the root.

Raw `apdp join <token>` and private in-game `apdp invite [seconds] [delegate]` also
exist. Prefer file commands: plugins can observe raw command events and core redaction
cannot prevent plugin logging. APDP commands are excluded from console history. Console
invitations require `invite-file`; output files are mode 0600 and never overwritten.
Bearers are not retained in APDP config, authority, identity or pending state.

## Configuration

Separate `apdp.toml` (not a table under the scalar `velocity.toml` proxy-backend setting):
```toml
name = "survival"
listen = "0.0.0.0:25650"
advertise = "proxy.example.net:25650"
parent-address = "parent.example.net:25650"
publication = "exposed"
exposed = ["public", "games"]
proxy-destinations = ["games"]
max-nodes = 4096
max-depth = 64
max-peers = 128
weight = -1 # optional independent local weight; -1 defaults to depth
```

Defaults bind loopback on an allocated port that is persisted. Configure bind,
advertised endpoint, firewall/NAT **before issuing invitations** for remote machines.
Wildcard binding requires an advertised endpoint. `APDP_ADVERTISE_ADDRESS` and
`APDP_PARENT_ADDRESS` override runtime endpoints without rewriting TOML. Listener
changes require restart; publication changes reload automatically. After editing a
parent endpoint run `apdp parent reconnect`. Endpoint changes never reparent a node
or change its cryptographic identity.

Command edits preserve comments/unknown fields, lock writers, detect racing edits and
replace atomically. Do not edit state files. Back up the complete `apdp-state` securely:
directory mode 0700, private identity mode 0600. Missing/corrupt enrolled identities fail
closed. POSIX permissions, hard links, atomic rename and directory fsync are required;
unsupported filesystems fail rather than silently weaken durability.

## Publication

* `local`: this proxy only.
* `exposed`: selected direct child/leaf names from `exposed`.
* `transparent`: all eligible direct destinations and their already-exported subtrees.

Minecraft servers are leaves and run no APDP. List configured proxy destinations in
`proxy-destinations` so they are not advertised as Minecraft leaves before enrollment.
Use matching child/local destination names. Sibling names are unique and match
`[a-z0-9][a-z0-9_-]{0,63}`. Every proxy signs its selected export: ancestors may omit
branches but cannot expand private child exports. Announcements recurse upward; the
head validates/signs the authoritative published tree and sends it downward. Views
contain IDs/names/types/edges, never private socket addresses. They do not authorize
connections. Public sibling branches are visible; v1 has no additional recipient ACLs.

Head depth is zero, child depth is parent depth plus one; independent local weight
defaults to depth. Persistent UUIDs and one active parent identify proxies. The discovered
catalog is separate from `ServerMap`: descendants are not registered as direct sockets.
Internal APIs expose local/head identity, depth/weight, immutable topology, lookup,
listeners and adjacent `nextHop`. Stale views remain diagnostic but yield no routing hints.

## Administration

```text
apdp status
apdp peers
apdp topology
apdp routes
apdp resync
apdp invite list
apdp invite revoke <invitation-uuid>
apdp parent info
apdp parent setaddress <host:port>
apdp parent reconnect
apdp node revoke <node-uuid>
apdp node permissions <node-uuid> <true|false>
```

Node revocation and invitation-permission changes require the head. Issuers revoke
their own invitations. Removing invitation permission invalidates pending invitations,
not accepted children. Node revocation invalidates descendant authorization. Signed
monotonic policies remove known revoked branches and close their sessions. Permission
nodes are `acceleration.apdp.<first-operation>`; grant only to trusted administrators.

## Security and availability

Pinned `Noise_XK_25519_ChaChaPoly_SHA256` uses Signal's
`org.signal.forks:noise-java:0.1.1` (MIT, Southern Storm). Ed25519 head assertions bind
network/node, both public keys, parent, permission and generation. Enrollment proves
both child keys; every new session requires fresh head approval bound to its handshake
hash. No TOFU or plaintext fallback. Tests are **not an independent security audit**.
Noise protects APDP only, not Minecraft traffic.

Existing authenticated sessions survive head loss. Enrollment/reconnection waits for
the head instead of trusting stale revocation caches. An isolated peer cannot learn
a new revocation until connectivity returns. No election, consensus, multiparent,
automatic reparenting or cross-head reconciliation is implemented.

HELLO/AUTH/NODE_ANNOUNCE/UPDATE/TOPOLOGY_SNAPSHOT/WITHDRAW/HEARTBEAT/RESYNC/ERROR frames
have strict schemas and session sequences. CONTROL/RESULT relay signed authority
requests. Updates are complete validated replacements. Origin high-water marks persist
across disconnect/restart; snapshot/policy revisions reject rollback. Withdrawals remove
subtrees. Logical messages are at most 1 MiB, fragmented into bounded Noise records.
Supported maxima: 4096 nodes, depth 256, 512 peers (defaults 4096/64/128). The serialized
1 MiB limit may be reached first, particularly for deeply nested signed exports.
Handshakes, RPCs, collections, invitations, idle sessions and stalled writes are bounded.
Invalid/oversized exports are rejected, never partially installed. All ancestors' limits
must accommodate an export; this is not an unlimited-depth/resource implementation.

## Verification

```sh
./gradlew build
python3 tests/integration/apdp_network.py proxy/build/libs/acceleration-proxy-4.3.2-SNAPSHOT-all.jar
```

Tests cover published Noise vectors, pinned-key failure, fragmentation, strict parsing,
config races, identity persistence, concurrent single-use enrollment, expired committed
retries, head partitions, durable pending recovery and revocation. The integration
harness runs four real JVMs with zero players: head → survival → group → shard, with
public/private leaf fixtures. It checks recursive filtering, depth 3, downward views,
routing hints, publication reload, withdrawal and tokenless restart. Leaves are endpoint
fixtures, not Paper processes. Conventional/Modern Forwarding regression uses
`tests/integration/proxy_chain.py`; see [proxy-backend.md](proxy-backend.md).
