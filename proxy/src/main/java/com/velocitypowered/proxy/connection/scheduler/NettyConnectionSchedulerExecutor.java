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

import io.netty.channel.EventLoop;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ConnectionSchedulerExecutor} backed by a Netty {@link EventLoop}.
 */
final class NettyConnectionSchedulerExecutor implements ConnectionSchedulerExecutor {

  private final EventLoop eventLoop;

  NettyConnectionSchedulerExecutor(EventLoop eventLoop) {
    this.eventLoop = eventLoop;
  }

  @Override
  public boolean inEventLoop() {
    return eventLoop.inEventLoop();
  }

  @Override
  public void execute(Runnable task) {
    eventLoop.execute(task);
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable task, long delayNanos) {
    return eventLoop.schedule(task, delayNanos, TimeUnit.NANOSECONDS);
  }

  @Override
  public long nanoTime() {
    return System.nanoTime();
  }
}
