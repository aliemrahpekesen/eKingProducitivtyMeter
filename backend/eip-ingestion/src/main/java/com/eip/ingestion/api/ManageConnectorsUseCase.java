/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Tenant-scoped connector administration (M1 admin panel): the connector-type catalog with
 * JSON-Schema config forms, registration with envelope-encrypted secrets (never returned by any
 * API), status management, and an HONEST connection test — types whose real implementation has not
 * landed (DEBT-018) report {@code NOT_AVAILABLE}, never a fake success.
 */
public interface ManageConnectorsUseCase {

  /**
   * Returns the connector-type catalog (static until the full SPI descriptors land — DEBT-018).
   *
   * @return the available types with their config schemas
   */
  List<ConnectorTypeView> types();

  /**
   * Registers a connector for the current tenant.
   *
   * @param command type, name, config and optional secret
   * @return the created connector (secret never included)
   */
  ConnectorAdminView register(RegisterConnectorCommand command);

  /**
   * Lists the current tenant's connectors with admin detail.
   *
   * @return the connectors, newest first (secrets never included)
   */
  List<ConnectorAdminView> list();

  /**
   * Enables or disables a connector.
   *
   * @param connectorId the connector
   * @param status {@code ACTIVE} or {@code DISABLED}
   * @return the updated connector
   */
  ConnectorAdminView setStatus(UUID connectorId, String status);

  /**
   * Tests a connector's connectivity, honestly.
   *
   * @param connectorId the connector
   * @return {@code OK} (simulation), {@code NOT_AVAILABLE} (real type pending M2), or {@code
   *     FAILED}
   */
  TestConnectionResult test(UUID connectorId);

  /**
   * One catalog entry.
   *
   * @param type the {@code core.connector.type} discriminator
   * @param displayName human name
   * @param description what this integration ingests
   * @param configSchema JSON Schema (draft 2020-12) for the config form
   * @param secretLabel label for the secret input, or null when the type needs no secret
   * @param syncAvailable whether a real sync implementation exists in this release
   */
  record ConnectorTypeView(
      String type,
      String displayName,
      String description,
      String configSchema,
      @Nullable String secretLabel,
      boolean syncAvailable) {}

  /**
   * Registration input.
   *
   * @param type catalog type
   * @param name operator-facing connector name
   * @param config non-secret configuration (validated against the type's schema-required keys)
   * @param secret the API token/secret, or null for types without one
   */
  record RegisterConnectorCommand(
      String type, String name, Map<String, String> config, @Nullable String secret) {}

  /**
   * Admin view of one connector — never carries secret material.
   *
   * @param id connector id
   * @param type catalog type
   * @param name operator-facing name
   * @param status lifecycle status
   * @param simulation whether the data source is simulated
   * @param config non-secret configuration
   * @param hasSecret whether an encrypted secret is stored
   */
  record ConnectorAdminView(
      UUID id,
      String type,
      String name,
      String status,
      boolean simulation,
      Map<String, String> config,
      boolean hasSecret) {}

  /**
   * Connection-test outcome.
   *
   * @param outcome {@code OK} | {@code NOT_AVAILABLE} | {@code FAILED}
   * @param message operator-facing detail
   */
  record TestConnectionResult(String outcome, String message) {}
}
