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
import static com.velocitypowered.proxy.connection.util.ConnectionRequestResults.successful;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.ConnectionSchedulerConfig;
import com.velocitypowered.proxy.config.ConnectionSchedulerConfig.Policy;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerConnectionQueueTest {

  private final VelocityServer server = mock(VelocityServer.class);
  private final ConnectedPlayer player = mock(ConnectedPlayer.class);
  private final ManualConnectionSchedulerExecutor executor = new ManualConnectionSchedulerExecutor();

  @BeforeEach
  void setUp() {
    when(player.isActive()).thenReturn(true);
  }

  @Test
  void serializesOperationsForTheSamePlayer() {
    final PlayerConnectionQueue queue = queue(config(Policy.SERIALIZED, Duration.ZERO, 2, false, 0));
    final AtomicInteger starts = new AtomicInteger();
    final CompletableFuture<Impl> first = new CompletableFuture<>();
    final CompletableFuture<Impl> second = new CompletableFuture<>();

    final ConnectionOperation firstOperation = operation(destination("lobby"), Policy.SERIALIZED,
        false, operation -> {
          starts.incrementAndGet();
          return first;
        });
    final ConnectionOperation secondOperation = operation(destination("survival"), Policy.SERIALIZED,
        false, operation -> {
          starts.incrementAndGet();
          return second;
        });

    queue.enqueue(firstOperation);
    queue.enqueue(secondOperation);
    executor.runDueTasks();

    assertEquals(1, starts.get());
    assertFalse(secondOperation.getFuture().isDone());

    first.complete(successful(firstOperation.getDestination()));
    executor.runDueTasks();

    assertEquals(2, starts.get());
    assertTrue(firstOperation.getFuture().isDone());
    assertFalse(secondOperation.getFuture().isDone());

    second.complete(successful(secondOperation.getDestination()));
    executor.runDueTasks();

    assertTrue(secondOperation.getFuture().isDone());
  }

  @Test
  void waitsForCooldownAfterPreviousConnectionFinishes() {
    final PlayerConnectionQueue queue =
        queue(config(Policy.COOLDOWN, Duration.ofMillis(100), 2, false, 0));
    final AtomicInteger starts = new AtomicInteger();
    final CompletableFuture<Impl> first = new CompletableFuture<>();

    final ConnectionOperation firstOperation = operation(destination("lobby"), Policy.COOLDOWN,
        false, operation -> {
          starts.incrementAndGet();
          return first;
        });
    final ConnectionOperation secondOperation = operation(destination("survival"), Policy.COOLDOWN,
        false, operation -> {
          starts.incrementAndGet();
          return CompletableFuture.completedFuture(successful(operation.getDestination()));
        });

    queue.enqueue(firstOperation);
    executor.runDueTasks();
    first.complete(successful(firstOperation.getDestination()));
    executor.runDueTasks();

    queue.enqueue(secondOperation);
    executor.runDueTasks();
    assertEquals(1, starts.get());

    executor.advance(Duration.ofMillis(99).toNanos());
    assertEquals(1, starts.get());

    executor.advance(Duration.ofMillis(1).toNanos());
    assertEquals(2, starts.get());
  }

  @Test
  void rejectsNewOperationsWhenTheQueueIsFull() {
    final PlayerConnectionQueue queue =
        queue(config(Policy.SERIALIZED, Duration.ZERO, 1, false, 0));
    final CompletableFuture<Impl> first = new CompletableFuture<>();

    queue.enqueue(operation(destination("lobby"), Policy.SERIALIZED, false, operation -> first));
    queue.enqueue(operation(destination("survival"), Policy.SERIALIZED, false,
        operation -> new CompletableFuture<>()));
    final ConnectionOperation rejected = operation(destination("creative"), Policy.SERIALIZED,
        false, operation -> new CompletableFuture<>());

    queue.enqueue(rejected);
    executor.runDueTasks();

    assertTrue(rejected.getFuture().isDone());
    assertEquals(CONNECTION_CANCELLED, rejected.getFuture().join().getStatus());
    assertEquals(1, queue.scheduler().metrics().snapshot().rejectedQueueFull());
  }

  @Test
  void coalescesPendingOperationsWhenConfigured() {
    final PlayerConnectionQueue queue =
        queue(config(Policy.SERIALIZED, Duration.ZERO, 2, true, 0));
    final AtomicInteger starts = new AtomicInteger();
    final CompletableFuture<Impl> first = new CompletableFuture<>();

    final ConnectionOperation firstOperation = operation(destination("lobby"), Policy.SERIALIZED,
        false, operation -> {
          starts.incrementAndGet();
          return first;
        });
    final ConnectionOperation superseded = operation(destination("survival"), Policy.SERIALIZED,
        false, operation -> {
          starts.incrementAndGet();
          return new CompletableFuture<>();
        });
    final ConnectionOperation latest = operation(destination("creative"), Policy.SERIALIZED,
        false, operation -> {
          starts.incrementAndGet();
          return CompletableFuture.completedFuture(successful(operation.getDestination()));
        });

    queue.enqueue(firstOperation);
    queue.enqueue(superseded);
    queue.enqueue(latest);
    executor.runDueTasks();

    assertEquals(1, starts.get());
    assertEquals(CONNECTION_CANCELLED, superseded.getFuture().join().getStatus());
    assertEquals(1, queue.scheduler().metrics().snapshot().coalesced());

    first.complete(successful(firstOperation.getDestination()));
    executor.runDueTasks();

    assertEquals(2, starts.get());
    assertTrue(latest.getFuture().isDone());
  }

  @Test
  void cancelsOperationThatWaitsTooLongInTheQueue() {
    final PlayerConnectionQueue queue =
        queue(config(Policy.SERIALIZED, Duration.ZERO, 2, false, 0));
    final CompletableFuture<Impl> first = new CompletableFuture<>();

    queue.enqueue(operation(destination("lobby"), Policy.SERIALIZED, false, operation -> first));
    final ConnectionOperation waiting = operation(destination("survival"), Policy.SERIALIZED,
        false, operation -> new CompletableFuture<>());
    queue.enqueue(waiting);
    executor.runDueTasks();

    executor.advance(Duration.ofSeconds(15).toNanos());

    assertTrue(waiting.getFuture().isDone());
    assertEquals(CONNECTION_CANCELLED, waiting.getFuture().join().getStatus());
    assertEquals(1, queue.scheduler().metrics().snapshot().timedOutQueue());
  }

  @Test
  void timesOutRunningOperation() {
    final PlayerConnectionQueue queue =
        queue(config(Policy.SERIALIZED, Duration.ZERO, 2, false, 0));
    final ConnectionOperation operation = operation(destination("lobby"), Policy.SERIALIZED,
        false, ignored -> new CompletableFuture<>());

    queue.enqueue(operation);
    executor.runDueTasks();
    executor.advance(Duration.ofSeconds(30).toNanos());

    final ExecutionException exception =
        assertThrows(ExecutionException.class, () -> operation.getFuture().get());
    assertTrue(exception.getCause() instanceof TimeoutException);
    assertEquals(1, queue.scheduler().metrics().snapshot().timedOutOperation());
  }

  @Test
  void disconnectsBackendWhenOperationTimesOut() {
    final PlayerConnectionQueue queue =
        queue(config(Policy.SERIALIZED, Duration.ZERO, 2, false, 0));
    final ConnectionOperation operation = operation(destination("lobby"), Policy.SERIALIZED,
        false, ignored -> new CompletableFuture<>());
    final VelocityServerConnection connection = mock(VelocityServerConnection.class);
    operation.attachConnection(connection);

    queue.enqueue(operation);
    executor.runDueTasks();
    executor.advance(Duration.ofSeconds(30).toNanos());

    verify(connection).disconnect();
    assertTrue(operation.getFuture().isCompletedExceptionally());
  }

  @Test
  void respectsGlobalConcurrencyLimit() {
    final ConnectionSchedulerConfig config = config(Policy.SERIALIZED, Duration.ZERO, 2, false, 1);
    final ConnectionOperationScheduler scheduler = new ConnectionOperationScheduler(server, config);
    final PlayerConnectionQueue firstQueue =
        new PlayerConnectionQueue(scheduler, player, executor);
    final ConnectedPlayer secondPlayer = mock(ConnectedPlayer.class);
    when(secondPlayer.isActive()).thenReturn(true);
    final PlayerConnectionQueue secondQueue =
        new PlayerConnectionQueue(scheduler, secondPlayer, executor);

    final CompletableFuture<Impl> first = new CompletableFuture<>();
    final AtomicInteger starts = new AtomicInteger();
    final ConnectionOperation firstOperation = operation(player, destination("lobby"),
        Policy.SERIALIZED, false, operation -> {
          starts.incrementAndGet();
          return first;
        });
    final ConnectionOperation secondOperation = operation(secondPlayer, destination("survival"),
        Policy.SERIALIZED, false, operation -> {
          starts.incrementAndGet();
          return CompletableFuture.completedFuture(successful(operation.getDestination()));
        });

    firstQueue.enqueue(firstOperation);
    secondQueue.enqueue(secondOperation);
    executor.runDueTasks();

    assertEquals(1, starts.get());
    assertFalse(secondOperation.getFuture().isDone());
    assertEquals(1, scheduler.metrics().snapshot().globalWaiting());

    first.complete(successful(firstOperation.getDestination()));
    executor.runDueTasks();

    assertEquals(2, starts.get());
    assertTrue(secondOperation.getFuture().isDone());
  }

  @Test
  void serializedPolicyDoesNotWaitForCooldown() {
    final PlayerConnectionQueue queue =
        queue(config(Policy.COOLDOWN, Duration.ofMillis(100), 2, false, 0));
    final AtomicInteger starts = new AtomicInteger();
    final CompletableFuture<Impl> first = new CompletableFuture<>();

    final ConnectionOperation firstOperation = operation(destination("lobby"), Policy.SERIALIZED,
        false, operation -> {
          starts.incrementAndGet();
          return first;
        });
    final ConnectionOperation secondOperation = operation(destination("survival"),
        Policy.SERIALIZED, false, operation -> {
          starts.incrementAndGet();
          return CompletableFuture.completedFuture(successful(operation.getDestination()));
        });

    queue.enqueue(firstOperation);
    executor.runDueTasks();
    first.complete(successful(firstOperation.getDestination()));
    executor.runDueTasks();

    queue.enqueue(secondOperation);
    executor.runDueTasks();

    assertEquals(2, starts.get());
    assertTrue(secondOperation.getFuture().isDone());
  }

  @Test
  void immediatePolicyBypassesCooldown() {
    final PlayerConnectionQueue queue =
        queue(config(Policy.COOLDOWN, Duration.ofMillis(100), 2, false, 0));
    final AtomicInteger starts = new AtomicInteger();
    final CompletableFuture<Impl> first = new CompletableFuture<>();

    final ConnectionOperation firstOperation = operation(destination("lobby"), Policy.COOLDOWN,
        false, operation -> {
          starts.incrementAndGet();
          return first;
        });
    final ConnectionOperation immediate = operation(destination("survival"), Policy.IMMEDIATE,
        false, operation -> {
          starts.incrementAndGet();
          return CompletableFuture.completedFuture(successful(operation.getDestination()));
        });

    queue.enqueue(firstOperation);
    executor.runDueTasks();
    first.complete(successful(firstOperation.getDestination()));
    executor.runDueTasks();

    queue.enqueue(immediate);
    executor.runDueTasks();

    assertEquals(2, starts.get());
    assertTrue(immediate.getFuture().isDone());
  }

  @Test
  void recoveryOperationsBypassCooldownAndQueueTimeout() {
    final PlayerConnectionQueue queue =
        queue(config(Policy.COOLDOWN, Duration.ofMillis(100), 1, false, 0));
    final AtomicInteger starts = new AtomicInteger();
    final CompletableFuture<Impl> first = new CompletableFuture<>();

    final ConnectionOperation firstOperation = operation(destination("lobby"), Policy.COOLDOWN,
        false, operation -> {
          starts.incrementAndGet();
          return first;
        });
    final ConnectionOperation recovery = operation(destination("survival"), Policy.COOLDOWN,
        true, operation -> {
          starts.incrementAndGet();
          return CompletableFuture.completedFuture(successful(operation.getDestination()));
        });

    queue.enqueue(firstOperation);
    executor.runDueTasks();
    first.complete(successful(firstOperation.getDestination()));
    executor.runDueTasks();

    queue.enqueue(recovery);
    executor.runDueTasks();

    assertEquals(2, starts.get());
    assertTrue(recovery.getFuture().isDone());
  }

  @Test
  void coalescingPreservesRecoveryOperations() {
    final PlayerConnectionQueue queue =
        queue(config(Policy.SERIALIZED, Duration.ZERO, 2, true, 0));
    final List<String> order = new ArrayList<>();
    final CompletableFuture<Impl> first = new CompletableFuture<>();

    final ConnectionOperation firstOperation = operation(destination("lobby"), Policy.SERIALIZED,
        false, operation -> {
          order.add("lobby");
          return first;
        });
    final ConnectionOperation recovery = operation(destination("survival"), Policy.COOLDOWN, true,
        operation -> {
          order.add("survival");
          return CompletableFuture.completedFuture(successful(operation.getDestination()));
        });
    final ConnectionOperation latest = operation(destination("creative"), Policy.SERIALIZED, false,
        operation -> {
          order.add("creative");
          return CompletableFuture.completedFuture(successful(operation.getDestination()));
        });

    queue.enqueue(firstOperation);
    queue.enqueue(recovery);
    queue.enqueue(latest);
    executor.runDueTasks();

    first.complete(successful(firstOperation.getDestination()));
    executor.runDueTasks();

    assertEquals(List.of("lobby", "survival", "creative"), order);
    assertTrue(recovery.getFuture().isDone());
    assertTrue(latest.getFuture().isDone());
    assertEquals(0, queue.scheduler().metrics().snapshot().coalesced());
  }

  @Test
  void maxPendingDoesNotCountRecoveryOperations() {
    final PlayerConnectionQueue queue =
        queue(config(Policy.SERIALIZED, Duration.ZERO, 1, false, 0));
    final List<String> order = new ArrayList<>();
    final CompletableFuture<Impl> first = new CompletableFuture<>();

    final ConnectionOperation firstOperation = operation(destination("lobby"), Policy.SERIALIZED,
        false, operation -> {
          order.add("lobby");
          return first;
        });
    final ConnectionOperation recovery = operation(destination("survival"), Policy.COOLDOWN, true,
        operation -> {
          order.add("survival");
          return CompletableFuture.completedFuture(successful(operation.getDestination()));
        });
    final ConnectionOperation normal = operation(destination("creative"), Policy.SERIALIZED, false,
        operation -> {
          order.add("creative");
          return CompletableFuture.completedFuture(successful(operation.getDestination()));
        });
    final ConnectionOperation rejected = operation(destination("skyblock"), Policy.SERIALIZED,
        false, operation -> {
          order.add("skyblock");
          return CompletableFuture.completedFuture(successful(operation.getDestination()));
        });

    queue.enqueue(firstOperation);
    queue.enqueue(recovery);
    queue.enqueue(normal);
    queue.enqueue(rejected);
    executor.runDueTasks();

    assertEquals(CONNECTION_CANCELLED, rejected.getFuture().join().getStatus());

    first.complete(successful(firstOperation.getDestination()));
    executor.runDueTasks();

    assertEquals(List.of("lobby", "survival", "creative"), order);
    assertEquals(1, queue.scheduler().metrics().snapshot().rejectedQueueFull());
  }

  private PlayerConnectionQueue queue(ConnectionSchedulerConfig config) {
    final ConnectionOperationScheduler scheduler = new ConnectionOperationScheduler(server, config);
    return new PlayerConnectionQueue(scheduler, player, executor);
  }

  private static ConnectionSchedulerConfig config(Policy policy, Duration cooldown,
      int maxPending, boolean coalescePending, int maxConcurrent) {
    return new ConnectionSchedulerConfig(true, policy, cooldown, maxPending, coalescePending,
        Duration.ofSeconds(15), Duration.ofSeconds(30), maxConcurrent, true);
  }

  private static ConnectionOperation operation(RegisteredServer destination, Policy policy,
      boolean recovery, ConnectionExecution execution) {
    return operation(null, destination, policy, recovery, execution);
  }

  private static ConnectionOperation operation(ConnectedPlayer player, RegisteredServer destination,
      Policy policy, boolean recovery, ConnectionExecution execution) {
    return new ConnectionOperation(1L, player, destination, null, policy, recovery, execution);
  }

  private static RegisteredServer destination(String name) {
    final RegisteredServer server = mock(RegisteredServer.class);
    when(server.getServerInfo()).thenReturn(
        new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565)));
    return server;
  }
}
