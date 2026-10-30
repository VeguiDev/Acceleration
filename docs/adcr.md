# Acceleration Distributed Command Registry (ADCR)

ADCR uses the existing [APDP](apdp.md) Noise connections. It does not install a
second command framework, register remote commands in the local dispatcher, or
require plugins to use a new API. Unenrolled proxies and ordinary console/API
command execution retain Velocity's local behavior.

## Enabling and session authentication

Initialize and enroll the proxies with APDP. Use Modern Forwarding between
proxies, with the receiving proxy in `proxy-backend` mode. ADCR capabilities are
negotiated over APDP; incompatible peers are not sent command RPCs.

During Minecraft login, `acceleration:command_route` binds the actual outgoing
server connection to an authenticated APDP neighbor using a fresh, single-use
handoff. The receiving proxy claims it over Noise. A player UUID alone is not
proof of a route. Each route carries a session UUID and generation; disconnects
and backend changes invalidate the corresponding handles.

## Resolution and permissions

The deepest proxy with the player's real connection checks its registered
commands first, followed by its ancestors up to HEAD. The first registered owner
wins. A denied command does **not** fall through to a more permissive ancestor.
If nobody owns an unqualified command, the original Minecraft forwarding path
is retained. An explicit Brigadier `FORWARD` result also preserves forwarding.

Supported explicit namespaces:

* `/local:command`: the deepest proxy;
* `/head:command`: HEAD;
* `/<nodeSlug>:command`: a globally unambiguous published proxy on the active route
  (Minecraft leaf server names do not participate in this namespace).
  The slug is its APDP `nodeName` (the `name` setting in `apdp.toml`), which already
  uses lowercase letters, digits, hyphens and underscores. For example,
  `/survival:command`. UUID command prefixes are no longer accepted or advertised;
  UUIDs remain internal protocol identifiers. `head` and `local` are reserved aliases.

Unknown, ambiguous and off-route generic command targets are rejected. Owners
execute using their **real local Player**, original permission predicates and
plugin executor. There is no remote-console execution or synthetic permission
grant. Output travels through that player's normal Minecraft connection.

Registrations, replacements and removals made through the standard Velocity
command manager are observed automatically, including aliases and Brigadier,
Simple and Raw command registrations. Replacement changes the registration ID;
execution checks that ID again before invoking the plugin.

## Global `/server` and `/send`

The original built-ins default to HEAD coordination, preserving their permission
rules. A plugin that replaces either registration remains an ordinary command
owner. `/local:server` selects the existing local built-in explicitly.

* `/server` lists public destinations.
* `/server <destination>` moves the invoking player.
* `/send <player|all|current> <destination>` selects players known to HEAD;
  `current` means the same terminal destination, not merely the same first branch.

Destinations are public qualified names, unambiguous names or published UUIDs.
Private destinations and socket addresses are not exposed. HEAD signs short-lived
session-bound transfer intents; intermediate proxies perform real local connection
requests along adjacent topology hops. Normal connection events still apply,
including plugin vetoes and redirections. Success requires final destination
confirmation, not just connection to the first proxy. Partial failure is reported
without blind rollback or automatic retry. Console commands remain local.

## Client trees and suggestions

Root-label completion includes permitted command names and published nodeSlug
prefixes (for example `/surv` or `/head:`), not only command arguments. Refresh
fingerprints are recorded only after a PLAY-state delivery, so a refresh skipped
during configuration is retried instead of being treated as already delivered.
Namespaced child built-ins remain advertised even though unqualified `/server`
and `/send` belong to the head. Their head-owned destination completion uses
public qualified paths (`survival.survival-back1`); `/send` also offers player
names, `all`, and `current`, subject to the owner's normal permissions.

Permission-filtered Brigadier descriptions are assembled along the real route.
A nearer denied registration masks the ancestor's unqualified alias. Explicit
namespaces expose only the corresponding permitted owner's description.
Descriptions contain data, not executable code or serialized predicates.
Unsupported argument types and redirects use a server-parsed text fallback;
the owner's parser remains authoritative. Remote suggestions are bounded and
permission checked; remote tooltip transport is not implemented.

Client trees refresh approximately every two seconds when in PLAY, after a
backend command tree has been received. Registration and permission changes are
therefore eventually reflected without plugin modifications. Available-command
events continue to apply. Tree delivery is polling-based, not instantaneous push.

## Failure and security behavior

RPCs are bounded, correlated, expiring and authenticated to an adjacent peer and
an active player session. Process-local invocation ledgers reserve IDs before
side effects and share matching duplicate results. Conflicting IDs are denied.
This is **not durable exactly-once execution across process crashes**.
Timeouts and uncertain execution are not retried or silently forwarded.

Loss of APDP does not make ancestors steal commands from an already-bound deeper
proxy. Local deepest commands remain usable; unavailable remote operations fail
closed. Signed command arguments are forwarded unchanged before distributed
execution can cause side effects: ADCR does not strip signatures, weaken secure
chat, or claim that signed commands can be intercepted safely.

## Verification

Run from the repository root:

```sh
./gradlew build
python3 tests/integration/adcr_commands.py proxy/build/libs/acceleration-proxy-4.3.2-SNAPSHOT-all.jar
python3 tests/integration/apdp_network.py proxy/build/libs/acceleration-proxy-4.3.2-SNAPSHOT-all.jar
```

The ADCR fixture uses three independent proxy JVMs, a real Minecraft 1.13
protocol connection and an ordinary Velocity API plugin. It verifies deepest and
ancestor execution, namespaces, denied-owner behavior, all three command families,
permission-filtered trees, remote suggestions, local terminal switching and
cross-branch transfers with preserved player identity. Unit tests cover registry
updates, route constraints, deduplication and malformed command descriptions.

This is not certification of the full production protocol matrix: authenticated
online accounts, modern CONFIG/reconfiguration, compression combinations and
signed-chat interoperability still require dedicated end-to-end coverage.
There has been no independent security audit. Updating a running testnet is a
separate deployment operation; building this implementation does not deploy it.
