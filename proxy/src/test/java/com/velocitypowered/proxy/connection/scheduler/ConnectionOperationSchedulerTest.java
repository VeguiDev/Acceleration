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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.ConnectionSchedulerConfig;
import com.velocitypowered.proxy.config.ConnectionSchedulerConfig.Policy;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import io.netty.channel.EventLoop;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectionOperationSchedulerTest {

  private final VelocityServer server = mock(VelocityServer.class);
  private final ConnectedPlayer player = mock(ConnectedPlayer.class);
  private final MinecraftConnection connection = mock(MinecraftConnection.class);
  private final EventLoop eventLoop = mock(EventLoop.class);

  @BeforeEach
  void setUp() {
    when(player.isActive()).thenReturn(true);
    when(player.getConnection()).thenReturn(connection);
    when(connection.eventLoop()).thenReturn(eventLoop);
    when(eventLoop.inEventLoop()).thenReturn(true);
  }

  @Test
  void disabledSchedulerExecutesOperationDirectly() {
    final ConnectionOperationScheduler scheduler =
        new ConnectionOperationScheduler(server, config(false, Policy.COOLDOWN, Duration.ofMillis(100)));
    final RegisteredServer destination = destination("lobby");

    final CompletableFuture<?> future = scheduler.submit(player, destination, null,
        Policy.COOLDOWN, false, operation -> CompletableFuture.completedFuture(
            successful(operation.getDestination())));

    assertTrue(future.join() instanceof com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl);
    final ConnectionSchedulerMetrics.Snapshot snapshot = scheduler.metrics().snapshot();
    assertEquals(1, snapshot.submitted());
    assertEquals(1, snapshot.started());
    assertEquals(1, snapshot.succeeded());
    assertEquals(0, snapshot.queued());
    assertEquals(0, snapshot.running());
  }

  @Test
  void inactivePlayerIsCancelledWithoutExecuting() {
    when(player.isActive()).thenReturn(false);
    final ConnectionOperationScheduler scheduler =
        new ConnectionOperationScheduler(server, config(false, Policy.COOLDOWN, Duration.ofMillis(100)));

    final var result = scheduler.submit(player, destination("lobby"), null, Policy.COOLDOWN, false,
        operation -> {
          throw new AssertionError("operation must not run for an inactive player");
        }).join();

    assertEquals(CONNECTION_CANCELLED, result.getStatus());
    assertEquals(1, scheduler.metrics().snapshot().cancelled());
  }

  @Test
  void enabledSchedulerQueuesAndCompletesOperation() {
    final ConnectionOperationScheduler scheduler =
        new ConnectionOperationScheduler(server, config(true, Policy.SERIALIZED, Duration.ZERO));

    final var result = scheduler.submit(player, destination("lobby"), null, Policy.SERIALIZED,
        false, operation -> CompletableFuture.completedFuture(
            successful(operation.getDestination()))).join();

    assertTrue(result.isSuccessful());
    final ConnectionSchedulerMetrics.Snapshot snapshot = scheduler.metrics().snapshot();
    assertEquals(1, snapshot.submitted());
    assertEquals(1, snapshot.started());
    assertEquals(1, snapshot.succeeded());
    assertEquals(0, snapshot.queued());
    assertEquals(0, snapshot.running());
    assertEquals(1, snapshot.playersWithQueues());
  }

  @Test
  void cancelAllRejectsQueuedOperations() {
    final ConnectionOperationScheduler scheduler =
        new ConnectionOperationScheduler(server, config(true, Policy.SERIALIZED, Duration.ZERO));
    final CompletableFuture<Impl> running = new CompletableFuture<>();

    final CompletableFuture<?> first = scheduler.submit(player, destination("lobby"), null,
        Policy.SERIALIZED, false, operation -> running);
    final CompletableFuture<?> second = scheduler.submit(player, destination("survival"), null,
        Policy.SERIALIZED, false, operation -> new CompletableFuture<>());

    scheduler.cancelAll(player);

    assertTrue(second.isDone());
    assertEquals(CONNECTION_CANCELLED, ((com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl)
        second.join()).getStatus());
    assertEquals(false, first.isDone());
  }

  private static ConnectionSchedulerConfig config(boolean enabled, Policy policy,
      Duration cooldown) {
    return new ConnectionSchedulerConfig(enabled, policy, cooldown, 2, false,
        Duration.ofSeconds(15), Duration.ofSeconds(30), 0, true);
  }

  private static RegisteredServer destination(String name) {
    final RegisteredServer server = mock(RegisteredServer.class);
    when(server.getServerInfo()).thenReturn(
        new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565)));
    return server;
  }
}
