/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.application;

import com.eip.ai.api.AiDisabledException;
import com.eip.ai.api.ExplainInsightsUseCase;
import com.eip.ai.api.ExplanationView;
import com.eip.ai.api.LlmUnavailableException;
import com.eip.ai.api.NarrateReportUseCase;
import com.eip.ai.api.NarrativeRejectedException;
import com.eip.ai.api.ReportNarrativeInput;
import com.eip.ai.application.PromptComposer.ComposedPrompt;
import com.eip.ai.persistence.LlmCallAuditRepository;
import com.eip.ai.persistence.TenantAiPolicyRepository;
import com.eip.ai.persistence.TenantAiPolicyRepository.Row;
import com.eip.ai.providers.LlmClientFactory;
import com.eip.ai.spi.LlmClient;
import com.eip.ai.spi.LlmRequest;
import com.eip.ai.spi.LlmResponse;
import com.eip.analytics.api.FrictionSummaryView;
import com.eip.analytics.api.GetFrictionSummaryQuery;
import com.eip.analytics.api.GetMetricTrendsQuery;
import com.eip.analytics.api.GetRecommendationsQuery;
import com.eip.analytics.api.TeamRecommendationsView;
import com.eip.analytics.api.TrendsView;
import com.eip.core.error.ValidationException;
import com.eip.tenancy.secrets.SecretsService;
import com.eip.tenancy.tx.TenantTransactionRunner;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Orchestrates {@link ExplainInsightsUseCase#explain} and {@link NarrateReportUseCase#narrate}
 * (ADR-024): loads the tenant's policy and reveals its secret in one short read transaction, fails
 * closed with {@link AiDisabledException} if the layer is off, composes the prompt from the same
 * deterministic reads the dashboard/report shows ({@link PromptComposer}), calls the selected
 * {@link LlmClient} OUTSIDE any transaction (a remote call must never hold a connection open —
 * {@code TenantTransactionRunner}'s own contract), verifies every number the narrative cites
 * against the prompt's whitelist ({@link NumericCrossChecker}), and — in a {@code finally} block,
 * so it runs on every outcome including a provider failure or a rejected narrative — writes exactly
 * one hash-only {@code ai.llm_call_audit} row in its own transaction and increments the {@code
 * eip.ai.calls{purpose,status}} counter.
 */
@Service
public class ExplainService implements ExplainInsightsUseCase, NarrateReportUseCase {

  /** Minimum accepted trend window width, in weeks (mirrors {@code GetMetricTrendsQuery}). */
  static final int MIN_WEEKS = 4;

  /** Maximum accepted trend window width, in weeks. */
  static final int MAX_WEEKS = 52;

  private static final String PURPOSE_EXPLAIN = "EXPLAIN_DASHBOARD";
  private static final String PURPOSE_NARRATE = "REPORT_NARRATIVE";
  private static final String STATUS_OK = "OK";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_REJECTED = "REJECTED";
  private static final String COUNTER_NAME = "eip.ai.calls";

  private final TenantTransactionRunner tx;
  private final TenantAiPolicyRepository policyRepository;
  private final SecretsService secrets;
  private final LlmCallAuditRepository auditRepository;
  private final LlmClientFactory clientFactory;
  private final GetMetricTrendsQuery trendsQuery;
  private final GetRecommendationsQuery recommendationsQuery;
  private final GetFrictionSummaryQuery summaryQuery;
  private final MeterRegistry meterRegistry;

  /**
   * Creates the service.
   *
   * @param tx the tenant-bound transaction runner
   * @param policyRepository the policy store
   * @param secrets the secret store (provider API key reveal)
   * @param auditRepository the call-audit store
   * @param clientFactory selects the {@link LlmClient} for the tenant's configured provider
   * @param trendsQuery the metric-trends query port
   * @param recommendationsQuery the recommendations query port
   * @param summaryQuery the friction-summary query port
   * @param meterRegistry the Micrometer registry ({@code eip.ai.calls} counter)
   */
  public ExplainService(
      TenantTransactionRunner tx,
      TenantAiPolicyRepository policyRepository,
      SecretsService secrets,
      LlmCallAuditRepository auditRepository,
      LlmClientFactory clientFactory,
      GetMetricTrendsQuery trendsQuery,
      GetRecommendationsQuery recommendationsQuery,
      GetFrictionSummaryQuery summaryQuery,
      MeterRegistry meterRegistry) {
    this.tx = tx;
    this.policyRepository = policyRepository;
    this.secrets = secrets;
    this.auditRepository = auditRepository;
    this.clientFactory = clientFactory;
    this.trendsQuery = trendsQuery;
    this.recommendationsQuery = recommendationsQuery;
    this.summaryQuery = summaryQuery;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public ExplanationView explain(int weeks) {
    if (weeks < MIN_WEEKS || weeks > MAX_WEEKS) {
      throw new ValidationException(
          "weeks must be between " + MIN_WEEKS + " and " + MAX_WEEKS + ", was: " + weeks);
    }
    Loaded loaded = loadPolicyAndSecret();
    TrendsView trends = trendsQuery.trends(weeks);
    List<TeamRecommendationsView> recommendations = recommendationsQuery.recommendations();
    FrictionSummaryView summary = summaryQuery.summary();
    ComposedPrompt composed =
        PromptComposer.composeExplain(summary, trends, recommendations, weeks);
    return run(loaded, PURPOSE_EXPLAIN, composed);
  }

  @Override
  public ExplanationView narrate(ReportNarrativeInput input) {
    Loaded loaded = loadPolicyAndSecret();
    ComposedPrompt composed = PromptComposer.composeNarrate(input);
    return run(loaded, PURPOSE_NARRATE, composed);
  }

  private Loaded loadPolicyAndSecret() {
    return tx.readCurrent(
        () -> {
          Row row =
              policyRepository
                  .find()
                  .filter(Row::enabled)
                  .orElseThrow(
                      () ->
                          new AiDisabledException(
                              "AI explanations are not enabled for this tenant"));
          @Nullable String secret = row.secretId() == null ? null : secrets.reveal(row.secretId());
          return new Loaded(row, secret);
        });
  }

  private ExplanationView run(Loaded loaded, String purpose, ComposedPrompt composed) {
    Row policy = loaded.policy();
    String provider =
        Objects.requireNonNull(policy.provider(), "enabled policy must have a provider");
    String model = Objects.requireNonNull(policy.model(), "enabled policy must have a model");
    String baseUrl = Objects.requireNonNull(policy.baseUrl(), "enabled policy must have a baseUrl");
    LlmRequest request =
        new LlmRequest(
            composed.systemPrompt(),
            composed.userPrompt(),
            model,
            policy.temperature(),
            policy.maxTokens());
    LlmClient client = clientFactory.forPolicy(provider, baseUrl, loaded.secret());

    long start = System.nanoTime();
    String status = STATUS_FAILED;
    @Nullable String errorMessage = null;
    @Nullable LlmResponse response = null;
    try {
      response = client.complete(request);
      Set<String> cited = NumericCrossChecker.verify(response.text(), composed.numericWhitelist());
      status = STATUS_OK;
      return new ExplanationView(
          response.text(),
          provider,
          model,
          List.copyOf(new TreeSet<>(cited)),
          Instant.now(),
          ExplanationView.DISCLAIMER);
    } catch (LlmUnavailableException e) {
      status = STATUS_FAILED;
      errorMessage = e.getMessage();
      throw e;
    } catch (NarrativeRejectedException e) {
      status = STATUS_REJECTED;
      errorMessage = e.getMessage();
      throw e;
    } finally {
      long latencyMs =
          response != null ? response.latencyMs() : (System.nanoTime() - start) / 1_000_000;
      writeAudit(
          purpose,
          provider,
          model,
          composed.userPrompt(),
          response,
          status,
          errorMessage,
          (int) latencyMs);
      meterRegistry.counter(COUNTER_NAME, "purpose", purpose, "status", status).increment();
    }
  }

  private void writeAudit(
      String purpose,
      String provider,
      String model,
      String userPrompt,
      @Nullable LlmResponse response,
      String status,
      @Nullable String error,
      int latencyMs) {
    String promptSha256 = sha256(userPrompt);
    int promptChars = userPrompt.length();
    @Nullable String responseSha256 = response == null ? null : sha256(response.text());
    @Nullable Integer responseChars = response == null ? null : response.text().length();
    tx.callCurrent(
        () -> {
          auditRepository.insert(
              purpose,
              provider,
              model,
              promptSha256,
              promptChars,
              responseSha256,
              responseChars,
              latencyMs,
              status,
              error);
          return Boolean.TRUE;
        });
  }

  private static String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available on this JVM", e);
    }
  }

  private record Loaded(Row policy, @Nullable String secret) {}
}
