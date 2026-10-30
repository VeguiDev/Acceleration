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

import com.google.gson.JsonObject;
import com.velocitypowered.proxy.apdp.identity.Membership;
import com.velocitypowered.proxy.apdp.identity.Signed;
import com.velocitypowered.proxy.apdp.protocol.Wire;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Recursive export proof: an ancestor can omit a child export, never expand its private data. */
public record Publication(UUID node, Signed member, String name, long revision,
                          List<String> leaves, List<Signed> children) {
  /** Verifies every origin signature and constructs a validated authoritative tree. */
  public static Topology topology(Signed publication, String root, UUID network,
      int maxNodes, int maxDepth) throws IOException, GeneralSecurityException {
    List<Topology.Node> nodes = new ArrayList<>();
    List<Topology.Edge> edges = new ArrayList<>();
    collect(publication, root, network, null, 0, maxDepth, maxNodes, nodes, edges, new HashSet<>());
    return new Topology(network, nodes, edges, maxNodes, maxDepth);
  }

  /** Validates a complete origin branch before an intermediate accepts or relays it. */
  public static void validateBranch(Signed publication, String root, UUID network, UUID parent,
      int maxNodes, int maxDepth) throws IOException, GeneralSecurityException {
    List<Topology.Node> nodes = new ArrayList<>();
    List<Topology.Edge> edges = new ArrayList<>();
    collect(publication, root, network, parent, 0, maxDepth, maxNodes, nodes, edges, new HashSet<>());
    Set<UUID> ids = new HashSet<>();
    java.util.Map<UUID, Topology.Node> indexed = new java.util.HashMap<>();
    java.util.Map<UUID, Set<String>> names = new java.util.HashMap<>();
    for (Topology.Node node : nodes) {
      if (!ids.add(node.id())) {
        throw new IOException("Duplicate branch identity");
      }
      indexed.put(node.id(), node);
    }
    for (Topology.Edge edge : edges) {
      if (!names.computeIfAbsent(edge.parent(), ignored -> new HashSet<>())
          .add(indexed.get(edge.child()).name())) {
        throw new IOException("Duplicate branch name");
      }
    }
  }

  /** Verifies one origin's export against its root-signed membership. */
  public static Publication verify(Signed signed, String root)
      throws IOException, GeneralSecurityException {
    JsonObject untrusted = Wire.parse(Wire.unb64(signed.body()));
    Signed assertion = Signed.decode(untrusted.getAsJsonObject("member"));
    Membership member = Membership.verify(assertion, root);
    JsonObject object = signed.verify(member.signing());
    Wire.fields(object, "node", "member", "name", "revision", "leaves", "children");
    Wire.integer(object, "revision", 1, Long.MAX_VALUE);
    Publication publication = Wire.GSON.fromJson(object, Publication.class);
    if (!member.node().equals(publication.node) || publication.name == null
        || !publication.name.matches("[a-z0-9][a-z0-9_-]{0,63}")
        || publication.revision < 1 || publication.leaves == null || publication.children == null
        || publication.leaves.size() + publication.children.size() > 4096) {
      throw new IOException("Invalid APDP publication");
    }
    return publication;
  }

  private static void collect(Signed signed, String root, UUID network, UUID parent,
      int depth, int maxDepth, int maxNodes, List<Topology.Node> nodes,
      List<Topology.Edge> edges, Set<UUID> visited) throws IOException, GeneralSecurityException {
    if (depth > maxDepth || nodes.size() >= maxNodes) {
      throw new IOException("APDP graph bounds exceeded");
    }
    Publication publication = verify(signed, root);
    Membership member = Membership.verify(publication.member, root);
    if (!member.network().equals(network) || !visited.add(member.node())
        || (parent == null ? !member.parent().equals(member.node())
            : !member.parent().equals(parent))) {
      throw new IOException("Invalid publication ancestry");
    }
    nodes.add(new Topology.Node(member.node(), network, member.node(), publication.name,
        parent == null ? Topology.Type.HEAD_PROXY : Topology.Type.BACKEND_PROXY));
    if (parent != null) {
      edges.add(new Topology.Edge(parent, member.node(), Topology.Link.PROXY));
    }
    for (String leaf : publication.leaves) {
      UUID id = leafId(member.node(), leaf);
      if (nodes.size() >= maxNodes) {
        throw new IOException("APDP graph bounds exceeded");
      }
      nodes.add(new Topology.Node(id, network, member.node(), leaf, Topology.Type.MINECRAFT_SERVER));
      edges.add(new Topology.Edge(member.node(), id, Topology.Link.MINECRAFT));
    }
    for (Signed child : publication.children) {
      collect(child, root, network, member.node(), depth + 1, maxDepth, maxNodes,
          nodes, edges, visited);
    }
  }

  public static UUID leafId(UUID owner, String name) {
    return UUID.nameUUIDFromBytes(("APDP/leaf/" + owner + "/" + name)
        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
