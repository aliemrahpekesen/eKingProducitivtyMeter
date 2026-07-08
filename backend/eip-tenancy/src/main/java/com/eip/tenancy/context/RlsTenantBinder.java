/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Binds a {@link TenantContext} onto a JDBC {@link Connection} as the transaction-scoped {@code
 * app.tenant_id} GUC that Postgres RLS policies key on (DatabasePlan §5/§12).
 *
 * <p>Uses {@code set_config('app.tenant_id', ?, true)} — the parameterized, injection-safe
 * equivalent of {@code SET LOCAL} (the {@code true} makes it transaction-local, so it resets on
 * commit/rollback and never leaks back into the pooled connection). The caller MUST invoke this
 * inside the transaction, before any tenant-scoped query. Because {@code current_setting('app
 * .tenant_id')} is declared with no default in the RLS policies, forgetting to bind fails loud
 * (query errors) rather than silently returning zero rows.
 *
 * <p>This is pure JDBC by design: {@code eip-tenancy} holds the mechanism but takes no Spring
 * dependency, so a datasource decorator, a plain {@code Connection}, or a test can all drive it.
 */
public final class RlsTenantBinder {

  /** Parameterized, transaction-local GUC set (equivalent to {@code SET LOCAL app.tenant_id}). */
  static final String SET_TENANT_SQL = "select set_config('app.tenant_id', ?, true)";

  private RlsTenantBinder() {}

  /**
   * Sets {@code app.tenant_id} on the connection for the current transaction.
   *
   * @param connection an open JDBC connection inside an active transaction
   * @param tenant the tenant to bind
   * @throws SQLException if the statement fails
   */
  public static void bind(Connection connection, TenantContext tenant) throws SQLException {
    Objects.requireNonNull(connection, "connection");
    Objects.requireNonNull(tenant, "tenant");
    try (PreparedStatement statement = connection.prepareStatement(SET_TENANT_SQL)) {
      statement.setString(1, tenant.tenantId().toString());
      statement.execute();
    }
  }
}
