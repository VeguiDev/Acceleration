# Connection Operations Scheduler

Acceleration can serialise backend connection requests per player. The scheduler
keeps at most one backend connection in progress for each player and can enforce
a minimum delay between the end of one connection and the start of the next.
This prevents race conditions during rapid server changes, especially with
Geyser/Bedrock clients that may send several switch requests in a short window.

The scheduler is enabled by default. Set `enabled = false` or use the
`IMMEDIATE` policy to restore Velocity's original connection behaviour.

## Configuration

The scheduler is configured in `velocity.toml`:

```toml
[connection-scheduler]
enabled = true
policy = "COOLDOWN"
recovery-bypass = true

[connection-scheduler.per-player]
cooldown = "100ms"
max-pending = 2
coalesce-pending = false
queue-timeout = "15s"
operation-timeout = "30s"

[connection-scheduler.global]
max-concurrent = 0
```

| Key | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Enables the scheduler. When disabled, connections behave as in upstream Velocity. |
| `policy` | `"COOLDOWN"` | `IMMEDIATE`, `SERIALIZED`, or `COOLDOWN`. See the policy table below. |
| `recovery-bypass` | `true` | Critical recovery operations bypass the cooldown, queue limits and queue timeout. They are still serialised and still have an operation timeout. |
| `per-player.cooldown` | `"100ms"` | Minimum time between the end of one connection and the start of the next. Accepts `"100ms"`, `"1s"`, `"2m"`, or an integer number of milliseconds. |
| `per-player.max-pending` | `2` | Maximum number of operations waiting behind the running connection. `0` means unlimited. |
| `per-player.coalesce-pending` | `false` | When `true`, a new request replaces all pending requests for the player. The running connection is never interrupted. |
| `per-player.queue-timeout` | `"15s"` | Maximum time an operation may wait in the queue before it is cancelled. |
| `per-player.operation-timeout` | `"30s"` | Maximum time a backend connection may take before it is disconnected. |
| `global.max-concurrent` | `0` | Maximum number of backend connections in progress across all players. `0` means unlimited. |

Durations accept a number (milliseconds) or a string with an `ms`, `s`, or `m`
suffix. Invalid values stop the proxy at startup with a configuration error.

## Policies

| Policy | Behaviour |
| --- | --- |
| `IMMEDIATE` | No queue and no cooldown. The operation still cannot overlap with another connection for the same player; it is placed at the front of that player's queue. It bypasses `max-pending` and the global limit. |
| `SERIALIZED` | Operations for the same player are executed in FIFO order with no cooldown. The global limit is respected. |
| `COOLDOWN` | FIFO queue with the configured cooldown between the end of one connection and the start of the next. This is the default. |

`enabled = false` is equivalent to bypassing the scheduler entirely and
preserves the original Velocity behaviour.

## Operation lifecycle

1. A plugin, command, or internal recovery path calls
   `ConnectionRequestBuilder#connect()`, `connectWithIndication()`, or
   `fireAndForget()`.
2. The request is validated against the player's current connection. If the
   player is already connected to the destination, the existing
   `ALREADY_CONNECTED` result is returned.
3. If the scheduler is enabled, the operation is queued for that player.
4. Before the socket is opened, the scheduler revalidates the player state. An
   operation for a disconnected player, or one that has become obsolete, is
   completed with `CONNECTION_CANCELLED` without opening a connection.
5. The connection runs. The next operation starts only after the current one
   finishes and the cooldown has elapsed.
6. The scheduler releases the global permit and completes the caller's future
   with the normal `ConnectionRequestBuilder.Result`.

All queue state is confined to the player's Netty event loop. Waiting for a
cooldown or a global permit uses scheduled tasks, never a blocking `sleep` or
`Future#get()` on the event loop.

## Coalescing and obsolescence

With `coalesce-pending = false`, requests are handled in FIFO order. If the
number of waiting operations reaches `max-pending`, new requests are rejected
with `CONNECTION_CANCELLED`.

With `coalesce-pending = true`, a new request cancels the waiting requests for
that player and takes their place. The operation that is already running is
never cancelled, and pending recovery or `IMMEDIATE` operations are preserved
so a failover cannot be discarded by a normal request. This is useful when only
the latest destination matters, such as a player quickly clicking several
server signs.

Operations also become obsolete when the player disconnects. `teardown()`
cancels the player's queue before the in-flight connection is closed, so a new
connection cannot start for a player that is leaving.

## Timeouts

* **Queue timeout**: an operation that waits longer than `queue-timeout` is
  cancelled with `CONNECTION_CANCELLED`.
* **Operation timeout**: a connection that takes longer than
  `operation-timeout` is disconnected and its future completes exceptionally
  with `TimeoutException`. The normal `connectWithIndication()` error handling
  then triggers the configured fallback behaviour.

Recovery operations created by `recovery-bypass = true` skip the queue timeout
so that a failover is not cancelled while it waits for a slow backend. They
still have the operation timeout and remain serialised with other operations.

## Recovery operations

The initial server connection and the `KickedFromServerEvent.RedirectPlayer`
failover path are marked as recovery operations. With `recovery-bypass = true`
they:

* ignore the per-player cooldown,
* ignore `max-pending`,
* ignore the global concurrency limit,
* do not receive a queue timeout.

They never bypass the "one connection per player" rule. This keeps failover
responsive without allowing two simultaneous backend connections for the same
player.

ADCR transfers use the `SERIALIZED` policy, so a distributed move does not pay
the cooldown but still respects the per-player queue.

## Metrics

Use `/acceleration connections` (permission `velocity.command.connections`) to
inspect the active configuration and the in-memory counters:

* submitted, started, succeeded, failed, cancelled
* superseded/coalesced operations and queue-full rejections
* queue and operation timeouts
* immediate and recovery bypasses
* cooldown waits and global-permit waits
* current queued, running, global-waiting and player queue counts
* average queue wait and connection duration

The counters are also available through
`VelocityServer#getConnectionScheduler()` and its `metrics()` snapshot for
plugins or diagnostics.

## Compatibility

The scheduler is internal to the proxy. It does not add values to
`ConnectionRequestBuilder.Status`, does not change the public API, and does not
alter `ServerPreConnectEvent` timing. Rejected and cancelled operations use the
existing `CONNECTION_CANCELLED` status; operation timeouts complete
exceptionally, just like a socket failure.

`Player#transferToHost(...)` sends a client-side transfer packet and is not a
backend connection request, so it is not queued by this scheduler.
