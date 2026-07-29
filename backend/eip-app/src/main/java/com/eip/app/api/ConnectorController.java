/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.application.ListConnectorsQuery;
import com.eip.app.security.RequiresPermission;
import com.eip.ingestion.api.ManageConnectorsUseCase;
import com.eip.ingestion.api.ManageConnectorsUseCase.TestConnectionResult;
import com.eip.tenancy.rbac.Permission;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The connector-administration read surface. A pure DTO adapter (BackendPlan §2.4): parameter
 * binding only; cursor decoding, limit clamping, and the keyset query live in the {@link
 * ListConnectorsQuery} adapter. Responses use the cursor-paginated {@link PageView} envelope
 * (APIDesign §1.4).
 */
@RestController
@RequestMapping("/api/v1")
public class ConnectorController {

  private final ListConnectorsQuery connectors;
  private final ManageConnectorsUseCase connectorAdmin;

  public ConnectorController(
      ListConnectorsQuery connectors, ManageConnectorsUseCase connectorAdmin) {
    this.connectors = connectors;
    this.connectorAdmin = connectorAdmin;
  }

  /**
   * Lists the current tenant's connectors, cursor-paginated.
   *
   * @param cursor opaque cursor from a previous page's {@code nextCursor}, or absent for the first
   *     page
   * @param limit page size, clamped to [1, {@value ListConnectorsQuery#MAX_LIMIT}] (default {@value
   *     ListConnectorsQuery#DEFAULT_LIMIT})
   * @param sort the whitelisted sort — {@code name} (default) | {@code -name} | {@code createdAt} |
   *     {@code -createdAt}, a leading {@code -} meaning descending (DEBT-010, APIDesign §1.4); an
   *     unrecognized value is a 400, and requesting a page with a different {@code sort} than the
   *     one its cursor was issued under is also a 400
   * @return a page of the tenant's connectors, ordered by the resolved sort
   */
  @GetMapping("/connectors")
  @RequiresPermission(Permission.DASHBOARD_VIEW)
  public PageView<ConnectorView> connectors(
      @RequestParam(name = "cursor", required = false) @Nullable String cursor,
      @RequestParam(name = "limit", defaultValue = "" + ListConnectorsQuery.DEFAULT_LIMIT)
          int limit,
      @RequestParam(name = "sort", required = false) @Nullable String sort) {
    return connectors.list(cursor, limit, sort);
  }

  /**
   * Probes a connector's health (ConnectorFramework §9: "{@code healthCheck()} results are exposed
   * at {@code /api/v1/connectors/{instanceId}/health}"; DEBT-018 item 2). Permission choice: {@link
   * Permission#DASHBOARD_VIEW} rather than {@code AdminConnectorController}'s {@code
   * CONNECTOR_CONFIGURE} — health is a read-only probe result, safe for any dashboard viewer,
   * unlike registering/editing a connector.
   *
   * @param connectorId the connector
   * @return the same {@code OK}/{@code NOT_AVAILABLE}/{@code FAILED} shape {@code
   *     AdminConnectorController}'s {@code /test} endpoint returns
   */
  @GetMapping("/connectors/{connectorId}/health")
  @RequiresPermission(Permission.DASHBOARD_VIEW)
  public TestConnectionResult health(@PathVariable UUID connectorId) {
    return connectorAdmin.health(connectorId);
  }
}
