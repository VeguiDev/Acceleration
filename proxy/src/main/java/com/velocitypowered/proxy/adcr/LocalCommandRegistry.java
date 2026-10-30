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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Metadata only: this catalog cannot execute commands or grant permissions. */
public final class LocalCommandRegistry {
  /** API command families supported by Velocity. */
  public enum Kind {
    BRIGADIER, SIMPLE, RAW
  }

  /** A registration identity changes even when a replacement has identical aliases. */
  public record Entry(UUID registration, String name, List<String> aliases, Kind kind) {
    /** Copies aliases so published metadata cannot be mutated by a subscriber. */
    public Entry {
      Objects.requireNonNull(registration);
      Objects.requireNonNull(name);
      Objects.requireNonNull(kind);
      aliases = List.copyOf(aliases);
      if (aliases.isEmpty() || !aliases.contains(name)) {
        throw new IllegalArgumentException("Registration must contain its primary alias");
      }
    }
  }

  /** An immutable full replacement; consumers must ignore older revisions. */
  public record Snapshot(long revision, Map<String, Entry> aliases) {
    public Snapshot {
      aliases = Map.copyOf(aliases);
    }
  }

  private volatile Snapshot snapshot = new Snapshot(0, Map.of());
  private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
  private final AtomicLong notifiedRevision = new AtomicLong();

  public Snapshot snapshot() {
    return snapshot;
  }

  /** Called under the command manager's write lock; never invokes subscriber code. */
  public synchronized Snapshot capture(Map<String, Entry> aliases) {
    if (snapshot.aliases().equals(aliases)) {
      return snapshot;
    }
    snapshot = new Snapshot(Math.incrementExact(snapshot.revision()), aliases);
    return snapshot;
  }

  /** Called after releasing command locks. Delivery can race; revisions order updates. */
  public void notifyListeners(Snapshot update) {
    long previous;
    do {
      previous = notifiedRevision.get();
      if (update.revision() <= previous) {
        return;
      }
    } while (!notifiedRevision.compareAndSet(previous, update.revision()));
    for (Consumer<Snapshot> listener : listeners) {
      try {
        listener.accept(update);
      } catch (RuntimeException ignored) {
        // A broken discovery subscriber must not break plugin registration.
      }
    }
  }

  /** Registers an observer without replay; read snapshot after subscribing. */
  public AutoCloseable listen(Consumer<Snapshot> listener) {
    listeners.add(Objects.requireNonNull(listener));
    return () -> listeners.remove(listener);
  }
}
