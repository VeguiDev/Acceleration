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

import com.velocitypowered.proxy.config.ConnectionSchedulerConfig;
import com.velocitypowered.proxy.config.ConnectionSchedulerConfig.Policy;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The per-player connection operation queue.
 *
 * <p>All mutable state in this class is confined to the player's event loop.</p>
 */
final class PlayerConnectionQueue {

  private final ConnectionOperationScheduler scheduler;
  private final ConnectedPlayer player;
  private final ConnectionSchedulerExecutor executor;
  private final ArrayDeque<ConnectionOperation> pending = new ArrayDeque<>();
  private final Map<String, Integer> destinations = new ConcurrentHashMap<>();

  private @Nullable ConnectionOperation running;
  private @Nullable ScheduledFuture<?> cooldownTask;
  private long lastFinishedNanos;
  private boolean hasFinished;
  private boolean closed;

  PlayerConnectionQueue(ConnectionOperationScheduler scheduler, ConnectedPlayer player) {
    this(scheduler, player, new NettyConnectionSchedulerExecutor(
        player.getConnection().eventLoop()));
  }

  PlayerConnectionQueue(ConnectionOperationScheduler scheduler, ConnectedPlayer player,
      ConnectionSchedulerExecutor executor) {
    this.scheduler = scheduler;
    this.player = player;
    this.executor = executor;
  }

  void enqueue(ConnectionOperation operation) {
    if (executor.inEventLoop()) {
      enqueueOnLoop(operation);
    } else {
      executor.execute(() -> enqueueOnLoop(operation));
    }
  }

  boolean isPending(String serverName) {
    return destinations.containsKey(serverName.toLowerCase(Locale.ROOT));
  }

  private void removeDestination(String serverName) {
    destinations.computeIfPresent(serverName.toLowerCase(Locale.ROOT),
        (key, count) -> count <= 1 ? null : count - 1);
  }

  ConnectionOperationScheduler scheduler() {
    return scheduler;
  }

  private boolean isBypassingQueue(ConnectionOperation operation) {
    return operation.getPolicy() == Policy.IMMEDIATE
        || (operation.isRecovery() && scheduler.config().recoveryBypass());
  }

  private int countQueuedOperations() {
    int count = 0;
    for (ConnectionOperation queued : pending) {
      if (!isBypassingQueue(queued)) {
        count++;
      }
    }
    return count;
  }

  private void coalescePending() {
    final Iterator<ConnectionOperation> iterator = pending.iterator();
    while (iterator.hasNext()) {
      final ConnectionOperation queued = iterator.next();
      if (!isBypassingQueue(queued)) {
        iterator.remove();
        cancelPending(queued, true, scheduler.metrics()::recordCoalesced);
      }
    }
  }

  private void enqueueOnLoop(ConnectionOperation operation) {
    final ConnectionSchedulerConfig config = scheduler.config();
    if (closed || !player.isActive()) {
      operation.markCancelled();
      scheduler.metrics().recordCancelled();
      operation.completeFuture(plainResult(CONNECTION_CANCELLED, operation.getDestination()));
      return;
    }

    operation.markEnqueued(executor.nanoTime());
    final boolean bypass = operation.isRecovery() && config.recoveryBypass();
    if (!bypass) {
      operation.setQueueTimeout(executor.schedule(
          () -> queueTimedOut(operation), config.queueTimeout().toNanos()));
    }

    final boolean immediate = operation.getPolicy() == Policy.IMMEDIATE
        || bypass
        || !config.enabled();

    if (immediate) {
      if (cooldownTask != null) {
        cooldownTask.cancel(false);
        cooldownTask = null;
      }
      pending.addFirst(operation);
      scheduler.metrics().recordImmediateBypass();
    } else if (config.coalescePending()) {
      coalescePending();
      pending.addLast(operation);
    } else {
      final int maxPending = config.maxPending();
      if (maxPending > 0 && countQueuedOperations() >= maxPending) {
        scheduler.metrics().recordRejectedQueueFull();
        scheduler.metrics().recordCancelled();
        operation.markCancelled();
        operation.completeFuture(plainResult(CONNECTION_CANCELLED, operation.getDestination()));
        return;
      }
      pending.addLast(operation);
    }

    destinations.merge(operation.getDestination().getServerInfo().getName()
        .toLowerCase(Locale.ROOT), 1, Integer::sum);
    scheduler.metrics().queueDepthIncrement();
    drain(immediate);
  }

