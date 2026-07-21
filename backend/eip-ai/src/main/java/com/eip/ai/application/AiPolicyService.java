/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.application;

import com.eip.ai.api.AiPolicyView;
import com.eip.ai.api.GetAiStatusQuery;
import com.eip.ai.api.ManageAiPolicyUseCase;
import com.eip.ai.persistence.TenantAiPolicyRepository;
import com.eip.ai.persistence.TenantAiPolicyRepository.Row;
import com.eip.core.error.ValidationException;
import com.eip.tenancy.secrets.SecretsService;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Reads and updates the current tenant's AI policy (ADR-024). The provider API key goes through
 * {@link SecretsService#store}, the same envelope-encrypting store connector registration uses
 * ({@code com.eip.ingestion.application.ConnectorAdminService}) — never returned or re-readable
 * through this or any other API, only revealed in-process by {@link ExplainService} immediately
 * before a provider call.
 */
@Service
public class AiPolicyService implements ManageAiPolicyUseCase, GetAiStatusQuery {

  private static final Set<String> ALLOWED_PROVIDERS = Set.of("ollama", "openai-compatible");
  private static final AiPolicyView DEFAULT_VIEW =
      new AiPolicyView(false, null, null, null, false, 0.0, 800);

  private final TenantTransactionRunner tx;
  private final TenantAiPolicyRepository repository;
  private final SecretsService secrets;

  /**
   * Creates the service.
   *
   * @param tx the tenant-bound transaction runner
   * @param repository the policy store
   * @param secrets the secret store (provider API keys)
   */
  public AiPolicyService(
      TenantTransactionRunner tx, TenantAiPolicyRepository repository, SecretsService secrets) {
    this.tx = tx;
    this.repository = repository;
    this.secrets = secrets;
  }

  @Override
  public AiPolicyView get() {
    return tx.readCurrent(() -> toView(repository.find()));
  }

  @Override
  public boolean enabled() {
    return tx.readCurrent(() -> repository.find().map(Row::enabled).orElse(false));
  }

  @Override
  public AiPolicyView update(UpdateAiPolicyCommand command) {
    return tx.callCurrent(
        () -> {
          Optional<Row> current = repository.find();
          @Nullable String provider =
              orCurrent(command.provider(), current.map(Row::provider).orElse(null));
          @Nullable String baseUrl =
              orCurrent(command.baseUrl(), current.map(Row::baseUrl).orElse(null));
          @Nullable String model = orCurrent(command.model(), current.map(Row::model).orElse(null));
          double temperature =
              command.temperature() != null
                  ? command.temperature()
                  : current.map(Row::temperature).orElse(0.0);
          int maxTokens =
              command.maxTokens() != null
                  ? command.maxTokens()
                  : current.map(Row::maxTokens).orElse(800);
          @Nullable UUID secretId = current.map(Row::secretId).orElse(null);
          if (command.secret() != null && !command.secret().isBlank()) {
            secretId = secrets.store("ai:policy", command.secret());
          }

          if (command.enabled()) {
            validateEnabled(provider, baseUrl, model, secretId);
          }

          repository.upsert(
              command.enabled(), provider, baseUrl, model, secretId, temperature, maxTokens);
          return new AiPolicyView(
              command.enabled(),
              provider,
              baseUrl,
              model,
              secretId != null,
              temperature,
              maxTokens);
        });
  }

  private static void validateEnabled(
      @Nullable String provider,
      @Nullable String baseUrl,
      @Nullable String model,
      @Nullable UUID secretId) {
    if (isBlank(provider) || isBlank(baseUrl) || isBlank(model)) {
      throw new ValidationException("enabling AI requires provider, baseUrl, and model");
    }
    if (!ALLOWED_PROVIDERS.contains(provider)) {
      throw new ValidationException("provider must be one of " + ALLOWED_PROVIDERS);
    }
    if ("openai-compatible".equals(provider) && secretId == null) {
      throw new ValidationException("the openai-compatible provider requires a secret (API key)");
    }
  }

  private static boolean isBlank(@Nullable String value) {
    return value == null || value.isBlank();
  }

  private static @Nullable String orCurrent(@Nullable String supplied, @Nullable String current) {
    return supplied != null ? supplied : current;
  }

  private static AiPolicyView toView(Optional<Row> row) {
    return row.map(
            r ->
                new AiPolicyView(
                    r.enabled(),
                    r.provider(),
                    r.baseUrl(),
                    r.model(),
                    r.secretId() != null,
                    r.temperature(),
                    r.maxTokens()))
        .orElse(DEFAULT_VIEW);
  }
}
