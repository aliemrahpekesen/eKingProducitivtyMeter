/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.application.GetSessionQuery;
import com.eip.app.security.EipPrincipal;
import com.eip.app.security.EipPrincipalHolder;
import com.eip.app.security.EipSecurityProperties;
import com.eip.app.security.PermissionExempt;
import com.eip.app.security.RequiresPermission;
import com.eip.tenancy.rbac.Permission;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the current session's tenant identity and the pre-login auth-mode discovery — pure DTO
 * adapters (BackendPlan §2.4) over the {@link GetSessionQuery} port and {@link
 * EipSecurityProperties}.
 */
@RestController
@RequestMapping("/api/v1")
public class SessionController {

  private final GetSessionQuery session;
  private final EipSecurityProperties securityProperties;
  private final @Nullable String issuerUri;

  /**
   * Creates the controller.
   *
   * @param session the session-identity query port
   * @param securityProperties the active {@code eip.security.mode}
   * @param issuerUri the configured OIDC issuer (blank when unset)
   */
  public SessionController(
      GetSessionQuery session,
      EipSecurityProperties securityProperties,
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
    this.session = session;
    this.securityProperties = securityProperties;
    this.issuerUri = issuerUri.isBlank() ? null : issuerUri;
  }

  /**
   * Returns the current session's tenant identity plus the caller's effective permissions.
   *
   * <p>The permission set is drawn from the request principal ({@link EipPrincipalHolder}) — the
   * very set {@code PermissionEnforcementInterceptor} enforces, including tenant-editable role
   * overrides — mapped to stable {@link Permission#wireId()} strings and sorted ascending so the
   * response (and the committed OpenAPI snapshot) is byte-stable. It drives the frontend {@code
   * <Can>} gate; the backend interceptor remains the actual enforcement boundary.
   *
   * @return the resolved tenant, its organisation name (read under RLS), and the caller's effective
   *     permission wire ids (empty, never null, when no principal is bound)
   */
  @GetMapping("/session")
  @RequiresPermission(Permission.DASHBOARD_VIEW)
  public SessionView session() {
    return session.current().withEffectivePermissions(effectivePermissionWireIds());
  }

  private static List<String> effectivePermissionWireIds() {
    return EipPrincipalHolder.current().map(EipPrincipal::permissions).orElseGet(Set::of).stream()
        .map(Permission::wireId)
        .sorted()
        .toList();
  }

  /**
   * Pre-login auth-mode discovery (SecurityModel §3): tells the frontend whether to speak {@code
   * X-EIP-Tenant} or start an OIDC Authorization Code + PKCE flow, and against which issuer. {@code
   * permitAll} by design — a client cannot present credentials it does not yet know the shape of.
   *
   * @return the active auth mode + OIDC coordinates (null in header mode)
   */
  @GetMapping("/session/auth")
  @PermissionExempt
  public AuthConfigView authConfig() {
    boolean oidc = securityProperties.oidc();
    return new AuthConfigView(
        oidc ? "OIDC" : "HEADER", oidc ? issuerUri : null, oidc ? "eip-frontend" : null);
  }
}
