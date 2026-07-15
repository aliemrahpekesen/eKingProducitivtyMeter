/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.connectors.simulation.SimulationConnector;
import com.eip.core.error.ResourceNotFoundException;
import com.eip.core.error.ValidationException;
import com.eip.ingestion.api.ManageConnectorsUseCase.ConnectorAdminView;
import com.eip.ingestion.api.ManageConnectorsUseCase.RegisterConnectorCommand;
import com.eip.ingestion.api.ManageConnectorsUseCase.TestConnectionResult;
import com.eip.ingestion.application.ConnectorAdminService;
import com.eip.ingestion.persistence.ConnectorAdminRepository;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import com.eip.tenancy.secrets.EnvelopeCipher;
import com.eip.tenancy.secrets.SecretsService;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Module-level proof of connector administration against real PostgreSQL as the NOBYPASSRLS {@code
 * eip_app} role: catalog-validated registration with envelope-encrypted secrets, admin listing
 * without secret material, status lifecycle, HONEST connection tests (simulation OK, real types
 * NOT_AVAILABLE until M2), and tenant isolation.
 */
@Tag("integration")
class ConnectorAdminIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  private static ConnectorAdminService service;

  @BeforeAll
  static void setUp() throws SQLException {
    POSTGRES.start();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(
            "filesystem:"
                + Paths.get("../eip-app/src/main/resources/db/migration")
                    .toAbsolutePath()
                    .normalize())
        .load()
        .migrate();
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute("CREATE ROLE eip_app LOGIN PASSWORD 'eip_app_pw' NOBYPASSRLS");
      st.execute("GRANT USAGE ON SCHEMA core TO eip_app");
      st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA core TO eip_app");
      st.execute("INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT_A + "', 'A', 'a')");
      st.execute("INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT_B + "', 'B', 'b')");
    }
    PGSimpleDataSource app = new PGSimpleDataSource();
    app.setUrl(POSTGRES.getJdbcUrl());
    app.setUser("eip_app");
    app.setPassword("eip_app_pw");
    DataSource dataSource = app;
    TenantTransactionRunner runner =
        new TenantTransactionRunner(new JdbcTransactionManager(dataSource), dataSource);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    ObjectMapper mapper = new ObjectMapper();
    service =
        new ConnectorAdminService(
            runner,
            new ConnectorAdminRepository(jdbc, mapper),
            new SecretsService(jdbc, EnvelopeCipher.newDek(), 1),
            new SimulationConnector(),
            mapper);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @AfterEach
  void clearHolder() {
    TenantContextHolder.clear();
  }

  @Test
  void registers_lists_toggles_and_tests_honestly_with_tenant_isolation() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    assertThat(service.types())
        .extracting("type")
        .containsExactly("simulation", "jira", "bitbucket", "sonarqube");

    ConnectorAdminView jira =
        service.register(
            new RegisterConnectorCommand(
                "jira",
                "Acme Jira",
                Map.of("baseUrl", "https://acme.atlassian.net", "email", "svc@acme.io"),
                "token-123"));
    assertThat(jira.hasSecret()).isTrue();
    assertThat(jira.status()).isEqualTo("CONFIGURED");

    // Validation fails closed: unknown type, missing required config, missing secret.
    assertThatThrownBy(
            () -> service.register(new RegisterConnectorCommand("nope", "x", Map.of(), null)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> service.register(new RegisterConnectorCommand("jira", "x", Map.of(), "t")))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                service.register(
                    new RegisterConnectorCommand(
                        "jira", "x", Map.of("baseUrl", "https://x", "email", "a@b.c"), null)))
        .isInstanceOf(ValidationException.class);

    ConnectorAdminView sim =
        service.register(new RegisterConnectorCommand("simulation", "Sample", Map.of(), null));

    // Honest connection tests.
    TestConnectionResult jiraTest = service.test(jira.id());
    assertThat(jiraTest.outcome()).isEqualTo("NOT_AVAILABLE");
    assertThat(jiraTest.message()).contains("DEBT-018");
    assertThat(service.test(sim.id()).outcome()).isEqualTo("OK");

    // Status lifecycle + invalid status fails closed.
    assertThat(service.setStatus(jira.id(), "DISABLED").status()).isEqualTo("DISABLED");
    assertThatThrownBy(() -> service.setStatus(jira.id(), "BROKEN"))
        .isInstanceOf(ValidationException.class);

    assertThat(service.list()).hasSize(2);

    // Tenant B: sees nothing, cannot touch tenant A's connector.
    TenantContextHolder.set(TenantContext.of(TENANT_B));
    assertThat(service.list()).isEmpty();
    assertThatThrownBy(() -> service.setStatus(jira.id(), "ACTIVE"))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> service.test(jira.id())).isInstanceOf(ResourceNotFoundException.class);
  }
}
