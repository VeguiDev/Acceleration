/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.scheduler;

import static com.velocitypowered.api.proxy.ConnectionRequestBuilder.Status.CONNECTION_CANCELLED;
import static com.velocitypowered.proxy.connection.util.ConnectionRequestResults.plainResult;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.ConnectionSchedulerConfig;
import com.velocitypowered.proxy.config.ConnectionSchedulerConfig.Policy;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Centralises backend connection and transfer operations.
 *
 * <p>The scheduler keeps one FIFO queue per player. Operations for a single player are always
 * executed sequentially, with an optional cooldown between the end of one operation and the start
 * of the next. All queue mutations happen on the player's Netty event loop, so the scheduler never
 * blocks a thread while waiting for a connection to finish.</p>
 */
public final class ConnectionOperationScheduler implements AutoCloseable {

  private final VelocityServer server;
  private final ConnectionSchedulerMetrics metrics = new ConnectionSchedulerMetrics();
  private final GlobalConnectionLimiter globalLimiter = new GlobalConnectionLimiter();
  private final ConcurrentMap<ConnectedPlayer, PlayerConnectionQueue> queues =
      new ConcurrentHashMap<>();
  private final AtomicLong operationIds = new AtomicLong();

  private volatile ConnectionSchedulerConfig config;
  private volatile boolean closed;

  /**
   * Creates a new scheduler.
   *
   * @param server the proxy server
   * @param config the scheduler configuration
   */
  public ConnectionOperationScheduler(VelocityServer server, ConnectionSchedulerConfig config) {
    this.server = Objects.requireNonNull(server, "server");
    this.config = Objects.requireNonNull(config, "config");
    this.globalLimiter.reconfigure(config.maxConcurrent());
  }

  /**
   * Returns the active configuration.
   *
   * @return the configuration
   */
  public ConnectionSchedulerConfig config() {
    return config;
  }

  /**
   * Returns the scheduler metrics.
   *
   * @return the metrics
   */
  public ConnectionSchedulerMetrics metrics() {
    return metrics;
  }

  /**
   * Applies a new configuration to the scheduler.
   *
   * @param newConfig the new configuration
   */
  public void reconfigure(ConnectionSchedulerConfig newConfig) {
    this.config = Objects.requireNonNull(newConfig, "newConfig");
    this.globalLimiter.reconfigure(newConfig.maxConcurrent());
    for (PlayerConnectionQueue queue : queues.values()) {
      queue.reconfigure();
    }
  }

  /**
   * Submits a backend connection operation.
   *
   * @param player the player requesting the operation
   * @param destination the destination server
   * @param previousServer the previous server, if any
   * @param policy the scheduling policy
   * @param recovery whether this is a critical recovery operation
   * @param execution the operation body
   * @return a future completed when the operation finishes
   */
  public CompletableFuture<Impl> submit(ConnectedPlayer player, RegisteredServer destination,
      @Nullable VelocityRegisteredServer previousServer, Policy policy, boolean recovery,
      ConnectionExecution execution) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(execution, "execution");

    final ConnectionOperation operation = new ConnectionOperation(operationIds.incrementAndGet(),
        player, destination, previousServer, policy, recovery, execution);
    metrics.recordSubmitted();

    if (closed) {
      operation.markCancelled();
      metrics.recordCancelled();
      operation.completeFuture(plainResult(CONNECTION_CANCELLED, destination));
      return operation.getFuture();
    }

    if (!config.enabled()) {
      executeDirect(operation);
      return operation.getFuture();
    }

    queueFor(player).enqueue(operation);
    return operation.getFuture();
  }

  /**
   * Cancels all queued operations for a player.
   *
   * <p>The operation that is already running is intentionally left alone; it will finish on its
   * own and must never overlap with another backend connection.</p>
   *
   * @param player the player
   */
  public void cancelAll(ConnectedPlayer player) {
    final PlayerConnectionQueue queue = queues.remove(player);
    if (queue != null) {
      metrics.playersWithQueuesDecrement();
      queue.cancelAll();
    }
  }

  /**
   * Returns whether the player has a queued or running operation for the given server.
   *
   * @param player the player
   * @param serverName the server name
   * @return {@code true} when an operation is pending for that server
   */
  public boolean isPending(ConnectedPlayer player, String serverName) {
    final PlayerConnectionQueue queue = queues.get(player);
    return queue != null && queue.isPending(serverName);
  }

  @Override
  public void close() {
    closed = true;
    for (ConnectedPlayer player : List.copyOf(queues.keySet())) {
      cancelAll(player);
    }
    queues.clear();
  }

  GlobalConnectionLimiter globalLimiter() {
    return globalLimiter;
  }

  private PlayerConnectionQueue queueFor(ConnectedPlayer player) {
    return queues.computeIfAbsent(player, key -> {
      metrics.playersWithQueuesIncrement();
      return new PlayerConnectionQueue(this, key);
    });
  }

  private void executeDirect(ConnectionOperation operation) {
    final Runnable task = () -> {
      final ConnectedPlayer player = operation.getPlayer();
      if (closed || !player.isActive()) {
        operation.markCancelled();
        metrics.recordCancelled();
        operation.completeFuture(plainResult(CONNECTION_CANCELLED, operation.getDestination()));
        return;
      }

      final long now = System.nanoTime();
      operation.markEnqueued(now);
      operation.markRunning(now);
      metrics.recordStartedDirect();

      final CompletableFuture<Impl> future;
      try {
        future = operation.getExecution().execute(operation);
      } catch (Throwable throwable) {
        finishDirect(operation, null, throwable, now);
        return;
      }

      future.whenComplete((result, error) -> {
        final Runnable finish = () -> finishDirect(operation, result, error, now);
        if (player.getConnection().eventLoop().inEventLoop()) {
          finish.run();
        } else {
          player.getConnection().eventLoop().execute(finish);
        }
      });
    };

    if (operation.getPlayer().getConnection().eventLoop().inEventLoop()) {
      task.run();
    } else {
      operation.getPlayer().getConnection().eventLoop().execute(task);
    }
  }

  private void finishDirect(ConnectionOperation operation, @Nullable Impl result,
      @Nullable Throwable error, long startedAtNanos) {
    if (!operation.markFinished()) {
      return;
    }

    final long runNanos = System.nanoTime() - startedAtNanos;
    final boolean success = error == null && result != null && result.isSuccessful();
    metrics.recordFinished(runNanos, success);

    if (!operation.isFutureDone()) {
      if (error != null) {
        operation.completeExceptionally(unwrap(error));
      } else if (result == null) {
        operation.completeExceptionally(
            new IllegalStateException("Connection operation completed without a result"));
      } else {
        operation.completeFuture(result);
      }
    }
  }

  private static Throwable unwrap(Throwable error) {
    if (error instanceof CompletionException && error.getCause() != null) {
      return error.getCause();
    }
    return error;
  }
}
