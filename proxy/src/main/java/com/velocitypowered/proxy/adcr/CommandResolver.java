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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Pure owner selection. Permission denial is deliberately not treated as absence. */
public final class CommandResolver {
  /** Selection is not execution, and conveys no permissions. */
  public enum Status {
    OWNER, FORWARD, NOT_REGISTERED, UNAVAILABLE, INVALID_NAMESPACE
  }

  /** Captures the exact registration to revalidate before executing. */
  public record Selection(Status status, UUID owner, UUID registration, String command) { }

  private CommandResolver() { }

  /**
   * Selects deepest-first using complete catalogs for every eligible node.
   * Missing catalogs stop resolution: an unavailable nearer owner must never be bypassed.
   */
  public static Selection select(CommandRoute route, UUID local, String input,
      Map<UUID, String> publishedNames, Map<UUID, LocalCommandRegistry.Snapshot> catalogs) {
    Objects.requireNonNull(route);
    Objects.requireNonNull(input);
    if (!route.nodes().contains(local) || input.isEmpty() || input.length() > 32767
        || input.charAt(0) == '/' || Character.isWhitespace(input.charAt(0))) {
      throw new IllegalArgumentException("Invalid command resolution context");
    }
    int space = input.indexOf(' ');
    String label = space < 0 ? input : input.substring(0, space);
    String arguments = space < 0 ? "" : input.substring(space);
    int colon = label.indexOf(':');
    if (colon >= 0) {
      String namespace = label.substring(0, colon);
      String alias = label.substring(colon + 1);
      UUID target = target(route.nodes(), local, namespace, publishedNames);
      if (target == null || alias.isEmpty() || alias.indexOf(':') >= 0) {
        return new Selection(Status.INVALID_NAMESPACE, null, null, input);
      }
      Selection selection = at(target, alias, arguments, catalogs);
      return selection.status() == Status.FORWARD
          ? new Selection(Status.NOT_REGISTERED, target, null, alias + arguments) : selection;
    }
    for (UUID node : route.nodes().reversed()) {
      Selection selected = at(node, label, arguments, catalogs);
      if (selected.status() != Status.FORWARD) {
        return selected;
      }
    }
    return new Selection(Status.FORWARD, null, null, input);
  }

  private static Selection at(UUID node, String alias, String arguments,
      Map<UUID, LocalCommandRegistry.Snapshot> catalogs) {
    LocalCommandRegistry.Snapshot catalog = catalogs.get(node);
    if (catalog == null) {
      return new Selection(Status.UNAVAILABLE, node, null, alias + arguments);
    }
    LocalCommandRegistry.Entry entry = catalog.aliases().get(alias.toLowerCase(Locale.ROOT));
    return entry == null ? new Selection(Status.FORWARD, null, null, alias + arguments)
        : new Selection(Status.OWNER, node, entry.registration(), alias + arguments);
  }

  private static UUID target(List<UUID> nodes, UUID local, String namespace,
      Map<UUID, String> names) {
    if (namespace.equals("head")) {
      return nodes.getFirst();
    }
    if (namespace.equals("local")) {
      return local;
    }
    // APDP node names already use the lowercase slug alphabet. Ambiguity is global,
    // even when only one of the identically named nodes belongs to this route.
    UUID match = null;
    for (var entry : names.entrySet()) {
      UUID node = entry.getKey();
      if (entry.getValue().equals(namespace)) {
        if (match != null && !match.equals(node)) {
          return null;
        }
        match = node;
      }
    }
    return match != null && nodes.contains(match) ? match : null;
  }
}
