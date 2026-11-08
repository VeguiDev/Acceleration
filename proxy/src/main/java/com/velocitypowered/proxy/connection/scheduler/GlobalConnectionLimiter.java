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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A non-blocking, FIFO permit limiter for concurrent backend connections.
 */
final class GlobalConnectionLimiter {

  private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
  private int max;
  private int active;

  synchronized void reconfigure(int newMax) {
    this.max = newMax;
    final List<Waiter> granted = new ArrayList<>();
    if (newMax <= 0) {
      Waiter waiter;
      while ((waiter = waiters.pollFirst()) != null) {
        if (!waiter.operation.isCancelled() && !waiter.operation.isFinished()) {
          waiter.operation.setPermit(false);
          granted.add(waiter);
        }
      }
    } else {
      collectWaitersLocked(granted);
    }
    runGranted(granted);
  }

  synchronized boolean acquireOrEnqueue(ConnectionOperation operation, Runnable onGrant) {
    if (max <= 0) {
      return true;
    }
    if (active < max) {
      active++;
      return true;
    }
    waiters.addLast(new Waiter(operation, onGrant));
    return false;
  }

  void release() {
    final List<Waiter> granted;
    synchronized (this) {
      if (active > 0) {
        active--;
      }
      if (max <= 0) {
        return;
      }
      granted = new ArrayList<>();
      collectWaitersLocked(granted);
    }
    runGranted(granted);
  }

  synchronized void remove(ConnectionOperation operation) {
    waiters.removeIf(waiter -> waiter.operation == operation);
  }

  synchronized int waitingCount() {
    return waiters.size();
  }

  private void collectWaitersLocked(List<Waiter> granted) {
    if (max <= 0) {
      return;
    }
    while (active < max) {
      final Waiter waiter = waiters.pollFirst();
      if (waiter == null) {
        return;
      }
      if (waiter.operation.isCancelled() || waiter.operation.isFinished()) {
        continue;
      }
      active++;
      waiter.operation.setPermit(true);
      granted.add(waiter);
    }
  }

  private static void runGranted(List<Waiter> granted) {
    for (Waiter waiter : granted) {
      waiter.onGrant.run();
    }
  }

  private record Waiter(ConnectionOperation operation, Runnable onGrant) {
  }
}
