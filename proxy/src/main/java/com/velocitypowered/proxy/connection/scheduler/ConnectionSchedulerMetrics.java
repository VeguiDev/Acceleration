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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runtime metrics for the connection operations scheduler.
 */
public final class ConnectionSchedulerMetrics {

  private final LongAdder submitted = new LongAdder();
  private final LongAdder started = new LongAdder();
  private final LongAdder succeeded = new LongAdder();
  private final LongAdder failed = new LongAdder();
  private final LongAdder cancelled = new LongAdder();
  private final LongAdder superseded = new LongAdder();
  private final LongAdder coalesced = new LongAdder();
  private final LongAdder rejectedQueueFull = new LongAdder();
  private final LongAdder timedOutQueue = new LongAdder();
  private final LongAdder timedOutOperation = new LongAdder();
  private final LongAdder immediateBypass = new LongAdder();
  private final LongAdder cooldownWaits = new LongAdder();
  private final LongAdder globalWaits = new LongAdder();

  private final AtomicInteger queued = new AtomicInteger();
  private final AtomicInteger running = new AtomicInteger();
  private final AtomicInteger globalWaiting = new AtomicInteger();
  private final AtomicInteger playersWithQueues = new AtomicInteger();

  private final LongAdder totalQueueWaitNanos = new LongAdder();
  private final AtomicLong maxQueueWaitNanos = new AtomicLong();
  private final LongAdder totalRunNanos = new LongAdder();
  private final AtomicLong maxRunNanos = new AtomicLong();

  void recordSubmitted() {
    submitted.increment();
  }

  void recordStarted(long queueWaitNanos) {
    started.increment();
    queued.decrementAndGet();
    running.incrementAndGet();
    totalQueueWaitNanos.add(queueWaitNanos);
    updateMax(maxQueueWaitNanos, queueWaitNanos);
  }

  void recordStartedDirect() {
    started.increment();
    running.incrementAndGet();
  }

  void recordFinished(long runNanos, boolean success) {
    running.decrementAndGet();
    if (success) {
      succeeded.increment();
    } else {
      failed.increment();
    }
    totalRunNanos.add(runNanos);
    updateMax(maxRunNanos, runNanos);
  }

  void recordCancelled() {
    cancelled.increment();
  }

  void recordSuperseded() {
    superseded.increment();
  }

  void recordCoalesced() {
    coalesced.increment();
  }

  void recordRejectedQueueFull() {
    rejectedQueueFull.increment();
  }

  void recordQueueTimeout() {
    timedOutQueue.increment();
  }

  void recordOperationTimeout() {
    timedOutOperation.increment();
  }

  void recordImmediateBypass() {
    immediateBypass.increment();
  }

  void recordCooldownWait() {
    cooldownWaits.increment();
  }

  void recordGlobalWait() {
    globalWaits.increment();
  }

  void queueDepthIncrement() {
    queued.incrementAndGet();
  }

  void queueDepthDecrement() {
    queued.decrementAndGet();
  }

  void globalWaitingIncrement() {
    globalWaiting.incrementAndGet();
  }

  void globalWaitingDecrement() {
    globalWaiting.updateAndGet(value -> Math.max(0, value - 1));
  }

  void playersWithQueuesIncrement() {
    playersWithQueues.incrementAndGet();
  }

  void playersWithQueuesDecrement() {
    playersWithQueues.updateAndGet(value -> Math.max(0, value - 1));
  }

  private static void updateMax(AtomicLong target, long value) {
    long current;
    while (value > (current = target.get())) {
      if (target.compareAndSet(current, value)) {
        return;
      }
    }
  }

  /**
   * Returns an immutable snapshot of the current metrics.
   *
   * @return the snapshot
   */
  public Snapshot snapshot() {
    return new Snapshot(
        submitted.sum(),
        started.sum(),
        succeeded.sum(),
        failed.sum(),
        cancelled.sum(),
        superseded.sum(),
        coalesced.sum(),
        rejectedQueueFull.sum(),
        timedOutQueue.sum(),
        timedOutOperation.sum(),
        immediateBypass.sum(),
        cooldownWaits.sum(),
        globalWaits.sum(),
        queued.get(),
        running.get(),
        globalWaiting.get(),
        playersWithQueues.get(),
        totalQueueWaitNanos.sum(),
        maxQueueWaitNanos.get(),
        totalRunNanos.sum(),
        maxRunNanos.get());
  }

  /**
   * An immutable point-in-time view of the scheduler metrics.
   *
   * @param submitted           operations submitted
   * @param started             operations started
   * @param succeeded           operations that completed successfully
   * @param failed              operations that completed unsuccessfully
   * @param cancelled           operations cancelled
   * @param superseded          operations discarded because they became obsolete
   * @param coalesced           operations replaced by a newer pending operation
   * @param rejectedQueueFull   operations rejected because the queue was full
   * @param timedOutQueue       operations that timed out while queued
   * @param timedOutOperation   operations that timed out while connecting
   * @param immediateBypass     operations that bypassed the queue
   * @param cooldownWaits       operations delayed by the cooldown
   * @param globalWaits         operations delayed by the global concurrency limit
   * @param queued              operations currently queued
   * @param running             operations currently running
   * @param globalWaiting       operations waiting for a global permit
   * @param playersWithQueues   players with an active queue
   * @param totalQueueWaitNanos total time operations spent queued
   * @param maxQueueWaitNanos   maximum time an operation spent queued
   * @param totalRunNanos       total time operations spent connecting
   * @param maxRunNanos         maximum time an operation spent connecting
   */
  public record Snapshot(
      long submitted,
      long started,
      long succeeded,
      long failed,
      long cancelled,
      long superseded,
      long coalesced,
      long rejectedQueueFull,
      long timedOutQueue,
      long timedOutOperation,
      long immediateBypass,
      long cooldownWaits,
      long globalWaits,
      int queued,
      int running,
      int globalWaiting,
      int playersWithQueues,
      long totalQueueWaitNanos,
      long maxQueueWaitNanos,
      long totalRunNanos,
      long maxRunNanos) {

    /**
     * Returns the average queue wait in milliseconds.
     *
     * @return the average queue wait
     */
    public double averageQueueWaitMillis() {
      return started == 0 ? 0.0 : totalQueueWaitNanos / 1_000_000.0 / started;
    }

    /**
     * Returns the average connection duration in milliseconds.
     *
     * @return the average connection duration
     */
    public double averageRunMillis() {
      final long completed = succeeded + failed;
      return completed == 0 ? 0.0 : totalRunNanos / 1_000_000.0 / completed;
    }
  }
}
