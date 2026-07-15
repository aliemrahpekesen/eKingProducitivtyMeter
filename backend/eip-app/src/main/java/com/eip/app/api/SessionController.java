/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.application.GetSessionQuery;
import com.eip.app.security.EipSecurityProperties;
import com.eip.app.security.PermissionExempt;
import com.eip.app.security.RequiresPermission;
import com.eip.tenancy.rbac.Permission;
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
   * Returns the current session's tenant identity.
   *
   * @return the resolved tenant and its organisation name (read under RLS)
   */
  @GetMapping("/session")
  @RequiresPermission(Permission.DASHBOARD_VIEW)
  public SessionView session() {
    return session.current();
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
