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

package com.velocitypowered.proxy.apdp.identity;

import com.google.gson.JsonObject;
import com.velocitypowered.proxy.apdp.config.AtomicFile;
import com.velocitypowered.proxy.apdp.protocol.Wire;
import com.velocitypowered.proxy.apdp.transport.NoiseChannel;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Single-head durable membership transactions. All mutation commits before acknowledgment. */
public final class Authority {
  private final NodeIdentity identity;
  private final Path file;
  private final String root;
  private final int maxNodes;
  private final int maxDepth;
  private State state;
  private boolean failed;

  /** Database has no bearer tokens: invitation IDs, hashes and committed bindings only. */
  public record State(UUID network, long revision, Map<UUID, Signed> members,
                      Map<UUID, Consumption> consumed, Set<UUID> revokedInvites,
                      Set<UUID> revokedNodes) { }

  /** Identity and request are bound atomically to a single invitation consumption. */
  public record Consumption(UUID node, String noise, String signing, UUID request, Signed member) { }

  /** Signed invitation metadata. The secret is transported separately, never stored here. */
  public record Ticket(int version, UUID network, String root, UUID issuer, String endpoint,
                       UUID invitation, long expires, String secretHash, Signed parent,
                       boolean canInvite) { }

  /** Signed end-to-end control request, relayed unchanged through parents. */
  public record Request(UUID id, UUID actor, long expires, String operation, JsonObject payload) { }

  /** Creates a new network or loads its existing, non-regenerable authority database. */
  public Authority(NodeIdentity identity, Path file, boolean create)
      throws IOException, GeneralSecurityException {
    this(identity, file, create, 4096, 256);
  }

  /** Applies head-wide resource limits in addition to each proxy's local admission limits. */
  public Authority(NodeIdentity identity, Path file, boolean create, int maxNodes, int maxDepth)
      throws IOException, GeneralSecurityException {
    this.identity = identity;
    this.file = file;
    this.maxNodes = maxNodes;
    this.maxDepth = maxDepth;
    root = Wire.b64(identity.signing().getPublic().getEncoded());
    if (create) {
      UUID network = UUID.randomUUID();
      Signed member = Signed.sign(new Membership(network, identity.nodeId(), identity.nodeId(),
          Wire.b64(NoiseChannel.publicKey(identity)), root, true, 1),
          identity.signing().getPrivate());
      state = new State(network, 1, Map.of(identity.nodeId(), member), Map.of(), Set.of(), Set.of());
      AtomicFile.write(file, Wire.encode(state), true);
    } else {
      state = Wire.GSON.fromJson(Wire.parse(AtomicFile.read(file, Wire.MAX)), State.class);
      if (state.network == null || state.members == null || state.consumed == null
          || state.revokedNodes == null || state.revokedInvites == null || state.revision < 1) {
        throw new IOException("Corrupt APDP authority database");
      }
      for (Signed member : state.members.values()) {
        if (!Membership.verify(member, root).network().equals(state.network)) {
          throw new IOException("Foreign APDP authority membership");
        }
      }
    }
  }

  public synchronized State snapshot() {
    return new State(state.network, state.revision, Map.copyOf(state.members),
        Map.copyOf(state.consumed), Set.copyOf(state.revokedInvites), Set.copyOf(state.revokedNodes));
  }

  public String root() {
    return root;
  }

