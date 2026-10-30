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

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** An immutable, validated published tree. Socket endpoints deliberately do not belong here. */
public final class Topology {
  /** The role of a published node. */
  public enum Type { HEAD_PROXY, BACKEND_PROXY, MINECRAFT_SERVER }

  /** The connection represented by an edge. */
  public enum Link { PROXY, MINECRAFT }

  /** Local publication policy. */
  public enum Mode { LOCAL, EXPOSED, TRANSPARENT }

  /** Identity and ownership supplied by an already authenticated publication. */
  public record Node(UUID id, UUID networkId, UUID ownerId, String name, Type type) {
    /** Validates the identity and namespace segment. */
    public Node {
      java.util.Objects.requireNonNull(id);
      java.util.Objects.requireNonNull(networkId);
      java.util.Objects.requireNonNull(ownerId);
      java.util.Objects.requireNonNull(type);
      if (name == null || !name.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
        throw new IllegalArgumentException("Invalid APDP name segment");
      }
    }
  }

  /** An explicit parent-child relationship. */
  public record Edge(UUID parent, UUID child, Link type) { }

  /** Depth and initial routing weight are stored separately. */
  public record Position(int depth, int weight, String qualifiedName) { }

  private final UUID head;
  private final Map<UUID, Node> nodes;
  private final Map<UUID, Edge> parents;
  private final Map<UUID, Position> positions;
  private final Map<String, UUID> names;

  /** Validates the entire candidate graph before constructing an immutable view. */
  public Topology(UUID networkId, Collection<Node> input, Collection<Edge> edges,
      int maxNodes, int maxDepth) {
    if (maxNodes < 1 || maxDepth < 0 || input.isEmpty() || input.size() > maxNodes
        || edges.size() != input.size() - 1) {
      throw new IllegalArgumentException("Invalid topology size");
    }
    Map<UUID, Node> byId = new LinkedHashMap<>();
    UUID root = null;
    for (Node node : input) {
      if (!node.networkId().equals(networkId) || byId.putIfAbsent(node.id(), node) != null) {
        throw new IllegalArgumentException("Duplicate identity or foreign network");
      }
      if (node.type() == Type.HEAD_PROXY) {
        if (root != null) {
          throw new IllegalArgumentException("Multiple heads");
        }
        root = node.id();
      }
      if (node.type() != Type.MINECRAFT_SERVER && !node.ownerId().equals(node.id())) {
        throw new IllegalArgumentException("Proxy identity must own itself");
      }
    }
    if (root == null) {
      throw new IllegalArgumentException("Missing head");
    }
    Map<UUID, Edge> byChild = new HashMap<>();
    Map<UUID, java.util.List<UUID>> children = new HashMap<>();
    for (Edge edge : edges) {
      Node parent = byId.get(edge.parent());
      Node child = byId.get(edge.child());
      if (parent == null || child == null || child.id().equals(root)
          || parent.type() == Type.MINECRAFT_SERVER
          || byChild.putIfAbsent(child.id(), edge) != null
          || (child.type() == Type.MINECRAFT_SERVER
              && (!child.ownerId().equals(parent.id()) || edge.type() != Link.MINECRAFT))
          || (child.type() != Type.MINECRAFT_SERVER && edge.type() != Link.PROXY)) {
        throw new IllegalArgumentException("Invalid parent relationship");
      }
      children.computeIfAbsent(parent.id(), ignored -> new java.util.ArrayList<>()).add(child.id());
    }
    Map<UUID, Position> resolved = new HashMap<>();
    Map<String, UUID> qualified = new HashMap<>();
    ArrayDeque<UUID> pending = new ArrayDeque<>();
    resolved.put(root, new Position(0, 0, ""));
    pending.add(root);
    while (!pending.isEmpty()) {
      UUID current = pending.remove();
      Position position = resolved.get(current);
      Set<String> siblings = new HashSet<>();
      for (UUID id : children.getOrDefault(current, java.util.List.of())) {
        Node child = byId.get(id);
        int depth = position.depth() + 1;
        if (depth > maxDepth || resolved.containsKey(id) || !siblings.add(child.name())) {
          throw new IllegalArgumentException("Cycle, duplicate name or excessive depth");
        }
        String name = position.qualifiedName().isEmpty() ? child.name()
            : position.qualifiedName() + "." + child.name();
        resolved.put(id, new Position(depth, depth, name));
        qualified.put(name, id);
        pending.add(id);
      }
    }
    if (resolved.size() != byId.size()) {
      throw new IllegalArgumentException("Disconnected component or cycle");
    }
    head = root;
    nodes = Map.copyOf(byId);
    parents = Map.copyOf(byChild);
    positions = Map.copyOf(resolved);
    names = Map.copyOf(qualified);
  }

  public UUID head() {
    return head;
  }

  public Map<UUID, Node> nodes() {
    return nodes;
  }

  public Map<UUID, Edge> parents() {
    return parents;
  }

  /** Returns derived position, rejecting unknown identities. */
  public Position position(UUID id) {
    Position value = positions.get(id);
    if (value == null) {
      throw new IllegalArgumentException("Unknown node");
    }
    return value;
  }

  public Optional<Node> find(String name) {
    return Optional.ofNullable(names.get(name)).map(nodes::get);
  }

  /** Resolves only an adjacent node, not a socket or connection authorization. */
  public Optional<UUID> nextHop(UUID from, UUID destination) {
    if (!nodes.containsKey(from) || !nodes.containsKey(destination) || from.equals(destination)
        || nodes.get(from).type() == Type.MINECRAFT_SERVER) {
      return Optional.empty();
    }
    UUID child = destination;
    while (parents.containsKey(child)) {
      UUID parent = parents.get(child).parent();
      if (parent.equals(from)) {
        return Optional.of(child);
      }
      child = parent;
    }
    return Optional.ofNullable(parents.get(from)).map(Edge::parent);
  }

  /** Selects direct children from existing exports; never reconstructs a private subtree. */
  public Set<UUID> exportChildren(UUID proxy, Mode mode, Set<String> exposed) {
    if (!nodes.containsKey(proxy) || nodes.get(proxy).type() == Type.MINECRAFT_SERVER) {
      throw new IllegalArgumentException("Unknown proxy");
    }
    Set<UUID> selected = new HashSet<>();
    for (Edge edge : parents.values()) {
      if (edge.parent().equals(proxy) && (mode == Mode.TRANSPARENT
          || (mode == Mode.EXPOSED && exposed.contains(nodes.get(edge.child()).name())))) {
        selected.add(edge.child());
      }
    }
    return Set.copyOf(selected);
  }
}
