/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import com.eip.core.error.PermissionDeniedException;
import com.eip.tenancy.rbac.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Deny-by-default RBAC enforcement (SecurityModel §4, FR-122) over the {@code /api/v1} handler
 * mapping (registered for that path pattern only — see {@code WebMvcSecurityConfig}). Runs the same
 * check regardless of {@code eip.security.mode}: header mode's implicit {@code TENANT_ADMIN}
 * principal (see {@link EipPrincipalFilter}) means RBAC is genuinely exercised in every mode, not
 * bypassed in the dev/demo one.
 *
 * <p>Throws {@link PermissionDeniedException} (the existing sealed {@code EipException} leaf,
 * already mapped to a 403 {@code /problems/permission-denied} body by {@code ApiExceptionHandler})
 * rather than writing a response directly — thrown from {@link HandlerInterceptor#preHandle}, this
 * propagates through Spring MVC's normal exception resolution exactly like a controller-thrown
 * exception, so no new problem+json wiring is needed.
 *
 * <p><strong>{@code access.denied} audit retrofit deliberately deferred (DEBT-024 Wave
 * 3B).</strong> SecurityModel §11's broader taxonomy names {@code access.denied}, but it is NOT in
 * DEBT-024's explicit Wave 3B retrofit list (secret reveal, tenant/role mutations, service-token
 * lifecycle) — only those four sites were instrumented this wave. Wiring it here is materially more
 * entangled than the four sites that were done: {@link #preHandle} runs pre-handler, with NO active
 * transaction (the audit write path requires one, {@code AuditService}/{@code
 * RecordAuditEventUseCase} javadoc) and, for an unauthenticated caller, no tenant bound either
 * (only {@link EipPrincipal#tenantId()} would even be available to bind, and it is {@code null} for
 * the 401-ish/anonymous case this method also guards) — a correct retrofit would need to (a) only
 * attempt the write when a tenant IS resolvable, wrapped in its own {@code
 * TenantTransactionRunner#run}, and (b) never let that write's failure mask the more important
 * {@link PermissionDeniedException} already being thrown. Left for a follow-up wave rather than
 * forced in here.
 */
@Component
public class PermissionEnforcementInterceptor implements HandlerInterceptor {

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod handlerMethod)) {
      // Not a controller method (e.g. an unmatched/static handler) — nothing to enforce.
      return true;
    }
    if (handlerMethod.hasMethodAnnotation(PermissionExempt.class)) {
      return true;
    }
    RequiresPermission requires = handlerMethod.getMethodAnnotation(RequiresPermission.class);
    if (requires == null) {
      throw new PermissionDeniedException(
          "endpoint declares no permission (deny-by-default, FR-122): "
              + handlerMethod.getBeanType().getSimpleName()
              + "#"
              + handlerMethod.getMethod().getName()
              + " must add @RequiresPermission or @PermissionExempt");
    }
    Set<Permission> required = Set.of(requires.value());
    EipPrincipal principal = EipPrincipalHolder.current().orElseGet(EipPrincipal::anonymous);
    if (!principal.hasAnyOf(required)) {
      String missing = required.stream().map(Permission::wireId).collect(Collectors.joining(", "));
      throw new PermissionDeniedException("missing permission: " + missing);
    }
    return true;
  }
}
