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

package com.velocitypowered.proxy.apdp.identity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

/** Persistent local keys. Enrollment and membership are separate from key possession. */
public final class NodeIdentity {
  private static final int MAGIC = 0x41504431;
  private final UUID nodeId;
  private final KeyPair noise;
  private final KeyPair signing;

  private NodeIdentity(UUID nodeId, KeyPair noise, KeyPair signing) {
    this.nodeId = nodeId;
    this.noise = noise;
    this.signing = signing;
  }

  public UUID nodeId() {
    return nodeId;
  }

  public KeyPair noise() {
    return noise;
  }

  public KeyPair signing() {
    return signing;
  }

  /**
   * Loads or creates owner-only POSIX state without replacing an existing identity.
   * A fully written temporary inode is linked atomically; concurrent creators load the winner.
   * Unsupported file protection fails closed, rather than writing unprotected private keys.
   */
  public static NodeIdentity loadOrCreate(Path directory)
      throws IOException, GeneralSecurityException {
    Files.createDirectories(directory,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    if (Files.isSymbolicLink(directory)
        || !Files.getPosixFilePermissions(directory).equals(
            PosixFilePermissions.fromString("rwx------"))) {
      throw new IOException("APDP identity directory must be owner-only and not a symlink");
    }
    Path target = directory.resolve("identity.bin");
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return load(target);
    }
    NodeIdentity created = new NodeIdentity(UUID.randomUUID(),
        KeyPairGenerator.getInstance("X25519").generateKeyPair(),
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);
      out.writeLong(created.nodeId.getMostSignificantBits());
      out.writeLong(created.nodeId.getLeastSignificantBits());
      for (KeyPair keys : new KeyPair[]{created.noise, created.signing}) {
        write(out, keys.getPublic().getEncoded());
        write(out, keys.getPrivate().getEncoded());
      }
    }
    Path temporary = Files.createTempFile(directory, ".identity-", ".tmp",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    try {
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      try {
        Files.createLink(target, temporary);
      } catch (FileAlreadyExistsException concurrentCreator) {
        return load(target);
      }
      try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
        channel.force(true);
      }
      return created;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static void write(DataOutputStream out, byte[] value) throws IOException {
    out.writeInt(value.length);
    out.write(value);
  }

  private static byte[] read(DataInputStream in) throws IOException {
    int length = in.readInt();
    if (length < 1 || length > 2048) {
      throw new IOException("Invalid identity field length");
    }
    byte[] data = new byte[length];
    in.readFully(data);
    return data;
  }

  private static NodeIdentity load(Path target) throws IOException, GeneralSecurityException {
    if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
        || !Files.getPosixFilePermissions(target).equals(
            PosixFilePermissions.fromString("rw-------"))) {
      throw new IOException("APDP identity must be an owner-only regular file");
    }
    byte[] data;
    try (var stream = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
      data = stream.readNBytes(8193);
    }
    if (data.length > 8192) {
      throw new IOException("Oversized identity file");
    }
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
      if (in.readInt() != MAGIC) {
        throw new IOException("Unknown identity format");
      }
      final UUID id = new UUID(in.readLong(), in.readLong());
      KeyPair[] pairs = new KeyPair[2];
      String[] algorithms = {"X25519", "Ed25519"};
      for (int i = 0; i < pairs.length; i++) {
        KeyFactory factory = KeyFactory.getInstance(algorithms[i]);
        pairs[i] = new KeyPair(factory.generatePublic(new X509EncodedKeySpec(read(in))),
            factory.generatePrivate(new PKCS8EncodedKeySpec(read(in))));
      }
      if (in.available() != 0) {
        throw new IOException("Trailing identity data");
      }
      verifyPairs(pairs);
      return new NodeIdentity(id, pairs[0], pairs[1]);
    }
  }

  private static void verifyPairs(KeyPair[] pairs) throws GeneralSecurityException {
    KeyPair probe = KeyPairGenerator.getInstance("X25519").generateKeyPair();
    javax.crypto.KeyAgreement first = javax.crypto.KeyAgreement.getInstance("X25519");
    first.init(pairs[0].getPrivate());
    first.doPhase(probe.getPublic(), true);
    javax.crypto.KeyAgreement second = javax.crypto.KeyAgreement.getInstance("X25519");
    second.init(probe.getPrivate());
    second.doPhase(pairs[0].getPublic(), true);
    java.security.Signature signature = java.security.Signature.getInstance("Ed25519");
    signature.initSign(pairs[1].getPrivate());
    signature.update(pairs[0].getPublic().getEncoded());
    byte[] proof = signature.sign();
    signature.initVerify(pairs[1].getPublic());
    signature.update(pairs[0].getPublic().getEncoded());
    if (!java.security.MessageDigest.isEqual(first.generateSecret(), second.generateSecret())
        || !signature.verify(proof)) {
      throw new GeneralSecurityException("Mismatched APDP identity keys");
    }
  }
}
