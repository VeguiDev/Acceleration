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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.proxy.apdp.protocol.Wire;
import java.io.IOException;

/** Data-only Brigadier subset. Redirects and unsupported arguments degrade to server-parsed text. */
public final class CommandDescription {
  private CommandDescription() { }

  /** Describes filtered nodes without serializing executable code or requirements. */
  public static JsonObject encode(CommandNode<CommandSource> node, int depth) {
    JsonObject result = new JsonObject();
    result.addProperty("name", node.getName());
    result.addProperty("executable", node.getCommand() != null);
    String type = "literal";
    if (node instanceof ArgumentCommandNode<CommandSource, ?> argument) {
      if (argument.getType() instanceof BoolArgumentType) {
        type = "bool";
      } else if (argument.getType() instanceof IntegerArgumentType value) {
        type = "int";
        result.addProperty("min", value.getMinimum());
        result.addProperty("max", value.getMaximum());
      } else if (argument.getType() instanceof LongArgumentType value) {
        type = "long";
        result.addProperty("min", value.getMinimum());
        result.addProperty("max", value.getMaximum());
      } else if (argument.getType() instanceof StringArgumentType value) {
        type = "string";
        result.addProperty("mode", value.getType().name());
      } else {
        type = "fallback";
      }
    }
    result.addProperty("type", type);
    JsonArray children = new JsonArray();
    if (depth >= 24 || node.getRedirect() != null) {
      result.addProperty("executable", true);
      JsonObject fallback = new JsonObject();
      fallback.addProperty("name", "arguments");
      fallback.addProperty("type", "fallback");
      fallback.addProperty("executable", true);
      fallback.add("children", new JsonArray());
      children.add(fallback);
    } else if (!type.equals("fallback")) {
      for (CommandNode<CommandSource> child : node.getChildren()) {
        children.add(encode(child, depth + 1));
      }
    }
    result.add("children", children);
    return result;
  }

  /** Reconstructs an inert, bounded client tree using known vanilla argument types. */
  public static CommandNode<CommandSource> decode(JsonObject value, int depth, int[] count)
      throws IOException {
    if (depth > 26 || ++count[0] > 8192) {
      throw new IOException("Command graph bounds exceeded");
    }
    String name = Wire.string(value, "name", 128);
    if (name.isEmpty() || name.chars().anyMatch(Character::isWhitespace)) {
      throw new IOException("Invalid command node name");
    }
    String type = Wire.string(value, "type", 16);
    if (type.equals("int") || type.equals("long")) {
      Wire.fields(value, "name", "type", "executable", "children", "min", "max");
      if (Wire.integer(value, "min", Long.MIN_VALUE, Long.MAX_VALUE)
          > Wire.integer(value, "max", Long.MIN_VALUE, Long.MAX_VALUE)) {
        throw new IOException("Inverted argument bounds");
      }
    } else if (type.equals("string")) {
      Wire.fields(value, "name", "type", "executable", "children", "mode");
    } else {
      Wire.fields(value, "name", "type", "executable", "children");
    }
    if (!value.get("children").isJsonArray()) {
      throw new IOException("Invalid command children");
    }
    ArgumentBuilder<CommandSource, ?> builder;
    switch (type) {
      case "literal":
        builder = LiteralArgumentBuilder.literal(name);
        break;
      case "bool":
        builder = RequiredArgumentBuilder.argument(name, BoolArgumentType.bool());
        break;
      case "int":
        builder = RequiredArgumentBuilder.argument(name, IntegerArgumentType.integer(
            (int) Wire.integer(value, "min", Integer.MIN_VALUE, Integer.MAX_VALUE),
            (int) Wire.integer(value, "max", Integer.MIN_VALUE, Integer.MAX_VALUE)));
        break;
      case "long":
        builder = RequiredArgumentBuilder.argument(name, LongArgumentType.longArg(
            Wire.integer(value, "min", Long.MIN_VALUE, Long.MAX_VALUE),
            Wire.integer(value, "max", Long.MIN_VALUE, Long.MAX_VALUE)));
        break;
      case "string":
        String mode = Wire.string(value, "mode", 32);
        builder = RequiredArgumentBuilder.argument(name, switch (mode) {
          case "SINGLE_WORD" -> StringArgumentType.word();
          case "QUOTABLE_PHRASE" -> StringArgumentType.string();
          case "GREEDY_PHRASE" -> StringArgumentType.greedyString();
          default -> throw new IOException("Unsupported string mode");
        });
        break;
      case "fallback":
        builder = RequiredArgumentBuilder.argument(name, StringArgumentType.greedyString());
        break;
      default:
        throw new IOException("Unsupported command node");
    }
    if (builder instanceof RequiredArgumentBuilder<?, ?> argument) {
      argument.suggests((context, suggestions) -> suggestions.buildFuture());
    }
    if (Wire.bool(value, "executable")) {
      builder.executes(context -> 0);
    }
    java.util.Set<String> names = new java.util.HashSet<>();
    for (var child : value.getAsJsonArray("children")) {
      if (!child.isJsonObject()
          || !names.add(Wire.string(child.getAsJsonObject(), "name", 128))) {
        throw new IOException("Invalid or duplicate command child");
      }
      builder.then(decode(child.getAsJsonObject(), depth + 1, count));
    }
    return builder.build();
  }

  /** Reconstructs independent roots and validates their advertised aliases. */
  public static RootCommandNode<CommandSource> tree(JsonObject nodes) throws IOException {
    RootCommandNode<CommandSource> root = new RootCommandNode<>();
    int[] count = {0};
    for (var entry : nodes.entrySet()) {
      var node = decode(entry.getValue().getAsJsonObject(), 0, count);
      if (!node.getName().equals(entry.getKey())) {
        throw new IOException("Mismatched command label");
      }
      root.addChild(node);
    }
    return root;
  }
}
