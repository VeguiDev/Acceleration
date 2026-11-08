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

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A deterministic {@link ConnectionSchedulerExecutor} for unit tests.
 */
final class ManualConnectionSchedulerExecutor implements ConnectionSchedulerExecutor {

  private final PriorityQueue<Task> tasks = new PriorityQueue<>(
      Comparator.comparingLong((Task task) -> task.deadlineNanos).thenComparingLong(task -> task.order));

  private long nowNanos;
  private long sequence;
  private boolean inEventLoop;

  @Override
  public boolean inEventLoop() {
    return inEventLoop;
  }

  @Override
  public void execute(Runnable task) {
    schedule(task, 0L);
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable task, long delayNanos) {
    if (delayNanos < 0) {
      throw new IllegalArgumentException("delayNanos must not be negative");
    }
    final Task scheduled = new Task(nowNanos + delayNanos, sequence++, task);
    tasks.add(scheduled);
    return scheduled;
  }

  @Override
  public long nanoTime() {
    return nowNanos;
  }

  void runDueTasks() {
    while (!tasks.isEmpty()) {
      final Task task = tasks.peek();
      if (task.deadlineNanos > nowNanos) {
        return;
      }
      tasks.poll();
      task.run();
    }
  }

  void runUntilIdle() {
    while (!tasks.isEmpty()) {
      tasks.poll().run();
    }
  }

  void advance(long nanos) {
    nowNanos += nanos;
    runDueTasks();
  }

  int pendingTasks() {
    return tasks.size();
  }

  private final class Task implements ScheduledFuture<Object> {

    private final long deadlineNanos;
    private final long order;
    private final Runnable runnable;
    private final CompletableFuture<Object> completion = new CompletableFuture<>();
    private boolean cancelled;

    private Task(long deadlineNanos, long order, Runnable runnable) {
      this.deadlineNanos = deadlineNanos;
      this.order = order;
      this.runnable = runnable;
    }

    private void run() {
      if (cancelled) {
        return;
      }
      inEventLoop = true;
      try {
        runnable.run();
        completion.complete(null);
      } catch (Throwable throwable) {
        completion.completeExceptionally(throwable);
      } finally {
        inEventLoop = false;
      }
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(Math.max(0L, deadlineNanos - nowNanos), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
      return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      if (cancelled || completion.isDone()) {
        return false;
      }
      cancelled = true;
      completion.cancel(false);
      return true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    @Override
    public boolean isDone() {
      return cancelled || completion.isDone();
    }

    @Override
    public Object get() throws InterruptedException, ExecutionException {
      return completion.get();
    }

    @Override
    public Object get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      return completion.get(timeout, unit);
    }
  }
}
