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

package com.velocitypowered.proxy.apdp.transport;

import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;
import com.velocitypowered.proxy.apdp.identity.NodeIdentity;
import com.velocitypowered.proxy.apdp.protocol.Wire;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.util.Arrays;

/** One reader and serialized writers on a Noise XK channel over TCP. */
public final class NoiseChannel implements AutoCloseable {
  public static final String PROFILE = "Noise_XK_25519_ChaChaPoly_SHA256";
  private final Socket socket;
  private final DataInputStream input;
  private final DataOutputStream output;
  private final CipherStatePair ciphers;
  private final byte[] remoteKey;
  private final String binding;
  private long sent;
  private long received;

  /** Completes a pinned XK handshake; a null parent key selects the responder role. */
  public NoiseChannel(Socket socket, NodeIdentity identity, byte[] parentKey, String context)
      throws IOException, GeneralSecurityException {
    this.socket = socket;
    socket.setSoTimeout(15000);
    socket.setTcpNoDelay(true);
    input = new DataInputStream(socket.getInputStream());
    output = new DataOutputStream(socket.getOutputStream());
    HandshakeState handshake = new HandshakeState(PROFILE, parentKey == null
        ? HandshakeState.RESPONDER : HandshakeState.INITIATOR);
    try {
      byte[] privateKey = ((XECPrivateKey) identity.noise().getPrivate()).getScalar().orElseThrow();
      try {
        handshake.getLocalKeyPair().setPrivateKey(privateKey, 0);
      } finally {
        Arrays.fill(privateKey, (byte) 0);
      }
      if (parentKey != null) {
        if (parentKey.length != 32) {
          throw new IOException("Invalid parent Noise key");
        }
        validateKey(parentKey, identity);
        handshake.getRemotePublicKey().setPublicKey(parentKey, 0);
      }
      byte[] prologue = ("APDP/1/" + context).getBytes(StandardCharsets.UTF_8);
      handshake.setPrologue(prologue, 0, prologue.length);
      handshake.start();
      while (handshake.getAction() != HandshakeState.SPLIT) {
        if (handshake.getAction() == HandshakeState.WRITE_MESSAGE) {
          byte[] message = new byte[256];
          int length = handshake.writeMessage(message, 0, null, 0, 0);
          output.writeShort(length);
          output.write(message, 0, length);
          output.flush();
        } else if (handshake.getAction() == HandshakeState.READ_MESSAGE) {
          int length = input.readUnsignedShort();
          if (length > 256) {
            throw new IOException("Oversized Noise handshake");
          }
          byte[] message = new byte[length];
          input.readFully(message);
          if (handshake.readMessage(message, 0, length, new byte[256], 0) != 0) {
            throw new IOException("Unexpected Noise handshake payload");
          }
        } else {
          throw new IOException("Noise handshake failed");
        }
      }
      remoteKey = new byte[32];
      handshake.getRemotePublicKey().getPublicKey(remoteKey, 0);
      validateKey(remoteKey, identity);
      binding = Wire.b64(handshake.getHandshakeHash());
      ciphers = handshake.split();
    } finally {
      handshake.destroy();
    }
  }

  /** Converts the JCA X25519 public coordinate into Noise's 32-byte little-endian format. */
  public static byte[] publicKey(NodeIdentity identity) {
    byte[] big = ((XECPublicKey) identity.noise().getPublic()).getU().toByteArray();
    byte[] little = new byte[32];
    for (int i = 0; i < Math.min(big.length, 32); i++) {
      little[i] = big[big.length - 1 - i];
    }
    return little;
  }

  private static void validateKey(byte[] raw, NodeIdentity identity)
      throws GeneralSecurityException {
    byte[] big = new byte[32];
    for (int i = 0; i < 32; i++) {
      big[31 - i] = raw[i];
    }
    big[0] &= 0x7f;
    var key = java.security.KeyFactory.getInstance("X25519").generatePublic(
        new java.security.spec.XECPublicKeySpec(java.security.spec.NamedParameterSpec.X25519,
            new java.math.BigInteger(1, big)));
    var agreement = javax.crypto.KeyAgreement.getInstance("X25519");
    agreement.init(identity.noise().getPrivate());
    // The JCA provider rejects low-order points / all-zero shared secrets. Do not rely on
    // the third-party Noise primitive's treatment of malicious static public keys.
    agreement.doPhase(key, true);
    byte[] secret = agreement.generateSecret();
    Arrays.fill(secret, (byte) 0);
  }

  public String remoteKey() {
    return Wire.b64(remoteKey);
  }

  public String binding() {
    return binding;
  }

  /** Atomically writes one bounded logical message across authenticated Noise records. */
  public synchronized void send(byte[] logical) throws IOException, GeneralSecurityException {
    if (logical.length > Wire.MAX) {
      throw new IOException("APDP message too large");
    }
    int offset = 0;
    do {
      int amount = Math.min(60000, logical.length - offset);
      byte[] plain = new byte[amount + 1];
      plain[0] = (byte) (offset + amount == logical.length ? 1 : 0);
      System.arraycopy(logical, offset, plain, 1, amount);
      if (++sent > (1L << 32)) {
        throw new IOException("Noise session rekey required");
      }
      byte[] encrypted = new byte[plain.length + 16];
      int length = ciphers.getSender().encryptWithAd(null, plain, 0, encrypted, 0, plain.length);
      output.writeShort(length);
      output.write(encrypted, 0, length);
      offset += amount;
    } while (offset < logical.length);
    output.flush();
  }

  /** Authenticates and reassembles a whole message before making any bytes available. */
  public byte[] receive() throws IOException, GeneralSecurityException {
    ByteArrayOutputStream logical = new ByteArrayOutputStream();
    for (int fragment = 0; fragment < 18; fragment++) {
      int length = input.readUnsignedShort();
      if (length < 17 || ++received > (1L << 32)) {
        throw new IOException("Invalid Noise record");
      }
      byte[] encrypted = new byte[length];
      input.readFully(encrypted);
      byte[] plain = new byte[length];
      int size = ciphers.getReceiver().decryptWithAd(null, encrypted, 0, plain, 0, length);
      if ((plain[0] != 0 && plain[0] != 1) || logical.size() + size - 1 > Wire.MAX) {
        throw new IOException("Invalid APDP fragment");
      }
      logical.write(plain, 1, size - 1);
      if (plain[0] == 1) {
        return logical.toByteArray();
      }
    }
    throw new IOException("Too many APDP fragments");
  }

  @Override
  public void close() throws IOException {
    socket.close();
    // Closing the socket unblocks the sole reader before the session owner destroys ciphers.
  }

  public synchronized void destroy() {
    ciphers.destroy();
  }
}
