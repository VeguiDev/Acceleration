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

import com.google.gson.JsonObject;
import com.velocitypowered.proxy.apdp.protocol.Wire;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/** Ed25519 signed APDP statements, domain separated from any other signing use. */
public record Signed(String body, String signature) {
  private static final byte[] DOMAIN = "Acceleration/APDP/1/statement\0"
      .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  /** Signs the exact encoded bytes with the APDP statement domain separator. */
  public static Signed sign(Object value, PrivateKey key) throws GeneralSecurityException {
    byte[] bytes = Wire.encode(value);
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(key);
    signer.update(DOMAIN);
    signer.update(bytes);
    return new Signed(Wire.b64(bytes), Wire.b64(signer.sign()));
  }

  /** Authenticates original bytes before interpreting their JSON contents. */
  public JsonObject verify(String publicKey) throws IOException, GeneralSecurityException {
    byte[] bytes = Wire.unb64(body);
    Signature signer = Signature.getInstance("Ed25519");
    signer.initVerify(KeyFactory.getInstance("Ed25519")
        .generatePublic(new X509EncodedKeySpec(Wire.unb64(publicKey))));
    signer.update(DOMAIN);
    signer.update(bytes);
    if (!signer.verify(Wire.unb64(signature))) {
      throw new GeneralSecurityException("Invalid APDP signature");
    }
    return Wire.parse(bytes);
  }

  /** Reads the bounded signature envelope without accepting unknown fields. */
  public static Signed decode(JsonObject object) throws IOException {
    Wire.fields(object, "body", "signature");
    return new Signed(Wire.string(object, "body", Wire.MAX * 2),
        Wire.string(object, "signature", 128));
  }
}
