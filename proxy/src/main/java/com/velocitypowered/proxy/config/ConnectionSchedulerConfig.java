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

import com.electronwill.nightconfig.core.CommentedConfig;
import java.time.Duration;
import java.util.Locale;
import org.apache.logging.log4j.Logger;

/**
 * Configuration for the connection operations scheduler.
 *
 * @param enabled          whether the scheduler is enabled
 * @param policy           the default scheduling policy
 * @param cooldown         the minimum delay between two backend connections for a player
 * @param maxPending       the maximum number of queued operations per player, or {@code 0} for no limit
 * @param coalescePending  whether only the most recent queued operation should be kept
 * @param queueTimeout     the maximum time an operation may wait in the queue
 * @param operationTimeout the maximum time a backend connection may take
 * @param maxConcurrent    the maximum number of concurrent backend connections, or {@code 0} for no limit
 * @param recoveryBypass   whether recovery operations may bypass cooldown and queue limits
 */
public record ConnectionSchedulerConfig(
    boolean enabled,
    Policy policy,
    Duration cooldown,
    int maxPending,
    boolean coalescePending,
    Duration queueTimeout,
    Duration operationTimeout,
    int maxConcurrent,
    boolean recoveryBypass) {

  /**
   * Scheduling policy for a connection operation.
   */
  public enum Policy {
    /**
     * Executes the operation without queueing or cooldown. Operations are still serialized per player.
     */
    IMMEDIATE,
    /**
     * Executes operations sequentially per player without an additional cooldown.
     */
    SERIALIZED,
    /**
     * Executes operations sequentially per player with a configurable cooldown.
     */
    COOLDOWN
  }

  /**
   * Default scheduler configuration.
   */
  public static final ConnectionSchedulerConfig DEFAULT = new ConnectionSchedulerConfig(
      true,
      Policy.COOLDOWN,
      Duration.ofMillis(100),
      2,
      false,
      Duration.ofSeconds(15),
      Duration.ofSeconds(30),
      0,
      true);

  /**
   * Reads the scheduler configuration from a configuration section.
   *
   * @param config the {@code [connection-scheduler]} section, or {@code null}
   * @return the parsed configuration, or the default configuration when {@code config} is {@code null}
   * @throws IllegalArgumentException if a duration or a numeric value is invalid
   */
  public static ConnectionSchedulerConfig fromConfig(CommentedConfig config) {
    if (config == null) {
      return DEFAULT;
    }

    final Object perPlayerValue = config.get("per-player");
    final Object globalValue = config.get("global");
    final CommentedConfig perPlayer =
        perPlayerValue instanceof CommentedConfig value ? value : null;
    final CommentedConfig global =
        globalValue instanceof CommentedConfig value ? value : null;

    final boolean enabled = config.getOrElse("enabled", DEFAULT.enabled());
    final Policy policy = parsePolicy(config.get("policy"));
    final Duration cooldown = parseDuration(perPlayer, "cooldown", "cooldown-ms", DEFAULT.cooldown());
    final int maxPending = perPlayer == null
        ? DEFAULT.maxPending()
        : perPlayer.getIntOrElse("max-pending", DEFAULT.maxPending());
    final boolean coalescePending = perPlayer == null
        ? DEFAULT.coalescePending()
        : perPlayer.getOrElse("coalesce-pending", DEFAULT.coalescePending());
    final Duration queueTimeout = parseDuration(perPlayer, "queue-timeout", "queue-timeout-ms",
        DEFAULT.queueTimeout());
    final Duration operationTimeout = parseDuration(perPlayer, "operation-timeout",
        "operation-timeout-ms", DEFAULT.operationTimeout());
    final int maxConcurrent = global == null
        ? DEFAULT.maxConcurrent()
        : global.getIntOrElse("max-concurrent", DEFAULT.maxConcurrent());
    final boolean recoveryBypass = config.getOrElse("recovery-bypass", DEFAULT.recoveryBypass());

    return new ConnectionSchedulerConfig(enabled, policy, cooldown, maxPending, coalescePending,
        queueTimeout, operationTimeout, maxConcurrent, recoveryBypass);
  }

  private static Policy parsePolicy(Object value) {
    if (value == null) {
      return DEFAULT.policy();
    }
    if (value instanceof String string) {
      try {
        return Policy.valueOf(string.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Invalid connection-scheduler.policy '" + string
            + "'. Expected IMMEDIATE, SERIALIZED or COOLDOWN.", e);
      }
    }
    throw new IllegalArgumentException("Invalid connection-scheduler.policy value: " + value);
  }

  private static Duration parseDuration(CommentedConfig config, String key, String millisKey,
      Duration defaultValue) {
    if (config == null) {
      return defaultValue;
    }

    Object value = config.get(key);
    if (value == null) {
      value = config.get(millisKey);
    }
    if (value == null) {
      return defaultValue;
    }

    try {
      if (value instanceof Number number) {
        final long millis = number.longValue();
        if (millis < 0) {
          throw new IllegalArgumentException("must not be negative");
        }
        return Duration.ofMillis(millis);
      }
      if (value instanceof String string) {
        return parseDurationString(string);
      }
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid connection-scheduler value for '" + key
          + "': " + value, e);
    }
    throw new IllegalArgumentException("Invalid connection-scheduler value for '" + key
        + "': " + value);
  }

  private static Duration parseDurationString(String input) {
    final String value = input.trim().toLowerCase(Locale.ROOT);
    if (value.isEmpty()) {
      throw new IllegalArgumentException("empty duration");
    }

    long multiplier = 1;
    String number = value;
    if (value.endsWith("ms")) {
      number = value.substring(0, value.length() - 2);
    } else if (value.endsWith("s")) {
      multiplier = 1_000L;
      number = value.substring(0, value.length() - 1);
    } else if (value.endsWith("m")) {
      multiplier = 60_000L;
      number = value.substring(0, value.length() - 1);
    }

    final long amount = Long.parseLong(number.trim());
    if (amount < 0) {
      throw new IllegalArgumentException("must not be negative");
    }
    return Duration.ofMillis(Math.multiplyExact(amount, multiplier));
  }

  /**
   * Validates this configuration.
   *
   * @param logger the logger to report problems to
   * @return {@code true} if the configuration is valid
   */
  public boolean validate(Logger logger) {
    boolean valid = true;

    if (cooldown.isNegative()) {
      logger.error("'connection-scheduler.per-player.cooldown' must not be negative.");
      valid = false;
    }
    if (queueTimeout.isNegative() || queueTimeout.isZero()) {
      logger.error("'connection-scheduler.per-player.queue-timeout' must be greater than zero.");
      valid = false;
    }
    if (operationTimeout.isNegative() || operationTimeout.isZero()) {
      logger.error("'connection-scheduler.per-player.operation-timeout' must be greater than zero.");
      valid = false;
    }
    if (maxPending < 0) {
      logger.error("'connection-scheduler.per-player.max-pending' must not be negative.");
      valid = false;
    }
    if (maxConcurrent < 0) {
      logger.error("'connection-scheduler.global.max-concurrent' must not be negative.");
      valid = false;
    }
    if (coalescePending && maxPending > 1) {
      logger.warn("'connection-scheduler.per-player.coalesce-pending' is enabled; "
          + "'max-pending' is effectively 1.");
    }
    if (maxConcurrent == 1) {
      logger.warn("'connection-scheduler.global.max-concurrent' is 1; all players will share a "
          + "single backend connection slot.");
    }
    if (!enabled) {
      logger.info("The connection operations scheduler is disabled; backend connections use the "
          + "legacy behavior.");
    }

    return valid;
  }
}
