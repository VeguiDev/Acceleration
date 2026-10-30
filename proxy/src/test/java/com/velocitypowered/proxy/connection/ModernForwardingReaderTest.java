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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.util.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModernForwardingReaderTest {
  private static final byte[] SECRET = "test-only-secret".getBytes(StandardCharsets.UTF_8);
  private static final GameProfile PROFILE = new GameProfile(UUID.randomUUID(), "TestPlayer",
      List.of(new GameProfile.Property("textures", "texture-value", "texture-signature")));

  private byte[] signed(ProtocolVersion protocol, String ip, int version) {
    ByteBuf buffer = PlayerDataForwarding.createForwardingData(SECRET, ip, protocol,
        PROFILE, null, version);
    try {
      return ByteBufUtil.getBytes(buffer);
    } finally {
      buffer.release();
    }
  }

  @Test
  void acceptsExistingVelocitySerializerAndPreservesIdentity() {
    for (ProtocolVersion protocol : List.of(ProtocolVersion.MINECRAFT_1_13,
        ProtocolVersion.MINECRAFT_1_19_3, ProtocolVersion.MINECRAFT_1_20_2)) {
      var identity = ModernForwardingReader.read(signed(protocol, "2001:db8::1", 4), SECRET,
          protocol);
      assertEquals(PROFILE.getId(), identity.profile().getId());
      assertEquals(PROFILE.getName(), identity.profile().getName());
      assertEquals(1, identity.profile().getProperties().size());
      assertEquals("textures", identity.profile().getProperties().getFirst().getName());
      assertEquals("texture-value", identity.profile().getProperties().getFirst().getValue());
      assertEquals("texture-signature", identity.profile().getProperties().getFirst().getSignature());
      assertEquals("2001:db8:0:0:0:0:0:1", identity.address().getHostAddress());
    }
  }

  @Test
  void rejectsWrongSecretAndTampering() {
    byte[] payload = signed(ProtocolVersion.MINECRAFT_1_20_2, "192.0.2.1", 4);
    assertThrows(IllegalArgumentException.class, () -> ModernForwardingReader.read(payload,
        new byte[] {1}, ProtocolVersion.MINECRAFT_1_20_2));
    payload[payload.length - 1] ^= 1;
    assertThrows(IllegalArgumentException.class, () -> ModernForwardingReader.read(payload,
        SECRET, ProtocolVersion.MINECRAFT_1_20_2));
  }

  @Test
  void rejectsHostnamesAndTruncation() {
    assertThrows(IllegalArgumentException.class, () -> ModernForwardingReader.read(
        signed(ProtocolVersion.MINECRAFT_1_13, "localhost", 1), SECRET,
        ProtocolVersion.MINECRAFT_1_13));
    assertThrows(IllegalArgumentException.class, () -> ModernForwardingReader.read(new byte[32],
        SECRET, ProtocolVersion.MINECRAFT_1_13));
  }

  private byte[] resign(byte[] payload) throws java.security.GeneralSecurityException {
    var mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(new javax.crypto.spec.SecretKeySpec(SECRET, "HmacSHA256"));
    mac.update(payload, 32, payload.length - 32);
    System.arraycopy(mac.doFinal(), 0, payload, 0, 32);
    return payload;
  }

  @Test
  void rejectsAuthenticatedUnsupportedVersionAndTrailingData() throws Exception {
    byte[] payload = signed(ProtocolVersion.MINECRAFT_1_20_2, "192.0.2.1", 4);
    payload[32] = 5;
    byte[] unsupported = resign(payload);
    assertThrows(IllegalArgumentException.class, () -> ModernForwardingReader.read(unsupported,
        SECRET, ProtocolVersion.MINECRAFT_1_20_2));
    byte[] trailing = resign(java.util.Arrays.copyOf(
        signed(ProtocolVersion.MINECRAFT_1_20_2, "192.0.2.1", 4), payload.length + 1));
    assertThrows(IllegalArgumentException.class, () -> ModernForwardingReader.read(trailing,
        SECRET, ProtocolVersion.MINECRAFT_1_20_2));
  }

  @Test
  void rejectsIncompatibleVersionAndEmptySecret() {
    byte[] payload = signed(ProtocolVersion.MINECRAFT_1_20_2, "192.0.2.1", 4);
    assertThrows(IllegalArgumentException.class, () -> ModernForwardingReader.read(payload,
        SECRET, ProtocolVersion.MINECRAFT_1_13));
    assertThrows(IllegalArgumentException.class, () -> ModernForwardingReader.read(payload,
        new byte[0], ProtocolVersion.MINECRAFT_1_20_2));
  }
}
