/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.api.ConnectorCursor.InvalidCursorException;
import com.eip.tenancy.context.TenantContextHolder.NoTenantBoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates errors into RFC 7807 problem+json (APIDesign §5/§7). Spring renders {@link
 * ProblemDetail} as {@code application/problem+json}. {@code type} URIs follow the BackendPlan §10
 * {@code /problems/*} taxonomy. The catalogue grows with the API surface; the full sealed {@code
 * com.eip.core.error} hierarchy (BackendPlan §10) lands with the error-taxonomy work.
 *
 * <p>{@code traceId} on the problem body (BackendPlan §10) is delivered with the observability
 * wiring that populates the OTel/{@code traceparent} MDC (DEBT-009 → TASK-0012).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  /**
   * No tenant could be resolved for the request (dev: missing/invalid {@code X-EIP-Tenant}; prod:
   * unauthenticated). Fails closed rather than leaking or defaulting.
   *
   * @param e the failure
   * @return a 401 problem+json
   */
  @ExceptionHandler(NoTenantBoundException.class)
  public ProblemDetail handleNoTenant(NoTenantBoundException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "No tenant is bound to this request.");
    problem.setTitle("Tenant required");
    problem.setType(URI.create("/problems/unauthenticated"));
    return problem;
  }

  /**
   * A client supplied a malformed pagination cursor.
   *
   * @param e the failure
   * @return a 400 problem+json
   */
  @ExceptionHandler(InvalidCursorException.class)
  public ProblemDetail handleInvalidCursor(InvalidCursorException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "The pagination cursor is invalid; omit it to start over.");
    problem.setTitle("Invalid cursor");
    problem.setType(URI.create("/problems/validation"));
    return problem;
  }
}
