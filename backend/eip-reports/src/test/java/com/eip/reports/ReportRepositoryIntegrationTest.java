/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.core.domain.DefaultUuidV7Generator;
import com.eip.core.error.ResourceNotFoundException;
import com.eip.core.error.ValidationException;
import com.eip.reports.api.ReportDocument;
import com.eip.reports.api.ReportDocumentView;
import com.eip.reports.api.ReportPageView;
import com.eip.reports.api.ReportTotals;
import com.eip.reports.api.ReportView;
import com.eip.reports.persistence.ReportRepository;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
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
 * Module-level proof of {@link ReportRepository} against real PostgreSQL as the NOBYPASSRLS {@code
 * eip_app} role, migrated with the app's Flyway scripts (filesystem path — single schema source),
 * proving V6 ({@code reports.generated_report}) plus {@code R__rls_policies} apply and that
 * insert/list/find round-trip the document correctly. No Spring context: the repository is
 * constructed directly, mirroring {@code TrendsAndInsightsIntegrationTest}'s harness.
 */
@Tag("integration")
class ReportRepositoryIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  private static ReportRepository repository;

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
      for (String schema : new String[] {"core", "reports"}) {
        st.execute("GRANT USAGE ON SCHEMA " + schema + " TO eip_app");
        st.execute(
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                + schema
                + " TO eip_app");
      }
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
    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    repository =
        new ReportRepository(runner, jdbc, mapper, new DefaultUuidV7Generator(Clock.systemUTC()));
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
  void inserts_lists_and_finds_a_report_scoped_to_its_own_tenant() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    ReportDocument document = document("Alpha report");
    ReportView inserted =
        repository.insert("EXEC_SUMMARY", "deterministic/exec-summary-v1", document);

    assertThat(inserted.type()).isEqualTo("EXEC_SUMMARY");
    assertThat(inserted.title()).isEqualTo("Alpha report");
    assertThat(inserted.status()).isEqualTo("READY");
    assertThat(inserted.weeks()).isEqualTo(document.weeks());
    assertThat(inserted.periodStart()).isEqualTo(document.periodStart());
    assertThat(inserted.periodEnd()).isEqualTo(document.periodEnd());
    assertThat(inserted.completedAt()).isEqualTo(document.generatedAt());
    assertThat(inserted.createdAt()).isNotNull();

    ReportDocumentView found = repository.get(inserted.id());
    assertThat(found.report().id()).isEqualTo(inserted.id());
    assertThat(found.document().title()).isEqualTo("Alpha report");
    assertThat(found.document().totals().itemsResolved())
        .isEqualTo(document.totals().itemsResolved());
    assertThat(found.document().teams()).hasSize(1);
    assertThat(found.document().teams().get(0).teamName()).isEqualTo("Alpha");

    String html = repository.html(inserted.id());
    assertThat(html).contains("Alpha report").contains("Alpha");

    ReportPageView page = repository.list(null, 20);
    assertThat(page.items()).extracting(ReportView::id).contains(inserted.id());
    assertThat(page.hasMore()).isFalse();

    // RLS: tenant B's read never sees tenant A's report — 404, not leaked existence.
    TenantContextHolder.set(TenantContext.of(TENANT_B));
    assertThatThrownBy(() -> repository.get(inserted.id()))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThat(repository.list(null, 20).items()).isEmpty();
  }

  @Test
  void unknown_id_is_reported_not_found() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));
    assertThatThrownBy(() -> repository.get(UUID.randomUUID()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void list_paginates_by_created_at_desc_then_id_with_a_cursor() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));
    UUID first =
        repository.insert("EXEC_SUMMARY", "deterministic/exec-summary-v1", document("R1")).id();
    UUID second =
        repository.insert("EXEC_SUMMARY", "deterministic/exec-summary-v1", document("R2")).id();
    UUID third =
        repository.insert("EXEC_SUMMARY", "deterministic/exec-summary-v1", document("R3")).id();

    ReportPageView page1 = repository.list(null, 2);
    assertThat(page1.items()).hasSize(2);
    assertThat(page1.hasMore()).isTrue();
    assertThat(page1.nextCursor()).isNotNull();
    // Newest first: the three most-recently-inserted reports created in this test, third last in.
    assertThat(page1.items()).extracting(ReportView::id).containsExactly(third, second);

    ReportPageView page2 = repository.list(page1.nextCursor(), 2);
    assertThat(page2.items()).extracting(ReportView::id).contains(first);
    assertThat(page2.hasMore()).isFalse();
  }

  @Test
  void a_malformed_cursor_is_rejected_as_a_validation_failure() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));
    assertThatThrownBy(() -> repository.list("not-a-real-cursor!!", 10))
        .isInstanceOf(ValidationException.class);
  }

  private static ReportDocument document(String title) {
    ReportTotals totals = new ReportTotals(1, 3, 100L, 200L, 40, 10, 20, 55);
    Instant periodStart = Instant.parse("2026-01-05T00:00:00Z");
    Instant periodEnd = Instant.parse("2026-01-19T00:00:00Z");
    return new ReportDocument(
        ReportDocument.CURRENT_VERSION,
        title,
        periodStart,
        periodEnd,
        8,
        Instant.now(),
        "engineering_friction_v0.1",
        totals,
        java.util.List.of(
            new com.eip.reports.api.ReportTeamSection(
                UUID.randomUUID(),
                "Alpha",
                55,
                "REVIEW_WAIT",
                java.util.List.of(),
                java.util.List.of(),
                0,
                0)));
  }
}
