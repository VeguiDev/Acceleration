/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package dev.vegui.acceleration.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Local command registration observations. Metadata never grants permission to execute. */
public interface CommandRegistry {
  /** Data only; registration identity changes when a command is replaced. */
  record Entry(UUID registration, String name, List<String> aliases, String kind) {
    public Entry {
      aliases = List.copyOf(aliases);
    }
  }

  /** Immutable alias ownership at a monotonically increasing local revision. */
  record Snapshot(long revision, Map<String, Entry> aliases) {
    public Snapshot {
      aliases = Map.copyOf(aliases);
    }
  }

  Snapshot snapshot();

  /** Subscribe before reading a snapshot. Callbacks must not block; ignore older revisions. */
  AutoCloseable listen(Consumer<Snapshot> listener);
}
