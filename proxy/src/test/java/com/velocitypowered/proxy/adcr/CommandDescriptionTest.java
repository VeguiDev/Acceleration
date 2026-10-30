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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.velocitypowered.api.command.CommandSource;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class CommandDescriptionTest {
  private JsonObject description() {
    return CommandDescription.encode(LiteralArgumentBuilder.<CommandSource>literal("test")
        .then(RequiredArgumentBuilder.<CommandSource, Integer>argument("count",
            IntegerArgumentType.integer(1, 10)).executes(context -> 42)).build(), 0);
  }

  @Test
  void roundTripIsInertAndRetainsBounds() throws Exception {
    var decoded = CommandDescription.decode(description(), 0, new int[]{0});
    var argument = (ArgumentCommandNode<CommandSource, ?>) decoded.getChild("count");
    var type = (IntegerArgumentType) argument.getType();
    assertEquals(1, type.getMinimum());
    assertEquals(10, type.getMaximum());
    assertEquals(0, argument.getCommand().run(null));
  }

  @Test
  void unknownArgumentsBecomeServerParsedText() throws Exception {
    var original = RequiredArgumentBuilder.<CommandSource, Double>argument("value",
        DoubleArgumentType.doubleArg()).build();
    var decoded = CommandDescription.decode(CommandDescription.encode(original, 0),
        0, new int[]{0});
    assertTrue(((ArgumentCommandNode<CommandSource, ?>) decoded).getType()
        instanceof StringArgumentType);
  }

  @Test
  void rejectsSchemaDuplicatesBoundsAndGraphLimits() {
    JsonObject value = description();
    value.addProperty("executableCode", "forbidden");
    assertThrows(IOException.class, () -> CommandDescription.decode(value, 0, new int[]{0}));
    value.remove("executableCode");
    value.getAsJsonArray("children").add(value.getAsJsonArray("children").get(0).deepCopy());
    assertThrows(IOException.class, () -> CommandDescription.decode(value, 0, new int[]{0}));
    JsonObject argument = description().getAsJsonArray("children").get(0).getAsJsonObject();
    argument.addProperty("min", 11);
    assertThrows(IOException.class, () -> CommandDescription.decode(argument, 0, new int[]{0}));
    assertThrows(IOException.class, () -> CommandDescription.decode(description(), 27, new int[]{0}));
    assertThrows(IOException.class,
        () -> CommandDescription.decode(description(), 0, new int[]{8192}));
  }
}
