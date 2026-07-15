/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.persistence;

import com.eip.tenancy.api.ManageTenantsUseCase.TenantView;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Platform-scoped adapter over {@code core.tenant} (the enumerated no-RLS exception). */
@Repository
public class TenantAdminRepository {

  private final JdbcClient jdbc;

  public TenantAdminRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Checks whether a tenant slug is taken.
   *
   * @param slug the normalized slug
   * @return true if a tenant with this slug exists
   */
  public boolean slugExists(String slug) {
    return jdbc.sql("SELECT count(*) FROM core.tenant WHERE slug = :slug")
            .param("slug", slug)
            .query(Long.class)
            .single()
        > 0;
  }

  /**
   * Inserts a tenant.
   *
   * @param name display name
   * @param slug normalized unique slug
   * @return the created row
   */
  public TenantView insert(String name, String slug) {
    UUID id =
        jdbc.sql("INSERT INTO core.tenant (name, slug) VALUES (:name, :slug) RETURNING id")
            .param("name", name)
            .param("slug", slug)
            .query(UUID.class)
            .single();
    return new TenantView(id, name, slug);
  }

  /**
   * Lists all tenants, newest first.
   *
   * @return the tenants
   */
  public List<TenantView> list() {
    return jdbc.sql("SELECT id, name, slug FROM core.tenant ORDER BY created_at DESC")
        .query(
            (rs, rowNum) ->
                new TenantView(
                    rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("slug")))
        .list();
  }
}
