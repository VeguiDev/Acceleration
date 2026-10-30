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

package com.velocitypowered.proxy.apdp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.southernstorm.noise.protocol.HandshakeState;
import com.velocitypowered.proxy.apdp.identity.NodeIdentity;
import com.velocitypowered.proxy.apdp.protocol.Wire;
import com.velocitypowered.proxy.apdp.transport.NoiseChannel;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(25)
class NoiseChannelTest {
  @TempDir
  Path temporary;

  private static byte[] hex(String text) {
    return HexFormat.of().parseHex(text);
  }

  @Test
  void selectedProfileMatchesPublishedNoiseVector() throws Exception {
    // Signal/Southern Storm test-vectors.json, Noise_XK_25519_ChaChaPoly_SHA256.
    // https://github.com/signalapp/noise-java/blob/master/src/test/resources/
    // com/southernstorm/noise/tests/test-vectors.json (MIT-licensed reference data).
    HandshakeState initiator = new HandshakeState(NoiseChannel.PROFILE, HandshakeState.INITIATOR);
    HandshakeState responder = new HandshakeState(NoiseChannel.PROFILE, HandshakeState.RESPONDER);
    try {
      initiator.getLocalKeyPair().setPrivateKey(hex("e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1"), 0);
      initiator.getFixedEphemeralKey().setPrivateKey(hex("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a"), 0);
      initiator.getRemotePublicKey().setPublicKey(hex("31e0303fd6418d2f8c0e78b91f22e8caed0fbe48656dcf4767e4834f701b8f62"), 0);
      responder.getLocalKeyPair().setPrivateKey(hex("4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893"), 0);
      responder.getFixedEphemeralKey().setPrivateKey(hex("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b"), 0);
      byte[] prologue = hex("50726f6c6f677565313233");
      initiator.setPrologue(prologue, 0, prologue.length);
      responder.setPrologue(prologue, 0, prologue.length);
      initiator.start();
      responder.start();
      String[] payloads = {"4c756477696720766f6e204d69736573", "4d757272617920526f746862617264", "462e20412e20486179656b"};
      String[] expected = {
          "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c7944a3785af283c991bab613473804356ef6f6f2b62da8045c2a09d21b404107542d",
          "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f1448088433a4534805fa9fe4eb8343ace6609166aa62b33a49adbffaa09fcac8710d3c0",
          "5d8e67b9c1b8e36f5dc674bc5cd2ce243fb5d1710fa57de0370da7cc979015392b410497a747e8c643b342b426b06653aa6"
              + "fd4288dfd8d70f419bf1262ef247cedf3f54cb0b69e7cbc63a0"
      };
      for (int i = 0; i < 3; i++) {
        HandshakeState sender = i % 2 == 0 ? initiator : responder;
        HandshakeState receiver = i % 2 == 0 ? responder : initiator;
        byte[] buffer = new byte[256];
        byte[] payload = hex(payloads[i]);
        int length = sender.writeMessage(buffer, 0, payload, 0, payload.length);
        assertArrayEquals(hex(expected[i]), java.util.Arrays.copyOf(buffer, length));
        byte[] decoded = new byte[256];
        int size = receiver.readMessage(buffer, 0, length, decoded, 0);
        assertArrayEquals(payload, java.util.Arrays.copyOf(decoded, size));
      }
      assertArrayEquals(hex("c7fc7c515bbcec7001e3b09df01f4691ef55912ae6c0c1283581557483ebb29a"), initiator.getHandshakeHash());
      assertArrayEquals(initiator.getHandshakeHash(), responder.getHandshakeHash());
    } finally {
      initiator.destroy();
      responder.destroy();
    }
  }

  @Test
  void maximumLogicalMessageFragmentsAndPinsPeerIdentity() throws Exception {
    NodeIdentity parent = NodeIdentity.loadOrCreate(temporary.resolve("parent"));
    NodeIdentity child = NodeIdentity.loadOrCreate(temporary.resolve("child"));
    byte[] data = new byte[Wire.MAX];
    new java.security.SecureRandom().nextBytes(data);
    try (ServerSocket listener = new ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress());
         var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var response = executor.submit(() -> {
        try (NoiseChannel channel = new NoiseChannel(listener.accept(), parent, null, "test/session")) {
          assertEquals(Wire.b64(NoiseChannel.publicKey(child)), channel.remoteKey());
          assertArrayEquals(data, channel.receive());
          channel.send(data);
          channel.destroy();
        }
        return true;
      });
      try (NoiseChannel channel = new NoiseChannel(new Socket("localhost", listener.getLocalPort()),
          child, NoiseChannel.publicKey(parent), "test/session")) {
        channel.send(data);
        assertArrayEquals(data, channel.receive());
        assertThrows(Exception.class, () -> channel.send(new byte[Wire.MAX + 1]));
        channel.destroy();
      }
      response.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void wrongPinnedParentCannotCompleteHandshake() throws Exception {
    NodeIdentity parent = NodeIdentity.loadOrCreate(temporary.resolve("parent"));
    NodeIdentity child = NodeIdentity.loadOrCreate(temporary.resolve("child"));
    NodeIdentity fake = NodeIdentity.loadOrCreate(temporary.resolve("fake"));
    try (ServerSocket listener = new ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress());
         var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var rejected = executor.submit(() -> {
        try (Socket socket = listener.accept()) {
          assertThrows(Exception.class, () -> new NoiseChannel(socket, fake, null, "test/session"));
        }
        return true;
      });
      try (Socket socket = new Socket("localhost", listener.getLocalPort())) {
        assertThrows(Exception.class, () -> new NoiseChannel(socket, child,
            NoiseChannel.publicKey(parent), "test/session"));
      }
      rejected.get(10, TimeUnit.SECONDS);
    }
  }
}
