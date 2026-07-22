/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.application.RolePermissionOverrideService;
import com.eip.app.application.RolePermissionOverrideService.OverrideCommand;
import com.eip.app.application.RolePermissionOverrideService.RolePermissionsView;
import com.eip.app.security.RequiresPermission;
import com.eip.tenancy.rbac.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-editable persona role-permission overrides (SecurityModel §4, DEBT-012 residual part 2,
 * ADR-026). Pure DTO adapter over {@link RolePermissionOverrideService}.
 *
 * <p>RBAC: every endpoint requires {@link Permission#USER_MANAGE} — the same permission cell {@link
 * ServiceTokenController} gates on ({@code TENANT_ADMIN}/{@code PLATFORM_ADMIN} hold it, every
 * lesser role does not; SecurityModel §4). {@code PUT}'s self-lockout guard ({@code
 * RolePermissionOverrideService}) additionally refuses a batch that would revoke {@code
 * TENANT_ADMIN}'s own {@code user.manage} permission, regardless of the caller's role.
 *
 * <p>{@code PUT} semantics: an upsert-merge over the given entries, not a full replace —
 * permissions not named in the request body are left exactly as they were (granted, revoked, or at
 * their default). A full-replace semantics would let a caller accidentally clear every OTHER
 * already- configured override for the role by omission when they only intended to toggle one
 * permission; upsert-merge cannot do that.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminRolePermissionController {

  private final RolePermissionOverrideService overrides;

  public AdminRolePermissionController(RolePermissionOverrideService overrides) {
    this.overrides = overrides;
  }

  /**
   * Returns a role's effective permission set and stored overrides for the current tenant.
   *
   * @param role the {@code Role} enum name (e.g. {@code "ENGINEERING_MANAGER"})
   * @return the effective view
   */
  @GetMapping("/roles/{role}/permissions")
  @RequiresPermission(Permission.USER_MANAGE)
  public RolePermissionsView get(@PathVariable String role) {
    return overrides.effectivePermissionsForCurrentTenant(role);
  }

  /**
   * Upserts one or more permission overrides for a role in the current tenant (merge, not replace —
   * see class javadoc), then returns the resulting effective view.
   *
   * @param role the {@code Role} enum name
   * @param request the overrides to apply
   * @return the resulting effective view
   */
  @PutMapping("/roles/{role}/permissions")
  @RequiresPermission(Permission.USER_MANAGE)
  public RolePermissionsView put(
      @PathVariable String role, @Valid @RequestBody UpdateRolePermissionsRequest request) {
    List<OverrideCommand> commands =
        request.overrides().stream()
            .map(entry -> new OverrideCommand(entry.permission(), entry.granted()))
            .toList();
    return overrides.applyOverrides(role, commands);
  }

  /**
   * One override entry in a {@code PUT} request.
   *
   * @param permission the {@code Permission} enum name (e.g. {@code "REPORT_EXPORT"})
   * @param granted {@code true} to grant beyond the role's default, {@code false} to revoke
   */
  public record OverrideRequest(@NotBlank String permission, boolean granted) {}

  /**
   * A {@code PUT /api/v1/admin/roles/{role}/permissions} request body.
   *
   * @param overrides the overrides to upsert (at least one)
   */
  public record UpdateRolePermissionsRequest(@NotEmpty List<@Valid OverrideRequest> overrides) {}
}
