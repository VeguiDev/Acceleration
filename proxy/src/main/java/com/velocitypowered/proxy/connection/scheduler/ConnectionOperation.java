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

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.config.ConnectionSchedulerConfig.Policy;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A single backend connection operation managed by the scheduler.
 */
public final class ConnectionOperation {

  enum State {
    QUEUED,
    WAITING_GLOBAL,
    RUNNING,
    DONE
  }

  private final long id;
  private final ConnectedPlayer player;
  private final RegisteredServer destination;
  private final @Nullable VelocityRegisteredServer previousServer;
  private final Policy policy;
  private final boolean recovery;
  private final ConnectionExecution execution;
  private final CompletableFuture<Impl> future = new CompletableFuture<>();

  private State state = State.QUEUED;
  private boolean finished;
  private boolean cancelled;
  private boolean timedOut;
  private boolean futureDone;
  private boolean permit;
  private long enqueuedAtNanos;
  private long startedAtNanos;
  private @Nullable VelocityServerConnection connection;
  private @Nullable ScheduledFuture<?> queueTimeout;
  private @Nullable ScheduledFuture<?> operationTimeout;

  ConnectionOperation(long id, ConnectedPlayer player, RegisteredServer destination,
      @Nullable VelocityRegisteredServer previousServer, Policy policy, boolean recovery,
      ConnectionExecution execution) {
    this.id = id;
    this.player = player;
    this.destination = destination;
    this.previousServer = previousServer;
    this.policy = policy;
    this.recovery = recovery;
    this.execution = execution;
  }

  /**
   * Returns the operation identifier.
   *
   * @return the identifier
   */
  public long getId() {
    return id;
  }

  /**
   * Returns the player that requested this operation.
   *
   * @return the player
   */
  public ConnectedPlayer getPlayer() {
    return player;
  }

  /**
   * Returns the destination server.
   *
   * @return the destination
   */
  public RegisteredServer getDestination() {
    return destination;
  }

  /**
   * Returns the previous server, if any.
   *
   * @return the previous server or {@code null}
   */
  public @Nullable VelocityRegisteredServer getPreviousServer() {
    return previousServer;
  }

  /**
   * Returns the scheduling policy for this operation.
   *
   * @return the policy
   */
  public Policy getPolicy() {
    return policy;
  }

  /**
   * Returns whether this operation was submitted as a critical recovery operation.
   *
   * @return {@code true} for recovery operations
   */
  public boolean isRecovery() {
    return recovery;
  }

  /**
   * Returns the future completed when the operation finishes.
   *
   * @return the operation future
   */
  public CompletableFuture<Impl> getFuture() {
    return future;
  }

  /**
   * Associates the underlying backend connection with this operation.
   *
   * @param connection the backend connection
   */
  public void attachConnection(VelocityServerConnection connection) {
    this.connection = connection;
  }

  /**
   * Returns the underlying backend connection, if it has been created.
   *
   * @return the backend connection or {@code null}
   */
  public @Nullable VelocityServerConnection getConnection() {
    return connection;
  }

  /**
   * Returns whether this operation has been cancelled.
   *
   * @return {@code true} when cancelled
   */
  public boolean isCancelled() {
    return cancelled;
  }

  /**
   * Returns whether this operation has finished.
   *
   * @return {@code true} when finished
   */
  public boolean isFinished() {
    return finished;
  }

  /**
   * Returns whether this operation has exceeded its operation timeout.
   *
   * @return {@code true} when timed out
   */
  public boolean isTimedOut() {
    return timedOut;
  }

  ConnectionExecution getExecution() {
    return execution;
  }

  State getState() {
    return state;
  }

  long getEnqueuedAtNanos() {
    return enqueuedAtNanos;
  }

  long getStartedAtNanos() {
    return startedAtNanos;
  }

  void markEnqueued(long nowNanos) {
    this.enqueuedAtNanos = nowNanos;
  }

  void markWaitingGlobal() {
    this.state = State.WAITING_GLOBAL;
  }

  void markRunning(long nowNanos) {
    this.state = State.RUNNING;
    this.startedAtNanos = nowNanos;
  }

  boolean markFinished() {
    if (finished) {
      return false;
    }
    finished = true;
    state = State.DONE;
    cancelTimeouts();
    return true;
  }

  void markCancelled() {
    cancelled = true;
    finished = true;
    state = State.DONE;
    cancelTimeouts();
  }

  void setTimedOut() {
    timedOut = true;
  }

  boolean isFutureDone() {
    return futureDone;
  }

  boolean completeFuture(Impl result) {
    if (futureDone) {
      return false;
    }
    futureDone = true;
    future.complete(result);
    return true;
  }

  boolean completeExceptionally(Throwable error) {
    if (futureDone) {
      return false;
    }
    futureDone = true;
    future.completeExceptionally(error);
    return true;
  }

  boolean hasPermit() {
    return permit;
  }

  void setPermit(boolean permit) {
    this.permit = permit;
  }

  void setQueueTimeout(@Nullable ScheduledFuture<?> queueTimeout) {
    this.queueTimeout = queueTimeout;
  }

  void setOperationTimeout(@Nullable ScheduledFuture<?> operationTimeout) {
    this.operationTimeout = operationTimeout;
  }

  void cancelQueueTimeout() {
    if (queueTimeout != null) {
      queueTimeout.cancel(false);
      queueTimeout = null;
    }
  }

  void cancelOperationTimeout() {
    if (operationTimeout != null) {
      operationTimeout.cancel(false);
      operationTimeout = null;
    }
  }

  void cancelTimeouts() {
    cancelQueueTimeout();
    cancelOperationTimeout();
  }
}
