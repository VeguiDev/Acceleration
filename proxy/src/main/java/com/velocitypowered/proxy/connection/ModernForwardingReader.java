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

package com.velocitypowered.proxy.connection;

import com.google.common.net.InetAddresses;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.crypto.IdentifiedKeyImpl;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/** Authenticates and decodes the server side of Modern Forwarding. */
public final class ModernForwardingReader {
  private ModernForwardingReader() {
  }

  /** Authenticated identity, independent of the upstream transport address. */
  public record Identity(InetAddress address, GameProfile profile, @Nullable IdentifiedKey key) {
  }

  /** Rejects untrusted data before parsing any identity fields. */
  public static Identity read(byte[] payload, byte[] secret, ProtocolVersion protocol) {
    if (secret.length == 0 || payload.length < 33 || payload.length > 1_048_576) {
      throw new IllegalArgumentException("Invalid forwarding length");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      mac.update(payload, 32, payload.length - 32);
      if (!MessageDigest.isEqual(java.util.Arrays.copyOf(payload, 32), mac.doFinal())) {
        throw new IllegalArgumentException("Invalid forwarding signature");
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC unavailable", e);
    }
    ByteBuf data = Unpooled.wrappedBuffer(payload, 32, payload.length - 32);
    try {
      int version = ProtocolUtils.readVarInt(data);
      if (version < 1 || version > 4
          || (version == 4 && protocol.lessThan(ProtocolVersion.MINECRAFT_1_19_3))
          || ((version == 2 || version == 3)
          && (protocol.lessThan(ProtocolVersion.MINECRAFT_1_19)
          || protocol.noLessThan(ProtocolVersion.MINECRAFT_1_19_3)))) {
        throw new IllegalArgumentException("Unsupported forwarding version");
      }
      final InetAddress address = InetAddresses.forString(ProtocolUtils.readString(data, 64));
      final UUID uuid = ProtocolUtils.readUuid(data);
      String name = ProtocolUtils.readString(data, 16);
      if (!name.matches("[A-Za-z0-9_]{1,16}")) {
        throw new IllegalArgumentException("Invalid forwarded username");
      }
      int count = ProtocolUtils.readVarInt(data);
      if (count < 0 || count > 64) {
        throw new IllegalArgumentException("Too many profile properties");
      }
      var properties = new ArrayList<GameProfile.Property>(count);
      for (int i = 0; i < count; i++) {
        String propertyName = ProtocolUtils.readString(data, 256);
        String value = ProtocolUtils.readString(data, 32767);
        String signature = data.readBoolean() ? ProtocolUtils.readString(data, 32767) : "";
        properties.add(new GameProfile.Property(propertyName, value, signature));
      }
      IdentifiedKey key = null;
      if (version == 2 || version == 3) {
        long expiry = data.readLong();
        key = new IdentifiedKeyImpl(version == 2 ? IdentifiedKey.Revision.GENERIC_V1
            : IdentifiedKey.Revision.LINKED_V2, ProtocolUtils.readByteArray(data, 4096),
            expiry, ProtocolUtils.readByteArray(data, 4096));
        if (version == 3) {
          UUID holder = data.readBoolean() ? ProtocolUtils.readUuid(data) : uuid;
          if (!((IdentifiedKeyImpl) key).internalAddHolder(holder)) {
            throw new IllegalArgumentException("Invalid forwarded key holder");
          }
        }
        if (key.hasExpired() || !key.isSignatureValid()) {
          throw new IllegalArgumentException("Invalid forwarded key");
        }
      }
      if (data.isReadable()) {
        throw new IllegalArgumentException("Trailing forwarding data");
      }
      return new Identity(address, new GameProfile(uuid, name, properties), key);
    } finally {
      data.release();
    }
  }
}
