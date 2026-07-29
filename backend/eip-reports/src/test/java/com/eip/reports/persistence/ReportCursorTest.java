/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.core.error.ValidationException;
import com.eip.reports.api.ReportView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the signed, expiring {@link ReportCursor} (DEBT-010): sign/verify roundtrip, a tampered
 * payload is rejected, and an expired cursor is rejected (a fixed test clock, never a sleep).
 * Mirrors {@code com.eip.app.persistence.ConnectorCursorTest} — same scheme, no {@code sort} to
 * embed/compare (the report list has a fixed order; see class javadoc).
 */
class ReportCursorTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void roundtrips_a_cursor() {
    ReportView last = report(Instant.parse("2025-06-01T12:00:00Z"));

    String cursor = ReportCursor.encode(last, CLOCK);
    ReportCursor decoded = Objects.requireNonNull(ReportCursor.decode(cursor, CLOCK));

    assertThat(decoded.createdAt()).isEqualTo(last.createdAt());
    assertThat(decoded.id()).isEqualTo(last.id());
  }

  @Test
  void null_or_blank_cursor_decodes_to_null_for_the_first_page() {
    assertThat(ReportCursor.decode(null, CLOCK)).isNull();
    assertThat(ReportCursor.decode("  ", CLOCK)).isNull();
  }

  @Test
  void a_tampered_cursor_is_rejected() {
    String cursor = ReportCursor.encode(report(Instant.parse("2025-06-01T12:00:00Z")), CLOCK);
    char[] chars = cursor.toCharArray();
    int mid = chars.length / 2;
    chars[mid] = chars[mid] == 'A' ? 'B' : 'A';
    String tampered = new String(chars);

    assertThatThrownBy(() -> ReportCursor.decode(tampered, CLOCK))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void an_expired_cursor_is_rejected() {
    String cursor = ReportCursor.encode(report(Instant.parse("2025-06-01T12:00:00Z")), CLOCK);
    Clock afterExpiry = Clock.fixed(NOW.plus(ReportCursor.TTL).plusSeconds(1), ZoneOffset.UTC);

    assertThatThrownBy(() -> ReportCursor.decode(cursor, afterExpiry))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void a_cursor_still_within_its_ttl_is_accepted() {
    String cursor = ReportCursor.encode(report(Instant.parse("2025-06-01T12:00:00Z")), CLOCK);
    Clock justBeforeExpiry =
        Clock.fixed(NOW.plus(ReportCursor.TTL).minusSeconds(1), ZoneOffset.UTC);

    assertThat(ReportCursor.decode(cursor, justBeforeExpiry)).isNotNull();
  }

  @Test
  void a_non_base64_cursor_is_rejected() {
    assertThatThrownBy(() -> ReportCursor.decode("!!!not-base64!!!", CLOCK))
        .isInstanceOf(ValidationException.class);
  }

  private static ReportView report(Instant createdAt) {
    return new ReportView(
        UUID.randomUUID(),
        "EXEC_SUMMARY",
        "Weekly Summary",
        "READY",
        createdAt.minusSeconds(3600),
        createdAt,
        12,
        createdAt,
        createdAt);
  }
}
