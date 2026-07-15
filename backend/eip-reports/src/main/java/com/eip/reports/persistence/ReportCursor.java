/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.persistence;

import com.eip.core.error.ValidationException;
import com.eip.reports.api.ReportView;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Opaque keyset cursor for the report list, encoding the {@code (created_at desc, id desc)} sort
 * key of the last row on a page. Base64url over {@code "<createdAt><NUL><id>"} — the same shape as
 * {@code com.eip.app.persistence.ConnectorCursor}, reimplemented locally because {@code
 * eip-reports} cannot depend on {@code eip-app} (module dependency direction, BackendPlan §1).
 * Deliberate, acknowledged duplication (TASK-0022) rather than a shared abstraction for two call
 * sites. Unlike {@code ConnectorCursor}, a malformed cursor raises the shared {@link
 * ValidationException} directly (400 problem+json via the existing {@code ApiExceptionHandler}
 * taxonomy) instead of a bespoke exception type, so no new handler needs registering in {@code
 * eip-app}.
 *
 * @param createdAt the {@code reports.generated_report.created_at} of the last row returned
 * @param id the tie-breaker id of the last row returned
 */
record ReportCursor(Instant createdAt, UUID id) {

  private static final char NUL = '\u0000';

  /**
   * Decodes a client-supplied cursor.
   *
   * @param cursor the opaque cursor, or {@code null}/blank when requesting the first page
   * @return the decoded cursor, or {@code null} for the first page
   * @throws ValidationException if the cursor is non-null but not a valid encoding
   */
  static @Nullable ReportCursor decode(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int sep = raw.indexOf(NUL);
      if (sep < 0) {
        throw new ValidationException("invalid pagination cursor");
      }
      return new ReportCursor(
          Instant.parse(raw.substring(0, sep)), UUID.fromString(raw.substring(sep + 1)));
    } catch (IllegalArgumentException | DateTimeParseException malformed) {
      throw new ValidationException("invalid pagination cursor");
    }
  }

  /**
   * Encodes the cursor pointing just past the given row.
   *
   * @param last the last row on the current page
   * @return the opaque cursor for the next page
   */
  static String encode(ReportView last) {
    String raw = last.createdAt().toString() + NUL + last.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }
}
