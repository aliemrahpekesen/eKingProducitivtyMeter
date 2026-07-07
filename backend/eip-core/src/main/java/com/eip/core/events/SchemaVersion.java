/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.core.events;

import java.util.Objects;

/**
 * {@code MAJOR.MINOR} schema version carried by every {@link EventEnvelope} for additive-only event
 * evolution (DomainModel §6; EventModel governs the compatibility policy in Phase 1).
 *
 * <p>Value type only: {@code major} is at least 1 and {@code minor} at least 0. The comparison and
 * compatibility semantics (e.g. minor-additive back-compatibility) are defined alongside the topic
 * catalog later; this kernel type fixes the shape and parsing.
 *
 * @param major major version, incremented on breaking envelope/payload changes (≥ 1)
 * @param minor minor version, incremented on additive changes (≥ 0)
 */
public record SchemaVersion(int major, int minor) {

  public SchemaVersion {
    if (major < 1) {
      throw new IllegalArgumentException("major must be >= 1, was " + major);
    }
    if (minor < 0) {
      throw new IllegalArgumentException("minor must be >= 0, was " + minor);
    }
  }

  /**
   * Factory mirror of the canonical constructor.
   *
   * @param major major version (≥ 1)
   * @param minor minor version (≥ 0)
   * @return the schema version
   */
  public static SchemaVersion of(int major, int minor) {
    return new SchemaVersion(major, minor);
  }

  /**
   * Parses a {@code "MAJOR.MINOR"} string (e.g. {@code "1.0"}).
   *
   * @param text the dotted version string; exactly two integer components
   * @return the parsed schema version
   * @throws IllegalArgumentException if the text is not exactly two integer components
   */
  public static SchemaVersion parse(String text) {
    Objects.requireNonNull(text, "text");
    String[] parts = text.split("\\.", -1);
    if (parts.length != 2) {
      throw new IllegalArgumentException("expected MAJOR.MINOR, was '" + text + "'");
    }
    try {
      return new SchemaVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("non-numeric version component in '" + text + "'", e);
    }
  }

  @Override
  public String toString() {
    return major + "." + minor;
  }
}
