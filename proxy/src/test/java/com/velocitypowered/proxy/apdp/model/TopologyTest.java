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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TopologyTest {
  private final UUID network = UUID.randomUUID();
  private final Topology.Node head = proxy("head", Topology.Type.HEAD_PROXY);
  private final Topology.Node survival = proxy("survival", Topology.Type.BACKEND_PROXY);
  private final Topology.Node group = proxy("group", Topology.Type.BACKEND_PROXY);
  private final Topology.Node shard = proxy("shard", Topology.Type.BACKEND_PROXY);

  private Topology.Node proxy(String name, Topology.Type type) {
    UUID id = UUID.randomUUID();
    return new Topology.Node(id, network, id, name, type);
  }

  private Topology.Edge edge(Topology.Node parent, Topology.Node child) {
    return new Topology.Edge(parent.id(), child.id(), child.type() == Topology.Type.MINECRAFT_SERVER
        ? Topology.Link.MINECRAFT : Topology.Link.PROXY);
  }

  private Topology tree() {
    return new Topology(network, List.of(head, survival, group, shard),
        List.of(edge(head, survival), edge(survival, group), edge(group, shard)), 4096, 32);
  }

  @Test
  void headAlone() {
    Topology graph = new Topology(network, List.of(head), List.of(), 1, 0);
    assertEquals(head.id(), graph.head());
    assertEquals(0, graph.position(head.id()).depth());
    assertTrue(graph.nextHop(head.id(), head.id()).isEmpty());
  }

  @Test
  void derivesThirdLevelNamesAndNextHops() {
    Topology graph = tree();
    assertEquals(shard, graph.find("survival.group.shard").orElseThrow());
    assertEquals(3, graph.position(shard.id()).depth());
    assertEquals(3, graph.position(shard.id()).weight());
    assertEquals(survival.id(), graph.nextHop(head.id(), shard.id()).orElseThrow());
    assertEquals(group.id(), graph.nextHop(shard.id(), head.id()).orElseThrow());
    assertThrows(UnsupportedOperationException.class, () -> graph.nodes().clear());
  }

  @Test
  void selectsOnlyExistingDirectExports() {
    Topology graph = tree();
    assertEquals(Set.of(), graph.exportChildren(survival.id(), Topology.Mode.LOCAL, Set.of()));
    assertEquals(Set.of(group.id()), graph.exportChildren(survival.id(),
        Topology.Mode.EXPOSED, Set.of("group", "private-not-in-export")));
    assertEquals(Set.of(group.id()), graph.exportChildren(survival.id(),
        Topology.Mode.TRANSPARENT, Set.of()));
    assertEquals(Set.of(), graph.exportChildren(survival.id(),
        Topology.Mode.EXPOSED, Set.of("shard")));
  }

  @Test
  void rejectsDisconnectedCycle() {
    assertThrows(IllegalArgumentException.class, () -> new Topology(network,
        List.of(head, survival, group), List.of(edge(survival, group), edge(group, survival)), 10, 32));
  }

  @Test
  void rejectsDuplicateIdentityAndMultipleHeads() {
    assertThrows(IllegalArgumentException.class, () -> new Topology(network,
        List.of(head, head), List.of(edge(head, head)), 10, 32));
    Topology.Node secondHead = proxy("other", Topology.Type.HEAD_PROXY);
    assertThrows(IllegalArgumentException.class, () -> new Topology(network,
        List.of(head, secondHead), List.of(edge(head, secondHead)), 10, 32));
  }

  @Test
  void rejectsForeignNetwork() {
    assertThrows(IllegalArgumentException.class, () -> new Topology(UUID.randomUUID(),
        List.of(head), List.of(), 10, 32));
  }

  @Test
  void rejectsSiblingCollisionButAllowsNamesInDifferentNamespaces() {
    Topology.Node otherGroup = proxy("group", Topology.Type.BACKEND_PROXY);
    assertThrows(IllegalArgumentException.class, () -> new Topology(network,
        List.of(head, group, otherGroup), List.of(edge(head, group), edge(head, otherGroup)), 10, 32));
    new Topology(network, List.of(head, survival, group, otherGroup),
        List.of(edge(head, group), edge(head, survival), edge(survival, otherGroup)), 10, 32);
  }

  @Test
  void validatesLeavesAndOwnership() {
    UUID id = UUID.randomUUID();
    Topology.Node leaf = new Topology.Node(id, network, survival.id(), "paper",
        Topology.Type.MINECRAFT_SERVER);
    assertThrows(IllegalArgumentException.class, () -> new Topology(network,
        List.of(head, leaf), List.of(edge(head, leaf)), 10, 32));
    Topology graph = new Topology(network, List.of(head, survival, leaf),
        List.of(edge(head, survival), edge(survival, leaf)), 10, 32);
    assertTrue(graph.nextHop(leaf.id(), head.id()).isEmpty());
  }

  @Test
  void enforcesDepthAndSizeLimits() {
    assertThrows(IllegalArgumentException.class, () -> new Topology(network,
        List.of(head, survival), List.of(edge(head, survival)), 1, 32));
    assertThrows(IllegalArgumentException.class, () -> new Topology(network,
        List.of(head, survival), List.of(edge(head, survival)), 10, 0));
  }

  @Test
  void validatesNamesAndCopiesInputs() {
    assertThrows(IllegalArgumentException.class, () -> proxy("a.b", Topology.Type.BACKEND_PROXY));
    List<Topology.Node> nodes = new ArrayList<>(List.of(head));
    Topology graph = new Topology(network, nodes, List.of(), 10, 32);
    nodes.clear();
    assertEquals(1, graph.nodes().size());
  }
}
