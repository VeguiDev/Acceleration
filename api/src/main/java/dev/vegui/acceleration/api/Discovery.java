/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package dev.vegui.acceleration.api;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Read-only published discovery. Visibility is not permission to connect. */
public interface Discovery {
  /** One published node, without private endpoints or credentials. */
  record Node(UUID id, String name, String qualifiedName, String type, int depth, int weight) { }

  /** Immutable published graph. Parent edges point from child identity to parent identity. */
  record Snapshot(UUID head, Map<UUID, Node> nodes, Map<UUID, UUID> parents) {
    public Snapshot {
      nodes = Map.copyOf(nodes);
      parents = Map.copyOf(parents);
    }
  }

  Optional<UUID> localNode();

  Optional<UUID> head();

  /** Whether the catalog can currently be used for routing queries. */
  boolean current();

  /** Last known graph, which may be stale when {@link #current()} is false. */
  Optional<Snapshot> topology();

  /** Resolves only an adjacent node; never returns a remote socket. */
  Optional<UUID> nextHop(UUID destination);

  /** Called on discovery threads; callbacks must not block. Close to unsubscribe. */
  AutoCloseable listen(Consumer<Snapshot> listener);
}
