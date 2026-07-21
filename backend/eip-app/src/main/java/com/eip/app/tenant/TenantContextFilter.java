/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.tenant;

import com.eip.app.security.ServiceTokenAuthentication;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the resolved tenant to the request thread for the duration of the request and clears it
 * afterwards (threads are pooled, so the {@code finally} clear is mandatory). If no tenant
 * resolves, nothing is bound and downstream tenant-scoped reads fail closed via {@link
 * TenantContextHolder#require()} — never a silent default.
 *
 * <p>A service token (SecurityModel §3) is checked FIRST, regardless of {@code eip.security.mode}:
 * if {@link com.eip.app.security.ServiceTokenAuthenticationFilter} authenticated this request, the
 * token's own bound tenant is used — a spoofed {@code X-EIP-Tenant} header is ignored exactly as a
 * spoofed one is in {@code oidc} mode (mirroring {@code OidcTenantResolver}) — and a
 * platform-scoped token (no bound tenant) binds no tenant at all, regardless of any header, so a
 * platform-scoped token can never widen into tenant-scoped data access via a header. Only when no
 * service token authenticated the request does {@link #resolver} (the mode-specific {@link
 * TenantResolver}) run.
 *
 * <p>Also mirrors the tenant onto the SLF4J {@link MDC} under {@value #MDC_TENANT_ID} so every log
 * line for the request carries it (ObservabilityModel §4). {@code traceId}/{@code spanId} are put
 * in the MDC by the OTel tracing scope, not here. The {@code finally} removal is mandatory — on a
 * pooled thread a leaked {@code tenantId} would mislabel the next request's logs.
 */
@Component
@Order(10)
public class TenantContextFilter extends OncePerRequestFilter {

  /** MDC key carrying the current tenant id (ObservabilityModel §4 log schema). */
  public static final String MDC_TENANT_ID = "tenantId";

  private final TenantResolver resolver;

  public TenantContextFilter(TenantResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<TenantContext> tenant = resolveTenant(request);
    tenant.ifPresent(
        t -> {
          TenantContextHolder.set(t);
          MDC.put(MDC_TENANT_ID, t.tenantId().toString());
        });
    try {
      chain.doFilter(request, response);
    } finally {
      TenantContextHolder.clear();
      MDC.remove(MDC_TENANT_ID);
    }
  }

  private Optional<TenantContext> resolveTenant(HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof ServiceTokenAuthentication serviceTokenAuth) {
      // A tenant-scoped token binds its own tenant (header ignored, matching OidcTenantResolver's
      // treatment of a spoofed header); a platform-scoped token (no bound tenant) binds nothing at
      // all, regardless of any header present — it must never widen into tenant-scoped access.
      return Optional.ofNullable(serviceTokenAuth.getPrincipal().tenantId()).map(TenantContext::of);
    }
    return resolver.resolve(request);
  }
}
