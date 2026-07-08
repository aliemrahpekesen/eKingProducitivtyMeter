/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.tenant.TenantScopedJdbc;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The connector-administration read surface: lists the tenant's registered connectors with their
 * status. Read tenant-scoped under RLS — a tenant never sees another tenant's connectors.
 */
@RestController
@RequestMapping("/api/v1")
public class ConnectorController {

  private final TenantScopedJdbc jdbc;

  public ConnectorController(TenantScopedJdbc jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Lists the current tenant's connectors.
   *
   * @return the current tenant's connectors, ordered by name
   */
  @GetMapping("/connectors")
  public List<ConnectorView> connectors() {
    return jdbc.read(
        client ->
            client
                .sql(
                    "SELECT id, type, name, status, simulation FROM core.connector "
                        + "WHERE deleted_at IS NULL ORDER BY name")
                .query(
                    (rs, rowNum) ->
                        new ConnectorView(
                            rs.getObject("id", UUID.class),
                            rs.getString("type"),
                            rs.getString("name"),
                            rs.getString("status"),
                            rs.getBoolean("simulation")))
                .list());
  }
}
