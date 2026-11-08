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

import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import java.util.concurrent.CompletableFuture;

/**
 * Executes the actual backend connection for a scheduled operation.
 */
@FunctionalInterface
public interface ConnectionExecution {

  /**
   * Starts the backend connection.
   *
   * @param operation the operation being executed
   * @return a future that completes when the connection attempt finishes
   */
  CompletableFuture<Impl> execute(ConnectionOperation operation);
}
