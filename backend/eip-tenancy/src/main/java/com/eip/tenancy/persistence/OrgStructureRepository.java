/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.persistence;

import com.eip.tenancy.api.ManageOrgStructureUseCase.BusinessUnitView;
import com.eip.tenancy.api.ManageOrgStructureUseCase.OrganizationView;
import com.eip.tenancy.api.ManageOrgStructureUseCase.TeamView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped adapter over the organisation-structure tables. The tree loads with three bounded
 * queries (organisations, business units, teams) assembled in memory — never per-node.
 */
@Repository
public class OrgStructureRepository {

  private final JdbcClient jdbc;

  public OrgStructureRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Loads the current tenant's structure tree.
   *
   * @return organisations with nested business units and teams
   */
  public List<OrganizationView> structure() {
    record BuRow(UUID id, UUID orgId, String name) {}
    record TeamRow(UUID id, UUID buId, String name) {}

    List<TeamRow> teams =
        jdbc.sql(
                """
                SELECT id, business_unit_id, name FROM core.team
                WHERE deleted_at IS NULL ORDER BY name
                """)
            .query(
                (rs, n) ->
                    new TeamRow(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
            .list();
    Map<UUID, List<TeamView>> teamsByBu = new LinkedHashMap<>();
    for (TeamRow t : teams) {
      teamsByBu
          .computeIfAbsent(t.buId(), k -> new ArrayList<>())
          .add(new TeamView(t.id(), t.name()));
    }

    List<BuRow> bus =
        jdbc.sql(
                """
                SELECT id, organization_id, name FROM core.business_unit
                WHERE deleted_at IS NULL ORDER BY name
                """)
            .query(
                (rs, n) ->
                    new BuRow(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
            .list();
    Map<UUID, List<BusinessUnitView>> busByOrg = new LinkedHashMap<>();
    for (BuRow b : bus) {
      busByOrg
          .computeIfAbsent(b.orgId(), k -> new ArrayList<>())
          .add(new BusinessUnitView(b.id(), b.name(), teamsByBu.getOrDefault(b.id(), List.of())));
    }

    return jdbc.sql(
            "SELECT id, name, slug FROM core.organization WHERE deleted_at IS NULL ORDER BY name")
        .query(
            (rs, n) -> {
              UUID id = rs.getObject(1, UUID.class);
              return new OrganizationView(
                  id, rs.getString(2), rs.getString(3), busByOrg.getOrDefault(id, List.of()));
            })
        .list();
  }

  /**
   * Inserts an organisation for the bound tenant.
   *
   * @param name display name
   * @param slug per-tenant identifier
   * @return the new id
   */
  public UUID insertOrganization(String name, String slug) {
    return jdbc.sql(
            """
            INSERT INTO core.organization (tenant_id, name, slug)
            VALUES (current_setting('app.tenant_id')::uuid, :name, :slug) RETURNING id
            """)
        .param("name", name)
        .param("slug", slug)
        .query(UUID.class)
        .single();
  }

  /**
   * Inserts a business unit.
   *
   * @param organizationId parent organisation
   * @param name display name
   * @return the new id
   */
  public UUID insertBusinessUnit(UUID organizationId, String name) {
    return jdbc.sql(
            """
            INSERT INTO core.business_unit (tenant_id, organization_id, name)
            VALUES (current_setting('app.tenant_id')::uuid, :orgId, :name) RETURNING id
            """)
        .param("orgId", organizationId)
        .param("name", name)
        .query(UUID.class)
        .single();
  }

  /**
   * Inserts a team.
   *
   * @param businessUnitId parent business unit
   * @param name display name
   * @return the new id
   */
  public UUID insertTeam(UUID businessUnitId, String name) {
    return jdbc.sql(
            """
            INSERT INTO core.team (tenant_id, business_unit_id, name, type)
            VALUES (current_setting('app.tenant_id')::uuid, :buId, :name, 'STREAM_ALIGNED')
            RETURNING id
            """)
        .param("buId", businessUnitId)
        .param("name", name)
        .query(UUID.class)
        .single();
  }
}
