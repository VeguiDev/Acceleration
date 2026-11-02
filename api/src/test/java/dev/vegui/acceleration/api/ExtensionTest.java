/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package dev.vegui.acceleration.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.ProxyServer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExtensionTest {
  @Test
  void velocityInterfaceIsStillTheBase() {
    assertTrue(ProxyServer.class.isAssignableFrom(AccelerationProxyServer.class));
  }

  @Test
  void publicSnapshotsOwnTheirCollections() {
    var nodes = new HashMap<UUID, Discovery.Node>();
    var snapshot = new Discovery.Snapshot(UUID.randomUUID(), nodes, Map.of());
    nodes.put(UUID.randomUUID(), null);
    assertTrue(snapshot.nodes().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.parents().clear());
    var aliases = new ArrayList<>(List.of("example"));
    var entry = new CommandRegistry.Entry(UUID.randomUUID(), "example", aliases, "SIMPLE");
    aliases.clear();
    assertEquals(List.of("example"), entry.aliases());
    assertThrows(UnsupportedOperationException.class, () -> entry.aliases().clear());
  }
}
