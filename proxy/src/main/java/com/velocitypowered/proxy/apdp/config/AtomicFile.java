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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Owner-only durable replacement. No fallback to a non-atomic state commit. */
public final class AtomicFile {
  private AtomicFile() { }

  /** Reads a bounded regular file without following a symbolic link. */
  public static byte[] read(Path path, int limit) throws IOException {
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("APDP state must be a regular file");
    }
    try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = stream.readNBytes(limit + 1);
      if (bytes.length > limit) {
        throw new IOException("APDP state exceeds size limit");
      }
      return bytes;
    }
  }

  /** Commits owner-only bytes durably; creation never overwrites an existing file. */
  public static void write(Path path, byte[] bytes, boolean create) throws IOException {
    Path directory = path.toAbsolutePath().getParent();
    if (Files.isSymbolicLink(path)) {
      throw new IOException("APDP refuses symlink state");
    }
    Path temporary = Files.createTempFile(directory, ".apdp-", ".tmp",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    try {
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      if (create) {
        Files.createLink(path, temporary);
      } else {
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      }
      try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
        channel.force(true);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
