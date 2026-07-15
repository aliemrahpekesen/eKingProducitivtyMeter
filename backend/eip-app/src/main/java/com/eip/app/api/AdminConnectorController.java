/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.application.LoadSampleDataUseCase;
import com.eip.app.application.RunFrictionPipelineUseCase.PipelineResult;
import com.eip.ingestion.api.ManageConnectorsUseCase;
import com.eip.ingestion.api.ManageConnectorsUseCase.ConnectorAdminView;
import com.eip.ingestion.api.ManageConnectorsUseCase.ConnectorTypeView;
import com.eip.ingestion.api.ManageConnectorsUseCase.RegisterConnectorCommand;
import com.eip.ingestion.api.ManageConnectorsUseCase.TestConnectionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped connector administration (M1 admin panel): the type catalog with JSON-Schema config
 * forms, registration (secrets envelope-encrypted, never returned), status management, an HONEST
 * connection test, and the one-click sample-data loader. Pure DTO adapter over the ingestion/app
 * ports.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminConnectorController {

  private final ManageConnectorsUseCase connectors;
  private final LoadSampleDataUseCase sampleData;

  public AdminConnectorController(
      ManageConnectorsUseCase connectors, LoadSampleDataUseCase sampleData) {
    this.connectors = connectors;
    this.sampleData = sampleData;
  }

  /**
   * Returns the connector-type catalog.
   *
   * @return available types with config schemas and honest availability flags
   */
  @GetMapping("/connector-types")
  public List<ConnectorTypeView> types() {
    return connectors.types();
  }

  /**
   * Lists the tenant's connectors with admin detail.
   *
   * @return connectors, newest first (no secret material)
   */
  @GetMapping("/connectors")
  public List<ConnectorAdminView> list() {
    return connectors.list();
  }

  /**
   * Registers a connector.
   *
   * @param request type, name, config and optional secret
   * @return the created connector
   */
  @PostMapping("/connectors")
  @ResponseStatus(HttpStatus.CREATED)
  public ConnectorAdminView register(@Valid @RequestBody RegisterConnectorRequest request) {
    return connectors.register(
        new RegisterConnectorCommand(
            request.type(),
            request.name(),
            request.config() == null ? Map.of() : request.config(),
            request.secret()));
  }

  /**
   * Enables or disables a connector.
   *
   * @param connectorId the connector
   * @param request the new status
   * @return the updated connector
   */
  @PostMapping("/connectors/{connectorId}/status")
  public ConnectorAdminView setStatus(
      @PathVariable UUID connectorId, @Valid @RequestBody SetStatusRequest request) {
    return connectors.setStatus(connectorId, request.status());
  }

  /**
   * Tests a connector's connectivity, honestly (real types report NOT_AVAILABLE until M2).
   *
   * @param connectorId the connector
   * @return the outcome
   */
  @PostMapping("/connectors/{connectorId}/test")
  public TestConnectionResult test(@PathVariable UUID connectorId) {
    return connectors.test(connectorId);
  }

  /**
   * Ensures sample structure and computes friction for the current tenant (SIMULATION data).
   *
   * @return the pipeline outcome
   */
  @PostMapping("/sample-data")
  public PipelineResult loadSampleData() {
    return sampleData.load();
  }

  /**
   * Connector-registration payload.
   *
   * @param type catalog type
   * @param name operator-facing name
   * @param config non-secret configuration
   * @param secret API token/secret when the type requires one (write-only; never returned)
   */
  public record RegisterConnectorRequest(
      @NotBlank String type,
      @NotBlank String name,
      @Nullable Map<String, String> config,
      @Nullable String secret) {}

  /**
   * Status-change payload.
   *
   * @param status {@code ACTIVE} or {@code DISABLED}
   */
  public record SetStatusRequest(@NotBlank String status) {}
}
