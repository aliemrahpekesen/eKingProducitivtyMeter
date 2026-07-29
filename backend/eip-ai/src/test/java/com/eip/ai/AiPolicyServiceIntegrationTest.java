/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.ai.api.AiPolicyView;
import com.eip.ai.api.ManageAiPolicyUseCase.UpdateAiPolicyCommand;
import com.eip.ai.application.AiPolicyService;
import com.eip.ai.persistence.TenantAiPolicyRepository;
import com.eip.core.error.ValidationException;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import com.eip.tenancy.secrets.EnvelopeCipher;
import com.eip.tenancy.secrets.SecretsService;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Module-level proof of {@link AiPolicyService} against real PostgreSQL as the NOBYPASSRLS {@code
 * eip_app} role, migrated with the app's Flyway scripts (filesystem path — single schema source),
 * proving V7 ({@code core.tenant_ai_policy}) plus {@code R__rls_policies} apply: default-disabled
 * read for a never-configured tenant, upsert semantics (null fields keep the current value),
 * validation (enabling requires provider/baseUrl/model; openai-compatible requires a secret), real
 * envelope-encrypted secret storage, and tenant isolation.
 */
@Tag("integration")
class AiPolicyServiceIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT_A = UUID.randomUUID();
  private static final UUID TENANT_B = UUID.randomUUID();

  private static AiPolicyService service;

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
    SecretsService secrets = new SecretsService(jdbc, EnvelopeCipher.newDek(), 1);
    service = new AiPolicyService(runner, new TenantAiPolicyRepository(jdbc), secrets);
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
  void a_never_configured_tenant_reads_a_disabled_default_view() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    AiPolicyView view = service.get();

    assertThat(view.enabled()).isFalse();
    assertThat(view.provider()).isNull();
    assertThat(view.hasSecret()).isFalse();
    assertThat(view.maxTokens()).isEqualTo(800);
    assertThat(service.enabled()).isFalse();
  }

  @Test
  void enabling_without_a_provider_base_url_or_model_is_rejected() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    assertThatThrownBy(
            () ->
                service.update(new UpdateAiPolicyCommand(true, null, null, null, null, null, null)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void enabling_openai_compatible_without_a_secret_is_rejected() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    assertThatThrownBy(
            () ->
                service.update(
                    new UpdateAiPolicyCommand(
                        true,
                        "openai-compatible",
                        "https://api.example.com",
                        "gpt-x",
                        null,
                        null,
                        null)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void configures_ollama_then_partially_updates_without_losing_unset_fields() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    AiPolicyView configured =
        service.update(
            new UpdateAiPolicyCommand(
                true, "ollama", "http://ollama.internal:11434", "llama3", null, 0.3, 600));
    assertThat(configured.enabled()).isTrue();
    assertThat(configured.provider()).isEqualTo("ollama");
    assertThat(configured.baseUrl()).isEqualTo("http://ollama.internal:11434");
    assertThat(configured.model()).isEqualTo("llama3");
    assertThat(configured.hasSecret()).isFalse();
    assertThat(configured.temperature()).isEqualTo(0.3);
    assertThat(configured.maxTokens()).isEqualTo(600);

    // Disabling with every other field null keeps provider/baseUrl/model/temperature/maxTokens.
    AiPolicyView disabled =
        service.update(new UpdateAiPolicyCommand(false, null, null, null, null, null, null));
    assertThat(disabled.enabled()).isFalse();
    assertThat(disabled.provider()).isEqualTo("ollama");
    assertThat(disabled.baseUrl()).isEqualTo("http://ollama.internal:11434");
    assertThat(disabled.model()).isEqualTo("llama3");
    assertThat(disabled.maxTokens()).isEqualTo(600);

    AiPolicyView reread = service.get();
    assertThat(reread.enabled()).isFalse();
    assertThat(reread.provider()).isEqualTo("ollama");
  }

  @Test
  void an_openai_compatible_secret_is_stored_but_never_revealed_through_the_view() {
    TenantContextHolder.set(TenantContext.of(TENANT_B));

    AiPolicyView view =
        service.update(
            new UpdateAiPolicyCommand(
                true,
                "openai-compatible",
                "https://api.example.com",
                "gpt-x",
                "sk-super-secret",
                null,
                null));

    assertThat(view.hasSecret()).isTrue();
    assertThat(view.toString()).doesNotContain("sk-super-secret");
  }

  @Test
  void policies_are_isolated_per_tenant() {
    UUID tenantC = UUID.randomUUID();
    UUID tenantD = UUID.randomUUID();
    insertTenant(tenantC, "C");
    insertTenant(tenantD, "D");

    TenantContextHolder.set(TenantContext.of(tenantC));
    service.update(new UpdateAiPolicyCommand(true, "ollama", "http://c", "llama3", null, 0.1, 400));

    TenantContextHolder.set(TenantContext.of(tenantD));
    // tenantD never configured a policy — its own default-disabled read must never observe
    // tenantC's write above, proving the RLS boundary, not just a Java-level filter.
    AiPolicyView tenantDView = service.get();
    assertThat(tenantDView.enabled()).isFalse();
    assertThat(tenantDView.provider()).isNull();

    TenantContextHolder.set(TenantContext.of(tenantC));
    assertThat(service.get().provider()).isEqualTo("ollama");
  }

  private static void insertTenant(UUID id, String label) {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "INSERT INTO core.tenant (id, name, slug) VALUES ('"
              + id
              + "', '"
              + label
              + "', '"
              + label.toLowerCase(java.util.Locale.ROOT)
              + "-"
              + id
              + "')");
    } catch (SQLException e) {
      throw new IllegalStateException("failed to insert test tenant", e);
    }
  }
}
