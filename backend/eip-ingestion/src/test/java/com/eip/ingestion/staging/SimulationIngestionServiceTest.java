/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.staging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eip.connectors.spi.Connector;
import com.eip.connectors.spi.SyncContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Proves connector-row resolution (find-or-create) and empty-sync orchestration. */
class SimulationIngestionServiceTest {

  private static final UUID EXISTING = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
  private static final UUID CREATED = UUID.fromString("00000000-0000-4000-8000-0000000000b2");

  private final SimulationIngestionService service =
      new SimulationIngestionService(new ObjectMapper());

  private final Connection connection = mock(Connection.class);
  private final PreparedStatement selectPs = mock(PreparedStatement.class);
  private final PreparedStatement insertPs = mock(PreparedStatement.class);
  private final ResultSet selectRs = mock(ResultSet.class);
  private final ResultSet insertRs = mock(ResultSet.class);

  private void wireLookup(boolean exists) throws SQLException {
    when(connection.prepareStatement(argThat(sql -> sql != null && sql.startsWith("SELECT id"))))
        .thenReturn(selectPs);
    when(connection.prepareStatement(argThat(sql -> sql != null && sql.startsWith("INSERT INTO"))))
        .thenReturn(insertPs);
    when(selectPs.executeQuery()).thenReturn(selectRs);
    when(selectRs.next()).thenReturn(exists);
    when(selectRs.getObject(1, UUID.class)).thenReturn(EXISTING);
    when(insertPs.executeQuery()).thenReturn(insertRs);
    when(insertRs.next()).thenReturn(true);
    when(insertRs.getObject(1, UUID.class)).thenReturn(CREATED);
  }

  @Test
  void ensure_connector_returns_the_existing_row_without_inserting() throws SQLException {
    wireLookup(true);

    assertThat(service.ensureConnector(connection, "simulation")).isEqualTo(EXISTING);
    verify(connection, never()).prepareStatement(argThat(s -> s != null && s.startsWith("INSERT")));
  }

  @Test
  void ensure_connector_creates_the_row_when_absent() throws SQLException {
    wireLookup(false);

    assertThat(service.ensureConnector(connection, "simulation")).isEqualTo(CREATED);
    verify(insertPs).setString(eq(1), eq("simulation"));
  }

  @Test
  void ingest_of_an_empty_connector_stages_nothing() throws SQLException {
    wireLookup(true);
    Connector empty =
        new Connector() {
          @Override
          public String type() {
            return "simulation";
          }

          @Override
          public boolean simulation() {
            return true;
          }

          @Override
          public void sync(SyncContext context) {
            // emits nothing
          }
        };

    IngestionResult result = service.ingest(connection, empty);

    assertThat(result).isEqualTo(new IngestionResult(0, 0, 0, 0));
  }
}