  private void drain(boolean forceImmediate) {
    if (closed || running != null) {
      return;
    }

    final ConnectionSchedulerConfig config = scheduler.config();
    final boolean ignoreCooldown = forceImmediate || !config.enabled();

    while (running == null) {
      final ConnectionOperation head = pending.peekFirst();
      if (head == null) {
        return;
      }
      if (head.isCancelled() || head.isFinished()) {
        pending.removeFirst();
        removeDestination(head.getDestination().getServerInfo().getName());
        scheduler.metrics().queueDepthDecrement();
        continue;
      }

      final boolean immediate = ignoreCooldown || head.getPolicy() == Policy.IMMEDIATE
          || (head.isRecovery() && config.recoveryBypass());

      if (!immediate && head.getPolicy() == Policy.COOLDOWN && hasFinished) {
        final long notBefore = lastFinishedNanos + config.cooldown().toNanos();
        final long now = executor.nanoTime();
        if (now < notBefore) {
          scheduler.metrics().recordCooldownWait();
          scheduleCooldown(notBefore - now);
          return;
        }
      }

      if (!immediate && config.maxConcurrent() > 0 && !head.hasPermit()) {
        final boolean acquired = scheduler.globalLimiter().acquireOrEnqueue(head,
            () -> executor.execute(() -> drain(false)));
        if (!acquired) {
          scheduler.metrics().recordGlobalWait();
          scheduler.metrics().globalWaitingIncrement();
          head.markWaitingGlobal();
          return;
        }
        if (scheduler.config().maxConcurrent() > 0) {
          head.setPermit(true);
        }
      }

      if (head.getState() == ConnectionOperation.State.WAITING_GLOBAL) {
        scheduler.metrics().globalWaitingDecrement();
      }

      pending.removeFirst();
      running = head;
      final long now = executor.nanoTime();
      head.markRunning(now);
      scheduler.metrics().recordStarted(now - head.getEnqueuedAtNanos());
      head.cancelQueueTimeout();
      head.setOperationTimeout(executor.schedule(
          () -> operationTimedOut(head), config.operationTimeout().toNanos()));

      final CompletableFuture<Impl> future;
      try {
        future = head.getExecution().execute(head);
      } catch (Throwable throwable) {
        finish(head, null, throwable);
        continue;
      }

      future.whenComplete((result, error) -> {
        if (executor.inEventLoop()) {
          finish(head, result, error);
        } else {
          executor.execute(() -> finish(head, result, error));
        }
      });
      return;
    }
  }

  private void scheduleCooldown(long delayNanos) {
    if (cooldownTask != null && !cooldownTask.isDone()) {
      return;
    }
    cooldownTask = executor.schedule(() -> {
      cooldownTask = null;
      drain(false);
    }, delayNanos);
  }

  private void finish(ConnectionOperation operation, @Nullable Impl result,
      @Nullable Throwable error) {
    if (!operation.markFinished()) {
      return;
    }

    final long now = executor.nanoTime();
    final long runNanos = operation.getStartedAtNanos() == 0
        ? 0 : now - operation.getStartedAtNanos();

    if (operation.hasPermit()) {
      operation.setPermit(false);
      scheduler.globalLimiter().release();
    }
    running = null;
    lastFinishedNanos = now;
    hasFinished = true;
    removeDestination(operation.getDestination().getServerInfo().getName());

    final boolean success = error == null && result != null && result.isSuccessful();
    scheduler.metrics().recordFinished(runNanos, success);

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

    drain(false);
  }

  private void queueTimedOut(ConnectionOperation operation) {
    if (operation.isFinished() || operation.getState() == ConnectionOperation.State.RUNNING) {
      return;
    }
    cancelPending(operation, () -> {
      scheduler.metrics().recordQueueTimeout();
      scheduler.metrics().recordCancelled();
    });
    drain(false);
  }

