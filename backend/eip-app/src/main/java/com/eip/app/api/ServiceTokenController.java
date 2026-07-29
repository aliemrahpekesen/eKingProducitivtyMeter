/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.application.ServiceTokenService;
import com.eip.app.application.ServiceTokenService.CreateServiceTokenCommand;
import com.eip.app.application.ServiceTokenService.CreateServiceTokenResult;
import com.eip.app.application.ServiceTokenService.ServiceTokenView;
import com.eip.app.security.RequiresPermission;
import com.eip.tenancy.rbac.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-token administration (SecurityModel §3): issue, list, revoke long-lived CI/script
 * credentials. Pure DTO adapter over {@link ServiceTokenService}.
 *
 * <p>RBAC: every endpoint requires {@link Permission#USER_MANAGE} — SecurityModel §3 states service
 * tokens are "created by TENANT_ADMIN (tenant-scoped) or PLATFORM_ADMIN (platform-scoped)", and
 * {@code user.manage} is the permission catalog cell both roles hold (SecurityModel §4) while every
 * lesser role lacks it. The platform-scoped path within {@link #create} is additionally gated
 * inside {@link ServiceTokenService} itself: a caller lacking {@code PLATFORM_ADMIN} who requests
 * {@code platformScoped=true} is refused there, not here (defense in depth — the controller's RBAC
 * check alone cannot distinguish "TENANT_ADMIN creating their own tenant's token" from
 * "TENANT_ADMIN attempting a platform-scoped one", since both hold {@code user.manage}).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class ServiceTokenController {

  private final ServiceTokenService tokens;

  public ServiceTokenController(ServiceTokenService tokens) {
    this.tokens = tokens;
  }

  /**
   * Issues a new service token. The raw bearer value is returned in this response body EXACTLY ONCE
   * — it is never stored in reversible form and can never be retrieved again; losing it means
   * revoking this token and issuing a new one.
   *
   * @param request the creation request
   * @return the created token, including the one-time raw value
   */
  @PostMapping("/service-tokens")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresPermission(Permission.USER_MANAGE)
  public ServiceTokenCreatedView create(@Valid @RequestBody CreateServiceTokenRequest request) {
    CreateServiceTokenResult result =
        tokens.create(
            new CreateServiceTokenCommand(
                request.name(),
                request.role(),
                request.permissionSubset(),
                request.expiresInDays(),
                request.platformScoped()));
    return new ServiceTokenCreatedView(
        result.id(), result.token(), result.prefix(), request.role(), result.expiresAt());
  }

  /**
   * Lists service tokens in the caller's current scope (the bound tenant's own tokens, or —
   * platform-scoped, PLATFORM_ADMIN only — when no tenant is bound).
   *
   * @return the visible tokens, newest first; never the raw token or its hash
   */
  @GetMapping("/service-tokens")
  @RequiresPermission(Permission.USER_MANAGE)
  public List<ServiceTokenView> list() {
    return tokens.list();
  }

  /**
   * Revokes a service token immediately.
   *
   * @param id the token id
   */
  @DeleteMapping("/service-tokens/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresPermission(Permission.USER_MANAGE)
  public void revoke(@PathVariable UUID id) {
    tokens.revoke(id);
  }

  /**
   * Service-token creation payload.
   *
   * @param name operator-facing label
   * @param role the {@code Role} enum name to bind (e.g. {@code "ANALYST"})
   * @param permissionSubset an optional narrowed permission subset (enum names); omit for "all of
   *     the role's permissions"
   * @param expiresInDays requested lifetime in days, clamped/validated to (0, 365]; omit for the
   *     90-day default
   * @param platformScoped {@code true} to mint a platform-scoped token (no tenant binding) — only
   *     honored for a caller holding {@code PLATFORM_ADMIN}, refused otherwise
   */
  public record CreateServiceTokenRequest(
      @NotBlank String name,
      @NotBlank String role,
      @Nullable List<String> permissionSubset,
      @Nullable Integer expiresInDays,
      @Nullable Boolean platformScoped) {}

  /**
   * The one-time creation response. {@code token} is the raw bearer value — displayed to the caller
   * exactly once; store it now, it cannot be retrieved again.
   *
   * @param id the created token's id
   * @param token the raw bearer value ({@code Authorization: Bearer <token>})
   * @param prefix the clear-text prefix, safe to display afterward in listings
   * @param role the bound role
   * @param expiresAt the computed expiry instant
   */
  public record ServiceTokenCreatedView(
      UUID id, String token, String prefix, String role, Instant expiresAt) {}
}
