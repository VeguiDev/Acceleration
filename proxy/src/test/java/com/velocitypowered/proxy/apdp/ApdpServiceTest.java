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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.apdp.config.ApdpConfig;
import com.velocitypowered.proxy.apdp.model.Topology;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(100)
class ApdpServiceTest {
  @TempDir
  Path temporary;

  private ApdpService node(String name, Set<String> leaves, String mode, String... exposed)
      throws Exception {
    Path directory = Files.createDirectory(temporary.resolve(name));
    ApdpConfig config = new ApdpConfig(directory.resolve("apdp.toml"));
    config.create(name);
    config.edit(value -> {
      value.set("publication", mode);
      value.set("exposed", java.util.List.of(exposed));
    });
    ApdpService service = new ApdpService(directory, () -> leaves);
    service.start();
    return service;
  }

  private static void await(Callable<Boolean> check) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
    while (!check.call()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("APDP did not converge");
      }
      Thread.sleep(50);
    }
  }

  @Test
  void recursiveDiscoveryPrivacyWithdrawalReconnectAndRevocation() throws Exception {
    Path headPath = Files.createDirectory(temporary.resolve("head"));
    try (ApdpService head = new ApdpService(headPath, Set::of);
         ApdpService first = node("survival", Set.of(), "transparent");
         ApdpService second = node("group", Set.of(), "transparent");
         ApdpService third = node("shard", Set.of("public", "private"), "exposed", "public")) {
      head.start();
      head.init("network");
      new ApdpConfig(headPath.resolve("apdp.toml")).edit(value -> value.set("publication", "transparent"));
      first.join(head.invite(600, true));
      second.join(first.invite(600, true));
      third.join(second.invite(600, false));
      await(() -> head.catalog().find("survival.group.shard.public").isPresent());
      Topology graph = head.catalog().topology().orElseThrow();
      assertFalse(graph.find("survival.group.shard.private").isPresent());
      assertEquals(3, graph.position(third.localNode()).depth());
      assertEquals(4, graph.position(graph.find("survival.group.shard.public").orElseThrow().id()).depth());
      assertEquals(first.localNode(), graph.nextHop(head.localNode(), third.localNode()).orElseThrow());
      assertEquals(3, third.depth());
      assertEquals(head.localNode(), third.head());
      await(() -> third.catalog().find("survival.group.shard.public").isPresent());
      assertFalse(third.catalog().find("survival.group.shard.private").isPresent());
      third.reconnect();
      await(() -> third.status().contains("connected"));
      new ApdpConfig(temporary.resolve("shard/apdp.toml")).edit(value -> value.set("publication", "local"));
      await(() -> head.catalog().find("survival.group.shard.public").isEmpty()
          && head.catalog().find("survival.group.shard").isPresent());
      assertTrue(head.catalog().find("survival.group.shard").isPresent());
      new ApdpConfig(temporary.resolve("shard/apdp.toml")).edit(value -> value.set("publication", "transparent"));
      await(() -> head.catalog().find("survival.group.shard.private").isPresent());
      head.revokeNode(third.localNode());
      await(() -> head.catalog().find("survival.group.shard").isEmpty());
      await(() -> third.status().contains("revoked"));
      assertFalse(Files.readString(temporary.resolve("shard/apdp-state/local.json")).contains("secret"));
    }
  }

  @Test
  void invitationSingleUseAndIdentitySurvivesRestart() throws Exception {
    Path headPath = Files.createDirectory(temporary.resolve("root"));
    try (ApdpService head = new ApdpService(headPath, Set::of);
         ApdpService child = node("one", Set.of(), "local");
         ApdpService intruder = node("two", Set.of(), "local")) {
      head.start();
      head.init("root");
      String token = head.invite(600, false);
      child.join(token);
      var id = child.localNode();
      assertThrows(Exception.class, () -> intruder.join(token));
      child.close();
      try (ApdpService restarted = new ApdpService(temporary.resolve("one"), Set::of)) {
        restarted.start();
        await(() -> restarted.status().contains("connected"));
        assertEquals(id, restarted.localNode());
        assertNotNull(restarted.head());
      }
    }
  }

  @Test
  void disabledServiceDoesNotCreateIdentityOrChangeLocalDestinations() throws Exception {
    Path path = Files.createDirectory(temporary.resolve("disabled"));
    try (ApdpService service = new ApdpService(path, () -> Set.of("lobby"))) {
      service.start();
      assertTrue(service.status().contains("disabled"));
      assertFalse(Files.exists(path.resolve("apdp-state")));
      assertTrue(service.catalog().topology().isEmpty());
    }
  }

  @Test
  void headPartitionPreservesSessionsButBlocksFreshConnections() throws Exception {
    Path rootPath = Files.createDirectory(temporary.resolve("root"));
    try (ApdpService root = new ApdpService(rootPath, Set::of);
         ApdpService first = node("first", Set.of(), "transparent");
         ApdpService second = node("second", Set.of(), "transparent")) {
      root.start();
      root.init("root");
      first.join(root.invite(600, true));
      second.join(first.invite(600, false));
      root.close();
      await(() -> first.peers().size() == 1);
      assertEquals(1, second.peers().size());
      assertThrows(Exception.class, () -> first.invite(600, false));
      second.reconnect();
      await(() -> second.peers().isEmpty());
      assertFalse(second.catalog().current());
      try (ApdpService recovered = new ApdpService(rootPath, Set::of)) {
        recovered.start();
        await(() -> first.peers().size() == 2 && second.peers().size() == 1);
        assertEquals(root.localNode(), recovered.localNode());
      }
    }
  }

  @Test
  void verifiedPendingEnrollmentRecoversWithoutPersistingBearer() throws Exception {
    Path rootPath = Files.createDirectory(temporary.resolve("root"));
    try (ApdpService root = new ApdpService(rootPath, Set::of);
         ApdpService first = node("first", Set.of(), "transparent");
         ApdpService joining = node("joining", Set.of(), "local")) {
      root.start();
      root.init("root");
      first.join(root.invite(600, true));
      String token = first.invite(600, false);
      root.close();
      await(() -> first.peers().isEmpty());
      assertThrows(Exception.class, () -> joining.join(token));
      String pending = Files.readString(temporary.resolve("first/apdp-state/pending.json"));
      assertFalse(pending.contains("APDP1."));
      assertFalse(pending.contains("\"secret\""));
      joining.close();
      first.close();
      try (ApdpService recoveredRoot = new ApdpService(rootPath, Set::of);
           ApdpService recoveredParent = new ApdpService(temporary.resolve("first"), Set::of);
           ApdpService recoveredJoiner = new ApdpService(temporary.resolve("joining"), Set::of)) {
        recoveredRoot.start();
        recoveredParent.start();
        recoveredJoiner.start();
        await(() -> recoveredJoiner.status().contains("connected"));
        assertEquals(joining.localNode(), recoveredJoiner.localNode());
      }
    }
  }
}
