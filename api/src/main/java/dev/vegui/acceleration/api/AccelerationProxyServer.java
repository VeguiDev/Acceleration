/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package dev.vegui.acceleration.api;

import com.velocitypowered.api.proxy.ProxyServer;

/** Additive Acceleration capabilities. Existing Velocity interfaces remain unchanged. */
public interface AccelerationProxyServer extends ProxyServer {
  /** Returns discovery access; an unconfigured proxy has an empty topology. */
  Discovery discovery();

  /** Immutable local ADCR registry metadata. No permission checks or commands are executed. */
  CommandRegistry commandRegistry();
}
