/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Converts each source system's timestamp shape to the strict ISO-8601 instant string the raw
 * record contract requires (e.g. {@code 2026-01-05T09:00:00Z}), so normalization can {@code
 * Instant.parse} every payload timestamp unconditionally.
 */
public final class SourceTimestamps {

  private static final DateTimeFormatter COMPACT_OFFSET =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT);
  private static final DateTimeFormatter COMPACT_OFFSET_MILLIS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT);

  private SourceTimestamps() {}

  /**
   * Converts a colon-delimited ISO-8601 offset timestamp — Bitbucket's shape, e.g. {@code
   * 2026-01-05T09:00:00.000000+00:00} — to a strict ISO-8601 instant string. The format is already
   * ISO-8601 compliant, so no custom pattern is needed.
   *
   * @param raw the source timestamp
   * @return the strict ISO-8601 instant string
   */
  public static String fromIsoOffset(String raw) {
    return OffsetDateTime.parse(raw).toInstant().toString();
  }

  /**
   * Converts a compact (no-colon) offset timestamp — SonarQube's shape, e.g. {@code
   * 2026-01-05T09:00:00+0000}, with or without the Jira-style millisecond field — to a strict
   * ISO-8601 instant string. Falls back to strict ISO-8601 parsing so an already-normalized value
   * round-trips unchanged.
   *
   * @param raw the source timestamp
   * @return the strict ISO-8601 instant string
   */
  public static String fromCompactOffset(String raw) {
    try {
      return OffsetDateTime.parse(raw, COMPACT_OFFSET).toInstant().toString();
    } catch (DateTimeParseException withoutMillis) {
      try {
        return OffsetDateTime.parse(raw, COMPACT_OFFSET_MILLIS).toInstant().toString();
      } catch (DateTimeParseException withMillis) {
        return Instant.parse(raw).toString();
      }
    }
  }
}
