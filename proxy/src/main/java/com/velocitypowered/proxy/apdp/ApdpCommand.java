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

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.apdp.config.AtomicFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/** Administration intentionally separated from player-routing commands. */
public final class ApdpCommand implements SimpleCommand {
  private final ApdpService service;

  public ApdpCommand(ApdpService service) {
    this.service = service;
  }

  public static String redact(String command) {
    return command.stripLeading().toLowerCase(Locale.ROOT).matches("/?apdp\\s+join\\s+.*")
        ? "apdp join <redacted>" : command;
  }

  @Override
  public boolean hasPermission(Invocation invocation) {
    String operation = invocation.arguments().length == 0 ? "status" : invocation.arguments()[0];
    return invocation.source().hasPermission("acceleration.apdp." + operation.toLowerCase(Locale.ROOT));
  }

  @Override
  public void execute(Invocation invocation) {
    CommandSource source = invocation.source();
    String[] args = invocation.arguments();
    if (!hasPermission(invocation)) {
      return;
    }
    try {
      String operation = args.length == 0 ? "status" : args[0];
      switch (operation) {
        case "status":
          reply(source, service.status());
          break;
        case "init":
          service.init(args[1]);
          reply(source, "APDP head initialized. " + service.status());
          break;
        case "join":
          service.join(args[1]);
          reply(source, "APDP enrollment complete. " + service.status());
          break;
        case "join-file":
          service.join(new String(AtomicFile.read(Path.of(args[1]), 32768), StandardCharsets.UTF_8).strip());
          reply(source, "APDP enrollment complete; remove the consumed input file.");
          break;
        case "invite":
        case "invite-file":
          if (args.length > 1 && args[1].equals("list")) {
            service.invitationList().forEach(line -> reply(source, line));
          } else if (args.length > 2 && args[1].equals("revoke")) {
            service.revokeInvite(UUID.fromString(args[2]));
            reply(source, "Invitation revoked.");
          } else {
            boolean file = operation.equals("invite-file");
            if (!file && !(source instanceof Player)) {
              reply(source, "Use apdp invite-file <private-output-file> [seconds] [delegate] "
                  + "to avoid logging a bearer token to the console.");
              return;
            }
            int start = file ? 2 : 1;
            int seconds = args.length > start ? Integer.parseInt(args[start]) : 600;
            boolean delegate = Arrays.asList(args).contains("delegate");
            String token = service.invite(seconds, delegate);
            if (file) {
              AtomicFile.write(Path.of(args[1]), token.getBytes(StandardCharsets.UTF_8), true);
              reply(source, "Invitation written to owner-only handoff file (not logged).");
            } else {
              reply(source, token);
            }
          }
          break;
        case "parent":
          if (args.length > 1 && args[1].equals("set")) {
            service.parentAddress(args[2]);
            service.reconnect();
          } else if (args.length > 1 && args[1].equals("reconnect")) {
            service.reconnect();
          }
          reply(source, service.parentInfo());
          break;
        case "node":
          UUID id = UUID.fromString(args[2]);
          if (args[1].equals("revoke")) {
            service.revokeNode(id);
          } else if (args[1].equals("permissions")) {
            if (!args[3].equals("true") && !args[3].equals("false")) {
              throw new IllegalArgumentException();
            }
            service.setInvite(id, Boolean.parseBoolean(args[3]));
          } else {
            throw new IllegalArgumentException();
          }
          reply(source, "Membership policy updated.");
          break;
        case "peers":
          service.peers().forEach(line -> reply(source, line));
          break;
        case "topology":
        case "routes":
          service.catalog().topology().ifPresentOrElse(tree -> tree.nodes().values().stream()
              .sorted(java.util.Comparator.comparing(node -> tree.position(node.id()).qualifiedName()))
              .forEach(node -> reply(source, tree.position(node.id()).qualifiedName() + " "
                  + node.type() + " depth=" + tree.position(node.id()).depth() + " weight="
                  + tree.position(node.id()).weight() + (operation.equals("routes")
                  ? " nextHop=" + tree.nextHop(service.localNode(), node.id()).orElse(null) : ""))),
              () -> reply(source, "No authorized head snapshot available."));
          break;
        case "resync":
          service.resync();
          reply(source, "APDP resynchronization requested.");
          break;
        default:
          reply(source, "apdp <init|invite|invite-file|join|join-file|parent|node|status|peers|topology|routes|resync>");
      }
    } catch (Exception e) {
      // No exception message: parsers or plugins may embed command input in it.
      reply(source, "APDP operation rejected (" + e.getClass().getSimpleName()
          + "). Check syntax, authorization, head connectivity and private configuration.");
    }
  }

  private static void reply(CommandSource source, String text) {
    source.sendMessage(Component.text(text));
  }

  @Override
  public List<String> suggest(Invocation invocation) {
    return List.of();
  }
}
