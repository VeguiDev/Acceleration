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

package com.velocitypowered.proxy.apdp.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.velocitypowered.proxy.apdp.model.Topology.Mode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Editable public settings. Commands preserve comments/unknown keys and reject racing edits. */
public final class ApdpConfig {
  private final Path path;

  public ApdpConfig(Path path) {
    this.path = path.toAbsolutePath();
  }

  /** Creates safe loopback/local-publication defaults without overwriting user configuration. */
  public synchronized void create(String name) throws IOException {
    String initial = "# APDP control plane; contains no private keys or bearer tokens.\n"
        + "name = \"" + name + "\"\nlisten = \"127.0.0.1:0\"\n"
        + "advertise = \"\"\npublication = \"local\"\nexposed = []\n"
        + "max-nodes = 4096\nmax-depth = 64\nmax-peers = 128\n"
        + "# Minecraft server names are leaves unless listed here as proxy destinations.\n"
        + "proxy-destinations = []\n";
    AtomicFile.write(path, initial.getBytes(StandardCharsets.UTF_8), true);
  }

  /** Loads validated settings and applies runtime-only environment overrides. */
  public synchronized Settings load() throws IOException {
    try (CommentedFileConfig config = CommentedFileConfig.builder(path).sync().build()) {
      config.load();
      String name = config.get("name");
      if (name == null || !name.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
        throw new IOException("Invalid APDP name");
      }
      List<String> exposed = config.getOrElse("exposed", List.of());
      List<String> proxies = config.getOrElse("proxy-destinations", List.of());
      Map<String, String> addresses = new HashMap<>();
      if (config.contains("parent-address")) {
        addresses.put("parent", config.get("parent-address"));
      }
      return new Settings(name, config.getOrElse("listen", "127.0.0.1:0"),
          override("APDP_ADVERTISE_ADDRESS", config.getOrElse("advertise", "")),
          override("APDP_PARENT_ADDRESS", addresses.getOrDefault("parent", "")),
          Mode.valueOf(config.<String>getOrElse("publication", "local").toUpperCase(
              java.util.Locale.ROOT)), Set.copyOf(exposed), Set.copyOf(proxies),
          bounded(config.getOrElse("max-nodes", 4096), 1, 4096),
          bounded(config.getOrElse("max-depth", 64), 1, 256),
           bounded(config.getOrElse("max-peers", 128), 1, 512),
           bounded(config.getOrElse("weight", -1), -1, Integer.MAX_VALUE));
    } catch (RuntimeException e) {
      throw new IOException("Invalid apdp.toml", e);
    }
  }

  private static int bounded(int value, int min, int max) throws IOException {
    if (value < min || value > max) {
      throw new IOException("APDP resource setting outside bounds");
    }
    return value;
  }

  private static String override(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  /** Applies a command edit while preserving unrelated fields and detecting concurrent writers. */
  public synchronized void edit(Consumer<CommentedFileConfig> change) throws IOException {
    Path lockPath = path.resolveSibling(".apdp-config.lock");
    try (FileChannel lock = FileChannel.open(lockPath, StandardOpenOption.CREATE,
        StandardOpenOption.WRITE); var ignored = lock.tryLock()) {
      if (ignored == null) {
        throw new IOException("Another APDP writer is active");
      }
      byte[] before = AtomicFile.read(path, 65536);
      Path temporary = Files.createTempFile(path.getParent(), ".apdp-config-", ".toml");
      try {
        Files.write(temporary, before);
        try (CommentedFileConfig config = CommentedFileConfig.builder(temporary).sync().build()) {
          config.load();
          change.accept(config);
          config.save();
        }
        if (!Arrays.equals(before, AtomicFile.read(path, 65536))) {
          throw new IOException("apdp.toml changed concurrently; edit aborted");
        }
        AtomicFile.write(path, AtomicFile.read(temporary, 65536), false);
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
  }

  /** Operational settings; endpoints never appear in published topology. */
  public record Settings(String name, String listen, String advertise, String parentAddress,
                         Mode publication, Set<String> exposed, Set<String> proxyDestinations,
                          int maxNodes, int maxDepth, int maxPeers, int weight) { }
}
