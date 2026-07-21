/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import com.eip.tenancy.rbac.Permission;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The Spring Security {@code Authentication} a valid service token resolves to (SecurityModel §3),
 * set by {@link ServiceTokenAuthenticationFilter} and read by {@link EipPrincipalFilter} (RBAC) and
 * {@link com.eip.app.tenant.TenantContextFilter} (RLS tenant binding) exactly like the {@code oidc}
 * mode's {@code JwtAuthenticationToken} is — the third, mode-independent authentication path
 * SecurityModel §3 describes running "the same request path from the JWT-validation step onward."
 *
 * <p>Carries the fully-resolved {@link EipPrincipal} as its principal — never the raw token or its
 * hash, and {@link #getCredentials()} always returns an empty string (a service token is validated
 * once, by the filter, and never re-examined downstream).
 */
public final class ServiceTokenAuthentication extends AbstractAuthenticationToken {

  private final UUID tokenId;
  private final EipPrincipal eipPrincipal;

  /**
   * Creates an authenticated token for a validated service token.
   *
   * @param tokenId the {@code core.service_token.id} of the presented token
   * @param eipPrincipal the resolved principal (tenant, role, effective permissions)
   */
  public ServiceTokenAuthentication(UUID tokenId, EipPrincipal eipPrincipal) {
    super(toAuthorities(eipPrincipal));
    this.tokenId = tokenId;
    this.eipPrincipal = eipPrincipal;
    setAuthenticated(true);
  }

  private static Set<GrantedAuthority> toAuthorities(EipPrincipal principal) {
    return principal.permissions().stream()
        .map(Permission::wireId)
        .map(SimpleGrantedAuthority::new)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Returns the resolved principal this authentication carries.
   *
   * @return the principal ({@link EipPrincipalFilter} reads this directly)
   */
  @Override
  public EipPrincipal getPrincipal() {
    return eipPrincipal;
  }

  /**
   * Never the raw token or its hash — a service token is validated once by the filter, not
   * re-examined downstream.
   *
   * @return an empty string
   */
  @Override
  public Object getCredentials() {
    return "";
  }

  /**
   * Returns the presented token's row id (for logs/audit — never trusted as a permission source).
   *
   * @return the {@code core.service_token.id}
   */
  public UUID tokenId() {
    return tokenId;
  }
}
