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

package com.velocitypowered.proxy.apdp.model;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Discovery-only catalog. Never registers descendants in the local ServerMap. */
public final class DiscoveredCatalog {
  private volatile Topology topology;
  private volatile boolean current;
  private final CopyOnWriteArrayList<Consumer<Topology>> listeners = new CopyOnWriteArrayList<>();

  public Optional<Topology> topology() {
    return Optional.ofNullable(topology);
  }

  public Optional<Topology.Node> find(String qualifiedName) {
    return topology().flatMap(tree -> tree.find(qualifiedName));
  }

  public Optional<UUID> nextHop(UUID from, UUID destination) {
    return current ? topology().flatMap(tree -> tree.nextHop(from, destination)) : Optional.empty();
  }

  public void markStale() {
    current = false;
  }

  public boolean current() {
    return current;
  }

  /** Listener exceptions cannot prevent atomic publication or delivery to other subscribers. */
  public void replace(Topology candidate) {
    topology = candidate;
    current = true;
    for (Consumer<Topology> listener : listeners) {
      try {
        listener.accept(candidate);
      } catch (RuntimeException ignored) {
        // A discovery subscriber does not control the network's catalog.
      }
    }
  }

  public AutoCloseable listen(Consumer<Topology> listener) {
    listeners.add(java.util.Objects.requireNonNull(listener));
    return () -> listeners.remove(listener);
  }
}
