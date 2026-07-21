/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Resolves the tenant's {@code core.connector} registry row for the ONE simulation-source connector
 * of a given type, creating it once per tenant. RLS scopes both the lookup and the insert to the
 * bound tenant.
 *
 * <p>Backed by the partial unique index {@code ux_connector_simulation_type} ({@code (tenant_id,
 * type) WHERE deleted_at IS NULL AND simulation = true}, {@code V8__connector_unique_constraint
 * .sql}): the insert is an {@code ON CONFLICT ... DO UPDATE} self-no-op upsert, so two concurrent
 * callers racing for the same tenant+type both resolve to the SAME row id instead of one of them
 * silently creating a duplicate (DEBT-020 item 6). This constraint is scoped to {@code simulation =
 * true} only — user-registered real connectors go through {@link ConnectorAdminRepository #insert}
 * and legitimately allow multiple rows of the same type.
 */
@Repository
public class ConnectorRegistryRepository {

  private final JdbcClient jdbc;

  public ConnectorRegistryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Returns the tenant's connector row id for {@code type}, creating it if absent.
   *
   * @param type the connector type discriminator
   * @param simulation whether the connector produces simulated data
   * @return the {@code core.connector.id}
   */
  public UUID ensureConnector(String type, boolean simulation) {
    return jdbc.sql(
            """
            SELECT id FROM core.connector
            WHERE type = :type AND simulation = :simulation AND deleted_at IS NULL
            ORDER BY created_at LIMIT 1
            """)
        .param("type", type)
        .param("simulation", simulation)
        .query(UUID.class)
        .optional()
        .orElseGet(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO core.connector (tenant_id, type, name, status, simulation)
                        VALUES (current_setting('app.tenant_id')::uuid, :type, 'Simulation Source',
                                'ACTIVE', :simulation)
                        ON CONFLICT (tenant_id, type) WHERE deleted_at IS NULL AND simulation = true
                        DO UPDATE SET id = connector.id
                        RETURNING id
                        """)
                    .param("type", type)
                    .param("simulation", simulation)
                    .query(UUID.class)
                    .single());
  }
}
