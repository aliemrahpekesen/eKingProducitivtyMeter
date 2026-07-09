/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.tenant;

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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the resolved tenant to the request thread for the duration of the request and clears it
 * afterwards (threads are pooled, so the {@code finally} clear is mandatory). If no tenant
 * resolves, nothing is bound and downstream tenant-scoped reads fail closed via {@link
 * TenantContextHolder#require()} — never a silent default.
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
    Optional<TenantContext> tenant = resolver.resolve(request);
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
}
