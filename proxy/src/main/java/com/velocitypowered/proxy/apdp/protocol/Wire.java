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

package com.velocitypowered.proxy.apdp.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/** Bounded, strict JSON payloads; signatures cover original bytes, not reserialized JSON. */
public final class Wire {
  public static final int MAX = 1 << 20;
  public static final Gson GSON = new Gson();

  private Wire() { }

  /** Serializes a bounded logical message, without transport compression. */
  public static byte[] encode(Object object) {
    byte[] bytes = GSON.toJson(object).getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX) {
      throw new IllegalArgumentException("APDP payload too large");
    }
    return bytes;
  }

  public static JsonObject object(Object value) {
    return GSON.toJsonTree(value).getAsJsonObject();
  }

  /** Parses exactly one strict object, rejecting duplicate fields and excessive nesting. */
  public static JsonObject parse(byte[] bytes) throws IOException {
    if (bytes.length > MAX) {
      throw new IOException("APDP payload too large");
    }
    String text = StandardCharsets.UTF_8.newDecoder()
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    try (JsonReader reader = new JsonReader(new StringReader(text))) {
      reader.setStrictness(Strictness.STRICT);
      JsonElement value = read(reader, 0, new int[]{0});
      if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
        throw new IOException("Expected one APDP object");
      }
      return value.getAsJsonObject();
    } catch (IllegalStateException | NumberFormatException e) {
      throw new IOException("Invalid APDP JSON");
    }
  }

  private static JsonElement read(JsonReader reader, int depth, int[] count) throws IOException {
    if (depth > 64 || ++count[0] > 100000) {
      throw new IOException("APDP JSON bounds exceeded");
    }
    switch (reader.peek()) {
      case BEGIN_OBJECT:
        JsonObject object = new JsonObject();
        reader.beginObject();
        while (reader.hasNext()) {
          String key = reader.nextName();
          if (key.length() > 128 || object.has(key)) {
            throw new IOException("Duplicate or oversized field");
          }
          object.add(key, read(reader, depth + 1, count));
        }
        reader.endObject();
        return object;
      case BEGIN_ARRAY:
        JsonArray array = new JsonArray();
        reader.beginArray();
        while (reader.hasNext()) {
          if (array.size() >= 8192) {
            throw new IOException("APDP collection too large");
          }
          array.add(read(reader, depth + 1, count));
        }
        reader.endArray();
        return array;
      case STRING:
        String string = reader.nextString();
        if (string.length() > MAX) {
          throw new IOException("APDP string too large");
        }
        return new JsonPrimitive(string);
      case NUMBER:
        String number = reader.nextString();
        if (!number.matches("-?[0-9]{1,19}")) {
          throw new IOException("Expected bounded integer");
        }
        return new JsonPrimitive(Long.parseLong(number));
      case BOOLEAN:
        return new JsonPrimitive(reader.nextBoolean());
      case NULL:
        reader.nextNull();
        return JsonNull.INSTANCE;
      default:
        throw new IOException("Invalid APDP value");
    }
  }

  /** Enforces an exact mandatory-field schema. */
  public static void fields(JsonObject object, String... names) throws IOException {
    if (!object.keySet().equals(Set.of(names))) {
      throw new IOException("Invalid APDP schema");
    }
  }

  /** Reads a bounded string without coercing booleans or numeric values. */
  public static String string(JsonObject object, String name, int max) throws IOException {
    JsonElement value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
        || value.getAsString().length() > max) {
      throw new IOException("Invalid APDP string field");
    }
    return value.getAsString();
  }

  /** Reads a JSON integer without accepting a quoted number or a boolean. */
  public static long integer(JsonObject object, String name, long min, long max) throws IOException {
    JsonElement value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
      throw new IOException("Invalid APDP integer field");
    }
    long number = value.getAsLong();
    if (number < min || number > max) {
      throw new IOException("APDP integer outside bounds");
    }
    return number;
  }

  /** Reads a JSON boolean without coercing strings. */
  public static boolean bool(JsonObject object, String name) throws IOException {
    JsonElement value = object.get(name);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
      throw new IOException("Invalid APDP boolean field");
    }
    return value.getAsBoolean();
  }

  public static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Decodes a bounded URL-safe binary field. */
  public static byte[] unb64(String text) {
    if (text.length() > MAX * 2) {
      throw new IllegalArgumentException("APDP encoded value too large");
    }
    return Base64.getUrlDecoder().decode(text);
  }
}
