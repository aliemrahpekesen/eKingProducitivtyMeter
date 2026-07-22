/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.app.application.ServiceTokenService.CreateServiceTokenCommand;
import com.eip.app.application.ServiceTokenService.CreateServiceTokenResult;
import com.eip.app.persistence.InMemoryServiceTokenStore;
import com.eip.app.security.EipPrincipal;
import com.eip.app.security.EipPrincipalHolder;
import com.eip.core.error.PermissionDeniedException;
import com.eip.core.error.ResourceNotFoundException;
import com.eip.core.error.ValidationException;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import com.eip.tenancy.context.TenantContextHolder.NoTenantBoundException;
import com.eip.tenancy.rbac.Role;
import com.eip.tenancy.rbac.ServiceTokenGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-level proof of {@link ServiceTokenService}'s validation and scoping rules against a
 * hand-rolled {@link InMemoryServiceTokenStore} fake (TestingStrategy §2 — no mocking of this
 * EIP-owned collaborator). Auth-path behavior (the filter, real hash lookups) is proven separately
 * against a real database by {@code ServiceTokenAuthIntegrationTest}.
 */
class ServiceTokenServiceTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
  private static final UUID TENANT_A = UUID.randomUUID();

  private final InMemoryServiceTokenStore store = new InMemoryServiceTokenStore();
  private final ServiceTokenService service =
      new ServiceTokenService(store, new ServiceTokenGenerator(), FIXED_CLOCK);

  @AfterEach
  void clearContext() {
    TenantContextHolder.clear();
  }

  @Test
  void create_defaultsToNinetyDayExpiryAndTenantScopedByTheBoundTenant() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    CreateServiceTokenResult result =
        service.create(new CreateServiceTokenCommand("ci token", "ANALYST", null, null, null));

    assertThat(result.token()).startsWith(ServiceTokenGenerator.TOKEN_PREFIX);
    assertThat(result.expiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofDays(90)));
    InMemoryServiceTokenStore.Row row = store.allRows().get(0);
    assertThat(row.tenantId()).isEqualTo(TENANT_A);
    assertThat(row.role()).isEqualTo("ANALYST");
    assertThat(row.permissionSubset()).isEmpty();
  }

  @Test
  void create_clampsExpiryToTheRequestedValueWithinBounds() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    CreateServiceTokenResult result =
        service.create(new CreateServiceTokenCommand("ci token", "VIEWER", null, 30, null));

    assertThat(result.expiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofDays(30)));
  }

  @Test
  void create_rejectsAnExpiryOutsideZeroToThreeSixtyFive() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    assertThatThrownBy(
            () -> service.create(new CreateServiceTokenCommand("t", "VIEWER", null, 0, null)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> service.create(new CreateServiceTokenCommand("t", "VIEWER", null, 366, null)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void create_rejectsAnUnknownRole() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    assertThatThrownBy(
            () ->
                service.create(new CreateServiceTokenCommand("t", "NOT_A_ROLE", null, null, null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("NOT_A_ROLE");
  }

  @Test
  void create_persistsAValidNarrowedPermissionSubset() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    service.create(
        new CreateServiceTokenCommand("t", "TENANT_ADMIN", List.of("DASHBOARD_VIEW"), null, null));

    assertThat(store.allRows().get(0).permissionSubset()).containsExactly("DASHBOARD_VIEW");
  }

  @Test
  void create_rejectsAPermissionSubsetOutsideTheRolesOwnPermissions() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    // VIEWER's only permission is dashboard.view (Role.java); report.generate is not in it.
    assertThatThrownBy(
            () ->
                service.create(
                    new CreateServiceTokenCommand(
                        "t", "VIEWER", List.of("REPORT_GENERATE"), null, null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("REPORT_GENERATE");
  }

  @Test
  void create_rejectsAnUnknownPermissionNameInTheSubset() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateServiceTokenCommand(
                        "t", "VIEWER", List.of("NOT_A_PERMISSION"), null, null)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void create_withNoTenantBoundAndNoPlatformScopeRequested_failsClosed() {
    // No TenantContextHolder.set(...) call: no tenant bound, platformScoped absent.
    assertThatThrownBy(
            () -> service.create(new CreateServiceTokenCommand("t", "VIEWER", null, null, null)))
        .isInstanceOf(NoTenantBoundException.class);
  }

  @Test
  void create_platformScopedRequiresPlatformAdmin_deniedForATenantAdminCaller() {
    // No FakeEipPrincipal wiring here — EipPrincipalHolder has nothing bound, so the caller
    // resolves to EipPrincipal.anonymous(), which holds no roles at all, let alone PLATFORM_ADMIN.
    assertThatThrownBy(
            () ->
                service.create(
                    new CreateServiceTokenCommand("t", "TENANT_ADMIN", null, null, true)))
        .isInstanceOf(PermissionDeniedException.class);
  }

  @Test
  void create_platformScopedSucceedsForAPlatformAdminCaller() {
    EipPrincipalHolder.set(
        new EipPrincipal(
            null,
            Set.of(Role.PLATFORM_ADMIN),
            Role.PLATFORM_ADMIN.permissions(),
            "test-admin",
            Set.of()));
    try {
      CreateServiceTokenResult result =
          service.create(
              new CreateServiceTokenCommand("platform-ci", "TENANT_ADMIN", null, null, true));

      assertThat(store.allRows().get(0).tenantId()).isNull();
      assertThat(result.token()).isNotBlank();
    } finally {
      EipPrincipalHolder.clear();
    }
  }

  @Test
  void list_returnsTenantScopedRowsWhenATenantIsBound() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));
    service.create(new CreateServiceTokenCommand("t", "VIEWER", null, null, null));

    assertThat(service.list()).hasSize(1);
  }

  @Test
  void list_withNoTenantBoundRequiresPlatformAdmin() {
    assertThatThrownBy(service::list).isInstanceOf(PermissionDeniedException.class);
  }

  @Test
  void revoke_ofAnUnknownIdInScopeThrowsNotFound() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));

    assertThatThrownBy(() -> service.revoke(UUID.randomUUID()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void revoke_removesTheTokenFromTheCallersScope() {
    TenantContextHolder.set(TenantContext.of(TENANT_A));
    CreateServiceTokenResult created =
        service.create(new CreateServiceTokenCommand("t", "VIEWER", null, null, null));

    service.revoke(created.id());

    assertThat(service.list()).allSatisfy(v -> assertThat(v.revoked()).isTrue());
  }
}
