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

package com.velocitypowered.proxy.adcr;

import com.google.gson.JsonObject;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.apdp.ApdpService;
import com.velocitypowered.proxy.apdp.protocol.Wire;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;

/** Connection-bound distributed dispatch; no remote console or synthetic Player sources. */
public final class AdcrService {
  public static final String CHANNEL = "acceleration:command_route";

  private record Session(CommandRoute route, UUID parent, UUID handle) { }

  private static final class Handoff {
    final ConnectedPlayer player;
    final VelocityServerConnection connection;
    final UUID child;
    final Session session;
    final com.velocitypowered.proxy.apdp.identity.Signed intent;
    final long expires = System.currentTimeMillis() + 30000;
    volatile boolean claimed;

    Handoff(ConnectedPlayer player, VelocityServerConnection connection, UUID child, Session session,
        com.velocitypowered.proxy.apdp.identity.Signed intent) {
      this.player = player;
      this.connection = connection;
      this.child = child;
      this.session = session;
      this.intent = intent;
    }
  }

  private final VelocityServer server;
  private final ApdpService apdp;
  private final Map<MinecraftConnection, Session> sessions = new ConcurrentHashMap<>();
  private final Map<MinecraftConnection, com.velocitypowered.proxy.apdp.identity.Signed> initialTransfers =
      new ConcurrentHashMap<>();
  private final Map<MinecraftConnection, com.velocitypowered.proxy.apdp.identity.Signed> outgoingTransfers =
      new ConcurrentHashMap<>();
  private final Map<UUID, Handoff> handoffs = new ConcurrentHashMap<>();
  private final InvocationLedger ledger = new InvocationLedger(Clock.systemUTC(), 4096);
  private final InvocationLedger transfers = new InvocationLedger(Clock.systemUTC(), 4096);
  private final java.util.concurrent.ExecutorService workers =
      java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
  private final Map<MinecraftConnection, JsonObject> remoteCatalogs = new ConcurrentHashMap<>();
  private final Map<MinecraftConnection, com.mojang.brigadier.tree.RootCommandNode<
      com.velocitypowered.api.command.CommandSource>> backendTrees = new ConcurrentHashMap<>();
  private final Map<MinecraftConnection, String> treeFingerprints = new ConcurrentHashMap<>();
  private final Map<MinecraftConnection, java.util.Set<String>> advertisedLabels = new ConcurrentHashMap<>();
  private final java.util.Set<MinecraftConnection> refreshing = ConcurrentHashMap.newKeySet();
  private final java.util.concurrent.ScheduledExecutorService refreshTimer =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ADCR-registry-refresh");
        thread.setDaemon(true);
        return thread;
      });

  /** Registers a separately negotiated extension on the existing authenticated APDP sockets. */
  public AdcrService(VelocityServer server, ApdpService apdp) {
    this.server = server;
    this.apdp = apdp;
    apdp.commandHandler(this::receive);
    refreshTimer.scheduleWithFixedDelay(this::refreshTrees, 2, 2, TimeUnit.SECONDS);
  }

  public boolean enabled() {
    return apdp.localNode() != null && apdp.head() != null;
  }

  /** Challenge reveals no credential and is meaningful only to an authenticated direct parent. */
  public byte[] challenge() {
    return Wire.encode(Map.of("version", 1, "child", apdp.localNode()));
  }

  /** Called for this exact outgoing Minecraft connection, not a player's last-known server. */
  public byte[] offer(VelocityServerConnection connection, byte[] challenge) throws IOException {
    JsonObject object = Wire.parse(challenge);
    Wire.fields(object, "version", "child");
    Wire.integer(object, "version", 1, 1);
    UUID child = UUID.fromString(Wire.string(object, "child", 36));
    if (!apdp.commandPeer(child) || child.equals(apdp.commandParent())) {
      throw new IOException("Unnegotiated ADCR child");
    }
    Session session = session(connection.getPlayer());
    if (session == null || session.route().nodes().contains(child)) {
      throw new IOException("Invalid ADCR route");
    }
    if (session.parent() == null) {
      session = new Session(session.route().moved(session.route().nodes()), null, null);
      sessions.put(connection.getPlayer().getConnection(), session);
    }
    handoffs.entrySet().removeIf(entry -> !entry.getValue().claimed
        && entry.getValue().expires < System.currentTimeMillis());
    if (handoffs.size() >= 4096) {
      throw new IOException("ADCR handoff capacity reached");
    }
    UUID handle = UUID.randomUUID();
    var intent = outgoingTransfers.get(connection.getPlayer().getConnection());
    if (intent == null) {
      intent = initialTransfers.get(connection.getPlayer().getConnection());
    }
    if (intent != null) {
      try {
        if (Wire.integer(apdp.verifyCommandIntent(intent), "expires", 1, Long.MAX_VALUE)
            < System.currentTimeMillis()) {
          intent = null;
        }
      } catch (Exception e) {
        throw new IOException("Invalid transfer context");
      }
    }
    handoffs.put(handle, new Handoff(connection.getPlayer(), connection, child, session, intent));
    return Wire.encode(Map.of("version", 1, "parent", apdp.localNode(), "handle", handle));
  }

  /** Noise RPC claims the fresh handoff before local authentication completes. */
  public CompletableFuture<Void> accept(MinecraftConnection connection, UUID player, byte[] reply) {
    return CompletableFuture.runAsync(() -> {
      try {
        JsonObject object = Wire.parse(reply);
        Wire.fields(object, "version", "parent", "handle");
        Wire.integer(object, "version", 1, 1);
        UUID parent = UUID.fromString(Wire.string(object, "parent", 36));
        UUID handle = UUID.fromString(Wire.string(object, "handle", 36));
        if (!apdp.commandPeer(parent) || !parent.equals(apdp.commandParent())) {
          throw new IOException("ADCR parent unavailable");
        }
        JsonObject request = Wire.object(Map.of("operation", "CLAIM", "handle", handle,
            "player", player));
        JsonObject response = apdp.commandRequest(parent, request);
        Wire.fields(response, "route", "intent");
        CommandRoute route = Wire.GSON.fromJson(response.getAsJsonObject("route"), CommandRoute.class);
        if (!route.player().equals(player) || !route.nodes().getLast().equals(parent)
            || !route.nodes().getFirst().equals(apdp.head())) {
          throw new IOException("Invalid ADCR session proof");
        }
        ArrayList<UUID> nodes = new ArrayList<>(route.nodes());
        nodes.add(apdp.localNode());
        sessions.put(connection, new Session(new CommandRoute(route.session(), player,
            route.epoch(), nodes), parent, handle));
        if (!response.getAsJsonObject("intent").isEmpty()) {
          var intent = com.velocitypowered.proxy.apdp.identity.Signed.decode(response.getAsJsonObject("intent"));
          JsonObject authorization = apdp.verifyCommandIntent(intent);
          Wire.fields(authorization, "kind", "id", "session", "player", "destination", "expires");
          long expires = Wire.integer(authorization, "expires", 1, Long.MAX_VALUE);
          if (!Wire.string(authorization, "kind", 32).equals("ADCR_TRANSFER_V1")
              || !Wire.string(authorization, "player", 36).equals(player.toString())
              || !Wire.string(authorization, "session", 36).equals(route.session().toString())
              || expires < System.currentTimeMillis() || expires - System.currentTimeMillis() > 15000) {
            throw new IOException("Invalid initial transfer context");
          }
          initialTransfers.put(connection, intent);
        }
        connection.getChannel().closeFuture().addListener(ignored -> {
          sessions.remove(connection);
          initialTransfers.remove(connection);
        });
      } catch (Exception e) {
        throw new java.util.concurrent.CompletionException(new IOException("ADCR binding failed"));
      }
    }).orTimeout(12, TimeUnit.SECONDS);
  }

  private Session session(ConnectedPlayer player) {
    Session existing = sessions.get(player.getConnection());
    if (existing != null || !enabled() || !apdp.localNode().equals(apdp.head())) {
      return existing;
    }
    return sessions.computeIfAbsent(player.getConnection(), ignored -> new Session(
        new CommandRoute(UUID.randomUUID(), player.getUniqueId(), 1, java.util.List.of(apdp.localNode())),
        null, null));
  }

  /** Ancestors defer only after their actual active backend connection was claimed. */
  public boolean defer(ConnectedPlayer player) {
    return handoffs.values().stream().anyMatch(handoff -> handoff.player == player
        && handoff.claimed && player.getConnectedServer() == handoff.connection);
  }

  public boolean active(ConnectedPlayer player) {
    return session(player) != null;
  }

  /** Entry point for unsigned player commands. No automatic retry, even on timeout. */
  public CompletableFuture<Boolean> execute(ConnectedPlayer player, String command) {
    if (!active(player)) {
      return server.getCommandManager().executeImmediatelyAsync(player, command);
    }
    if (defer(player)) {
      return CompletableFuture.completedFuture(false);
    }
    return resolve(player, session(player), command, UUID.randomUUID(),
        System.currentTimeMillis() + 10000).thenApply(outcome -> {
          if (outcome == InvocationLedger.Outcome.DENIED) {
            player.sendMessage(Component.text("You do not have permission to execute this command."));
          } else if (outcome == InvocationLedger.Outcome.UNAVAILABLE
              || outcome == InvocationLedger.Outcome.INDETERMINATE) {
            player.sendMessage(Component.text("Distributed command unavailable; it was not retried."));
          }
          return outcome != InvocationLedger.Outcome.NOT_REGISTERED
              && outcome != InvocationLedger.Outcome.EXPLICIT_FORWARD;
        });
  }

  private CompletableFuture<InvocationLedger.Outcome> resolve(ConnectedPlayer player,
      Session session, String command, UUID invocation, long expires) {
    String label = command.split(" ", 2)[0];
    int colon = label.indexOf(':');
    String target = colon < 0 ? "" : label.substring(0, colon);
    String alias = colon < 0 ? label : label.substring(colon + 1);
    boolean here = target.isEmpty() || target.equals("local")
        || (target.equals("head") && apdp.localNode().equals(apdp.head()));
    if (!here && !target.equals("head")) {
      var graph = apdp.catalog().topology();
      if (graph.isPresent()) {
        var matches = graph.get().nodes().values().stream().filter(node ->
            node.type() != com.velocitypowered.proxy.apdp.model.Topology.Type.MINECRAFT_SERVER
                && node.name().equals(target)).toList();
        if (matches.size() != 1 || !session.route().nodes().contains(matches.getFirst().id())) {
          return CompletableFuture.completedFuture(InvocationLedger.Outcome.DENIED);
        }
        here = matches.getFirst().id().equals(apdp.localNode());
      } else {
        return CompletableFuture.completedFuture(InvocationLedger.Outcome.UNAVAILABLE);
      }
    }
    LocalCommandRegistry.Entry entry = server.getCommandManager().getLocalRegistry().snapshot()
        .aliases().get(alias.toLowerCase(java.util.Locale.ROOT));
    if (here && entry != null) {
      boolean builtin = server.getCommandManager().isDistributedBuiltin(alias, entry.registration());
      if (builtin && (target.isEmpty() || target.equals("head"))) {
        if (apdp.localNode().equals(apdp.head())) {
          return global(player, session, command, alias, entry.registration(), invocation, expires);
        }
        here = false;
      }
    }
    if (here && entry != null) {
      String actual = alias + command.substring(label.length());
      InvocationLedger.Request request = new InvocationLedger.Request(invocation,
          session.route().session(), player.getUniqueId(), session.route().epoch(),
          session.route().nodes().getLast(), apdp.localNode(), entry.registration(), actual, expires);
      return ledger.execute(request, () -> {
        return server.getCommandManager().executeOwned(player, actual, entry.registration());
      });
    }
    if (here && !target.isEmpty()) {
      return CompletableFuture.completedFuture(InvocationLedger.Outcome.DENIED);
    }
    if (session.parent() == null) {
      return CompletableFuture.completedFuture(target.isEmpty()
          ? InvocationLedger.Outcome.NOT_REGISTERED : InvocationLedger.Outcome.DENIED);
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        JsonObject response = apdp.commandRequest(session.parent(), Wire.object(Map.of(
            "operation", "EXECUTE", "handle", session.handle(), "session", session.route().session(),
            "epoch", session.route().epoch(), "player", player.getUniqueId(), "command", command,
            "invocation", invocation, "expires", expires)));
        return InvocationLedger.Outcome.valueOf(Wire.string(response, "outcome", 32));
      } catch (Exception e) {
        return InvocationLedger.Outcome.INDETERMINATE;
      }
    }).completeOnTimeout(InvocationLedger.Outcome.INDETERMINATE, 12, TimeUnit.SECONDS);
  }

  private JsonObject receive(UUID peer, JsonObject request) {
    try {
      String operation = Wire.string(request, "operation", 16);
      if (operation.equals("DESCRIBE") || operation.equals("SUGGEST")) {
        if (operation.equals("DESCRIBE")) {
          Wire.fields(request, "operation", "handle", "player", "session", "epoch");
        } else {
          Wire.fields(request, "operation", "handle", "player", "session", "epoch", "command");
        }
        UUID handle = UUID.fromString(Wire.string(request, "handle", 36));
        Handoff handoff = handoffs.get(handle);
        if (handoff == null || !handoff.claimed || !peer.equals(handoff.child)
            || handoff.player.getConnectedServer() != handoff.connection
            || !handoff.player.getUniqueId().toString().equals(Wire.string(request, "player", 36))
            || !handoff.session.route().session().toString().equals(Wire.string(request, "session", 36))
            || handoff.session.route().epoch() != Wire.integer(request, "epoch", 1, Long.MAX_VALUE)) {
          throw new IOException("Invalid command catalog session");
        }
        if (operation.equals("DESCRIBE")) {
          return description(handoff.player, handoff.session).get(10, TimeUnit.SECONDS);
        }
        return encodeSuggestions(suggest(handoff.player, handoff.session,
            Wire.string(request, "command", 32767)).get(10, TimeUnit.SECONDS));
      }
      if (operation.equals("TERMINAL")) {
        Wire.fields(request, "operation", "handle", "player");
        UUID handle = UUID.fromString(Wire.string(request, "handle", 36));
        UUID player = UUID.fromString(Wire.string(request, "player", 36));
        ConnectedPlayer actual = (ConnectedPlayer) server.getPlayer(player).orElseThrow();
        Session session = sessions.get(actual.getConnection());
        if (session == null || !peer.equals(session.parent()) || !handle.equals(session.handle())) {
          throw new IOException("Invalid terminal query context");
        }
        return Wire.object(Map.of("terminal", terminal(actual).get(8, TimeUnit.SECONDS)));
      }
      if (operation.equals("MOVE")) {
        Wire.fields(request, "operation", "handle", "intent");
        UUID handle = UUID.fromString(Wire.string(request, "handle", 36));
        var intent = com.velocitypowered.proxy.apdp.identity.Signed.decode(
            request.getAsJsonObject("intent"));
        JsonObject authorization = apdp.verifyCommandIntent(intent);
        Wire.fields(authorization, "kind", "id", "session", "player", "destination", "expires");
        if (!Wire.string(authorization, "kind", 32).equals("ADCR_TRANSFER_V1")) {
          throw new IOException("Invalid transfer purpose");
        }
        UUID player = UUID.fromString(Wire.string(authorization, "player", 36));
        ConnectedPlayer actual = (ConnectedPlayer) server.getPlayer(player).orElseThrow();
        Session session = sessions.get(actual.getConnection());
        if (session == null || !peer.equals(session.parent()) || !handle.equals(session.handle())
            || !session.route().session().toString().equals(
                Wire.string(authorization, "session", 36))) {
          throw new IOException("Transfer is not bound to this Minecraft session");
        }
        return Wire.object(Map.of("outcome", move(actual, session, intent).get(12, TimeUnit.SECONDS)));
      }
      UUID handle = UUID.fromString(Wire.string(request, "handle", 36));
      UUID player = UUID.fromString(Wire.string(request, "player", 36));
      Handoff handoff = handoffs.get(handle);
      if (handoff == null || !handoff.child.equals(peer)
          || !handoff.player.getUniqueId().equals(player)) {
        throw new IOException("Unknown connection handoff");
      }
      if (operation.equals("CLAIM")) {
        Wire.fields(request, "operation", "handle", "player");
        synchronized (handoff) {
          if (handoff.expires < System.currentTimeMillis() || handoff.claimed
              || !handoff.connection.ensureConnected().getChannel().isActive()) {
            throw new IOException("Expired or consumed handoff");
          }
          handoff.claimed = true;
        }
        JsonObject response = Wire.object(Map.of("route", handoff.session.route()));
        response.add("intent", handoff.intent == null ? new JsonObject()
            : Wire.object(handoff.intent));
        return response;
      }
      Wire.fields(request, "operation", "handle", "session", "epoch", "player", "command",
          "invocation", "expires");
      if (!operation.equals("EXECUTE") || !handoff.claimed
          || !Wire.string(request, "session", 36).equals(handoff.session.route().session().toString())
          || Wire.integer(request, "epoch", 1, Long.MAX_VALUE) != handoff.session.route().epoch()) {
        throw new IOException("Stale or invalid command context");
      }
      long expires = Wire.integer(request, "expires", 1, Long.MAX_VALUE);
      if (expires < System.currentTimeMillis() || expires - System.currentTimeMillis() > 15000) {
        throw new IOException("Expired invocation");
      }
      String command = Wire.string(request, "command", 32767);
      UUID invocation = UUID.fromString(Wire.string(request, "invocation", 36));
      CompletableFuture<InvocationLedger.Outcome> result = onLoop(handoff.player, () -> {
        if (handoff.player.getConnectedServer() != handoff.connection
            || !handoff.player.isActive() || session(handoff.player) != handoff.session) {
          return CompletableFuture.completedFuture(InvocationLedger.Outcome.UNAVAILABLE);
        }
        return resolve(handoff.player, handoff.session, command, invocation, expires);
      });
      return Wire.object(Map.of("outcome", result.get(12, TimeUnit.SECONDS)));
    } catch (Exception e) {
      return Wire.object(Map.of("outcome", InvocationLedger.Outcome.UNAVAILABLE));
    }
  }

  private CompletableFuture<InvocationLedger.Outcome> global(ConnectedPlayer source, Session session,
      String command, String alias, UUID registration, UUID invocation, long expires) {
    InvocationLedger.Request request = new InvocationLedger.Request(invocation,
        session.route().session(), source.getUniqueId(), session.route().epoch(), apdp.head(),
        apdp.head(), registration, command, expires);
    return ledger.execute(request, () -> CompletableFuture.supplyAsync(() -> {
      try {
        var permission = source.getPermissionValue("velocity.command." + alias);
        if ((alias.equals("send") && permission != com.velocitypowered.api.permission.Tristate.TRUE)
            || (alias.equals("server") && permission == com.velocitypowered.api.permission.Tristate.FALSE)) {
          return InvocationLedger.Outcome.DENIED;
        }
        if (!apdp.catalog().current()) {
          return InvocationLedger.Outcome.UNAVAILABLE;
        }
        String[] arguments = command.trim().split(" +");
        var graph = apdp.catalog().topology().orElseThrow();
        if (alias.equals("server") && arguments.length == 1) {
          source.sendMessage(Component.text("Public destinations: " + graph.nodes().values().stream()
              .filter(node -> !node.id().equals(apdp.head()))
              .map(node -> graph.position(node.id()).qualifiedName()).sorted().limit(50).toList()));
          return InvocationLedger.Outcome.EXECUTED;
        }
        if ((alias.equals("server") && arguments.length != 2)
            || (alias.equals("send") && arguments.length != 3)) {
          source.sendMessage(Component.text(alias.equals("server")
              ? "Usage: /server <public destination>" : "Usage: /send <player|all|current> <public destination>"));
          return InvocationLedger.Outcome.EXECUTED;
        }
        String destination = arguments[arguments.length - 1];
        var candidates = graph.nodes().values().stream().filter(node -> !node.id().equals(apdp.head())
            && (graph.position(node.id()).qualifiedName().equals(destination)
                || node.name().equals(destination) || node.id().toString().equals(destination))).toList();
        if (candidates.size() != 1) {
          source.sendMessage(Component.text("Unknown, private or ambiguous destination."));
          return InvocationLedger.Outcome.DENIED;
        }
        UUID target = candidates.getFirst().id();
        java.util.List<ConnectedPlayer> players;
        if (alias.equals("server")) {
          players = java.util.List.of(source);
        } else if (arguments[1].equals("all")) {
          players = server.getAllPlayers().stream().map(player -> (ConnectedPlayer) player).toList();
        } else if (arguments[1].equals("current")) {
          // Head's active branch is deliberately not treated as the terminal server.
          UUID terminal = terminal(source).get(5, TimeUnit.SECONDS);
          ArrayList<ConnectedPlayer> matching = new ArrayList<>();
          for (var player : server.getAllPlayers()) {
            ConnectedPlayer actual = (ConnectedPlayer) player;
            if (terminal.equals(terminal(actual).get(5, TimeUnit.SECONDS))) {
              matching.add(actual);
            }
          }
          players = java.util.List.copyOf(matching);
        } else {
          players = server.getPlayer(arguments[1]).map(player -> java.util.List.of((ConnectedPlayer) player))
              .orElse(java.util.List.of());
        }
        if (players.isEmpty()) {
          source.sendMessage(Component.text("No matching player sessions."));
          return InvocationLedger.Outcome.DENIED;
        }
        ArrayList<CompletableFuture<InvocationLedger.Outcome>> results = new ArrayList<>();
        for (ConnectedPlayer player : players) {
          Session current = session(player);
          if (current == null) {
            throw new IOException("Player has no authenticated distributed session");
          }
          var intent = apdp.signCommandIntent(Wire.object(Map.of("kind", "ADCR_TRANSFER_V1",
              "id", UUID.randomUUID(), "session", current.route().session(),
              "player", player.getUniqueId(), "destination", target, "expires", expires)));
          results.add(move(player, current, intent));
        }
        CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).get(12, TimeUnit.SECONDS);
        long completed = results.stream().filter(result ->
            result.join() == InvocationLedger.Outcome.EXECUTED).count();
        source.sendMessage(Component.text("Transfer confirmed at destination: " + completed + "/"
            + results.size() + ". Failed/uncertain transfers were not retried or rolled back."));
        return completed == results.size() ? InvocationLedger.Outcome.EXECUTED
            : InvocationLedger.Outcome.INDETERMINATE;
      } catch (Exception e) {
        return InvocationLedger.Outcome.INDETERMINATE;
      }
    }, workers));
  }

  private CompletableFuture<UUID> terminal(ConnectedPlayer player) {
    Handoff child = handoffs.values().stream().filter(value -> value.player == player
        && value.claimed && player.getConnectedServer() == value.connection).findFirst().orElse(null);
    if (child == null) {
      return CompletableFuture.completedFuture(com.velocitypowered.proxy.apdp.model.Publication.leafId(
          apdp.localNode(), player.getCurrentServer().orElseThrow().getServerInfo().getName()));
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        var response = apdp.commandRequest(child.child, Wire.object(Map.of("operation", "TERMINAL",
            "handle", handoffHandle(child), "player", player.getUniqueId())));
        return UUID.fromString(Wire.string(response, "terminal", 36));
      } catch (Exception e) {
        throw new java.util.concurrent.CompletionException(e);
      }
    }, workers);
  }

  private UUID handoffHandle(Handoff handoff) {
    return handoffs.entrySet().stream().filter(entry -> entry.getValue() == handoff)
        .map(Map.Entry::getKey).findFirst().orElseThrow();
  }

  private CompletableFuture<InvocationLedger.Outcome> move(ConnectedPlayer player, Session session,
      com.velocitypowered.proxy.apdp.identity.Signed intent) throws Exception {
    JsonObject authorization = apdp.verifyCommandIntent(intent);
    UUID id = UUID.fromString(Wire.string(authorization, "id", 36));
    UUID target = UUID.fromString(Wire.string(authorization, "destination", 36));
    long expires = Wire.integer(authorization, "expires", 1, Long.MAX_VALUE);
    InvocationLedger.Request request = new InvocationLedger.Request(id, session.route().session(),
        player.getUniqueId(), session.route().epoch(), apdp.head(), apdp.localNode(), id,
        "transfer " + target, expires);
    return transfers.execute(request, () -> CompletableFuture.supplyAsync(() -> {
      try {
        if (sessions.get(player.getConnection()) != session || !player.isActive()
            || !apdp.catalog().current()) {
          return InvocationLedger.Outcome.UNAVAILABLE;
        }
        if (target.equals(apdp.localNode())) {
          return InvocationLedger.Outcome.EXECUTED;
        }
        var graph = apdp.catalog().topology().orElseThrow();
        UUID hop = apdp.catalog().nextHop(apdp.localNode(), target).orElseThrow();
        if (graph.parents().get(apdp.localNode()) != null
            && graph.parents().get(apdp.localNode()).equals(hop)) {
          return InvocationLedger.Outcome.DENIED;
        }
        var node = graph.nodes().get(hop);
        var destination = server.getServer(node.name()).orElseThrow();
        boolean already = player.getCurrentServer().map(value -> value.getServer() == destination).orElse(false);
        if (!already && intent.equals(initialTransfers.get(player.getConnection()))) {
          return InvocationLedger.Outcome.DENIED;
        }
        outgoingTransfers.put(player.getConnection(), intent);
        boolean connected;
        try {
          connected = onLoop(player, () -> {
            if (player.getCurrentServer().map(value -> value.getServer() == destination).orElse(false)) {
              return CompletableFuture.completedFuture(true);
            }
            return player.createConnectionRequest(destination).connect().thenApply(result ->
                result.isSuccessful() && player.getCurrentServer().map(value ->
                    value.getServer() == destination).orElse(false));
          }).get(8, TimeUnit.SECONDS);
        } finally {
          outgoingTransfers.remove(player.getConnection(), intent);
        }
        if (!connected) {
          return InvocationLedger.Outcome.DENIED;
        }
        if (node.type() == com.velocitypowered.proxy.apdp.model.Topology.Type.MINECRAFT_SERVER) {
          return hop.equals(target) ? InvocationLedger.Outcome.EXECUTED : InvocationLedger.Outcome.DENIED;
        }
        Handoff child = handoffs.values().stream().filter(value -> value.player == player
            && value.claimed && value.child.equals(hop)
            && value.connection == player.getConnectedServer()).findFirst().orElseThrow();
        var response = apdp.commandRequest(hop, Wire.object(Map.of("operation", "MOVE",
            "handle", handoffHandle(child), "intent", intent)));
        return InvocationLedger.Outcome.valueOf(Wire.string(response, "outcome", 32));
      } catch (Exception e) {
        return InvocationLedger.Outcome.INDETERMINATE;
      }
    }, workers));
  }

  private static <T> CompletableFuture<T> onLoop(ConnectedPlayer player,
      Supplier<CompletableFuture<T>> action) {
    CompletableFuture<T> result = new CompletableFuture<>();
    player.getConnection().eventLoop().execute(() -> {
      try {
        action.get().whenComplete((value, error) -> {
          if (error == null) {
            result.complete(value);
          } else {
            result.completeExceptionally(error);
          }
        });
      } catch (RuntimeException e) {
        result.completeExceptionally(e);
      }
    });
    return result;
  }

  /** Teardown invalidates exact connection capabilities, even if a UUID logs in again. */
  public void disconnect(ConnectedPlayer player) {
    sessions.remove(player.getConnection());
    initialTransfers.remove(player.getConnection());
    outgoingTransfers.remove(player.getConnection());
    remoteCatalogs.remove(player.getConnection());
    backendTrees.remove(player.getConnection());
    treeFingerprints.remove(player.getConnection());
    advertisedLabels.remove(player.getConnection());
    handoffs.entrySet().removeIf(entry -> entry.getValue().player == player);
  }

  public void backendClosed(VelocityServerConnection connection) {
    handoffs.entrySet().removeIf(entry -> entry.getValue().connection == connection);
  }

  /** Supplies the authorized first hop before normal plugin initial-server events are fired. */
  public java.util.Optional<com.velocitypowered.api.proxy.server.RegisteredServer> initialDestination(
      ConnectedPlayer player) {
    var intent = initialTransfers.get(player.getConnection());
    if (intent == null) {
      return java.util.Optional.empty();
    }
    try {
      var value = apdp.verifyCommandIntent(intent);
      if (Wire.integer(value, "expires", 1, Long.MAX_VALUE) < System.currentTimeMillis()) {
        throw new IOException("Expired initial transfer");
      }
      UUID target = UUID.fromString(Wire.string(value, "destination", 36));
      if (target.equals(apdp.localNode())) {
        return java.util.Optional.empty();
      }
      var graph = apdp.catalog().topology().orElseThrow();
      UUID hop = apdp.catalog().nextHop(apdp.localNode(), target).orElseThrow();
      if (hop.equals(apdp.commandParent())) {
        throw new IOException("Transfer cannot travel upstream during login");
      }
      return java.util.Optional.of(server.getServer(graph.nodes().get(hop).name()).orElseThrow());
    } catch (Exception e) {
      throw new IllegalStateException("Authenticated initial transfer is unavailable");
    }
  }

  /** Stops background refresh and abandons session-bound requests without retrying them. */
  public void close() {
    refreshTimer.shutdownNow();
    workers.shutdownNow();
    sessions.clear();
    handoffs.clear();
    advertisedLabels.clear();
  }

  private JsonObject localDescription(ConnectedPlayer player) {
    var root = new com.mojang.brigadier.tree.RootCommandNode<com.velocitypowered.api.command.CommandSource>();
    server.getCommandManager().getInjector().inject(root, player);
    var snapshot = server.getCommandManager().getLocalRegistry().snapshot();
    JsonObject tree = new JsonObject();
    JsonObject aliases = new JsonObject();
    for (var entry : snapshot.aliases().entrySet()) {
      aliases.add(entry.getKey(), Wire.object(entry.getValue()));
      var node = root.getChild(entry.getKey());
      if (node != null) {
        tree.add(entry.getKey(), CommandDescription.encode(node, 0));
      }
    }
    return Wire.object(Map.of("node", apdp.localNode(), "revision", snapshot.revision(),
        "aliases", aliases, "tree", tree));
  }

  private CompletableFuture<JsonObject> description(ConnectedPlayer player, Session session) {
    return onLoop(player, () -> CompletableFuture.completedFuture(localDescription(player)))
        .thenApplyAsync(local -> {
          JsonObject result = Wire.object(Map.of("aliases", new JsonObject(), "tree", new JsonObject(),
              "owners", new com.google.gson.JsonArray()));
          if (session.parent() != null) {
            try {
              result = apdp.commandRequest(session.parent(), catalogRequest("DESCRIBE", session));
              CommandDescription.tree(result.getAsJsonObject("tree"));
            } catch (Exception ignored) {
              // Unavailable ancestors are not advertised; local commands remain usable.
            }
          }
          JsonObject merged = result.getAsJsonObject("tree");
          for (String alias : local.getAsJsonObject("aliases").keySet()) {
            if (headOwnedDefault(alias)) {
              continue;
            }
            merged.remove(alias);
            result.getAsJsonObject("aliases").add(alias, local.getAsJsonObject("aliases").get(alias));
          }
          for (var entry : local.getAsJsonObject("tree").entrySet()) {
            if (!headOwnedDefault(entry.getKey())) {
              merged.add(entry.getKey(), entry.getValue());
            }
          }
          result.getAsJsonArray("owners").add(local);
          if (result.getAsJsonArray("owners").size() > 257) {
            throw new IllegalStateException("Command route too deep");
          }
          return result;
        }, workers);
  }

  private boolean headOwnedDefault(String alias) {
    var entry = server.getCommandManager().getLocalRegistry().snapshot().aliases().get(alias);
    return !apdp.localNode().equals(apdp.head()) && entry != null
        && server.getCommandManager().isDistributedBuiltin(alias, entry.registration());
  }

  private JsonObject catalogRequest(String operation, Session session) {
    return Wire.object(Map.of("operation", operation, "handle", session.handle(),
        "player", session.route().player(), "session", session.route().session(),
        "epoch", session.route().epoch()));
  }

  /** Builds the effective permission-filtered tree at the deepest active proxy. */
  public CompletableFuture<com.velocitypowered.proxy.protocol.packet.AvailableCommandsPacket> prepareCommands(
      ConnectedPlayer player, com.velocitypowered.proxy.protocol.packet.AvailableCommandsPacket packet) {
    Session session = session(player);
    if (session == null || defer(player)) {
      return CompletableFuture.completedFuture(packet);
    }
    var base = new com.mojang.brigadier.tree.RootCommandNode<com.velocitypowered.api.command.CommandSource>();
    packet.getRootNode().getChildren().forEach(base::addChild);
    backendTrees.put(player.getConnection(), base);
    return buildCommands(player, session, base);
  }

  private CompletableFuture<com.velocitypowered.proxy.protocol.packet.AvailableCommandsPacket> buildCommands(
      ConnectedPlayer player, Session session, com.mojang.brigadier.tree.RootCommandNode<
          com.velocitypowered.api.command.CommandSource> base) {
    return description(player, session).thenApply(value -> {
      try {
        var root = new com.mojang.brigadier.tree.RootCommandNode<com.velocitypowered.api.command.CommandSource>();
        base.getChildren().forEach(root::addChild);
        for (String alias : value.getAsJsonObject("aliases").keySet()) {
          root.removeChildByName(alias);
        }
        CommandDescription.tree(value.getAsJsonObject("tree")).getChildren().forEach(root::addChild);
        var graph = apdp.catalog().topology();
        for (var ownerValue : value.getAsJsonArray("owners")) {
          JsonObject owner = ownerValue.getAsJsonObject();
          UUID nodeId = UUID.fromString(Wire.string(owner, "node", 36));
          ArrayList<String> prefixes = new ArrayList<>();
          if (nodeId.equals(apdp.localNode())) {
            prefixes.add("local");
          }
          if (graph.isPresent() && apdp.catalog().current() && graph.get().nodes().containsKey(nodeId)) {
            var node = graph.get().nodes().get(nodeId);
            if (!node.name().equals("head") && !node.name().equals("local")
                && graph.get().nodes().values().stream().filter(other ->
                    other.type() != com.velocitypowered.proxy.apdp.model.Topology.Type.MINECRAFT_SERVER
                        && other.name().equals(node.name())).count() == 1) {
              prefixes.add(node.name());
            }
            if (nodeId.equals(apdp.head())) {
              prefixes.add("head");
            }
          }
          for (String prefix : prefixes) {
            for (var entry : owner.getAsJsonObject("tree").entrySet()) {
              JsonObject description = entry.getValue().getAsJsonObject().deepCopy();
              description.addProperty("name", prefix + ":" + entry.getKey());
              root.addChild(CommandDescription.decode(description, 0, new int[]{0}));
            }
          }
        }
        remoteCatalogs.put(player.getConnection(), value.deepCopy());
        advertisedLabels.put(player.getConnection(), root.getChildren().stream()
            .map(com.mojang.brigadier.tree.CommandNode::getName).collect(java.util.stream.Collectors.toUnmodifiableSet()));
        return new com.velocitypowered.proxy.protocol.packet.AvailableCommandsPacket(root);
      } catch (IOException e) {
        throw new java.util.concurrent.CompletionException(e);
      }
    });
  }

  private void refreshTrees() {
    for (var player : server.getAllPlayers()) {
      ConnectedPlayer actual = (ConnectedPlayer) player;
      MinecraftConnection connection = actual.getConnection();
      var base = backendTrees.get(connection);
      Session session = session(actual);
      if (base == null || session == null || defer(actual) || refreshing.size() >= 32
          || !refreshing.add(connection)) {
        continue;
      }
      buildCommands(actual, session, base).thenAcceptAsync(packet -> {
        String fingerprint = Wire.GSON.toJson(CommandDescription.encode(packet.getRootNode(), 0));
        if (!java.util.Objects.equals(treeFingerprints.get(connection), fingerprint)
            && connection.getState() == com.velocitypowered.proxy.protocol.StateRegistry.PLAY
            && !defer(actual)) {
          server.getEventManager().fire(new com.velocitypowered.api.event.command.PlayerAvailableCommandsEvent(
              actual, packet.getRootNode())).thenRunAsync(() -> {
                if (connection.getState() == com.velocitypowered.proxy.protocol.StateRegistry.PLAY
                    && !defer(actual)) {
                  connection.write(packet);
                  treeFingerprints.put(connection, fingerprint);
                }
              }, connection.eventLoop());
        }
      }, connection.eventLoop()).whenComplete((ignored, failure) -> refreshing.remove(connection));
    }
  }

  /** Determines whether a completion belongs to the active route's command catalog. */
  public boolean handlesSuggestions(ConnectedPlayer player, String command) {
    if (session(player) == null || defer(player)) {
      return false;
    }
    String label = command.split(" ", 2)[0];
    if (!command.contains(" ") && advertisedLabels.getOrDefault(player.getConnection(), java.util.Set.of())
        .stream().anyMatch(value -> value.startsWith(label))) {
      return true;
    }
    JsonObject catalog = remoteCatalogs.get(player.getConnection());
    return label.contains(":") || (catalog != null && catalog.getAsJsonObject("aliases").has(label))
        || server.getCommandManager().getLocalRegistry().snapshot().aliases().containsKey(label);
  }

  /** Resolves a completion using the owner's real Player and normal suggestion provider. */
  public CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestions(
      ConnectedPlayer player, String command) {
    if (!command.contains(" ") && active(player) && !defer(player)) {
      var builder = new com.mojang.brigadier.suggestion.SuggestionsBuilder(command, 0);
      advertisedLabels.getOrDefault(player.getConnection(), java.util.Set.of()).stream()
          .filter(label -> label.startsWith(command)).sorted().limit(256).forEach(builder::suggest);
      return builder.buildFuture();
    }
    Session session = session(player);
    return session == null ? server.getCommandManager().offerBrigadierSuggestions(player, command)
        : suggest(player, session, command);
  }

  private CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(
      ConnectedPlayer player, Session session, String command) {
    String label = command.split(" ", 2)[0];
    String target = label.contains(":") ? label.substring(0, label.indexOf(':')) : "";
    String alias = label.substring(label.indexOf(':') + 1);
    String local = command.substring(target.isEmpty() ? 0 : target.length() + 1);
    boolean here = target.isEmpty() || target.equals("local")
        || (target.equals("head") && apdp.localNode().equals(apdp.head()));
    if (!here && !target.equals("head")) {
      var graph = apdp.catalog().topology();
      if (graph.isEmpty() || !apdp.catalog().current()) {
        return com.mojang.brigadier.suggestion.Suggestions.empty();
      }
      var matches = graph.get().nodes().values().stream().filter(node ->
          node.type() != com.velocitypowered.proxy.apdp.model.Topology.Type.MINECRAFT_SERVER
              && node.name().equals(target)).toList();
      if (matches.size() != 1 || !session.route().nodes().contains(matches.getFirst().id())) {
        return com.mojang.brigadier.suggestion.Suggestions.empty();
      }
      here = matches.getFirst().id().equals(apdp.localNode());
    }
    var entry = server.getCommandManager().getLocalRegistry().snapshot().aliases().get(alias);
    if (here && entry != null && !(target.isEmpty() && !apdp.localNode().equals(apdp.head())
        && server.getCommandManager().isDistributedBuiltin(alias, entry.registration()))) {
      if (apdp.localNode().equals(apdp.head()) && !target.equals("local")
          && server.getCommandManager().isDistributedBuiltin(alias, entry.registration())) {
        return globalSuggestions(player, command, alias);
      }
      return server.getCommandManager().offerBrigadierSuggestions(player, local).thenApply(suggestions -> {
        int offset = command.length() - local.length();
        if (offset == 0) {
          return suggestions;
        }
        var range = com.mojang.brigadier.context.StringRange.between(
            suggestions.getRange().getStart() + offset, suggestions.getRange().getEnd() + offset);
        return new com.mojang.brigadier.suggestion.Suggestions(range, suggestions.getList().stream()
            .map(value -> new com.mojang.brigadier.suggestion.Suggestion(range, value.getText(), value.getTooltip())).toList());
      });
    }
    if (target.equals("local") || session.parent() == null) {
      return com.mojang.brigadier.suggestion.Suggestions.empty();
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        JsonObject request = catalogRequest("SUGGEST", session);
        request.addProperty("command", command);
        return decodeSuggestions(apdp.commandRequest(session.parent(), request), command.length());
      } catch (Exception e) {
        return com.mojang.brigadier.suggestion.Suggestions.empty().join();
      }
    }, workers);
  }

  private CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> globalSuggestions(
      ConnectedPlayer player, String command, String alias) {
    var builder = new com.mojang.brigadier.suggestion.SuggestionsBuilder(
        command, command.lastIndexOf(' ') + 1);
    var permission = player.getPermissionValue("velocity.command." + alias);
    if ((alias.equals("send") && permission != com.velocitypowered.api.permission.Tristate.TRUE)
        || (alias.equals("server") && permission == com.velocitypowered.api.permission.Tristate.FALSE)
        || !apdp.catalog().current()) {
      return builder.buildFuture();
    }
    String[] parts = command.split(" +", -1);
    java.util.stream.Stream<String> candidates = java.util.stream.Stream.empty();
    if ((alias.equals("server") && parts.length == 2)
        || (alias.equals("send") && parts.length == 3)) {
      var graph = apdp.catalog().topology().orElseThrow();
      candidates = graph.nodes().values().stream().filter(node -> !node.id().equals(apdp.head()))
          .map(node -> graph.position(node.id()).qualifiedName());
    } else if (alias.equals("send") && parts.length == 2) {
      candidates = java.util.stream.Stream.concat(java.util.stream.Stream.of("all", "current"),
          server.getAllPlayers().stream().map(com.velocitypowered.api.proxy.Player::getUsername));
    }
    String prefix = builder.getRemainingLowerCase();
    candidates.filter(value -> value.toLowerCase(java.util.Locale.ROOT).startsWith(prefix))
        .distinct().sorted().limit(256).forEach(builder::suggest);
    return builder.buildFuture();
  }

  private JsonObject encodeSuggestions(com.mojang.brigadier.suggestion.Suggestions suggestions) {
    return Wire.object(Map.of("start", suggestions.getRange().getStart(), "end", suggestions.getRange().getEnd(),
        "offers", suggestions.getList().stream().limit(256).map(value -> value.getText()).toList()));
  }

  private com.mojang.brigadier.suggestion.Suggestions decodeSuggestions(JsonObject value, int length)
      throws IOException {
    Wire.fields(value, "start", "end", "offers");
    int start = (int) Wire.integer(value, "start", 0, length);
    int end = (int) Wire.integer(value, "end", start, length);
    var range = com.mojang.brigadier.context.StringRange.between(start, end);
    var offers = new ArrayList<com.mojang.brigadier.suggestion.Suggestion>();
    if (value.getAsJsonArray("offers").size() > 256) {
      throw new IOException("Too many suggestions");
    }
    for (var item : value.getAsJsonArray("offers")) {
      if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().length() > 32767) {
        throw new IOException("Invalid suggestion");
      }
      offers.add(new com.mojang.brigadier.suggestion.Suggestion(range, item.getAsString()));
    }
    return new com.mojang.brigadier.suggestion.Suggestions(range, offers);
  }
}
