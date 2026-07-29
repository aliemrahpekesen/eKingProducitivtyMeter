/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.app.persistence.RolePermissionOverrideRepository.OverrideRow;
import com.eip.app.persistence.RolePermissionOverrideStore;
import com.eip.app.security.EipPrincipal;
import com.eip.app.security.EipPrincipalHolder;
import com.eip.core.error.ValidationException;
import com.eip.tenancy.audit.api.AuditActorType;
import com.eip.tenancy.audit.api.AuditCategory;
import com.eip.tenancy.audit.api.AuditEvent;
import com.eip.tenancy.audit.api.AuditOutcome;
import com.eip.tenancy.audit.api.RecordAuditEventUseCase;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.rbac.Permission;
import com.eip.tenancy.rbac.Role;
import com.eip.tenancy.tx.TenantTransactionRunner;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Tenant-editable persona role-permission overrides (SecurityModel §4, DEBT-012 residual part 2,
 * ADR-026): {@code core.tenant_role_permission_override} (V11) lets a {@code TENANT_ADMIN} grant a
 * permission a role's default set omits, or revoke one it includes, WITHOUT mutating {@link
 * Role#permissions()}'s own fixed matrix ({@code RbacMatrixTest} still transcribes SecurityModel §4
 * exactly). Effective permissions for a (tenant, role) pair = {@link Role#permissions()} with every
 * {@code granted=false} override's permission removed and every {@code granted=true} override's
 * permission added.
 *
 * <p>Implements {@link EffectivePermissionResolver} for {@code EipPrincipalFilter}'s per-request
 * principal resolution (a single indexed lookup covering every role the principal holds — {@code
 * WHERE role IN (:roles)} against the {@code (tenant_id, role, permission)} primary key — not one
 * query per role or per permission) and additionally exposes the {@code GET}/{@code PUT
 * /api/v1/admin/roles/{role}/permissions} operations {@code AdminRolePermissionController} calls.
 */
@Service
public class RolePermissionOverrideService implements EffectivePermissionResolver {

  private final TenantTransactionRunner tx;
  private final RolePermissionOverrideStore repository;
  private final RecordAuditEventUseCase audit;

  /**
   * Creates the service.
   *
   * @param tx the tenant-bound transaction boundary
   * @param repository the persistence adapter (the {@link RolePermissionOverrideStore} seam, not
   *     the concrete {@code RolePermissionOverrideRepository}, so tests can substitute a fake)
   * @param audit the audit write path (DEBT-024 Wave 3B: {@code role.permission.overridden} on
   *     every {@link #applyOverrides} call)
   */
  public RolePermissionOverrideService(
      TenantTransactionRunner tx,
      RolePermissionOverrideStore repository,
      RecordAuditEventUseCase audit) {
    this.tx = tx;
    this.repository = repository;
    this.audit = audit;
  }

  @Override
  public Set<Permission> resolve(UUID tenantId, Set<Role> roles) {
    if (roles.isEmpty()) {
      return Set.of();
    }
    List<String> roleNames = roles.stream().map(Role::name).toList();
    List<OverrideRow> overrides =
        tx.read(TenantContext.of(tenantId), () -> repository.findForRoles(roleNames));
    Map<String, List<OverrideRow>> byRole =
        overrides.stream().collect(Collectors.groupingBy(OverrideRow::role));
    Set<Permission> effective = EnumSet.noneOf(Permission.class);
    for (Role role : roles) {
      effective.addAll(
          applyOverrides(role.permissions(), byRole.getOrDefault(role.name(), List.of())));
    }
    return effective;
  }

  /**
   * Returns the current tenant's effective permission set and stored overrides for one role.
   *
   * @param roleName the {@code Role} enum name
   * @return the effective view
   * @throws ValidationException if {@code roleName} is not a known role
   */
  public RolePermissionsView effectivePermissionsForCurrentTenant(String roleName) {
    Role role = parseRole(roleName);
    List<OverrideRow> overrides =
        tx.readCurrent(() -> repository.findForRoles(List.of(role.name())));
    return toView(role, overrides);
  }

  /**
   * Applies a batch of overrides for one role in the current tenant, then returns the resulting
   * effective view. Rejected atomically (no partial application) if any entry would revoke {@code
   * TENANT_ADMIN}'s own {@link Permission#USER_MANAGE} (self-lockout prevention, SecurityModel §4).
   *
   * @param roleName the {@code Role} enum name
   * @param commands the overrides to upsert
   * @return the resulting effective view
   * @throws ValidationException if {@code roleName}/a permission name is unknown, or the
   *     self-lockout guard rejects the batch
   */
  public RolePermissionsView applyOverrides(String roleName, List<OverrideCommand> commands) {
    Role role = parseRole(roleName);
    List<Permission> parsed = commands.stream().map(c -> parsePermission(c.permission())).toList();
    rejectSelfLockout(role, commands);
    return tx.callCurrent(
        () -> {
          for (int i = 0; i < commands.size(); i++) {
            repository.upsert(role.name(), parsed.get(i).name(), commands.get(i).granted());
          }
          List<OverrideRow> overrides = repository.findForRoles(List.of(role.name()));
          audit.record(
              new AuditEvent(
                  AuditCategory.ADMIN,
                  "role.permission.overridden",
                  AuditOutcome.SUCCESS,
                  resolveActorType(),
                  null,
                  Map.of("role", role.name(), "changes", changeDetail(parsed, commands))));
          return toView(role, overrides);
        });
  }

  /**
   * Builds the audit {@code detail.changes} entries: wire-id permission + granted flag pairs, in
   * request order. Enum wire ids only — never a free-text description of what changed.
   */
  private static List<Map<String, Object>> changeDetail(
      List<Permission> parsed, List<OverrideCommand> commands) {
    List<Map<String, Object>> changes = new ArrayList<>(commands.size());
    for (int i = 0; i < commands.size(); i++) {
      changes.add(
          Map.of("permission", parsed.get(i).wireId(), "granted", commands.get(i).granted()));
    }
    return changes;
  }

  /**
   * Best-effort actor-type resolution from the caller's bound principal (SecurityModel §11) —
   * mirrors {@code ServiceTokenService}'s identical helper (duplicated rather than shared: neither
   * class is the other's dependency, and this is the only overlap between them). {@link
   * EipPrincipal} carries no member-UUID accessor yet (only {@link EipPrincipal#subject()},
   * documented as logs/audit-only) — {@link AuditActorType#USER} therefore cannot be populated with
   * a real {@code actorMemberId} until a member-id resolution path (e.g. a {@code
   * core.member.oidc_subject} lookup) is wired into the principal model, a natural DEBT-024
   * follow-up. A service-token-authenticated caller IS distinguishable today and is tagged
   * accordingly; every other caller (header/oidc human admin, or none at all) is tagged {@link
   * AuditActorType#SYSTEM}.
   */
  private static AuditActorType resolveActorType() {
    return EipPrincipalHolder.current()
        .map(EipPrincipal::subject)
        .filter(subject -> subject.startsWith("service-token:"))
        .map(subject -> AuditActorType.SERVICE_TOKEN)
        .orElse(AuditActorType.SYSTEM);
  }

  /**
   * Rejects a batch that would revoke {@code TENANT_ADMIN}'s own {@code user.manage} permission —
   * the one permission every tenant needs at least one holder of to ever administer roles again.
   */
  private static void rejectSelfLockout(Role role, List<OverrideCommand> commands) {
    if (role != Role.TENANT_ADMIN) {
      return;
    }
    boolean revokesUserManage =
        commands.stream()
            .anyMatch(c -> Permission.USER_MANAGE.name().equals(c.permission()) && !c.granted());
    if (revokesUserManage) {
      throw new ValidationException(
          "refusing to revoke TENANT_ADMIN's own "
              + Permission.USER_MANAGE.wireId()
              + " permission (self-lockout guard) — no tenant admin would be able to administer"
              + " roles/permissions again");
    }
  }

  private static RolePermissionsView toView(Role role, List<OverrideRow> overrides) {
    Set<Permission> effective = applyOverrides(role.permissions(), overrides);
    List<OverrideView> overrideViews =
        overrides.stream()
            .map(o -> new OverrideView(o.permission(), o.granted()))
            .sorted((a, b) -> a.permission().compareTo(b.permission()))
            .toList();
    List<String> effectiveWireIds = effective.stream().map(Permission::wireId).sorted().toList();
    return new RolePermissionsView(role.name(), effectiveWireIds, overrideViews);
  }

  private static Set<Permission> applyOverrides(
      Set<Permission> defaults, List<OverrideRow> overrides) {
    Set<Permission> effective = EnumSet.noneOf(Permission.class);
    effective.addAll(defaults);
    for (OverrideRow override : overrides) {
      Permission permission;
      try {
        permission = Permission.valueOf(override.permission());
      } catch (IllegalArgumentException unknownPermission) {
        // A stored override referencing a since-removed Permission constant — ignored, never
        // widens the effective set with something the current catalog does not recognize.
        continue;
      }
      if (override.granted()) {
        effective.add(permission);
      } else {
        effective.remove(permission);
      }
    }
    return effective;
  }

  private static Role parseRole(String name) {
    try {
      return Role.valueOf(name);
    } catch (IllegalArgumentException unknownRole) {
      throw new ValidationException("unknown role: " + name);
    }
  }

  private static Permission parsePermission(String name) {
    try {
      return Permission.valueOf(name);
    } catch (IllegalArgumentException unknownPermission) {
      throw new ValidationException("unknown permission: " + name);
    }
  }

  /**
   * A single override to apply.
   *
   * @param permission the {@code Permission} enum name
   * @param granted {@code true} to grant, {@code false} to revoke
   */
  public record OverrideCommand(String permission, boolean granted) {}

  /**
   * One stored override, as returned by a GET.
   *
   * @param permission the {@code Permission} enum name
   * @param granted {@code true} if granted beyond the default, {@code false} if revoked from it
   */
  public record OverrideView(String permission, boolean granted) {}

  /**
   * The effective-permissions view for one role in the current tenant.
   *
   * @param role the {@code Role} enum name
   * @param effectivePermissions the effective permission set's wire ids, sorted
   * @param overrides the role's stored overrides (empty if the role uses its default set unchanged)
   */
  public record RolePermissionsView(
      String role, List<String> effectivePermissions, List<OverrideView> overrides) {}
}
