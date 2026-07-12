/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import com.eip.app.application.RunFrictionPipelineUseCase;
import com.eip.app.application.RunFrictionPipelineUseCase.PipelineResult;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Seeds deterministic <strong>simulation</strong> demo data — one demo tenant with an organisation,
 * simulation connectors, and a team hierarchy — then runs the real friction pipeline ({@link
 * RunFrictionPipelineUseCase}: ingestion → normalization → computed friction), so the visible
 * slices demonstrate computed friction with no real source connectivity. Idempotent, and gated to
 * the {@code demo} profile so it never runs in production or in tests.
 *
 * <p>The control-plane seed runs in one tenant-bound transaction (committed before the pipeline
 * starts, because the pipeline reads the teams back in its own transactions); the fixed tenant id
 * comes from validated {@link DemoDataProperties}.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

  /**
   * The fixed demo tenant id (default of {@code eip.demo.tenant-id}). Stable so the one-command
   * demo ({@code make demo-up}) and the frontend default work without a DB lookup.
   */
  public static final UUID DEMO_TENANT_ID = UUID.fromString(DemoDataProperties.DEFAULT_TENANT_ID);

  private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

  private final TenantTransactionRunner tx;
  private final JdbcClient jdbc;
  private final RunFrictionPipelineUseCase pipeline;
  private final DemoDataProperties properties;

  public DemoDataSeeder(
      TenantTransactionRunner tx,
      JdbcClient jdbc,
      RunFrictionPipelineUseCase pipeline,
      DemoDataProperties properties) {
    this.tx = tx;
    this.jdbc = jdbc;
    this.pipeline = pipeline;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    UUID tenantId = properties.tenantId();
    boolean exists =
        jdbc.sql("SELECT count(*) FROM core.tenant WHERE slug = 'demo'")
                .query(Integer.class)
                .single()
            > 0;
    if (exists) {
      log.info("demo data already present — tenant {} (slug 'demo')", tenantId);
      return;
    }
    seedControlPlane(tenantId);
    PipelineResult result = pipeline.run(tenantId);
    log.info(
        "seeded demo tenant {} (slug 'demo') + ran friction pipeline: {} staged, {} teams"
            + " computed, {} items correlated",
        tenantId,
        result.ingestion().inserted(),
        result.teamsComputed(),
        result.itemsCorrelated());
  }

  /** Seeds the demo control plane (tenant, org, connectors, teams) in one committed transaction. */
  private void seedControlPlane(UUID tenantId) {
    UUID orgId = UUID.randomUUID();
    UUID buId = UUID.randomUUID();
    tx.run(
        TenantContext.of(tenantId),
        () -> {
          jdbc.sql("INSERT INTO core.tenant (id, name, slug) VALUES (:id, 'Demo Tenant', 'demo')")
              .param("id", tenantId)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO core.organization (id, tenant_id, name, slug)
                  VALUES (:id, :tenantId, 'Demo Org', 'demo-org')
                  """)
              .param("id", orgId)
              .param("tenantId", tenantId)
              .update();
          insertConnector(tenantId, "jira", "Demo Jira (simulation)");
          insertConnector(tenantId, "bitbucket", "Demo Bitbucket (simulation)");
          insertConnector(tenantId, "sonarqube", "Demo SonarQube (simulation)");
          jdbc.sql(
                  """
                  INSERT INTO core.business_unit (id, tenant_id, organization_id, name)
                  VALUES (:id, :tenantId, :orgId, 'Demo Engineering')
                  """)
              .param("id", buId)
              .param("tenantId", tenantId)
              .param("orgId", orgId)
              .update();
          // Team names match the simulation dataset so normalization resolves work items to them.
          insertTeam(tenantId, buId, "Platform");
          insertTeam(tenantId, buId, "Payments");
          insertTeam(tenantId, buId, "Web");
        });
  }

  private void insertConnector(UUID tenantId, String type, String name) {
    jdbc.sql(
            """
            INSERT INTO core.connector (id, tenant_id, type, name, status, simulation)
            VALUES (:id, :tenantId, :type, :name, 'REGISTERED', true)
            """)
        .param("id", UUID.randomUUID())
        .param("tenantId", tenantId)
        .param("type", type)
        .param("name", name)
        .update();
  }

  private void insertTeam(UUID tenantId, UUID buId, String name) {
    jdbc.sql(
            """
            INSERT INTO core.team (id, tenant_id, business_unit_id, name, type)
            VALUES (:id, :tenantId, :buId, :name, 'STREAM_ALIGNED')
            """)
        .param("id", UUID.randomUUID())
        .param("tenantId", tenantId)
        .param("buId", buId)
        .param("name", name)
        .update();
  }
}