  /** Authenticates, validates, durably commits and only then signs a control response. */
  public synchronized Signed execute(Signed signed) throws IOException, GeneralSecurityException {
    if (failed) {
      throw new IOException("Authority requires recovery after storage failure");
    }
    // Extract only the actor to select its verification key, then trust nothing until verification.
    JsonObject untrusted = Wire.parse(Wire.unb64(signed.body()));
    UUID actorId = UUID.fromString(Wire.string(untrusted, "actor", 36));
    Membership actor = active(state, actorId);
    JsonObject verified = signed.verify(actor.signing());
    Wire.fields(verified, "id", "actor", "expires", "operation", "payload");
    Wire.integer(verified, "expires", 1, Long.MAX_VALUE);
    Request request = Wire.GSON.fromJson(verified, Request.class);
    long now = System.currentTimeMillis();
    if (request.id == null || request.expires < now || request.expires > now + 60000) {
      throw new IOException("Expired APDP control request");
    }
    State candidate = new State(state.network, state.revision + 1,
        new HashMap<>(state.members), new HashMap<>(state.consumed),
        new HashSet<>(state.revokedInvites), new HashSet<>(state.revokedNodes));
    JsonObject result = new JsonObject();
    boolean changed = false;
    switch (request.operation) {
      case "ENROLL":
        Wire.fields(request.payload, "ticket", "specification");
        Signed ticketSigned = Signed.decode(request.payload.getAsJsonObject("ticket"));
        Ticket ticket = ticket(ticketSigned, actor.signing());
        if (!ticket.issuer.equals(actorId)
            || !ticket.network.equals(state.network) || !ticket.root.equals(root)) {
          throw new IOException("Enrollment not authorized");
        }
        JsonObject specification = request.payload.getAsJsonObject("specification");
        Wire.fields(specification, "node", "noise", "signing", "request");
        UUID node = UUID.fromString(Wire.string(specification, "node", 36));
        String noise = Wire.string(specification, "noise", 64);
        String signing = Wire.string(specification, "signing", 128);
        UUID enrollment = UUID.fromString(Wire.string(specification, "request", 36));
        Consumption previous = state.consumed.get(ticket.invitation);
        if (previous != null) {
          if (!previous.node.equals(node) || !previous.noise.equals(noise)
              || !previous.signing.equals(signing) || !previous.request.equals(enrollment)) {
            throw new IOException("Invitation already consumed");
          }
          active(state, node);
          result.add("member", Wire.object(previous.member));
          break;
        }
        if (!actor.canInvite()
            || Membership.verify(ticket.parent, root).generation() != actor.generation()
            || ticket.expires < now || state.revokedInvites.contains(ticket.invitation)
            || state.members.containsKey(node) || state.revokedNodes.contains(node)
            || state.members.size() >= maxNodes || Wire.unb64(noise).length != 32
            || Wire.unb64(signing).length != 44) {
          throw new IOException("Enrollment rejected");
        }
        int actorDepth = 0;
        Membership enrollmentAncestor = actor;
        while (!enrollmentAncestor.node().equals(identity.nodeId())) {
          actorDepth++;
          enrollmentAncestor = Membership.verify(state.members.get(enrollmentAncestor.parent()), root);
        }
        if (actorDepth + 1 > maxDepth) {
          throw new IOException("Enrollment depth limit reached");
        }
        for (Signed registered : state.members.values()) {
          Membership existing = Membership.verify(registered, root);
          if (existing.noise().equals(noise) || existing.signing().equals(signing)) {
            throw new IOException("Duplicate APDP key");
          }
        }
        Signed membership = Signed.sign(new Membership(state.network, node, actorId, noise,
            signing, ticket.canInvite, 1), identity.signing().getPrivate());
        candidate.members.put(node, membership);
        candidate.consumed.put(ticket.invitation,
            new Consumption(node, noise, signing, enrollment, membership));
        result.add("member", Wire.object(membership));
        changed = true;
        break;
      case "AUTHORIZE":
        Wire.fields(request.payload, "child", "binding");
        UUID childId = UUID.fromString(Wire.string(request.payload, "child", 36));
        Membership child = active(state, childId);
        if (!child.parent().equals(actorId)) {
          throw new IOException("Not the enrolled parent");
        }
        result.addProperty("binding", Wire.string(request.payload, "binding", 64));
        result.addProperty("child", childId.toString());
        result.addProperty("parent", actorId.toString());
        result.add("member", Wire.object(state.members.get(childId)));
        int depth = 0;
        Membership ancestor = child;
        while (!ancestor.node().equals(identity.nodeId())) {
          depth++;
          ancestor = Membership.verify(state.members.get(ancestor.parent()), root);
        }
        result.addProperty("depth", depth);
        break;
      case "REFRESH":
        Wire.fields(request.payload);
        result.add("member", Wire.object(state.members.get(actorId)));
        break;
      case "REVOKE_INVITE":
        Wire.fields(request.payload, "ticket");
        Signed invitation = Signed.decode(request.payload.getAsJsonObject("ticket"));
        Ticket revoked = ticket(invitation, actor.signing());
        if (!revoked.issuer.equals(actorId)) {
          throw new IOException("Not invitation issuer");
        }
        candidate.revokedInvites.add(revoked.invitation);
        changed = true;
        break;
      case "REVOKE_NODE":
      case "SET_INVITE":
        if (request.operation.equals("SET_INVITE")) {
          Wire.fields(request.payload, "node", "allow");
        } else {
          Wire.fields(request.payload, "node");
        }
        if (!actorId.equals(identity.nodeId())) {
          throw new IOException("Head administrator required");
        }
        UUID target = UUID.fromString(Wire.string(request.payload, "node", 36));
        if (target.equals(identity.nodeId())) {
          throw new IOException("Cannot revoke the head");
        }
        Membership targetMember = active(state, target);
        if (request.operation.equals("REVOKE_NODE")) {
          candidate.revokedNodes.add(target);
        } else {
          boolean allow = Wire.bool(request.payload, "allow");
          candidate.members.put(target, Signed.sign(new Membership(state.network, target,
              targetMember.parent(), targetMember.noise(), targetMember.signing(), allow,
              targetMember.generation() + 1), identity.signing().getPrivate()));
        }
        changed = true;
        break;
      default:
        throw new IOException("Unknown authority operation");
    }
    if (changed) {
      failed = true;
      AtomicFile.write(file, Wire.encode(candidate), false);
      state = candidate;
      failed = false;
    }
    result.addProperty("request", request.id.toString());
    result.addProperty("head", identity.nodeId().toString());
    result.addProperty("revision", state.revision);
    return Signed.sign(result, identity.signing().getPrivate());
  }

