/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Adapter over {@code core.tenant_role_permission_override} (V11, ADR-026). Unlike {@code
 * core.service_token}, this table is uniformly tenant-scoped and RLS-protected
 * (R__rls_policies.sql) — every method here relies entirely on the RLS {@code tenant_isolation}
 * policy (bound via {@code TenantTransactionRunner} in the caller) rather than an explicit {@code
 * tenant_id} bind parameter, matching every other RLS-backed repository in this codebase (e.g.
 * {@code OrgStructureRepository}): the insert uses {@code current_setting('app.tenant_id')::uuid}
 * directly, and reads carry no explicit tenant predicate at all.
 *
 * <p>Implements {@link RolePermissionOverrideStore} — the seam {@code
 * RolePermissionOverrideService} depends on, so its unit test can use a hand-rolled in-memory fake
 * instead of mocking this class.
 */
@Repository
public class RolePermissionOverrideRepository implements RolePermissionOverrideStore {

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JDBC client
   */
  public RolePermissionOverrideRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<OverrideRow> findForRoles(Collection<String> roles) {
    if (roles.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT role, permission, granted
            FROM core.tenant_role_permission_override
            WHERE role IN (:roles)
            """)
        .param("roles", roles)
        .query(this::mapRow)
        .list();
  }

  @Override
  public void upsert(String role, String permission, boolean granted) {
    jdbc.sql(
            """
            INSERT INTO core.tenant_role_permission_override (tenant_id, role, permission, granted)
            VALUES (current_setting('app.tenant_id')::uuid, :role, :permission, :granted)
            ON CONFLICT (tenant_id, role, permission) DO UPDATE SET granted = EXCLUDED.granted
            """)
        .param("role", role)
        .param("permission", permission)
        .param("granted", granted)
        .update();
  }

  private OverrideRow mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new OverrideRow(
        rs.getString("role"), rs.getString("permission"), rs.getBoolean("granted"));
  }

  /**
   * One override row.
   *
   * @param role the {@code Role} enum name
   * @param permission the {@code Permission} enum name
   * @param granted {@code true} to grant, {@code false} to revoke
   */
  public record OverrideRow(String role, String permission, boolean granted) {}
}
