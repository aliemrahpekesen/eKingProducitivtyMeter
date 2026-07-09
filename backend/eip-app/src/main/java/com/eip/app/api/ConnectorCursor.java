/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Opaque keyset cursor for the connector list, encoding the {@code (name, id)} sort key of the last
 * row on a page. Base64url over {@code "<name><NUL><id>"} — the {@code NUL} separator can never
 * occur in a Postgres {@code text} value, so the split is unambiguous. Clients treat this as opaque
 * (APIDesign §1.4).
 *
 * <p>Signing + expiry of the cursor are deferred to the pagination-hardening pass (DEBT-010); the
 * client-facing contract (an opaque string) is stable across that change.
 *
 * @param name the {@code core.connector.name} of the last row returned
 * @param id the {@code core.connector.id} tie-breaker of the last row returned
 */
record ConnectorCursor(String name, UUID id) {

  private static final char SEP = '\u0000';

  /**
   * Decodes a client-supplied cursor.
   *
   * @param cursor the opaque cursor, or {@code null}/blank when requesting the first page
   * @return the decoded cursor, or {@code null} for the first page
   * @throws InvalidCursorException if the cursor is non-null but not a valid encoding
   */
  static @Nullable ConnectorCursor decode(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int sep = raw.indexOf(SEP);
      if (sep < 0) {
        throw new InvalidCursorException();
      }
      return new ConnectorCursor(raw.substring(0, sep), UUID.fromString(raw.substring(sep + 1)));
    } catch (IllegalArgumentException malformed) {
      throw new InvalidCursorException();
    }
  }

  /**
   * Encodes the cursor pointing just past the given row.
   *
   * @param last the last row on the current page
   * @return the opaque cursor for the next page
   */
  static String encode(ConnectorView last) {
    String raw = last.name() + SEP + last.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** Thrown when a client supplies a cursor that is not a valid encoding (→ 400 problem+json). */
  static final class InvalidCursorException extends RuntimeException {
    InvalidCursorException() {
      super("invalid pagination cursor");
    }
  }
}
