/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.friction;

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
 * Runs the full Engineering Friction slice for a tenant, in one tenant-bound transaction: raw
 * ingestion → normalization into the canonical model → correlation + friction computation → {@code
 * metric_fact} / read model (TASK-0016 INC-2). Every step keys its writes off {@code
 * current_setting('app.tenant_id')}, so the whole pipeline runs — and its data is only ever visible
 * — under the bound tenant's RLS policy. Deterministic and idempotent: re-running produces
 * identical canonical, correlation, and metric rows.
 */
@Service
public class FrictionPipelineRunner {

  private final DataSource dataSource;
  private final SimulationIngestionService ingestionService;
  private final Connector connector;
  private final NormalizationService normalizationService;
  private final FrictionComputeService computeService;

  /**
   * Creates the runner.
   *
   * @param dataSource the app datasource ({@code eip_app}, NOBYPASSRLS)
   * @param objectMapper the shared JSON mapper
   */
  public FrictionPipelineRunner(DataSource dataSource, ObjectMapper objectMapper) {
    this.dataSource = dataSource;
    this.ingestionService = new SimulationIngestionService(objectMapper);
    this.connector = new SimulationConnector();
    this.normalizationService = new NormalizationService(objectMapper);
    this.computeService = new FrictionComputeService();
  }

  /** The outcome of one full pipeline run. */
  public record PipelineResult(IngestionResult ingestion, int teamsComputed, int itemsCorrelated) {}

  /**
   * Runs ingest → normalize → correlate → compute for the given tenant.
   *
   * @param tenantId the tenant to compute friction for
   * @return the pipeline outcome
   */
  public PipelineResult run(UUID tenantId) {
    try (Connection connection = dataSource.getConnection()) {
      boolean previousAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        RlsTenantBinder.bind(connection, TenantContext.of(tenantId));
        IngestionResult ingestion = ingestionService.ingest(connection, connector);
        normalizationService.normalize(connection);
        FrictionComputeService.Result compute = computeService.compute(connection);
        connection.commit();
        return new PipelineResult(ingestion, compute.teamsComputed(), compute.itemsCorrelated());
      } catch (RuntimeException | SQLException e) {
        connection.rollback();
        throw new IllegalStateException("friction pipeline failed for tenant " + tenantId, e);
      } finally {
        connection.setAutoCommit(previousAutoCommit);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("friction pipeline connection failure", e);
    }
  }
}
