/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.app.persistence.ServiceTokenRepository.ServiceTokenRow;
import com.eip.app.persistence.ServiceTokenStore;
import com.eip.app.security.EipPrincipal;
import com.eip.app.security.EipPrincipalHolder;
import com.eip.core.error.PermissionDeniedException;
import com.eip.core.error.ResourceNotFoundException;
import com.eip.core.error.ValidationException;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import com.eip.tenancy.rbac.Permission;
import com.eip.tenancy.rbac.Role;
import com.eip.tenancy.rbac.ServiceTokenGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Application service for service-token lifecycle (SecurityModel §3, ADR-025): create (tenant-
 * scoped by the caller's bound tenant, or platform-scoped when explicitly requested by a
 * PLATFORM_ADMIN caller), list (the caller's own scope), revoke. The raw token is generated and
 * returned exactly once, here, at creation — never again afterward (mirrors the write-only reveal
 * discipline {@code SecretsService} establishes for connector secrets, SecurityModel §6).
 *
 * <p>Every create/revoke is logged as a structured event (SLF4J + MDC, matching {@code
 * ApiObservabilityFilter}'s convention) rather than written to {@code audit.audit_event}: that
 * table exists in the schema (V1) but no write path exists anywhere in this codebase yet, and
 * building one is out of this class's scope — see the task's final report / DEBT-012 register entry
 * for that gap.
 */
@Service
public class ServiceTokenService {

  /** Default token lifetime when the caller specifies none (SecurityModel §3). */
  static final int DEFAULT_EXPIRY_DAYS = 90;

  /** Maximum token lifetime (SecurityModel §3). */
  static final int MAX_EXPIRY_DAYS = 365;

  private static final Logger log = LoggerFactory.getLogger("com.eip.app.security.serviceToken");

  private final ServiceTokenStore repository;
  private final ServiceTokenGenerator generator;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param repository the persistence adapter (the {@link ServiceTokenStore} seam, not the concrete
   *     {@code ServiceTokenRepository}, so tests can substitute a hand-rolled fake)
   * @param generator the raw-token generator/hasher
   * @param clock the clock expiry is computed from (testable — BackendPlan §2.5)
   */
  public ServiceTokenService(
      ServiceTokenStore repository, ServiceTokenGenerator generator, Clock clock) {
    this.repository = repository;
    this.generator = generator;
    this.clock = clock;
  }

  /**
   * Creates a new service token.
   *
   * @param command the creation request
   * @return the raw token result — the ONLY time the raw value is ever available
   */
  public CreateServiceTokenResult create(CreateServiceTokenCommand command) {
    Role role = parseRole(command.role());
    List<String> permissionSubset = validateSubset(role, command.permissionSubset());
    int expiresInDays = clampExpiry(command.expiresInDays());
    @Nullable UUID tenantId = resolveTenantId(command.platformScoped());

    String rawToken = generator.generateRawToken();
    String hash = generator.hash(rawToken);
    String prefix =
        rawToken.substring(
            ServiceTokenGenerator.TOKEN_PREFIX.length(),
            ServiceTokenGenerator.TOKEN_PREFIX.length() + 8);
    Instant expiresAt = clock.instant().plus(expiresInDays, ChronoUnit.DAYS);

    UUID id =
        repository.insert(
            tenantId, command.name(), prefix, hash, role.name(), permissionSubset, null, expiresAt);
    logEvent("auth.token.issued", id, tenantId, role.name());
    return new CreateServiceTokenResult(id, rawToken, prefix, expiresAt);
  }

  /**
   * Lists service tokens for the caller's current scope: the bound tenant's own tokens, or — when
   * no tenant is bound — the platform-scoped tokens (PLATFORM_ADMIN only).
   *
   * @return the visible tokens, newest first (never the raw token or its hash)
   */
  public List<ServiceTokenView> list() {
    Optional<TenantContext> tenant = TenantContextHolder.current();
    List<ServiceTokenRow> rows;
    if (tenant.isPresent()) {
      rows = repository.listForTenant(tenant.get().tenantId());
    } else {
      requirePlatformAdmin();
      rows = repository.listPlatformScoped();
    }
    return rows.stream().map(ServiceTokenService::toView).toList();
  }

  /**
   * Revokes a service token in the caller's current scope.
   *
   * @param id the token id
   * @throws ResourceNotFoundException if no such token is visible in the caller's scope
   */
  public void revoke(UUID id) {
    Optional<TenantContext> tenant = TenantContextHolder.current();
    int updated;
    if (tenant.isPresent()) {
      updated = repository.revokeForTenant(id, tenant.get().tenantId());
    } else {
      requirePlatformAdmin();
      updated = repository.revokePlatformScoped(id);
    }
    if (updated == 0) {
      throw new ResourceNotFoundException("service token not found: " + id);
    }
    logEvent("auth.token.revoked", id, tenant.map(TenantContext::tenantId).orElse(null), null);
  }

  private void requirePlatformAdmin() {
    EipPrincipal principal = EipPrincipalHolder.current().orElseGet(EipPrincipal::anonymous);
    if (!principal.roles().contains(Role.PLATFORM_ADMIN)) {
      throw new PermissionDeniedException("platform-scoped service tokens require PLATFORM_ADMIN");
    }
  }

  private @Nullable UUID resolveTenantId(@Nullable Boolean platformScoped) {
    if (Boolean.TRUE.equals(platformScoped)) {
      requirePlatformAdmin();
      return null;
    }
    return TenantContextHolder.require().tenantId();
  }

  private static Role parseRole(String role) {
    try {
      return Role.valueOf(role);
    } catch (IllegalArgumentException unknownRole) {
      throw new ValidationException("unknown role: " + role);
    }
  }

  /**
   * Validates that an optional permission subset is a subset of the role's own permission set.
   *
   * @param role the bound role
   * @param requested the requested subset (enum names), or {@code null}/empty for "all"
   * @return the validated subset (empty meaning "all of the role's permissions")
   */
  private static List<String> validateSubset(Role role, @Nullable List<String> requested) {
    if (requested == null || requested.isEmpty()) {
      return List.of();
    }
    List<String> excess = new ArrayList<>();
    for (String name : requested) {
      Permission permission;
      try {
        permission = Permission.valueOf(name);
      } catch (IllegalArgumentException unknownPermission) {
        throw new ValidationException("unknown permission: " + name);
      }
      if (!role.permissions().contains(permission)) {
        excess.add(name);
      }
    }
    if (!excess.isEmpty()) {
      throw new ValidationException(
          "permission subset exceeds role "
              + role.name()
              + "'s permissions: "
              + String.join(", ", excess));
    }
    return List.copyOf(requested);
  }

  private static int clampExpiry(@Nullable Integer requested) {
    if (requested == null) {
      return DEFAULT_EXPIRY_DAYS;
    }
    if (requested <= 0 || requested > MAX_EXPIRY_DAYS) {
      throw new ValidationException(
          "expiresInDays must be in (0, " + MAX_EXPIRY_DAYS + "], got: " + requested);
    }
    return requested;
  }

  private static ServiceTokenView toView(ServiceTokenRow row) {
    return new ServiceTokenView(
        row.id(),
        row.name(),
        row.tokenPrefix(),
        row.role(),
        row.permissionSubset(),
        row.expiresAt(),
        row.lastUsedAt(),
        row.revoked(),
        row.createdAt());
  }

  private static void logEvent(
      String event, UUID tokenId, @Nullable UUID tenantId, @Nullable String role) {
    MDC.put("event", event);
    MDC.put("tokenId", tokenId.toString());
    if (tenantId != null) {
      MDC.put("scopeTenantId", tenantId.toString());
    }
    if (role != null) {
      MDC.put("role", role);
    }
    try {
      log.info("service token lifecycle event");
    } finally {
      MDC.remove("event");
      MDC.remove("tokenId");
      MDC.remove("scopeTenantId");
      MDC.remove("role");
    }
  }

  /**
   * A service-token creation request.
   *
   * @param name operator-facing label
   * @param role the {@code Role} enum name to bind
   * @param permissionSubset an optional narrowed permission subset (enum names); {@code null}/empty
   *     means "all of the role's permissions"
   * @param expiresInDays the requested lifetime in days, clamped/validated to (0, 365], default 90
   * @param platformScoped {@code true} to mint a platform-scoped token (no tenant binding) — only
   *     honored when the caller holds {@code PLATFORM_ADMIN}
   */
  public record CreateServiceTokenCommand(
      String name,
      String role,
      @Nullable List<String> permissionSubset,
      @Nullable Integer expiresInDays,
      @Nullable Boolean platformScoped) {}

  /**
   * The one-time creation result. The raw token is NEVER retrievable again after this response.
   *
   * @param id the created token's id
   * @param token the raw bearer value — shown exactly once
   * @param prefix the clear-text prefix (safe to display in listings afterward)
   * @param expiresAt the computed expiry instant
   */
  public record CreateServiceTokenResult(UUID id, String token, String prefix, Instant expiresAt) {}

  /**
   * An admin-listing view. Never carries the raw token or its hash.
   *
   * @param id the token id
   * @param name operator-facing label
   * @param prefix the clear-text prefix
   * @param role the bound {@code Role} enum name
   * @param permissionSubset the narrowed permission subset (enum names), empty meaning "all"
   * @param expiresAt expiry instant
   * @param lastUsedAt last successful-use instant, or {@code null} if never used
   * @param revoked whether the token has been revoked
   * @param createdAt creation instant
   */
  public record ServiceTokenView(
      UUID id,
      String name,
      String prefix,
      String role,
      List<String> permissionSubset,
      Instant expiresAt,
      @Nullable Instant lastUsedAt,
      boolean revoked,
      Instant createdAt) {}
}
