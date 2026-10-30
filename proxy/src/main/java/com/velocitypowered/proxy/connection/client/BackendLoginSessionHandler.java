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

package com.velocitypowered.proxy.connection.client;

import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.ModernForwardingReader;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.LoginPluginResponsePacket;
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket;
import net.kyori.adventure.text.Component;

/** Login authenticated exclusively by the configured upstream forwarding secret. */
final class BackendLoginSessionHandler implements MinecraftSessionHandler {
  private final VelocityServer server;
  private final MinecraftConnection connection;
  private final LoginInboundConnection inbound;
  private final byte[] secret;
  private final boolean onlineMode;
  private boolean started;
  private boolean received;
  private int requestId;

  BackendLoginSessionHandler(VelocityServer server, MinecraftConnection connection,
      LoginInboundConnection inbound) {
    this.server = server;
    this.connection = connection;
    this.inbound = inbound;
    this.secret = server.getConfiguration().getProxyBackendSecret();
    this.onlineMode = server.getConfiguration().isOnlineMode();
  }

  @Override
  public boolean handle(ServerLoginPacket packet) {
    if (started) {
      reject();
      return true;
    }
    started = true;
    requestId = inbound.requestForwarding(payload -> {
      try {
        if (payload == null) {
          reject();
          return;
        }
        var identity = ModernForwardingReader.read(payload, secret, connection.getProtocolVersion());
        if (!identity.profile().getName().equals(packet.getUsername())
            || !server.getIpAttemptLimiter().attempt(identity.address())) {
          reject();
          return;
        }
        if (identity.key() == null && server.getConfiguration().isForceKeyAuthentication()
            && connection.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_19)
            && connection.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_19_3)) {
          reject();
          return;
        }
        connection.applyForwardedAddress(identity.address());
        if (identity.key() != null) {
          inbound.setPlayerKey(identity.key());
        }
        var event = new PreLoginEvent(inbound, identity.profile().getName(), identity.profile().getId());
        server.getEventManager().fire(event).thenRunAsync(() -> {
          if (connection.isClosed()) {
            return;
          }
          if (!event.getResult().isAllowed()) {
            inbound.disconnect(event.getResult().getReasonComponent().orElse(
                Component.text("Login denied")));
            return;
          }
          inbound.loginEventFired(() -> connection.eventLoop().execute(() -> {
            if (!connection.isClosed()) {
              connection.setActiveSessionHandler(StateRegistry.LOGIN,
                  new AuthSessionHandler(server, inbound, identity.profile(), onlineMode, null));
            }
          }));
        }, connection.eventLoop()).exceptionally(error -> {
          connection.eventLoop().execute(this::reject);
          return null;
        });
      } catch (RuntimeException invalid) {
        reject();
      }
    });
    return true;
  }

  @Override
  public boolean handle(LoginPluginResponsePacket packet) {
    if (!started || (packet.getId() == requestId && received)) {
      reject();
    } else {
      if (packet.getId() == requestId) {
        received = true;
      }
      inbound.handleLoginPluginResponse(packet);
    }
    return true;
  }

  private void reject() {
    inbound.disconnect(Component.text("Invalid or missing authenticated Modern Forwarding"));
  }

  @Override
  public void handleUnknown(io.netty.buffer.ByteBuf buffer) {
    reject();
  }

  @Override
  public void disconnected() {
    inbound.cleanup();
  }
}
