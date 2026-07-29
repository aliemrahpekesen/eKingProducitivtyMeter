/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.ai.api.AiDisabledException;
import com.eip.ai.api.ExplanationView;
import com.eip.ai.api.LlmUnavailableException;
import com.eip.ai.api.ManageAiPolicyUseCase.UpdateAiPolicyCommand;
import com.eip.ai.api.NarrativeRejectedException;
import com.eip.ai.api.ReportNarrativeInput;
import com.eip.ai.api.ReportNarrativeTeam;
import com.eip.ai.api.ReportNarrativeTotals;
import com.eip.ai.application.AiPolicyService;
import com.eip.ai.application.ExplainService;
import com.eip.ai.persistence.LlmCallAuditRepository;
import com.eip.ai.persistence.TenantAiPolicyRepository;
import com.eip.ai.providers.LlmClientFactory;
import com.eip.core.domain.DefaultUuidV7Generator;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import com.eip.tenancy.secrets.EnvelopeCipher;
import com.eip.tenancy.secrets.SecretsService;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Module-level proof of {@link ExplainService}'s orchestration against real PostgreSQL (V7 {@code
 * ai.llm_call_audit}) and a WireMock'd Ollama endpoint standing in for "a fake {@code LlmClient}"
 * (ExplainService talks to {@link LlmClientFactory}, a concrete class selecting a real provider
 * client — stubbing the HTTP endpoint it calls is the seam that lets a test control its output
 * without changing production wiring). Exercised through {@code narrate} only: it shares the exact
 * same {@code run(...)} orchestration {@code explain} does, and needs no analytics query port
 * fakes.
 */
@Tag("integration")
class ExplainServiceIntegrationTest {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(
          DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  private static final UUID TENANT = UUID.randomUUID();

  private static AiPolicyService policyService;
  private static ExplainService explainService;
  private static DataSource dataSource;

  private final WireMockServer llm =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

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
      for (String schema : new String[] {"core", "ai"}) {
        st.execute("GRANT USAGE ON SCHEMA " + schema + " TO eip_app");
        st.execute(
            "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                + schema
                + " TO eip_app");
      }
      st.execute("INSERT INTO core.tenant (id, name, slug) VALUES ('" + TENANT + "', 'T', 't')");
    }

    PGSimpleDataSource app = new PGSimpleDataSource();
    app.setUrl(POSTGRES.getJdbcUrl());
    app.setUser("eip_app");
    app.setPassword("eip_app_pw");
    dataSource = app;

    TenantTransactionRunner runner =
        new TenantTransactionRunner(new JdbcTransactionManager(dataSource), dataSource);
    JdbcClient jdbc = JdbcClient.create(dataSource);
    TenantAiPolicyRepository policyRepository = new TenantAiPolicyRepository(jdbc);
    SecretsService secrets = new SecretsService(jdbc, EnvelopeCipher.newDek(), 1);
    policyService = new AiPolicyService(runner, policyRepository, secrets);
    explainService =
        new ExplainService(
            runner,
            policyRepository,
            secrets,
            new LlmCallAuditRepository(jdbc, new DefaultUuidV7Generator(Clock.systemUTC())),
            new LlmClientFactory(),
            weeks -> {
              throw new UnsupportedOperationException("not exercised via narrate()");
            },
            List::of,
            () -> {
              throw new UnsupportedOperationException("not exercised via narrate()");
            },
            new SimpleMeterRegistry());
  }

  @AfterAll
  static void stopContainer() {
    POSTGRES.stop();
  }

  @BeforeEach
  void startWireMockAndConfigurePolicy() {
    llm.start();
    TenantContextHolder.set(TenantContext.of(TENANT));
    policyService.update(
        new UpdateAiPolicyCommand(true, "ollama", llm.baseUrl(), "llama3", null, 0.2, 500));
  }

  @AfterEach
  void stopWireMockAndClearHolder() {
    llm.stop();
    TenantContextHolder.clear();
  }

  @Test
  void a_disabled_tenant_is_refused_before_any_provider_call() {
    policyService.update(new UpdateAiPolicyCommand(false, null, null, null, null, null, null));

    assertThatThrownBy(() -> explainService.narrate(narrativeInput()))
        .isInstanceOf(AiDisabledException.class);
  }

  @Test
  void a_narrative_using_only_source_numbers_is_returned_and_audited_ok() {
    stubLlm(
        "The team Alpha has a friction score of 85, driven by review wait. Out of 10 items"
            + " resolved, average cycle time is about 8 hours (p85 12 hours), with 45% flow efficiency,"
            + " 18% blocked and 37% review wait.");

    ExplanationView view = explainService.narrate(narrativeInput());

    assertThat(view.narrative()).contains("85");
    assertThat(view.provider()).isEqualTo("ollama");
    assertThat(view.disclaimer()).isEqualTo(ExplanationView.DISCLAIMER);
    assertThat(latestAuditStatus()).isEqualTo("OK");
  }

  @Test
  void a_narrative_citing_an_invented_number_is_rejected_and_audited() {
    stubLlm("The friction score is 85, though it might spike to 999 next quarter.");

    assertThatThrownBy(() -> explainService.narrate(narrativeInput()))
        .isInstanceOf(NarrativeRejectedException.class);

    assertThat(latestAuditStatus()).isEqualTo("REJECTED");
  }

  @Test
  void a_provider_failure_is_surfaced_and_audited() {
    llm.stubFor(post(urlPathEqualTo("/api/chat")).willReturn(aResponse().withStatus(503)));

    assertThatThrownBy(() -> explainService.narrate(narrativeInput()))
        .isInstanceOf(LlmUnavailableException.class);

    assertThat(latestAuditStatus()).isEqualTo("FAILED");
  }

  private void stubLlm(String narrativeText) {
    Map<String, Object> body =
        Map.of("model", "llama3", "message", Map.of("role", "assistant", "content", narrativeText));
    String json;
    try {
      json = new ObjectMapper().writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to build the WireMock stub body", e);
    }
    llm.stubFor(
        post(urlPathEqualTo("/api/chat"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(json)));
  }

  private static ReportNarrativeInput narrativeInput() {
    ReportNarrativeTotals totals = new ReportNarrativeTotals(1, 10, 28800, 43200, 45, 18, 37, 85);
    ReportNarrativeTeam team =
        new ReportNarrativeTeam(
            UUID.randomUUID(), "Alpha", 85, "REVIEW_WAIT", List.of(), List.of());
    return new ReportNarrativeInput(
        UUID.randomUUID(),
        "Engineering Flow Report",
        Instant.parse("2026-01-05T00:00:00Z"),
        Instant.parse("2026-01-19T00:00:00Z"),
        8,
        totals,
        List.of(team));
  }

  private static String latestAuditStatus() {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT status FROM ai.llm_call_audit WHERE tenant_id = '"
                    + TENANT
                    + "' ORDER BY created_at DESC LIMIT 1")) {
      if (!rs.next()) {
        throw new IllegalStateException("no audit row found for tenant " + TENANT);
      }
      return rs.getString(1);
    } catch (SQLException e) {
      throw new IllegalStateException("failed to read audit row", e);
    }
  }
}
