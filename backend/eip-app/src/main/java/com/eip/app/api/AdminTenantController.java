/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.security.RequiresPermission;
import com.eip.tenancy.api.ManageTenantsUseCase;
import com.eip.tenancy.api.ManageTenantsUseCase.TenantView;
import com.eip.tenancy.rbac.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-level tenant administration (M1 admin panel). Pure DTO adapter over the tenancy port.
 * RBAC (SecurityModel §4, DEBT-012 M5 Wave S1a): both endpoints require {@link
 * Permission#TENANT_MANAGE} — header mode's implicit {@code TENANT_ADMIN} principal holds it
 * regardless of whether a tenant is yet named (tenant creation is platform-scoped and precedes any
 * tenant existing at all), so the dev/demo convenience is unchanged; {@code oidc} mode requires a
 * caller whose JWT roles include one that grants {@code tenant.manage}.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminTenantController {

  private final ManageTenantsUseCase tenants;

  public AdminTenantController(ManageTenantsUseCase tenants) {
    this.tenants = tenants;
  }

  /**
   * Lists all tenants.
   *
   * @return tenants, newest first
   */
  @GetMapping("/tenants")
  @RequiresPermission(Permission.TENANT_MANAGE)
  public List<TenantView> tenants() {
    return tenants.list();
  }

  /**
   * Registers a new tenant.
   *
   * @param request name + slug
   * @return the created tenant
   */
  @PostMapping("/tenants")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresPermission(Permission.TENANT_MANAGE)
  public TenantView create(@Valid @RequestBody CreateTenantRequest request) {
    return tenants.create(request.name(), request.slug());
  }

  /**
   * Tenant-creation payload.
   *
   * @param name display name
   * @param slug unique URL-safe identifier
   */
  public record CreateTenantRequest(@NotBlank String name, @NotBlank String slug) {}
}
