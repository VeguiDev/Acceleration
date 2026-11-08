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

package com.velocitypowered.proxy.connection.scheduler;

import java.util.concurrent.ScheduledFuture;

/**
 * Abstraction over the event loop used by the connection scheduler.
 *
 * <p>This exists so the scheduling logic can be tested deterministically without a real Netty
 * event loop.</p>
 */
public interface ConnectionSchedulerExecutor {

  /**
   * Returns whether the current thread is the executor's event loop.
   *
   * @return {@code true} when running on the event loop
   */
  boolean inEventLoop();

  /**
   * Executes a task on the event loop.
   *
   * @param task the task to execute
   */
  void execute(Runnable task);

  /**
   * Schedules a task on the event loop.
   *
   * @param task       the task to execute
   * @param delayNanos the delay in nanoseconds
   * @return a handle that can be used to cancel the task
   */
  ScheduledFuture<?> schedule(Runnable task, long delayNanos);

  /**
   * Returns a monotonic timestamp in nanoseconds.
   *
   * @return the current timestamp
   */
  long nanoTime();
}
