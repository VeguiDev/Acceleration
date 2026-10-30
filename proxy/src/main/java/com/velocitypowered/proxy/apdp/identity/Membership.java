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
import java.util.UUID;

/** Root-signed network membership, independent of endpoint and publication. */
public record Membership(UUID network, UUID node, UUID parent, String noise, String signing,
                         boolean canInvite, long generation) {
  /** Checks the network authority signature and identity/key invariants. */
  public static Membership verify(Signed assertion, String root)
      throws IOException, GeneralSecurityException {
    JsonObject object = assertion.verify(root);
    Wire.fields(object, "network", "node", "parent", "noise", "signing", "canInvite",
        "generation");
    Wire.integer(object, "generation", 1, Long.MAX_VALUE);
    Wire.bool(object, "canInvite");
    Membership member = Wire.GSON.fromJson(object, Membership.class);
    if (member.network == null || member.node == null || member.parent == null
        || member.generation < 1 || Wire.unb64(member.noise).length != 32
        || Wire.unb64(member.signing).length != 44) {
      throw new IOException("Invalid APDP membership");
    }
    return member;
  }
}
