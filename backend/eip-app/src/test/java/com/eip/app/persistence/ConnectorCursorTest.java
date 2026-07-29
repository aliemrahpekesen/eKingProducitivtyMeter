/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.app.config.ApiCursorSigningProperties;
import com.eip.app.persistence.ConnectorCursor.InvalidCursorException;
import com.eip.core.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the signed, expiring {@link ConnectorCursor} (DEBT-010): sign/verify roundtrip for both
 * sort families, a tampered payload is rejected, a cursor signed with a different key is rejected,
 * an expired cursor is rejected (a fixed test clock, never a sleep), and a cursor presented against
 * a different {@code sort} than it was issued under is rejected with a distinct, actionable
 * message.
 */
class ConnectorCursorTest {

  private static final byte[] KEY =
      Base64.getDecoder().decode(ApiCursorSigningProperties.DEV_ONLY_CURSOR_SIGNING_KEY);
  private static final byte[] OTHER_KEY =
      "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final UUID ID = UUID.randomUUID();

  @Test
  void roundtrips_a_name_sort_cursor() {
    String cursor = ConnectorCursor.encode(ConnectorSort.NAME_ASC, "Zeta", null, ID, KEY, CLOCK);
    ConnectorCursor decoded =
        Objects.requireNonNull(ConnectorCursor.decode(cursor, ConnectorSort.NAME_ASC, KEY, CLOCK));

    assertThat(decoded.sort()).isEqualTo(ConnectorSort.NAME_ASC);
    assertThat(decoded.name()).isEqualTo("Zeta");
    assertThat(decoded.createdAt()).isNull();
    assertThat(decoded.id()).isEqualTo(ID);
  }

  @Test
  void roundtrips_a_created_at_sort_cursor() {
    Instant createdAt = Instant.parse("2025-06-01T12:00:00Z");
    String cursor =
        ConnectorCursor.encode(ConnectorSort.CREATED_AT_DESC, null, createdAt, ID, KEY, CLOCK);
    ConnectorCursor decoded =
        Objects.requireNonNull(
            ConnectorCursor.decode(cursor, ConnectorSort.CREATED_AT_DESC, KEY, CLOCK));

    assertThat(decoded.sort()).isEqualTo(ConnectorSort.CREATED_AT_DESC);
    assertThat(decoded.name()).isNull();
    assertThat(decoded.createdAt()).isEqualTo(createdAt);
    assertThat(decoded.id()).isEqualTo(ID);
  }

  @Test
  void null_or_blank_cursor_decodes_to_null_for_the_first_page() {
    assertThat(ConnectorCursor.decode(null, ConnectorSort.NAME_ASC, KEY, CLOCK)).isNull();
    assertThat(ConnectorCursor.decode("  ", ConnectorSort.NAME_ASC, KEY, CLOCK)).isNull();
  }

  @Test
  void a_tampered_cursor_is_rejected() {
    String cursor = ConnectorCursor.encode(ConnectorSort.NAME_ASC, "Zeta", null, ID, KEY, CLOCK);
    String tampered = flipMiddleCharacter(cursor);

    assertThatThrownBy(() -> ConnectorCursor.decode(tampered, ConnectorSort.NAME_ASC, KEY, CLOCK))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void a_cursor_signed_with_a_different_key_is_rejected() {
    String cursor = ConnectorCursor.encode(ConnectorSort.NAME_ASC, "Zeta", null, ID, KEY, CLOCK);

    assertThatThrownBy(
            () -> ConnectorCursor.decode(cursor, ConnectorSort.NAME_ASC, OTHER_KEY, CLOCK))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void an_expired_cursor_is_rejected() {
    String cursor = ConnectorCursor.encode(ConnectorSort.NAME_ASC, "Zeta", null, ID, KEY, CLOCK);
    Clock afterExpiry = Clock.fixed(NOW.plus(ConnectorCursor.TTL).plusSeconds(1), ZoneOffset.UTC);

    assertThatThrownBy(
            () -> ConnectorCursor.decode(cursor, ConnectorSort.NAME_ASC, KEY, afterExpiry))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void a_cursor_still_within_its_ttl_is_accepted() {
    String cursor = ConnectorCursor.encode(ConnectorSort.NAME_ASC, "Zeta", null, ID, KEY, CLOCK);
    Clock justBeforeExpiry =
        Clock.fixed(NOW.plus(ConnectorCursor.TTL).minusSeconds(1), ZoneOffset.UTC);

    assertThat(ConnectorCursor.decode(cursor, ConnectorSort.NAME_ASC, KEY, justBeforeExpiry))
        .isNotNull();
  }

  @Test
  void a_cursor_presented_against_a_different_sort_than_it_was_issued_under_is_rejected() {
    String cursor = ConnectorCursor.encode(ConnectorSort.NAME_ASC, "Zeta", null, ID, KEY, CLOCK);

    assertThatThrownBy(() -> ConnectorCursor.decode(cursor, ConnectorSort.NAME_DESC, KEY, CLOCK))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("sort");
  }

  @Test
  void a_non_base64_cursor_is_rejected() {
    assertThatThrownBy(
            () -> ConnectorCursor.decode("!!!not-base64!!!", ConnectorSort.NAME_ASC, KEY, CLOCK))
        .isInstanceOf(InvalidCursorException.class);
  }

  private static String flipMiddleCharacter(String value) {
    char[] chars = value.toCharArray();
    int mid = chars.length / 2;
    chars[mid] = chars[mid] == 'A' ? 'B' : 'A';
    return new String(chars);
  }
}
