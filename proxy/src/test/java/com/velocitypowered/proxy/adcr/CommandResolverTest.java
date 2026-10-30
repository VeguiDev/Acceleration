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

package com.velocitypowered.proxy.adcr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CommandResolverTest {
  @Test
  void nearestOwnerAndUnavailableNeverFallThrough() {
    UUID head = UUID.randomUUID();
    UUID child = UUID.randomUUID();
    CommandRoute route = new CommandRoute(UUID.randomUUID(), UUID.randomUUID(), 1,
        List.of(head, child));
    LocalCommandRegistry.Entry entry = new LocalCommandRegistry.Entry(UUID.randomUUID(), "test",
        List.of("test"), LocalCommandRegistry.Kind.SIMPLE);
    LocalCommandRegistry.Snapshot populated = new LocalCommandRegistry.Snapshot(1,
        Map.of("test", entry));
    assertEquals(child, CommandResolver.select(route, child, "test args", Map.of(),
        Map.of(head, populated, child, populated)).owner());
    assertEquals(CommandResolver.Status.UNAVAILABLE,
        CommandResolver.select(route, child, "test", Map.of(), Map.of(head, populated)).status());
    assertEquals(head, CommandResolver.select(route, child, "head:test", Map.of(),
        Map.of(head, populated)).owner());
    assertEquals(CommandResolver.Status.INVALID_NAMESPACE,
        CommandResolver.select(route, child, child + ":test", Map.of(),
            Map.of(child, populated)).status());
    assertEquals(CommandResolver.Status.FORWARD,
        CommandResolver.select(route, child, "unknown", Map.of(),
            Map.of(head, populated, child, populated)).status());
  }

  @Test
  void namespacesUsePublishedSlugsNotNodeIds() {
    UUID head = UUID.randomUUID();
    UUID child = UUID.randomUUID();
    UUID sibling = UUID.randomUUID();
    CommandRoute route = new CommandRoute(UUID.randomUUID(), UUID.randomUUID(), 1,
        List.of(head, child));
    LocalCommandRegistry.Entry entry = new LocalCommandRegistry.Entry(UUID.randomUUID(), "test",
        List.of("test"), LocalCommandRegistry.Kind.SIMPLE);
    var catalogs = Map.of(child, new LocalCommandRegistry.Snapshot(1, Map.of("test", entry)));
    var names = Map.of(head, "root", child, "survival");
    assertEquals(child, CommandResolver.select(route, child, "survival:test", names,
        catalogs).owner());
    assertEquals(CommandResolver.Status.INVALID_NAMESPACE, CommandResolver.select(route, child,
        child + ":test", names, catalogs).status());
    assertEquals(CommandResolver.Status.INVALID_NAMESPACE, CommandResolver.select(route, child,
        "survival:test", Map.of(child, "survival", sibling, "survival"), catalogs).status());
    assertEquals(CommandResolver.Status.INVALID_NAMESPACE, CommandResolver.select(route, child,
        "private:test", Map.of(sibling, "private"), catalogs).status());
  }

  @Test
  void routeGenerationChangesAndRejectsCycles() {
    UUID head = UUID.randomUUID();
    CommandRoute route = new CommandRoute(UUID.randomUUID(), UUID.randomUUID(), 1, List.of(head));
    assertEquals(2, route.moved(List.of(head, UUID.randomUUID())).epoch());
    assertThrows(IllegalArgumentException.class, () -> route.moved(List.of(UUID.randomUUID())));
    assertThrows(IllegalArgumentException.class, () -> route.moved(List.of(head, head)));
  }

  @Test
  void duplicateExecutionIsSharedButCallerCannotCancelStoredResult() {
    InvocationLedger ledger = new InvocationLedger(Clock.systemUTC(), 1);
    InvocationLedger.Request request = new InvocationLedger.Request(UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), "test", System.currentTimeMillis() + 10000);
    AtomicInteger executions = new AtomicInteger();
    CompletableFuture<InvocationLedger.Outcome> pending = new CompletableFuture<>();
    CompletableFuture<InvocationLedger.Outcome> first = ledger.execute(request, () -> {
      executions.incrementAndGet();
      return pending;
    });
    first.cancel(false);
    CompletableFuture<InvocationLedger.Outcome> second = ledger.execute(request, () -> {
      executions.incrementAndGet();
      return pending;
    });
    pending.complete(InvocationLedger.Outcome.EXECUTED);
    assertEquals(InvocationLedger.Outcome.EXECUTED, second.join());
    assertEquals(1, executions.get());
    InvocationLedger.Request conflict = new InvocationLedger.Request(request.id(), request.session(),
        request.player(), request.epoch(), request.origin(), request.destination(),
        request.registration(), "different", request.expires());
    assertEquals(InvocationLedger.Outcome.DENIED,
        ledger.execute(conflict, () -> pending).join());
  }
}
