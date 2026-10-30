# Acceleration proxy backend

## Conventional mode

`velocity.toml` remains the configuration filename. With `proxy-backend = false`
(the default), use the normal Velocity configuration and authentication flow.

## Global Velocity -> Acceleration -> Paper

Global Velocity's `velocity.toml`:

```toml
online-mode = true
player-info-forwarding-mode = "modern"
forwarding-secret-file = "global.secret"

[servers]
region = "127.0.0.1:25566"
try = ["region"]
```

Acceleration's `velocity.toml`:

```toml
bind = "127.0.0.1:25566"
proxy-backend = true
online-mode = true
proxy-backend-secret-file = "global.secret"
player-info-forwarding-mode = "modern"
forwarding-secret-file = "paper.secret"

[servers]
paper = "127.0.0.1:25567"
try = ["paper"]
```

These are excerpts: remove or update default forced-host mappings when replacing
the default server list. Copy the **contents** of the global forwarding secret to
Acceleration's incoming secret file. If `proxy-backend-secret-file` is absent,
Acceleration reuses its existing forwarding secret (including the existing
`VELOCITY_FORWARDING_SECRET` environment override).

On current Paper, set `online-mode=false` in `server.properties` and configure
`proxies.velocity.enabled=true`, `proxies.velocity.online-mode=true` and
`proxies.velocity.secret` to the contents of `paper.secret` in
`config/paper-global.yml`. Follow the corresponding version's configuration for
older Paper and Paper-derived servers.

**Do not set Acceleration's online-mode=false merely to avoid double authentication.**
In backend mode it describes the trusted upstream's online policy, and must match
the upstream. No local encryption request or Mojang session lookup is performed.
HMAC authenticates the forwarding proxy, not an independent Mojang login claim.
An offline upstream can assert offline identities; it must never be treated as online.

## Security and compatibility

- Restrict Acceleration's port to trusted upstreams using private networking/firewalls.
  Modern Forwarding authenticates data but does not encrypt this transport or prevent replay.
- Wrong secrets, malformed payloads, invalid keys and missing forwarding are rejected.
- Original IP, UUID, username and signed texture properties are preserved. The original
  client TCP port is not available in this protocol.
- Incoming mode and secret changes require a restart. Do not expose secret files or logs
  from third-party plugins. Core diagnostic configuration excludes secret fields.
- Backend logins have an aggregate pre-authentication ceiling of 100 attempts/second;
  authenticated original IPs additionally use the ordinary login limiter.
- Modern Forwarding requires Minecraft 1.13+. Existing protocol support otherwise applies,
  but support is not a claim that every multi-proxy/version combination has been tested.
- Events and local server connections use the normal Velocity pipeline. Plugins may reject
  login but cannot disable mandatory forwarding through PreLoginEvent mode overrides.
- `LoginEvent.serverIdHash` is null because there was no local Mojang authentication.
  Plugins requiring that authentication or transport encryption need adaptation.
- `/acceleration` is the information command; `/velocity` remains an alias. Permissions,
  API packages and the virtual `velocity` plugin identifier are unchanged.
- Upstream bStats reporting and publication destinations are disabled for the fork;
  a fork-specific metrics identifier/publication repository has not been configured.
- Global commands may shadow local commands. No command, plugin or event synchronization
  is provided. Signed-chat restrictions are not disabled.

## Automated verification

```sh
./gradlew build
python3 tests/integration/proxy_chain.py \
  proxy/build/libs/acceleration-proxy-4.3.2-SNAPSHOT-all.jar \
  --upstream-jar /path/to/unmodified/velocity-all.jar
```

The Python harness starts actual proxy JVMs, compiles a plugin against the upstream
API, and uses a socket-level Minecraft 1.13 client and terminal backend fixture.
It verifies conventional login, a two-proxy chain, authenticated outgoing identity,
local server switching without reconnecting the client, essential events/commands,
and rejection with an incorrect incoming secret. Secrets for each hop differ.
Without `--upstream-jar`, the global proxy uses Acceleration in conventional mode.
No Minecraft server EULA or account credentials are needed.

Unit tests additionally verify texture properties, IPv6, HMAC tampering, truncation
and interoperability with Velocity's serializer across protocol versions.

### Verification limits

The fixture is **not Paper**, and upstream authentication is offline in this test.
Real online-account authentication, Paper/Folia/Luminol binaries, compression,
1.19 chat signing/secure profiles, 1.20.2+ CONFIG/reconfiguration, repeated switches
and ViaVersion still require a broader integration matrix before production use.
Do not interpret the fixture test as certification of these combinations.
