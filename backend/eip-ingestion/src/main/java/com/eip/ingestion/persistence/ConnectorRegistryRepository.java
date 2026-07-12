/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ingestion.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Resolves the tenant's {@code core.connector} registry row for a connector type, creating it once
 * per tenant. RLS scopes both the lookup and the insert to the bound tenant.
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
            WHERE type = :type AND deleted_at IS NULL
            ORDER BY created_at LIMIT 1
            """)
        .param("type", type)
        .query(UUID.class)
        .optional()
        .orElseGet(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO core.connector (tenant_id, type, name, status, simulation)
                        VALUES (current_setting('app.tenant_id')::uuid, :type, 'Simulation Source',
                                'ACTIVE', :simulation)
                        RETURNING id
                        """)
                    .param("type", type)
                    .param("simulation", simulation)
                    .query(UUID.class)
                    .single());
  }
}
