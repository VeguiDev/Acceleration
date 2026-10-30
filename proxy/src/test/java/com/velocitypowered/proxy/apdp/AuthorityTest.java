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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.velocitypowered.proxy.apdp.identity.Authority;
import com.velocitypowered.proxy.apdp.identity.Membership;
import com.velocitypowered.proxy.apdp.identity.NodeIdentity;
import com.velocitypowered.proxy.apdp.identity.Signed;
import com.velocitypowered.proxy.apdp.protocol.Wire;
import com.velocitypowered.proxy.apdp.transport.NoiseChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthorityTest {
  @TempDir
  Path temporary;

  private Signed request(Authority authority, NodeIdentity actor, String operation, JsonObject payload)
      throws Exception {
    return authority.execute(Signed.sign(new Authority.Request(UUID.randomUUID(), actor.nodeId(),
        System.currentTimeMillis() + 30000, operation, payload), actor.signing().getPrivate()));
  }

  private Signed ticket(Authority authority, NodeIdentity parent, Signed member, long expiry,
      boolean delegate) throws Exception {
    return Signed.sign(new Authority.Ticket(1, authority.snapshot().network(), authority.root(),
        parent.nodeId(), "localhost:12345", UUID.randomUUID(), expiry,
        Wire.b64(MessageDigest.getInstance("SHA-256").digest(new byte[32])), member, delegate),
        parent.signing().getPrivate());
  }

  private JsonObject enrollment(Signed ticket, NodeIdentity child) {
    JsonObject spec = new JsonObject();
    spec.addProperty("node", child.nodeId().toString());
    spec.addProperty("noise", Wire.b64(NoiseChannel.publicKey(child)));
    spec.addProperty("signing", Wire.b64(child.signing().getPublic().getEncoded()));
    spec.addProperty("request", UUID.randomUUID().toString());
    JsonObject payload = new JsonObject();
    payload.add("ticket", Wire.object(ticket));
    payload.add("specification", spec);
    return payload;
  }

  @Test
  void atomicSingleConsumptionUnderConcurrentIdentitiesAndDurableRetry() throws Exception {
    NodeIdentity root = NodeIdentity.loadOrCreate(temporary.resolve("root"));
    Path file = temporary.resolve("root/authority.json");
    Authority authority = new Authority(root, file, true);
    Signed invitation = ticket(authority, root, authority.snapshot().members().get(root.nodeId()),
        System.currentTimeMillis() + 60000, false);
    JsonObject first = enrollment(invitation, NodeIdentity.loadOrCreate(temporary.resolve("first")));
    JsonObject second = enrollment(invitation, NodeIdentity.loadOrCreate(temporary.resolve("second")));
    AtomicInteger accepted = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      for (JsonObject payload : java.util.List.of(first, second)) {
        executor.submit(() -> {
          try {
            request(authority, root, "ENROLL", payload);
            accepted.incrementAndGet();
          } catch (Exception ignored) {
            // Exactly one different identity may consume the invitation.
          }
        });
      }
    }
    assertEquals(1, accepted.get());
    assertEquals(2, authority.snapshot().members().size());
    UUID winner = authority.snapshot().consumed().values().iterator().next().node();
    JsonObject winning = first.getAsJsonObject("specification").get("node").getAsString()
        .equals(winner.toString()) ? first : second;
    Authority recovered = new Authority(root, file, false);
    Signed retried = request(recovered, root, "ENROLL", winning);
    Membership membership = Membership.verify(Signed.decode(retried.verify(authority.root())
        .getAsJsonObject("member")), authority.root());
    assertEquals(winner, membership.node());
    assertEquals(2, recovered.snapshot().members().size());
    assertFalse(Files.readString(file).contains("APDP1."));
    assertThrows(UnsupportedOperationException.class, () -> recovered.snapshot().members().clear());
  }

  @Test
  void expiryRevocationPermissionsAndHandshakeBinding() throws Exception {
    NodeIdentity root = NodeIdentity.loadOrCreate(temporary.resolve("root"));
    Authority authority = new Authority(root, temporary.resolve("root/db.json"), true);
    Signed rootMember = authority.snapshot().members().get(root.nodeId());
    NodeIdentity parent = NodeIdentity.loadOrCreate(temporary.resolve("parent"));
    JsonObject expired = enrollment(ticket(authority, root, rootMember, 1, true), parent);
    assertThrows(Exception.class, () -> request(authority, root, "ENROLL", expired));
    Signed parentTicket = ticket(authority, root, rootMember, System.currentTimeMillis() + 60000, true);
    JsonObject parentPayload = enrollment(parentTicket, parent);
    Signed parentMember = Signed.decode(request(authority, root, "ENROLL", parentPayload)
        .verify(authority.root()).getAsJsonObject("member"));
    Signed childTicket = ticket(authority, parent, parentMember, System.currentTimeMillis() + 60000, false);
    NodeIdentity child = NodeIdentity.loadOrCreate(temporary.resolve("child"));
    JsonObject childPayload = enrollment(childTicket, child);
    request(authority, parent, "ENROLL", childPayload);
    JsonObject permissions = new JsonObject();
    permissions.addProperty("node", parent.nodeId().toString());
    permissions.addProperty("allow", false);
    request(authority, root, "SET_INVITE", permissions);
    // Already committed memberships recover, even after issuer permission removal.
    request(authority, parent, "ENROLL", childPayload);
    JsonObject another = enrollment(ticket(authority, parent, parentMember,
        System.currentTimeMillis() + 60000, false), NodeIdentity.loadOrCreate(temporary.resolve("other")));
    assertThrows(Exception.class, () -> request(authority, parent, "ENROLL", another));
    JsonObject auth = new JsonObject();
    auth.addProperty("child", child.nodeId().toString());
    auth.addProperty("binding", "fresh-handshake-hash");
    JsonObject decision = request(authority, parent, "AUTHORIZE", auth).verify(authority.root());
    assertEquals("fresh-handshake-hash", decision.get("binding").getAsString());
    assertEquals(child.nodeId().toString(), decision.get("child").getAsString());
    assertThrows(Exception.class, () -> request(authority, root, "AUTHORIZE", auth));
    JsonObject revoke = new JsonObject();
    revoke.addProperty("node", parent.nodeId().toString());
    request(authority, root, "REVOKE_NODE", revoke);
    assertThrows(Exception.class, () -> request(authority, parent, "AUTHORIZE", auth));
    assertTrue(authority.snapshot().revokedNodes().contains(parent.nodeId()));
  }

  @Test
  void revokedInvitationAndForgedIssuerFailClosed() throws Exception {
    NodeIdentity root = NodeIdentity.loadOrCreate(temporary.resolve("root"));
    Authority authority = new Authority(root, temporary.resolve("root/db.json"), true);
    Signed invitation = ticket(authority, root, authority.snapshot().members().get(root.nodeId()),
        System.currentTimeMillis() + 60000, false);
    JsonObject payload = new JsonObject();
    payload.add("ticket", Wire.object(invitation));
    request(authority, root, "REVOKE_INVITE", payload);
    JsonObject enroll = enrollment(invitation, NodeIdentity.loadOrCreate(temporary.resolve("child")));
    assertThrows(Exception.class, () -> request(authority, root, "ENROLL", enroll));
    NodeIdentity outsider = NodeIdentity.loadOrCreate(temporary.resolve("outsider"));
    Signed forged = Signed.sign(new Authority.Request(UUID.randomUUID(), root.nodeId(),
        System.currentTimeMillis() + 30000, "ENROLL", enroll), outsider.signing().getPrivate());
    assertThrows(Exception.class, () -> authority.execute(forged));
  }

  @Test
  void alreadyCommittedEnrollmentRecoversAfterInvitationExpiry() throws Exception {
    NodeIdentity root = NodeIdentity.loadOrCreate(temporary.resolve("root"));
    Path file = temporary.resolve("root/db.json");
    Authority authority = new Authority(root, file, true);
    Signed invitation = ticket(authority, root, authority.snapshot().members().get(root.nodeId()),
        System.currentTimeMillis() + 1000, false);
    JsonObject payload = enrollment(invitation, NodeIdentity.loadOrCreate(temporary.resolve("child")));
    request(authority, root, "ENROLL", payload);
    Thread.sleep(1100);
    Authority recovered = new Authority(root, file, false);
    request(recovered, root, "ENROLL", payload);
    assertEquals(2, recovered.snapshot().members().size());
  }
}
