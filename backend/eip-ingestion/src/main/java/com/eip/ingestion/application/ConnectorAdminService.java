/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import com.eip.connectors.spi.Connector;
import com.eip.connectors.spi.ConnectorConfig;
import com.eip.connectors.spi.ConnectorDescriptor;
import com.eip.connectors.spi.HealthStatus;
import com.eip.connectors.spi.TestConnectionOutcome;
import com.eip.connectors.spi.ValidationResult;
import com.eip.core.error.ResourceNotFoundException;
import com.eip.core.error.ValidationException;
import com.eip.ingestion.api.ManageConnectorsUseCase;
import com.eip.ingestion.persistence.ConnectorAdminRepository;
import com.eip.tenancy.secrets.SecretsService;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Connector administration for the current tenant. The catalog is descriptor-driven (DEBT-018):
 * {@link #types()} asks the {@link ConnectorRegistry} for every INSTALLED connector's {@link
 * ConnectorDescriptor}, sorted by type for a deterministic listing — seven types ship today
 * (simulation, Jira, Bitbucket, SonarQube, GitHub, GitLab, Jenkins), and a newly installed
 * connector appears automatically, with no separate catalog edit. Registration delegates validation
 * entirely to the connector's own {@link Connector#validate(ConnectorConfig)} (ConnectorFramework
 * §3, DEBT-018 item 1) — pure schema/semantic checks against the descriptor's own {@code
 * configSchema}, no network — before any secret is stored or row persisted; secrets go through the
 * envelope-encrypting {@code SecretsService} and are never readable through any admin surface.
 * {@code test} is honest: every installed type probes its real source; {@code NOT_AVAILABLE} is
 * reported only when no connector implementation is installed for the type, never a fake OK. {@code
 * health} (DEBT-018 item 2) reuses the identical resolve-then-probe shape.
 */
@Service
public class ConnectorAdminService implements ManageConnectorsUseCase {

  private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "DISABLED");

  private final TenantTransactionRunner tx;
  private final ConnectorAdminRepository repository;
  private final SecretsService secrets;
  private final ConnectorRegistry registry;

  public ConnectorAdminService(
      TenantTransactionRunner tx,
      ConnectorAdminRepository repository,
      SecretsService secrets,
      ConnectorRegistry registry) {
    this.tx = tx;
    this.repository = repository;
    this.secrets = secrets;
    this.registry = registry;
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
    // Pure schema/semantic validation (ConnectorFramework §3, DEBT-018 item 1) — no network, and
    // no per-connector override needed since the default reads the connector's own descriptor.
    ValidationResult validation =
        connector.validate(new ConnectorConfig(command.config(), command.secret()));
    if (!validation.valid()) {
      throw new ValidationException(
          type.displayName()
              + " configuration is invalid: "
              + String.join("; ", validation.errors()));
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
    var resolved = resolveConfig(connectorId);
    Optional<Connector> connector = registry.byType(resolved.getKey().type());
    TestConnectionOutcome outcome;
    if (connector.isEmpty()) {
      outcome =
          TestConnectionOutcome.notAvailable(
              "no connector implementation installed for " + resolved.getKey().type());
    } else {
      try {
        outcome = connector.get().testConnection(resolved.getValue());
      } catch (IllegalArgumentException e) {
        // validate() gates new registrations (item 1), but the probe must stay defensive against a
        // row that predates that gate — a probe against a broken configuration IS a legitimate
        // FAILED result, never a server error.
        outcome =
            TestConnectionOutcome.failed(
                "connector configuration is incomplete: " + e.getMessage());
      }
    }
    return new TestConnectionResult(outcome.outcome(), outcome.message());
  }

  @Override
  public TestConnectionResult health(UUID connectorId) {
    // Same resolve-then-probe shape as test() (DEBT-018 item 2); the v0.1 default healthCheck
    // delegates to testConnection (HealthStatus class javadoc), so the response is honest in the
    // identical way test() already is — including the same defensive catch, since healthCheck's
    // default implementation runs the identical config.require(...) calls.
    var resolved = resolveConfig(connectorId);
    Optional<Connector> connector = registry.byType(resolved.getKey().type());
    HealthStatus status;
    if (connector.isEmpty()) {
      status =
          HealthStatus.notAvailable(
              "no connector implementation installed for " + resolved.getKey().type());
    } else {
      try {
        status = connector.get().healthCheck(resolved.getValue());
      } catch (IllegalArgumentException e) {
        status = HealthStatus.failed("connector configuration is incomplete: " + e.getMessage());
      }
    }
    return new TestConnectionResult(status.outcome(), status.message());
  }

  /**
   * Resolves a connector's config + revealed secret in a short tenant transaction. Callers probe
   * the source (the connector's {@code testConnection}/{@code healthCheck}) OUTSIDE any transaction
   * — this method only reads.
   */
  private Map.Entry<ConnectorAdminView, ConnectorConfig> resolveConfig(UUID connectorId) {
    return tx.readCurrent(
        () -> {
          var row =
              repository
                  .findWithSecret(connectorId)
                  .orElseThrow(() -> new ResourceNotFoundException("connector not found"));
          String secret = row.secretId() == null ? null : secrets.reveal(row.secretId());
          return Map.entry(row.view(), new ConnectorConfig(row.view().config(), secret));
        });
  }
}
