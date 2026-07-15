/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.application;

import com.eip.connectors.spi.Connector;
import com.eip.connectors.spi.RawRecord;
import com.eip.core.error.ResourceNotFoundException;
import com.eip.core.error.ValidationException;
import com.eip.ingestion.api.ManageConnectorsUseCase;
import com.eip.ingestion.persistence.ConnectorAdminRepository;
import com.eip.tenancy.secrets.SecretsService;
import com.eip.tenancy.tx.TenantTransactionRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Connector administration for the current tenant. Registration validates the type against the
 * catalog and the config against the schema's required keys; secrets go through the
 * envelope-encrypting {@code SecretsService} and are never readable through any admin surface.
 * {@code test} is honest: only the simulation source can really connect in this release — real
 * types report {@code NOT_AVAILABLE} until Connector Foundation lands (DEBT-018), never a fake OK.
 */
@Service
public class ConnectorAdminService implements ManageConnectorsUseCase {

  private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "DISABLED");

  private final TenantTransactionRunner tx;
  private final ConnectorAdminRepository repository;
  private final SecretsService secrets;
  private final Connector simulationConnector;
  private final ObjectMapper mapper;

  public ConnectorAdminService(
      TenantTransactionRunner tx,
      ConnectorAdminRepository repository,
      SecretsService secrets,
      Connector simulationConnector,
      ObjectMapper mapper) {
    this.tx = tx;
    this.repository = repository;
    this.secrets = secrets;
    this.simulationConnector = simulationConnector;
    this.mapper = mapper;
  }

  @Override
  public List<ConnectorTypeView> types() {
    return ConnectorTypeCatalog.all();
  }

  @Override
  public ConnectorAdminView register(RegisterConnectorCommand command) {
    ConnectorTypeView type =
        ConnectorTypeCatalog.byType(command.type())
            .orElseThrow(
                () -> new ValidationException("unknown connector type: " + command.type()));
    if (command.name().isBlank()) {
      throw new ValidationException("connector name must not be blank");
    }
    requireSchemaRequiredKeys(type, command.config());
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
    ConnectorAdminView connector =
        tx.readCurrent(
            () ->
                repository
                    .find(connectorId)
                    .orElseThrow(() -> new ResourceNotFoundException("connector not found")));
    if (connector.type().equals("simulation")) {
      List<RawRecord> probe = new ArrayList<>();
      simulationConnector.sync(() -> probe::add);
      return new TestConnectionResult(
          "OK", "Simulation source reachable — " + probe.size() + " records available.");
    }
    return new TestConnectionResult(
        "NOT_AVAILABLE",
        ConnectorTypeCatalog.byType(connector.type())
                .map(ConnectorTypeView::displayName)
                .orElse(connector.type())
            + " sync is not implemented in this release; it arrives with Connector Foundation"
            + " (DEBT-018). Configuration is stored and will be used as soon as the connector"
            + " lands.");
  }

  private void requireSchemaRequiredKeys(ConnectorTypeView type, Map<String, String> config) {
    try {
      JsonNode required = mapper.readTree(type.configSchema()).path("required");
      for (JsonNode key : required) {
        String k = key.asText();
        if (config.get(k) == null || config.get(k).isBlank()) {
          throw new ValidationException("missing required config field: " + k);
        }
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("catalog schema is not valid JSON", e);
    }
  }
}
