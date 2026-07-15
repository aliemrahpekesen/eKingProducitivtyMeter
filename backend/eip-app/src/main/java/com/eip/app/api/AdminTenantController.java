/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.tenancy.api.ManageTenantsUseCase;
import com.eip.tenancy.api.ManageTenantsUseCase.TenantView;
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
 * Unauthenticated until OIDC/RBAC lands (DEBT-012) — prod refuses to boot until then; the RBAC
 * permission catalog attaches to these endpoints in SPRINT-02.
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
