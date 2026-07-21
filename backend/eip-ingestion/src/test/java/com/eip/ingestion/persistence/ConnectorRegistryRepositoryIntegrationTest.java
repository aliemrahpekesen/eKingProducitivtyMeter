/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Module-level proof of {@link ConnectorRegistryRepository#ensureConnector} against real PostgreSQL
 * as the NOBYPASSRLS {@code eip_app} role (mirrors {@code
 * ConnectorCheckpointRepositoryIntegrationTest}'s harness — no Spring context, every component
 * hand-built): the partial unique index {@code ux_connector_simulation_type} ({@code
 * V8__connector_unique_constraint.sql}) plus the {@code ON CONFLICT ... DO UPDATE} self-no-op
 * upsert make concurrent {@code ensureConnector} calls for the same tenant+type resolve to the SAME
 * row (DEBT-020 item 6) instead of racing two inserts past each other, and a repeat call after the
 * row exists returns that same id without creating a duplicate.
 */
@Tag("integration")
class ConnectorRegistryRepositoryIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT_A = UUID.randomUUID();

  private static TenantTransactionRunner runner;
  private static ConnectorRegistryRepository registry;
  private static JdbcClient jdbc;

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
    }

    PGSimpleDataSource app = new PGSimpleDataSource();
    app.setUrl(POSTGRES.getJdbcUrl());
    app.setUser("eip_app");
    app.setPassword("eip_app_pw");
    DataSource dataSource = app;

    runner = new TenantTransactionRunner(new JdbcTransactionManager(dataSource), dataSource);
    jdbc = JdbcClient.create(dataSource);
    registry = new ConnectorRegistryRepository(jdbc);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void a_repeat_call_after_the_row_exists_returns_the_same_id_never_a_duplicate() {
    TenantContext tenant = TenantContext.of(TENANT_A);

    UUID first = runner.call(tenant, () -> registry.ensureConnector("simulation", true));
    UUID second = runner.call(tenant, () -> registry.ensureConnector("simulation", true));

    assertThat(second).isEqualTo(first);
    assertThat(simulationConnectorCount("simulation")).isEqualTo(1L);
  }

  @Test
  void concurrent_ensureConnector_calls_for_the_same_tenant_and_type_resolve_to_one_row()
      throws Exception {
    String type = "concurrent-probe";
    int callers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Callable<UUID>> tasks =
          IntStream.range(0, callers)
              .<Callable<UUID>>mapToObj(
                  i ->
                      () -> {
                        ready.countDown();
                        go.await();
                        return runner.call(
                            TenantContext.of(TENANT_A), () -> registry.ensureConnector(type, true));
                      })
              .toList();

      List<Future<UUID>> futures = new ArrayList<>();
      for (Callable<UUID> task : tasks) {
        futures.add(pool.submit(task));
      }
      ready.await(10, TimeUnit.SECONDS);
      go.countDown(); // release every caller at once to maximize overlap

      Set<UUID> ids = new HashSet<>();
      for (Future<UUID> future : futures) {
        ids.add(future.get(30, TimeUnit.SECONDS));
      }

      // Every concurrent caller must have resolved to the exact same connector row.
      assertThat(ids).hasSize(1);
      assertThat(simulationConnectorCount(type)).isEqualTo(1L);
    } finally {
      pool.shutdownNow();
    }
  }

  private long simulationConnectorCount(String type) {
    return runner.read(
        TenantContext.of(TENANT_A),
        () ->
            jdbc.sql(
                    "SELECT count(*) FROM core.connector WHERE type = :type AND simulation = true"
                        + " AND deleted_at IS NULL")
                .param("type", type)
                .query(Long.class)
                .single());
  }
}
