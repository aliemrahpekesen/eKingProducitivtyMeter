/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.ingestion;

import com.eip.connectors.simulation.SimulationConnector;
import com.eip.connectors.spi.Connector;
import com.eip.ingestion.staging.IngestionResult;
import com.eip.ingestion.staging.SimulationIngestionService;
import com.eip.tenancy.context.RlsTenantBinder;
import com.eip.tenancy.context.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/**
 * Runs the deterministic simulation ingestion for a tenant. This is the composition root's
 * write-side counterpart to {@code TenantScopedJdbc}: it opens a transaction, binds the tenant's
 * RLS GUC on that transaction's connection ({@link RlsTenantBinder}), and drives the
 * connector→staging ingestion inside it, so every staged row is written under — and only visible
 * through — that tenant's RLS policy. The heavy lifting lives in the framework-free {@code
 * eip-ingestion} library; this class is the Spring seam that supplies a {@link DataSource} and a
 * tenant.
 */
@Service
public class SimulationIngestionRunner {

  private final DataSource dataSource;
  private final SimulationIngestionService ingestionService;
  private final Connector connector;

  /**
   * Creates the runner.
   *
   * @param dataSource the app datasource (pool of {@code eip_app} connections, NOBYPASSRLS)
   * @param objectMapper the shared JSON mapper for raw-payload serialization
   */
  public SimulationIngestionRunner(DataSource dataSource, ObjectMapper objectMapper) {
    this.dataSource = dataSource;
    this.ingestionService = new SimulationIngestionService(objectMapper);
    this.connector = new SimulationConnector();
  }

  /**
   * Ingests the full simulation dataset for the given tenant. Idempotent: a second call with the
   * same dataset stages nothing new.
   *
   * @param tenantId the tenant to ingest for
   * @return the ingestion outcome
   */
  public IngestionResult ingest(UUID tenantId) {
    try (Connection connection = dataSource.getConnection()) {
      boolean previousAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        RlsTenantBinder.bind(connection, TenantContext.of(tenantId));
        IngestionResult result = ingestionService.ingest(connection, connector);
        connection.commit();
        return result;
      } catch (RuntimeException | SQLException e) {
        connection.rollback();
        throw new IllegalStateException("simulation ingestion failed for tenant " + tenantId, e);
      } finally {
        connection.setAutoCommit(previousAutoCommit);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("simulation ingestion connection failure", e);
    }
  }
}
