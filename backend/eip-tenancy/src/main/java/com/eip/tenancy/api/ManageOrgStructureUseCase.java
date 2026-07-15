/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.api;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped organisation-structure administration (organisation → business unit → team). All
 * operations run under the current tenant's RLS binding and fail closed without one.
 */
public interface ManageOrgStructureUseCase {

  /**
   * Returns the current tenant's full structure tree.
   *
   * @return organisations with nested business units and teams
   */
  List<OrganizationView> structure();

  /**
   * Creates an organisation for the current tenant.
   *
   * @param name display name
   * @param slug unique-per-tenant identifier
   * @return the new organisation id
   */
  UUID createOrganization(String name, String slug);

  /**
   * Creates a business unit under an organisation.
   *
   * @param organizationId the parent organisation
   * @param name display name
   * @return the new business-unit id
   */
  UUID createBusinessUnit(UUID organizationId, String name);

  /**
   * Creates a team under a business unit.
   *
   * @param businessUnitId the parent business unit
   * @param name display name
   * @return the new team id
   */
  UUID createTeam(UUID businessUnitId, String name);

  /**
   * One organisation with its nested structure.
   *
   * @param id organisation id
   * @param name display name
   * @param slug identifier
   * @param businessUnits nested business units
   */
  record OrganizationView(
      UUID id, String name, String slug, List<BusinessUnitView> businessUnits) {}

  /**
   * One business unit with its teams.
   *
   * @param id business-unit id
   * @param name display name
   * @param teams nested teams
   */
  record BusinessUnitView(UUID id, String name, List<TeamView> teams) {}

  /**
   * One team.
   *
   * @param id team id
   * @param name display name
   */
  record TeamView(UUID id, String name) {}
}
