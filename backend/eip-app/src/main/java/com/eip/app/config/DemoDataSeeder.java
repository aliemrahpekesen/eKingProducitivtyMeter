/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import com.eip.tenancy.context.RlsTenantBinder;
import com.eip.tenancy.context.TenantContext;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds deterministic <strong>simulation</strong> demo data — one demo tenant with an organisation
 * and a few simulation connectors — so the first visible slice ({@code /api/v1/session}, {@code
 * /api/v1/connectors}) demonstrates without any real source connectivity. Idempotent, and gated to
 * the {@code demo} profile so it never runs in production or in tests. Not real Jira/
 * Bitbucket/Sonar connectivity — just {@code core.connector} rows flagged {@code simulation =
 * true}.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

  private final DataSource dataSource;
  private final JdbcClient jdbc;

  public DemoDataSeeder(DataSource dataSource) {
    this.dataSource = dataSource;
    this.jdbc = JdbcClient.create(dataSource);
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    boolean exists =
        jdbc.sql("SELECT count(*) FROM core.tenant WHERE slug = 'demo'")
                .query(Integer.class)
                .single()
            > 0;
    if (exists) {
      return;
    }
    UUID tenantId = UUID.randomUUID();
    jdbc.sql("INSERT INTO core.tenant (id, name, slug) VALUES (?, 'Demo Tenant', 'demo')")
        .param(tenantId)
        .update();

    // Bind the demo tenant so the RLS WITH CHECK admits the tenant-scoped inserts below.
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      RlsTenantBinder.bind(connection, TenantContext.of(tenantId));
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException("failed to bind demo tenant", e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }

    jdbc.sql(
            "INSERT INTO core.organization (id, tenant_id, name, slug) VALUES (?, ?, 'Demo Org', 'demo-org')")
        .params(UUID.randomUUID(), tenantId)
        .update();
    insertConnector(tenantId, "jira", "Demo Jira (simulation)");
    insertConnector(tenantId, "bitbucket", "Demo Bitbucket (simulation)");
    insertConnector(tenantId, "sonarqube", "Demo SonarQube (simulation)");
  }

  private void insertConnector(UUID tenantId, String type, String name) {
    jdbc.sql(
            "INSERT INTO core.connector (id, tenant_id, type, name, status, simulation) "
                + "VALUES (?, ?, ?, ?, 'REGISTERED', true)")
        .params(UUID.randomUUID(), tenantId, type, name)
        .update();
  }
}