  private void operationTimedOut(ConnectionOperation operation) {
    if (running != operation || operation.isFinished() || operation.isTimedOut()) {
      return;
    }
    operation.setTimedOut();
    operation.cancelOperationTimeout();
    scheduler.metrics().recordOperationTimeout();

    final TimeoutException timeout =
        new TimeoutException("Backend connection timed out for " + operation.getDestination());

    final VelocityServerConnection connection = operation.getConnection();
    if (connection != null) {
      // Close the underlying channel before releasing the queue slot. The connect future is not
      // guaranteed to complete when we abort a handshake, so we must finish the operation here
      // instead of waiting for it forever.
      connection.disconnect();
      final ConnectedPlayer player = operation.getPlayer();
      if (player != null && player.getConnectionInFlight() == connection) {
        player.resetInFlightConnection();
      }
    }
    finish(operation, null, timeout);
  }

  private void cancelPending(ConnectionOperation operation, Runnable metric) {
    cancelPending(operation, pending.remove(operation), metric);
  }

  private void cancelPending(ConnectionOperation operation, boolean removed, Runnable metric) {
    if (operation.isFinished()) {
      if (removed) {
        removeDestination(operation.getDestination().getServerInfo().getName());
        scheduler.metrics().queueDepthDecrement();
      }
      return;
    }
    final boolean wasWaitingGlobal =
        operation.getState() == ConnectionOperation.State.WAITING_GLOBAL;
    operation.markCancelled();
    if (operation.hasPermit()) {
      operation.setPermit(false);
      scheduler.globalLimiter().release();
    }
    if (wasWaitingGlobal) {
      scheduler.globalLimiter().remove(operation);
      scheduler.metrics().globalWaitingDecrement();
    }
    if (removed) {
      removeDestination(operation.getDestination().getServerInfo().getName());
      scheduler.metrics().queueDepthDecrement();
    }
    metric.run();
    operation.completeFuture(plainResult(CONNECTION_CANCELLED, operation.getDestination()));
  }

  void cancelAll() {
    if (!executor.inEventLoop()) {
      executor.execute(this::cancelAll);
      return;
    }
    closed = true;
    if (cooldownTask != null) {
      cooldownTask.cancel(false);
      cooldownTask = null;
    }
    while (!pending.isEmpty()) {
      final ConnectionOperation operation = pending.removeFirst();
      final boolean wasWaitingGlobal =
          operation.getState() == ConnectionOperation.State.WAITING_GLOBAL;
      operation.markCancelled();
      if (operation.hasPermit()) {
        operation.setPermit(false);
        scheduler.globalLimiter().release();
      }
      if (wasWaitingGlobal) {
        scheduler.globalLimiter().remove(operation);
        scheduler.metrics().globalWaitingDecrement();
      }
      removeDestination(operation.getDestination().getServerInfo().getName());
      scheduler.metrics().queueDepthDecrement();
      scheduler.metrics().recordCancelled();
      scheduler.metrics().recordSuperseded();
      operation.completeFuture(plainResult(CONNECTION_CANCELLED, operation.getDestination()));
    }
    // The running operation is intentionally left alone. It will finish on its own and will
    // observe that the queue is closed when it tries to drain the next operation.
  }

  void reconfigure() {
    if (!executor.inEventLoop()) {
      executor.execute(this::reconfigure);
      return;
    }
    final ConnectionSchedulerConfig config = scheduler.config();
    if (config.coalescePending()) {
      keepNewestQueuedOperation();
    }
    if (!config.coalescePending() && config.maxPending() > 0) {
      trimQueuedOperations(config.maxPending());
    }
    drain(false);
  }

  private void keepNewestQueuedOperation() {
    ConnectionOperation newest = null;
    for (ConnectionOperation queued : pending) {
      if (!isBypassingQueue(queued)) {
        newest = queued;
      }
    }
    if (newest == null) {
      return;
    }
    final Iterator<ConnectionOperation> iterator = pending.iterator();
    while (iterator.hasNext()) {
      final ConnectionOperation queued = iterator.next();
      if (queued != newest && !isBypassingQueue(queued)) {
        iterator.remove();
        cancelPending(queued, true, scheduler.metrics()::recordCoalesced);
      }
    }
  }

  private void trimQueuedOperations(int maxPending) {
    int queuedCount = countQueuedOperations();
    final Iterator<ConnectionOperation> iterator = pending.descendingIterator();
    while (queuedCount > maxPending && iterator.hasNext()) {
      final ConnectionOperation queued = iterator.next();
      if (!isBypassingQueue(queued)) {
        iterator.remove();
        queuedCount--;
        cancelPending(queued, true, scheduler.metrics()::recordRejectedQueueFull);
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