  private Membership active(State data, UUID node) throws IOException, GeneralSecurityException {
    Membership member = null;
    Set<UUID> visited = new HashSet<>();
    UUID current = node;
    while (visited.add(current) && visited.size() <= 257) {
      if (data.revokedNodes.contains(current) || !data.members.containsKey(current)) {
        throw new IOException("Unknown or revoked APDP membership");
      }
      Membership ancestor = Membership.verify(data.members.get(current), root);
      if (member == null) {
        member = ancestor;
      }
      if (ancestor.node().equals(identity.nodeId())) {
        return member;
      }
      current = ancestor.parent();
    }
    throw new IOException("Invalid membership ancestry");
  }

  /** Verifies issuer signature, version and root-signed parent binding. */
  public static Ticket ticket(Signed signed, String issuerKey)
      throws IOException, GeneralSecurityException {
    JsonObject object = signed.verify(issuerKey);
    Wire.fields(object, "version", "network", "root", "issuer", "endpoint", "invitation",
        "expires", "secretHash", "parent", "canInvite");
    Wire.integer(object, "version", 1, 1);
    Wire.integer(object, "expires", 1, Long.MAX_VALUE);
    Wire.bool(object, "canInvite");
    Ticket ticket = Wire.GSON.fromJson(object, Ticket.class);
    if (ticket.version != 1 || ticket.network == null || ticket.issuer == null
        || ticket.invitation == null || ticket.endpoint == null || ticket.endpoint.length() > 320
        || Wire.unb64(ticket.secretHash).length != 32) {
      throw new IOException("Invalid APDP invitation");
    }
    Membership parent = Membership.verify(ticket.parent, ticket.root);
    if (!parent.node().equals(ticket.issuer) || !parent.network().equals(ticket.network)) {
      throw new IOException("Invalid invitation parent");
    }
    return ticket;
  }
}
