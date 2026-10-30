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

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Bounded, process-local deduplication; never retries ambiguous command execution. */
public final class InvocationLedger {
  /** Requests must additionally be authenticated against the actual local player connection. */
  public record Request(UUID id, UUID session, UUID player, long epoch, UUID origin,
                        UUID destination, UUID registration, String command, long expires) {
    /** Rejects malformed identities and oversized command strings before admission. */
    public Request {
      Objects.requireNonNull(id);
      Objects.requireNonNull(session);
      Objects.requireNonNull(player);
      Objects.requireNonNull(origin);
      Objects.requireNonNull(destination);
      Objects.requireNonNull(registration);
      Objects.requireNonNull(command);
      if (epoch < 1 || command.isEmpty() || command.length() > 32767) {
        throw new IllegalArgumentException("Invalid invocation");
      }
    }
  }

  /** Only NOT_REGISTERED permits another owner lookup; uncertain outcomes stop processing. */
  public enum Outcome {
    NOT_REGISTERED, DENIED, EXECUTED, EXPLICIT_FORWARD, UNAVAILABLE, INDETERMINATE
  }

  private record Slot(Request request, CompletableFuture<Outcome> result) { }

  private final Clock clock;
  private final int capacity;
  private final Map<UUID, Slot> entries = new HashMap<>();

  /** Uses an injectable clock and retains at most capacity live request identities. */
  public InvocationLedger(Clock clock, int capacity) {
    this.clock = Objects.requireNonNull(clock);
    if (capacity < 1) {
      throw new IllegalArgumentException("Invalid invocation capacity");
    }
    this.capacity = capacity;
  }

  /** Reserves before calling user code; callers cannot cancel or complete the stored result. */
  public CompletableFuture<Outcome> execute(Request request,
      Supplier<CompletableFuture<Outcome>> operation) {
    final CompletableFuture<Outcome> result;
    synchronized (this) {
      long now = clock.millis();
      if (request.expires() <= now || request.expires() - now > 15000) {
        return CompletableFuture.completedFuture(Outcome.UNAVAILABLE);
      }
      Slot previous = entries.get(request.id());
      if (previous != null) {
        return previous.request().equals(request) ? previous.result().copy()
            : CompletableFuture.completedFuture(Outcome.DENIED);
      }
      entries.values().removeIf(slot -> slot.request().expires() <= now
          && slot.result().isDone());
      if (entries.size() >= capacity) {
        return CompletableFuture.completedFuture(Outcome.UNAVAILABLE);
      }
      result = new CompletableFuture<>();
      entries.put(request.id(), new Slot(request, result));
    }
    try {
      operation.get().whenComplete((outcome, error) -> result.complete(
          error == null && outcome != null ? outcome : Outcome.INDETERMINATE));
    } catch (RuntimeException e) {
      result.complete(Outcome.INDETERMINATE);
    }
    return result.copy();
  }
}
