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

package com.velocitypowered.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.velocitypowered.proxy.config.ConnectionSchedulerConfig.Policy;
import java.time.Duration;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

class ConnectionSchedulerConfigTest {

  @Test
  void usesDefaultsWhenSectionIsMissing() {
    final ConnectionSchedulerConfig config = ConnectionSchedulerConfig.fromConfig(null);

    assertTrue(config.enabled());
    assertEquals(Policy.COOLDOWN, config.policy());
    assertEquals(Duration.ofMillis(100), config.cooldown());
    assertEquals(2, config.maxPending());
    assertFalse(config.coalescePending());
    assertEquals(Duration.ofSeconds(15), config.queueTimeout());
    assertEquals(Duration.ofSeconds(30), config.operationTimeout());
    assertEquals(0, config.maxConcurrent());
    assertTrue(config.recoveryBypass());
  }

  @Test
  void parsesDurationStringsAndNumbers() {
    final CommentedConfig root = CommentedConfig.inMemory();
    final CommentedConfig perPlayer = root.createSubConfig();
    root.set("per-player", perPlayer);
    perPlayer.set("cooldown", "1s");
    perPlayer.set("queue-timeout", "2m");
    perPlayer.set("operation-timeout", 250);

    final ConnectionSchedulerConfig config = ConnectionSchedulerConfig.fromConfig(root);

    assertEquals(Duration.ofSeconds(1), config.cooldown());
    assertEquals(Duration.ofMinutes(2), config.queueTimeout());
    assertEquals(Duration.ofMillis(250), config.operationTimeout());
  }

  @Test
  void parsesPolicyCaseInsensitively() {
    final CommentedConfig root = CommentedConfig.inMemory();
    root.set("policy", " serialized ");

    assertEquals(Policy.SERIALIZED, ConnectionSchedulerConfig.fromConfig(root).policy());
  }

  @Test
  void rejectsInvalidPolicy() {
    final CommentedConfig root = CommentedConfig.inMemory();
    root.set("policy", "FAST");

    assertThrows(IllegalArgumentException.class,
        () -> ConnectionSchedulerConfig.fromConfig(root));
  }

  @Test
  void rejectsInvalidDuration() {
    final CommentedConfig root = CommentedConfig.inMemory();
    final CommentedConfig perPlayer = root.createSubConfig();
    root.set("per-player", perPlayer);
    perPlayer.set("cooldown", "soon");

    assertThrows(IllegalArgumentException.class,
        () -> ConnectionSchedulerConfig.fromConfig(root));
  }

  @Test
  void validatesRanges() {
    final Logger logger = mock(Logger.class);
    final ConnectionSchedulerConfig invalid = new ConnectionSchedulerConfig(
        true, Policy.COOLDOWN, Duration.ofMillis(-1), -1, false,
        Duration.ZERO, Duration.ZERO, -1, true);

    assertFalse(invalid.validate(logger));
  }

  @Test
  void acceptsValidConfiguration() {
    final Logger logger = mock(Logger.class);

    assertTrue(ConnectionSchedulerConfig.DEFAULT.validate(logger));
  }
}
