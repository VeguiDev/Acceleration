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

package com.velocitypowered.proxy.adcr;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable session route, installed only after authenticating each Minecraft connection. */
public record CommandRoute(UUID session, UUID player, long epoch, List<UUID> nodes) {
  /** Nodes run from head to the deepest proxy, never include Minecraft leaves. */
  public CommandRoute {
    Objects.requireNonNull(session);
    Objects.requireNonNull(player);
    nodes = List.copyOf(nodes);
    if (epoch < 1 || nodes.isEmpty() || nodes.size() > 257
        || new HashSet<>(nodes).size() != nodes.size()) {
      throw new IllegalArgumentException("Invalid command session route");
    }
  }

  /** A move replaces, rather than extends, the previous route generation. */
  public CommandRoute moved(List<UUID> replacement) {
    if (replacement.isEmpty() || !nodes.getFirst().equals(replacement.getFirst())) {
      throw new IllegalArgumentException("Cannot move a session between heads");
    }
    return new CommandRoute(session, player, Math.incrementExact(epoch), replacement);
  }
}
