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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeIdentityTest {
  @TempDir Path temporary;

  @Test
  void persistsBothKeyPairsAndIdentity() throws Exception {
    Path state = temporary.resolve("state");
    NodeIdentity original = NodeIdentity.loadOrCreate(state);
    NodeIdentity loaded = NodeIdentity.loadOrCreate(state);
    assertEquals(original.nodeId(), loaded.nodeId());
    assertArrayEquals(original.noise().getPrivate().getEncoded(),
        loaded.noise().getPrivate().getEncoded());
    assertArrayEquals(original.signing().getPublic().getEncoded(),
        loaded.signing().getPublic().getEncoded());
  }

  @Test
  void neverRegeneratesCorruptState() throws Exception {
    Path state = temporary.resolve("state");
    NodeIdentity.loadOrCreate(state);
    byte[] corrupt = new byte[]{1, 2, 3};
    Files.write(state.resolve("identity.bin"), corrupt);
    assertThrows(IOException.class, () -> NodeIdentity.loadOrCreate(state));
    assertArrayEquals(corrupt, Files.readAllBytes(state.resolve("identity.bin")));
  }

  @Test
  void rejectsSymlinkIdentityDirectory() throws Exception {
    Path real = temporary.resolve("real");
    NodeIdentity.loadOrCreate(real);
    Path link = Files.createSymbolicLink(temporary.resolve("link"), real);
    assertThrows(IOException.class, () -> NodeIdentity.loadOrCreate(link));
  }

  @Test
  void concurrentCreationNeverReplacesTheWinningIdentity() throws Exception {
    Path state = temporary.resolve("state");
    try (var executor = Executors.newFixedThreadPool(8)) {
      var jobs = new ArrayList<Callable<NodeIdentity>>();
      for (int i = 0; i < 16; i++) {
        jobs.add(() -> NodeIdentity.loadOrCreate(state));
      }
      var results = executor.invokeAll(jobs);
      NodeIdentity winner = NodeIdentity.loadOrCreate(state);
      for (var result : results) {
        assertEquals(winner.nodeId(), result.get().nodeId());
        assertArrayEquals(winner.noise().getPublic().getEncoded(),
            result.get().noise().getPublic().getEncoded());
      }
    }
  }

  @Test
  void rejectsUnprotectedPrivateKeys() throws Exception {
    Path state = temporary.resolve("state");
    NodeIdentity.loadOrCreate(state);
    Files.setPosixFilePermissions(state.resolve("identity.bin"),
        PosixFilePermissions.fromString("rw-r--r--"));
    assertThrows(IOException.class, () -> NodeIdentity.loadOrCreate(state));
  }

  @Test
  void rejectsTrailingAndOversizedState() throws Exception {
    Path state = temporary.resolve("state");
    NodeIdentity.loadOrCreate(state);
    Files.write(state.resolve("identity.bin"), new byte[]{0},
        java.nio.file.StandardOpenOption.APPEND);
    assertThrows(IOException.class, () -> NodeIdentity.loadOrCreate(state));
    Files.write(state.resolve("identity.bin"), new byte[8193]);
    assertThrows(IOException.class, () -> NodeIdentity.loadOrCreate(state));
  }
}
