/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.security.RequiresPermission;
import com.eip.tenancy.api.ManageOrgStructureUseCase;
import com.eip.tenancy.api.ManageOrgStructureUseCase.OrganizationView;
import com.eip.tenancy.rbac.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped organisation-structure administration (M1 admin panel): organisation → business
 * unit → team. Pure DTO adapter; RLS scopes everything to the requesting tenant.
 *
 * <p>RBAC (SecurityModel §4): every endpoint here is {@link Permission#TENANT_MANAGE}. The doc
 * matrix names {@code tenant.manage} for tenant/structure administration as a whole; it does not
 * carve out a separate permission for organisation/business-unit/team CRUD, so this controller's
 * mutating endpoints (not explicitly enumerated in the M5 Wave S1a brief, which named only the
 * {@code GET /structure} read) use the same permission as the read for consistency — creating
 * structure is tenant administration, not a distinct capability in v0.1.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminStructureController {

  private final ManageOrgStructureUseCase structure;

  public AdminStructureController(ManageOrgStructureUseCase structure) {
    this.structure = structure;
  }

  /**
   * Returns the current tenant's structure tree.
   *
   * @return organisations with nested business units and teams
   */
  @GetMapping("/structure")
  @RequiresPermission(Permission.TENANT_MANAGE)
  public List<OrganizationView> structure() {
    return structure.structure();
  }

  /**
   * Creates an organisation.
   *
   * @param request name + slug
   * @return the new id
   */
  @PostMapping("/organizations")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresPermission(Permission.TENANT_MANAGE)
  public Map<String, UUID> createOrganization(
      @Valid @RequestBody CreateOrganizationRequest request) {
    return Map.of("id", structure.createOrganization(request.name(), request.slug()));
  }

  /**
   * Creates a business unit.
   *
   * @param request parent organisation + name
   * @return the new id
   */
  @PostMapping("/business-units")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresPermission(Permission.TENANT_MANAGE)
  public Map<String, UUID> createBusinessUnit(
      @Valid @RequestBody CreateBusinessUnitRequest request) {
    return Map.of("id", structure.createBusinessUnit(request.organizationId(), request.name()));
  }

  /**
   * Creates a team.
   *
   * @param request parent business unit + name
   * @return the new id
   */
  @PostMapping("/teams")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresPermission(Permission.TENANT_MANAGE)
  public Map<String, UUID> createTeam(@Valid @RequestBody CreateTeamRequest request) {
    return Map.of("id", structure.createTeam(request.businessUnitId(), request.name()));
  }

  /**
   * Organisation-creation payload.
   *
   * @param name display name
   * @param slug per-tenant identifier
   */
  public record CreateOrganizationRequest(@NotBlank String name, @NotBlank String slug) {}

  /**
   * Business-unit-creation payload.
   *
   * @param organizationId parent organisation
   * @param name display name
   */
  public record CreateBusinessUnitRequest(@NotNull UUID organizationId, @NotBlank String name) {}

  /**
   * Team-creation payload.
   *
   * @param businessUnitId parent business unit
   * @param name display name
   */
  public record CreateTeamRequest(@NotNull UUID businessUnitId, @NotBlank String name) {}
}
