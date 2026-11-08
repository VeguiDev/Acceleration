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

package com.velocitypowered.proxy.config.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionSchedulerMigrationTest {

  @TempDir
  Path tempDir;

  @Test
  void migratesOldConfiguration() throws Exception {
    final Path file = tempDir.resolve("velocity.toml");
    Files.writeString(file, "config-version = \"2.9\"\n");

    try (CommentedFileConfig config = CommentedFileConfig.builder(file).build()) {
      config.load();
      final ConnectionSchedulerMigration migration = new ConnectionSchedulerMigration();

      assertTrue(migration.shouldMigrate(config));
      migration.migrate(config, mock(Logger.class));

      assertEquals("3.0", config.get("config-version"));
      assertEquals(true, config.get("connection-scheduler.enabled"));
      assertEquals("COOLDOWN", config.get("connection-scheduler.policy"));
      assertEquals("100ms", config.get("connection-scheduler.per-player.cooldown"));
      assertEquals(2, config.<Integer>get("connection-scheduler.per-player.max-pending"));
      assertEquals(false, config.get("connection-scheduler.per-player.coalesce-pending"));
      assertEquals("15s", config.get("connection-scheduler.per-player.queue-timeout"));
      assertEquals("30s", config.get("connection-scheduler.per-player.operation-timeout"));
      assertEquals(0, config.<Integer>get("connection-scheduler.global.max-concurrent"));
      assertFalse(migration.shouldMigrate(config));
    }
  }

  @Test
  void preservesExistingValues() throws Exception {
    final Path file = tempDir.resolve("velocity.toml");
    Files.writeString(file, """
        config-version = "2.9"

        [connection-scheduler]
        enabled = false
        policy = "SERIALIZED"

        [connection-scheduler.per-player]
        cooldown = "250ms"
        max-pending = 5
        """);

    try (CommentedFileConfig config = CommentedFileConfig.builder(file).build()) {
      config.load();
      new ConnectionSchedulerMigration().migrate(config, mock(Logger.class));

      assertEquals(false, config.get("connection-scheduler.enabled"));
      assertEquals("SERIALIZED", config.get("connection-scheduler.policy"));
      assertEquals("250ms", config.get("connection-scheduler.per-player.cooldown"));
      assertEquals(5, config.<Integer>get("connection-scheduler.per-player.max-pending"));
      assertEquals("15s", config.get("connection-scheduler.per-player.queue-timeout"));
    }
  }
}
