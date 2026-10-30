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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.proxy.apdp.config.ApdpConfig;
import com.velocitypowered.proxy.apdp.protocol.Wire;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WireConfigTest {
  @TempDir
  Path temporary;

  @Test
  void rejectsDuplicateFieldsTrailingDataMalformedUtf8AndExcessiveNesting() {
    for (String invalid : java.util.List.of("{\"a\":1,\"a\":2}", "{} {}", "{a:1}",
        "{\"a\":9223372036854775808}", "{\"a\":1.1}",
        "{\"a\":".repeat(70) + "0" + "}".repeat(70))) {
      assertThrows(Exception.class, () -> Wire.parse(invalid.getBytes(StandardCharsets.UTF_8)));
    }
    assertThrows(Exception.class, () -> Wire.parse(new byte[]{(byte) 0xff}));
    assertThrows(Exception.class, () -> Wire.parse(new byte[Wire.MAX + 1]));
  }

  @Test
  void preservesUnknownSettingsAndCommentsAndRejectsConcurrentEdits() throws Exception {
    Path path = temporary.resolve("apdp.toml");
    ApdpConfig config = new ApdpConfig(path);
    config.create("test");
    Files.writeString(path, "\n# manual annotation\nunknown = \"preserve\"\n",
        java.nio.file.StandardOpenOption.APPEND);
    config.edit(value -> value.set("parent-address", "localhost:9000"));
    assertTrue(Files.readString(path).contains("manual annotation"));
    assertTrue(Files.readString(path).contains("preserve"));
    assertEquals("localhost:9000", config.load().parentAddress());
    assertThrows(Exception.class, () -> config.edit(value -> {
      value.set("name", "overwritten");
      try {
        Files.writeString(path, "\n# external concurrent change\n",
            java.nio.file.StandardOpenOption.APPEND);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }));
    assertTrue(Files.readString(path).contains("external concurrent change"));
    assertFalse(Files.readString(path).contains("overwritten"));
  }

  @Test
  void commandRedactionIsCaseInsensitiveAndDoesNotExposeToken() {
    assertEquals("apdp join <redacted>", ApdpCommand.redact("  APDP Join APDP1.secret"));
    assertEquals("server lobby", ApdpCommand.redact("server lobby"));
  }
}
