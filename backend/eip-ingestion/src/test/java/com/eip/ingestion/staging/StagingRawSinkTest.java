/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eip.connectors.spi.FetchKind;
import com.eip.connectors.spi.Op;
import com.eip.connectors.spi.RawRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the three-way content-hash idempotency of {@link StagingRawSink} — insert / update /
 * unchanged — over a mocked JDBC seam (mirrors eip-tenancy's approach), plus its failure wrapping.
 */
class StagingRawSinkTest {

  private static final UUID CONNECTOR_ID = UUID.fromString("00000000-0000-4000-8000-00000000c0de");

  private final RawPayloadCodec codec = new RawPayloadCodec(new ObjectMapper());
  private final RawRecord record =
      new RawRecord(
          "work_item",
          "PLAT-101",
          "jira",
          "sim",
          "jira:PLAT-101",
          Op.UPSERT,
          FetchKind.FULL,
          Map.of("key", "PLAT-101", "team", "Platform"));

  private Connection connection = mock(Connection.class);
  private PreparedStatement selectPs = mock(PreparedStatement.class);
  private PreparedStatement writePs = mock(PreparedStatement.class);
  private ResultSet selectRs = mock(ResultSet.class);

  @BeforeEach
  void wireStatements() throws SQLException {
    when(connection.prepareStatement(argThat(sql -> sql != null && sql.startsWith("SELECT"))))
        .thenReturn(selectPs);
    when(connection.prepareStatement(
            argThat(sql -> sql != null && (sql.startsWith("INSERT") || sql.startsWith("UPDATE")))))
        .thenReturn(writePs);
    when(selectPs.executeQuery()).thenReturn(selectRs);
  }

  private byte[] expectedHash() {
    return codec.contentHash(codec.toCanonicalJson(record.payload()));
  }

  @Test
  void inserts_when_no_row_exists() throws SQLException {
    when(selectRs.next()).thenReturn(false);

    StagingRawSink sink = new StagingRawSink(connection, CONNECTOR_ID, codec);
    sink.emit(record);

    assertThat(sink.emitted()).isEqualTo(1);
    assertThat(sink.inserted()).isEqualTo(1);
    assertThat(sink.updated()).isZero();
    assertThat(sink.unchanged()).isZero();
    verify(writePs, times(1)).executeUpdate();
  }

  @Test
  void skips_when_content_hash_is_unchanged() throws SQLException {
    when(selectRs.next()).thenReturn(true);
    when(selectRs.getBytes(1)).thenReturn(expectedHash());

    StagingRawSink sink = new StagingRawSink(connection, CONNECTOR_ID, codec);
    sink.emit(record);

    assertThat(sink.unchanged()).isEqualTo(1);
    assertThat(sink.inserted()).isZero();
    verify(writePs, never()).executeUpdate();
  }

  @Test
  void updates_when_content_hash_differs() throws SQLException {
    when(selectRs.next()).thenReturn(true);
    when(selectRs.getBytes(1)).thenReturn(new byte[] {1, 2, 3});

    StagingRawSink sink = new StagingRawSink(connection, CONNECTOR_ID, codec);
    sink.emit(record);

    assertThat(sink.updated()).isEqualTo(1);
    verify(writePs, times(1)).executeUpdate();
  }

  @Test
  void wraps_sql_failures_as_ingestion_exception() throws SQLException {
    when(selectPs.executeQuery()).thenThrow(new SQLException("boom"));

    StagingRawSink sink = new StagingRawSink(connection, CONNECTOR_ID, codec);
    assertThatThrownBy(() -> sink.emit(record))
        .isInstanceOf(IngestionException.class)
        .hasMessageContaining("work_item")
        .hasMessageContaining("PLAT-101");
  }
}
