/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.persistence;

import com.eip.app.api.SessionView;
import com.eip.app.application.GetSessionQuery;
import com.eip.tenancy.context.TenantContextHolder;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Session read adapter: resolves the bound tenant (fail-closed) and reads its organisation name
 * inside a tenant-bound read-only transaction (RLS-scoped).
 */
@Repository
public class SessionQueryRepository implements GetSessionQuery {

  private final TenantTransactionRunner tx;
  private final JdbcClient jdbc;

  public SessionQueryRepository(TenantTransactionRunner tx, JdbcClient jdbc) {
    this.tx = tx;
    this.jdbc = jdbc;
  }

  @Override
  public SessionView current() {
    UUID tenantId = TenantContextHolder.require().tenantId();
    Optional<String> organizationName =
        tx.readCurrent(
            () ->
                jdbc.sql(
                        """
                        SELECT name FROM core.organization
                        WHERE deleted_at IS NULL ORDER BY created_at LIMIT 1
                        """)
                    .query(String.class)
                    .optional());
    return new SessionView(tenantId, organizationName.orElse(null));
  }
}
