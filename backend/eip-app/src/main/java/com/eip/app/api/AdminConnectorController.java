/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.application.LoadSampleDataUseCase;
import com.eip.app.application.RunFrictionPipelineUseCase.PipelineResult;
import com.eip.app.application.SyncConnectorUseCase;
import com.eip.app.security.RequiresPermission;
import com.eip.ingestion.api.ManageConnectorsUseCase;
import com.eip.ingestion.api.ManageConnectorsUseCase.ConnectorAdminView;
import com.eip.ingestion.api.ManageConnectorsUseCase.ConnectorTypeView;
import com.eip.ingestion.api.ManageConnectorsUseCase.RegisterConnectorCommand;
import com.eip.ingestion.api.ManageConnectorsUseCase.TestConnectionResult;
import com.eip.tenancy.rbac.Permission;
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
 *
 * <p>RBAC (SecurityModel §4): every connector-administration endpoint here — including {@link
 * #register}, which accepts a write-only secret — requires only {@link
 * Permission#CONNECTOR_CONFIGURE} in v0.1. The doc matrix separately lists {@code
 * connector.secret.write}; this collapses the two into one check for now (both are TENANT_ADMIN-
 * only permissions with an identical effective grant in the current role set, so splitting them
 * changes no caller's outcome yet) rather than declaring a second permission with no behavioral
 * difference — revisit when a role exists that holds one but not the other. {@link #loadSampleData}
 * is TENANT_MANAGE (tenant bootstrap/demo-data, not connector config).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminConnectorController {

  private final ManageConnectorsUseCase connectors;
  private final LoadSampleDataUseCase sampleData;
  private final SyncConnectorUseCase syncConnector;

  public AdminConnectorController(
      ManageConnectorsUseCase connectors,
      LoadSampleDataUseCase sampleData,
      SyncConnectorUseCase syncConnector) {
    this.connectors = connectors;
    this.sampleData = sampleData;
    this.syncConnector = syncConnector;
  }

  /**
   * Returns the connector-type catalog.
   *
   * @return available types with config schemas and honest availability flags
   */
  @GetMapping("/connector-types")
  @RequiresPermission(Permission.CONNECTOR_CONFIGURE)
  public List<ConnectorTypeView> types() {
    return connectors.types();
  }

  /**
   * Lists the tenant's connectors with admin detail.
   *
   * @return connectors, newest first (no secret material)
   */
  @GetMapping("/connectors")
  @RequiresPermission(Permission.CONNECTOR_CONFIGURE)
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
  @RequiresPermission(Permission.CONNECTOR_CONFIGURE)
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
  @RequiresPermission(Permission.CONNECTOR_CONFIGURE)
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
  @RequiresPermission(Permission.CONNECTOR_CONFIGURE)
  public TestConnectionResult test(@PathVariable UUID connectorId) {
    return connectors.test(connectorId);
  }

  /**
   * Runs a real synchronization for one registered connector, then recomputes the tenant's metrics
   * (Jira live in M2; other types arrive with M2b and fail with a clear message).
   *
   * @param connectorId the registered connector
   * @return staging + computation outcome
   */
  @PostMapping("/connectors/{connectorId}/sync")
  @RequiresPermission(Permission.CONNECTOR_CONFIGURE)
  public PipelineResult sync(@PathVariable UUID connectorId) {
    return syncConnector.sync(connectorId);
  }

  /**
   * Ensures sample structure and computes friction for the current tenant (SIMULATION data).
   *
   * @return the pipeline outcome
   */
  @PostMapping("/sample-data")
  @RequiresPermission(Permission.TENANT_MANAGE)
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
