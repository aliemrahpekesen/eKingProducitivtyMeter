/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import com.eip.connectors.spi.Connector;
import com.eip.connectors.spi.ConnectorConfig;
import com.eip.connectors.spi.ConnectorDescriptor;
import com.eip.connectors.spi.TestConnectionOutcome;
import com.eip.core.error.ResourceNotFoundException;
import com.eip.core.error.ValidationException;
import com.eip.ingestion.api.ManageConnectorsUseCase;
import com.eip.ingestion.persistence.ConnectorAdminRepository;
import com.eip.tenancy.secrets.SecretsService;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Connector administration for the current tenant. The catalog is descriptor-driven (DEBT-018):
 * {@link #types()} asks the {@link ConnectorRegistry} for every INSTALLED connector's {@link
 * ConnectorDescriptor}, sorted by type for a deterministic listing — seven types ship today
 * (simulation, Jira, Bitbucket, SonarQube, GitHub, GitLab, Jenkins), and a newly installed
 * connector appears automatically, with no separate catalog edit. Registration validates the type
 * against that same registry and the config against the descriptor's schema-required keys; secrets
 * go through the envelope-encrypting {@code SecretsService} and are never readable through any
 * admin surface. {@code test} is honest: every installed type probes its real source; {@code
 * NOT_AVAILABLE} is reported only when no connector implementation is installed for the type, never
 * a fake OK.
 */
@Service
public class ConnectorAdminService implements ManageConnectorsUseCase {

  private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "DISABLED");

  private final TenantTransactionRunner tx;
  private final ConnectorAdminRepository repository;
  private final SecretsService secrets;
  private final ConnectorRegistry registry;
  private final ObjectMapper mapper;

  public ConnectorAdminService(
      TenantTransactionRunner tx,
      ConnectorAdminRepository repository,
      SecretsService secrets,
      ConnectorRegistry registry,
      ObjectMapper mapper) {
    this.tx = tx;
    this.repository = repository;
    this.secrets = secrets;
    this.registry = registry;
    this.mapper = mapper;
  }

  @Override
  public List<ConnectorTypeView> types() {
    // Descriptor-driven (DEBT-018): every INSTALLED connector describes itself; syncAvailable
    // reflects that same installed implementation, never a hand-maintained catalog entry.
    return registry.all().stream()
        .sorted(Comparator.comparing(Connector::type))
        .map(
            c -> {
              ConnectorDescriptor d = c.descriptor();
              return new ConnectorTypeView(
                  d.type(),
                  d.displayName(),
                  d.description(),
                  d.configSchema(),
                  d.secretLabel(),
                  c.syncAvailable());
            })
        .toList();
  }

  @Override
  public ConnectorAdminView register(RegisterConnectorCommand command) {
    Connector connector =
        registry
            .byType(command.type())
            .orElseThrow(
                () -> new ValidationException("unknown connector type: " + command.type()));
    ConnectorDescriptor type = connector.descriptor();
    if (command.name().isBlank()) {
      throw new ValidationException("connector name must not be blank");
    }
    requireSchemaRequiredKeys(type.configSchema(), command.config());
    if (type.secretLabel() != null && (command.secret() == null || command.secret().isBlank())) {
      throw new ValidationException(
          type.displayName() + " requires a secret (" + type.secretLabel() + ")");
    }

    return tx.callCurrent(
        () -> {
          UUID secretId =
              (type.secretLabel() != null && command.secret() != null)
                  ? secrets.store(
                      "connector:" + command.type() + ":" + command.name(), command.secret())
                  : null;
          return repository.insert(
              command.type(),
              command.name().trim(),
              Map.copyOf(command.config()),
              secretId,
              command.type().equals("simulation"));
        });
  }

  @Override
  public List<ConnectorAdminView> list() {
    return tx.readCurrent(repository::list);
  }

  @Override
  public ConnectorAdminView setStatus(UUID connectorId, String status) {
    if (!ALLOWED_STATUSES.contains(status)) {
      throw new ValidationException("status must be one of " + ALLOWED_STATUSES);
    }
    return tx.callCurrent(
        () -> {
          if (repository.updateStatus(connectorId, status) == 0) {
            throw new ResourceNotFoundException("connector not found");
          }
          return repository
              .find(connectorId)
              .orElseThrow(() -> new ResourceNotFoundException("connector not found"));
        });
  }

  @Override
  public TestConnectionResult test(UUID connectorId) {
    // Resolve config + reveal the secret in a short transaction; probe the source OUTSIDE it.
    var resolved =
        tx.readCurrent(
            () -> {
              var row =
                  repository
                      .findWithSecret(connectorId)
                      .orElseThrow(() -> new ResourceNotFoundException("connector not found"));
              String secret = row.secretId() == null ? null : secrets.reveal(row.secretId());
              return java.util.Map.entry(
                  row.view(), new ConnectorConfig(row.view().config(), secret));
            });
    TestConnectionOutcome outcome =
        registry
            .byType(resolved.getKey().type())
            .map(c -> c.testConnection(resolved.getValue()))
            .orElse(
                TestConnectionOutcome.notAvailable(
                    "no connector implementation installed for " + resolved.getKey().type()));
    return new TestConnectionResult(outcome.outcome(), outcome.message());
  }

  private void requireSchemaRequiredKeys(String configSchema, Map<String, String> config) {
    try {
      JsonNode required = mapper.readTree(configSchema).path("required");
      for (JsonNode key : required) {
        String k = key.asText();
        if (config.get(k) == null || config.get(k).isBlank()) {
          throw new ValidationException("missing required config field: " + k);
        }
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("descriptor schema is not valid JSON", e);
    }
  }
}
