/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A minimal, dependency-free JSON reader — just enough for {@link
 * Connector#validate(ConnectorConfig)} to answer two questions about a connector's own JSON Schema
 * (draft 2020-12) config-schema text: its {@code required} array and each required property's
 * declared {@code type}. Deliberately NOT a general-purpose JSON library: {@code
 * com.eip.connectors.spi} is architecturally pure Java (ArchitectureRulesTest's {@code
 * pure_domain_packages_stay_framework_free}) — connector CONTRACTS never depend on a framework,
 * including Jackson, even though concrete connector IMPLEMENTATIONS (e.g. {@code
 * com.eip.connectors.jira.JiraConnector}) are free to use it. Supports exactly the JSON subset a
 * config schema needs: objects, arrays, strings, numbers, booleans, and {@code null}.
 */
final class MinimalJson {

  private final String text;
  private int pos;

  private MinimalJson(String text) {
    this.text = text;
  }

  /**
   * Parses a JSON document.
   *
   * @param json the JSON text
   * @return the parsed value: {@code Map<String, Object>}, {@code List<Object>}, {@code String},
   *     {@code Double}, {@code Boolean}, or {@code null}
   */
  static @Nullable Object parse(String json) {
    MinimalJson parser = new MinimalJson(json);
    parser.skipWhitespace();
    @Nullable Object value = parser.readValue();
    parser.skipWhitespace();
    return value;
  }

  /**
   * Reads a string-keyed map entry as a nested map, defaulting to empty when absent or not a map.
   *
   * @param value a value previously returned by {@link #parse(String)} (or nested within one)
   * @param key the entry key
   * @return the nested map, or an empty map when the key is absent or not itself a map
   */
  @SuppressWarnings("unchecked")
  static Map<String, @Nullable Object> asMap(@Nullable Object value, String key) {
    if (!(value instanceof Map<?, ?> map)) {
      return Map.of();
    }
    @Nullable Object nested = map.get(key);
    return nested instanceof Map ? (Map<String, @Nullable Object>) nested : Map.of();
  }

  /**
   * Reads a string-keyed map entry as a list, defaulting to empty when absent or not a list.
   *
   * @param value a value previously returned by {@link #parse(String)} (or nested within one)
   * @param key the entry key
   * @return the list, or an empty list when the key is absent or not itself a list
   */
  @SuppressWarnings("unchecked")
  static List<@Nullable Object> asList(@Nullable Object value, String key) {
    if (!(value instanceof Map<?, ?> map)) {
      return List.of();
    }
    @Nullable Object nested = map.get(key);
    return nested instanceof List ? (List<@Nullable Object>) nested : List.of();
  }

  /**
   * Reads a string-keyed map entry as a string, falling back when absent or not a string.
   *
   * @param value a value previously returned by {@link #parse(String)} (or nested within one)
   * @param key the entry key
   * @param fallback the value to return when the key is absent or not itself a string
   * @return the string value, or {@code fallback}
   */
  static String asString(@Nullable Object value, String key, String fallback) {
    if (!(value instanceof Map<?, ?> map)) {
      return fallback;
    }
    @Nullable Object nested = map.get(key);
    return nested instanceof String s ? s : fallback;
  }

  private @Nullable Object readValue() {
    char c = peek();
    return switch (c) {
      case '{' -> readObject();
      case '[' -> readArray();
      case '"' -> readString();
      case 't' -> readLiteral("true", Boolean.TRUE);
      case 'f' -> readLiteral("false", Boolean.FALSE);
      case 'n' -> readLiteral("null", null);
      default -> readNumber();
    };
  }

  private Map<String, @Nullable Object> readObject() {
    expect('{');
    Map<String, @Nullable Object> result = new LinkedHashMap<>();
    skipWhitespace();
    if (peek() == '}') {
      pos++;
      return result;
    }
    while (true) {
      skipWhitespace();
      String key = readString();
      skipWhitespace();
      expect(':');
      skipWhitespace();
      result.put(key, readValue());
      skipWhitespace();
      char c = peek();
      pos++;
      if (c == '}') {
        return result;
      }
      if (c != ',') {
        throw new IllegalArgumentException("malformed JSON: expected ',' or '}' at " + pos);
      }
    }
  }

  private List<@Nullable Object> readArray() {
    expect('[');
    List<@Nullable Object> result = new ArrayList<>();
    skipWhitespace();
    if (peek() == ']') {
      pos++;
      return result;
    }
    while (true) {
      skipWhitespace();
      result.add(readValue());
      skipWhitespace();
      char c = peek();
      pos++;
      if (c == ']') {
        return result;
      }
      if (c != ',') {
        throw new IllegalArgumentException("malformed JSON: expected ',' or ']' at " + pos);
      }
    }
  }

  private String readString() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (true) {
      char c = text.charAt(pos++);
      if (c == '"') {
        return sb.toString();
      }
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      char escaped = text.charAt(pos++);
      switch (escaped) {
        case '"' -> sb.append('"');
        case '\\' -> sb.append('\\');
        case '/' -> sb.append('/');
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'u' -> {
          sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
          pos += 4;
        }
        default -> throw new IllegalArgumentException("malformed JSON: bad escape at " + pos);
      }
    }
  }

  private Double readNumber() {
    int start = pos;
    while (pos < text.length() && "+-.0123456789eE".indexOf(text.charAt(pos)) >= 0) {
      pos++;
    }
    if (pos == start) {
      throw new IllegalArgumentException("malformed JSON: expected a value at " + pos);
    }
    return Double.parseDouble(text.substring(start, pos));
  }

  private @Nullable Object readLiteral(String literal, @Nullable Boolean value) {
    if (!text.startsWith(literal, pos)) {
      throw new IllegalArgumentException("malformed JSON: expected " + literal + " at " + pos);
    }
    pos += literal.length();
    return value;
  }

  private char peek() {
    return text.charAt(pos);
  }

  private void expect(char c) {
    if (text.charAt(pos) != c) {
      throw new IllegalArgumentException("malformed JSON: expected '" + c + "' at " + pos);
    }
    pos++;
  }

  private void skipWhitespace() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
      pos++;
    }
  }
}
