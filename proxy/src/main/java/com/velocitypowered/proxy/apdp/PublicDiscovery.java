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

package com.velocitypowered.proxy.apdp;

import com.velocitypowered.proxy.apdp.model.Topology;
import dev.vegui.acceleration.api.Discovery;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Converts internal discovery into immutable public data, without leaking transport state. */
public final class PublicDiscovery implements Discovery {
  private final ApdpService service;

  public PublicDiscovery(ApdpService service) {
    this.service = service;
  }

  @Override
  public Optional<UUID> localNode() {
    return Optional.ofNullable(service.localNode());
  }

  @Override
  public Optional<UUID> head() {
    return Optional.ofNullable(service.head());
  }

  @Override
  public boolean current() {
    return service.catalog().current();
  }

  @Override
  public Optional<Snapshot> topology() {
    return service.catalog().topology().map(PublicDiscovery::snapshot);
  }

  private static Snapshot snapshot(Topology tree) {
    var nodes = new HashMap<UUID, Discovery.Node>();
    tree.nodes().forEach((id, node) -> {
      var position = tree.position(id);
      nodes.put(id, new Discovery.Node(id, node.name(), position.qualifiedName(),
          node.type().name(), position.depth(), position.weight()));
    });
    var parents = new HashMap<UUID, UUID>();
    tree.parents().forEach((id, edge) -> parents.put(id, edge.parent()));
    return new Snapshot(tree.head(), nodes, parents);
  }

  @Override
  public Optional<UUID> nextHop(UUID destination) {
    return localNode().flatMap(local -> service.catalog().nextHop(local, destination));
  }

  @Override
  public AutoCloseable listen(Consumer<Snapshot> listener) {
    java.util.Objects.requireNonNull(listener);
    return service.catalog().listen(tree -> listener.accept(snapshot(tree)));
  }
}
