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

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import org.apache.logging.log4j.Logger;

/**
 * Configuration migration for the [connection-scheduler] section.
 */
public final class ConnectionSchedulerMigration implements ConfigurationMigration {

  @Override
  public boolean shouldMigrate(CommentedFileConfig config) {
    return configVersion(config) < 3.0;
  }

  @Override
  public void migrate(CommentedFileConfig config, Logger logger) {
    setIfAbsent(config, "connection-scheduler.enabled", true, """
        Enables the connection operation scheduler.
        The scheduler serialises backend connections per player and applies a cooldown between
        connection attempts. This prevents race conditions during rapid server switches,
        especially with Geyser/Bedrock clients.""");

    setIfAbsent(config, "connection-scheduler.policy", "COOLDOWN", """
        Scheduling policy: IMMEDIATE, SERIALIZED or COOLDOWN.
        IMMEDIATE bypasses the queue and cooldown, SERIALIZED queues operations without a
        cooldown, and COOLDOWN queues operations and waits for the configured cooldown.""");

    setIfAbsent(config, "connection-scheduler.recovery-bypass", true, """
        Allows critical recovery operations (initial server connection and failover) to bypass
        the cooldown and queue limits.""");

    setIfAbsent(config, "connection-scheduler.per-player.cooldown", "100ms", """
        Minimum time between the end of one backend connection and the start of the next one.
        Accepts values such as "100ms", "1s" or "2m".""");

    setIfAbsent(config, "connection-scheduler.per-player.max-pending", 2, """
        Maximum number of queued operations per player. 0 means unlimited.""");

    setIfAbsent(config, "connection-scheduler.per-player.coalesce-pending", false, """
        When true, a new request replaces all pending requests for the same player instead of
        waiting behind them. The operation that is already running is never interrupted.""");

    setIfAbsent(config, "connection-scheduler.per-player.queue-timeout", "15s", """
        Maximum time an operation may wait in the queue before being cancelled.""");

    setIfAbsent(config, "connection-scheduler.per-player.operation-timeout", "30s", """
        Maximum time a backend connection may take before it is disconnected.""");

    setIfAbsent(config, "connection-scheduler.global.max-concurrent", 0, """
        Maximum number of simultaneous backend connections across all players.
        0 means unlimited.""");

    config.set("config-version", "3.0");
  }

  private static void setIfAbsent(CommentedFileConfig config, String path, Object value,
      String comment) {
    if (!config.contains(path)) {
      config.set(path, value);
    }
    config.setComment(path, comment);
  }
}
