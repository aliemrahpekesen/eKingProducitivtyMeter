/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import com.eip.core.error.PermissionDeniedException;
import com.eip.tenancy.audit.api.AuditActorType;
import com.eip.tenancy.audit.api.AuditCategory;
import com.eip.tenancy.audit.api.AuditEvent;
import com.eip.tenancy.audit.api.AuditOutcome;
import com.eip.tenancy.audit.api.RecordAuditEventUseCase;
import com.eip.tenancy.context.TenantContext;
import com.eip.tenancy.rbac.Permission;
import com.eip.tenancy.tx.TenantTransactionRunner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

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
 * <p><strong>{@code access.denied} audit retrofit (DEBT-024's Wave-3B-deferred residual, now
 * closed).</strong> A caller who is authenticated (a tenant IS resolvable off {@link
 * EipPrincipal#tenantId()}) but lacks the required permission gets one {@code access.denied} row,
 * best-effort, in its own short transaction ({@link #preHandle} has no ambient one — mirrors {@code
 * ServiceTokenService}/{@code TenantAdminService}'s {@link TenantTransactionRunner#run} wrapping).
 * The unauthenticated/no-tenant case (including the "endpoint declares no permission" deploy-time
 * guard below, which never has a specific required permission to report) intentionally writes no
 * row — {@code audit.audit_event} is tenant- partitioned and there is nothing to bind RLS to. The
 * audit write can never affect the denial outcome: any failure (including {@link AuditEvent}'s own
 * constructor validation) is caught here, logged at WARN, and swallowed — on top of {@code
 * AuditService}'s own failure-swallowing contract.
 *
 * <p>{@link AuditActorType#USER} requires a non-null {@code actorMemberId} ({@link AuditEvent}'s
 * constructor invariant), and {@link EipPrincipal} carries no member-UUID accessor yet (member-id
 * resolution off {@code core.member.oidc_subject} is a separate, not-yet-claimed DEBT-024 residual)
 * — so, exactly like {@code ServiceTokenService#resolveActorType}, only a service-token-
 * authenticated caller is distinguishable today ({@code "service-token:"}-prefixed {@link
 * EipPrincipal#subject()}); every other denied caller is tagged {@link AuditActorType#SYSTEM}.
 */
@Component
public class PermissionEnforcementInterceptor implements HandlerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(PermissionEnforcementInterceptor.class);

  private final @Nullable RecordAuditEventUseCase audit;
  private final @Nullable TenantTransactionRunner tx;

  /**
   * Creates the interceptor.
   *
   * @param audit the audit write path, or {@code null} to disable {@code access.denied} recording
   *     (no test in this codebase constructs this interceptor directly today — Spring's own
   *     component-scan injection always supplies real beans — but the dependency is kept
   *     {@code @Nullable} and null-skipped to mirror {@code ServiceTokenService}/{@code
   *     TenantAdminService} 's dual-construction accommodation, so a future direct-construction
   *     test still compiles)
   * @param tx binds RLS to the denied caller's own tenant for the audit write; {@code null}
   *     disables recording the same way
   */
  public PermissionEnforcementInterceptor(
      @Nullable RecordAuditEventUseCase audit, @Nullable TenantTransactionRunner tx) {
    this.audit = audit;
    this.tx = tx;
  }

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
      List<String> missing = required.stream().map(Permission::wireId).sorted().toList();
      recordAccessDenied(request, handlerMethod, principal, missing);
      throw new PermissionDeniedException("missing permission: " + String.join(", ", missing));
    }
    return true;
  }

  /**
   * Best-effort {@code access.denied} audit write for a caller with a resolvable tenant lacking the
   * required permission. Never throws — any failure, including one from {@link AuditEvent}'s own
   * constructor validation, is caught and logged at WARN so a broken audit path can never turn this
   * 403 into a 500 or suppress the denial that is about to be thrown by the caller.
   *
   * @param request the current request (source of the HTTP method)
   * @param handlerMethod the resolved handler (source of the route identifier)
   * @param principal the denied caller's principal
   * @param requiredPermissions the endpoint's required permission wire ids, sorted
   */
  private void recordAccessDenied(
      HttpServletRequest request,
      HandlerMethod handlerMethod,
      EipPrincipal principal,
      List<String> requiredPermissions) {
    if (audit == null || tx == null) {
      return;
    }
    @Nullable UUID tenantId = principal.tenantId();
    if (tenantId == null) {
      // No tenant resolvable (unauthenticated/anonymous caller) — audit.audit_event is
      // tenant-partitioned, nothing to bind RLS to; keep existing (no-audit) behavior.
      return;
    }
    String method = request.getMethod();
    String route = routeIdentifier(request, handlerMethod);
    try {
      AuditActorType actorType = resolveActorType(principal);
      Map<String, Object> detail =
          Map.of("requiredPermission", requiredPermissions, "method", method, "route", route);
      tx.run(
          TenantContext.of(tenantId),
          () ->
              audit.record(
                  new AuditEvent(
                      AuditCategory.ACCESS,
                      "access.denied",
                      AuditOutcome.FAILURE,
                      actorType,
                      null,
                      detail)));
    } catch (RuntimeException e) {
      log.warn(
          "access.denied audit write failed for {} {}; permission denial still enforced",
          method,
          route,
          e);
    }
  }

  /**
   * A PII-free, non-parameterized identifier for the denied route: the matched handler-mapping
   * pattern (e.g. {@code /api/v1/friction/teams/{teamId}/evidence} — a template, never a resolved
   * id) when available, else the {@link HandlerMethod}'s {@code Controller#method} name.
   */
  private static String routeIdentifier(HttpServletRequest request, HandlerMethod handlerMethod) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (pattern instanceof String patternString && !patternString.isBlank()) {
      return patternString;
    }
    return handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName();
  }

  /**
   * See the class javadoc: mirrors {@code ServiceTokenService#resolveActorType} exactly — {@link
   * AuditActorType#USER} cannot be populated without a real {@code actorMemberId}, which is not yet
   * resolvable from {@link EipPrincipal}.
   */
  private static AuditActorType resolveActorType(EipPrincipal principal) {
    return principal.subject().startsWith("service-token:")
        ? AuditActorType.SERVICE_TOKEN
        : AuditActorType.SYSTEM;
  }
}
