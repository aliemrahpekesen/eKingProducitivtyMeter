/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.tenant;

import com.eip.tenancy.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the resolved tenant to the request thread for the duration of the request and clears it
 * afterwards (threads are pooled, so the {@code finally} clear is mandatory). If no tenant
 * resolves, nothing is bound and downstream tenant-scoped reads fail closed via {@link
 * TenantContextHolder#require()} — never a silent default.
 */
@Component
@Order(10)
public class TenantContextFilter extends OncePerRequestFilter {

  private final TenantResolver resolver;

  public TenantContextFilter(TenantResolver resolver) {
    this.resolver = resolver;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    resolver.resolve(request).ifPresent(TenantContextHolder::set);
    try {
      chain.doFilter(request, response);
    } finally {
      TenantContextHolder.clear();
    }
  }
}
