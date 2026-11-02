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

import dev.vegui.acceleration.api.CommandRegistry;
import java.util.HashMap;
import java.util.function.Consumer;

/** Data-only public adapter, never exposes a dispatcher or executable callbacks. */
public final class PublicCommandRegistry implements CommandRegistry {
  private final LocalCommandRegistry registry;

  public PublicCommandRegistry(LocalCommandRegistry registry) {
    this.registry = registry;
  }

  private static Snapshot convert(LocalCommandRegistry.Snapshot source) {
    var entries = new HashMap<String, Entry>();
    source.aliases().forEach((alias, entry) -> entries.put(alias, new Entry(entry.registration(),
        entry.name(), entry.aliases(), entry.kind().name())));
    return new Snapshot(source.revision(), entries);
  }

  @Override
  public Snapshot snapshot() {
    return convert(registry.snapshot());
  }

  @Override
  public AutoCloseable listen(Consumer<Snapshot> listener) {
    java.util.Objects.requireNonNull(listener);
    return registry.listen(snapshot -> listener.accept(convert(snapshot)));
  }
}
