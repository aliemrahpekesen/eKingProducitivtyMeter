/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.app.persistence.ConnectorCursor.InvalidCursorException;
import com.eip.core.error.EipException;
import com.eip.core.error.InternalException;
import com.eip.core.error.PermissionDeniedException;
import com.eip.core.error.ResourceNotFoundException;
import com.eip.core.error.ValidationException;
import com.eip.ingestion.api.SourceSyncException;
import com.eip.tenancy.context.TenantContextHolder.NoTenantBoundException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates errors into RFC 7807 problem+json (APIDesign §5/§7). Spring renders {@link
 * ProblemDetail} as {@code application/problem+json}. {@code type} URIs follow the BackendPlan §10
 * {@code /problems/*} taxonomy. The catalogue grows with the API surface; the full sealed {@code
 * com.eip.core.error} hierarchy (BackendPlan §10) lands with the error-taxonomy work.
 *
 * <p>Every problem body carries the {@code traceId} of the active OTel span (BackendPlan §10), so a
 * client error can be walked straight to its trace and logs (TASK-0012).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private final Tracer tracer;

  public ApiExceptionHandler(Tracer tracer) {
    this.tracer = tracer;
  }

  /**
   * No tenant could be resolved for the request (dev: missing/invalid {@code X-EIP-Tenant}; prod:
   * unauthenticated). Fails closed rather than leaking or defaulting.
   *
   * @param e the failure
   * @return a 401 problem+json
   */
  @ExceptionHandler(NoTenantBoundException.class)
  public ProblemDetail handleNoTenant(NoTenantBoundException e) {
    return problem(
        HttpStatus.UNAUTHORIZED,
        "No tenant is bound to this request.",
        "Tenant required",
        "/problems/unauthenticated");
  }

  /**
   * A client supplied a malformed pagination cursor.
   *
   * @param e the failure
   * @return a 400 problem+json
   */
  @ExceptionHandler(InvalidCursorException.class)
  public ProblemDetail handleInvalidCursor(InvalidCursorException e) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "The pagination cursor is invalid; omit it to start over.",
        "Invalid cursor",
        "/problems/validation");
  }

  /**
   * A connector sync failed against the upstream source (unreachable, auth rejected, or non-2xx).
   *
   * @param e the failure
   * @return a 502 problem+json
   */
  @ExceptionHandler(SourceSyncException.class)
  public ProblemDetail handleSourceSync(SourceSyncException e) {
    return problem(
        HttpStatus.BAD_GATEWAY, message(e), "Source sync failed", "/problems/source-sync");
  }

  /**
   * Maps Jakarta Bean Validation failures on request bodies to 400 problem+json (BackendPlan §11).
   *
   * @param e the failure
   * @return a 400 problem+json listing the first offending field
   */
  @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
  public ProblemDetail handleBeanValidation(
      org.springframework.web.bind.MethodArgumentNotValidException e) {
    String detail =
        e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(f -> f.getField() + " " + f.getDefaultMessage())
            .orElse("request validation failed");
    return problem(HttpStatus.BAD_REQUEST, detail, "Validation failed", "/problems/validation");
  }

  /**
   * Maps the sealed {@code com.eip.core.error} taxonomy to problem+json (BackendPlan §10) with an
   * exhaustive switch. Internal errors return a generic detail — never the exception message.
   *
   * @param e the failure
   * @return the taxonomy-mapped problem+json
   */
  @ExceptionHandler(EipException.class)
  public ProblemDetail handleEip(EipException e) {
    return switch (e) {
      case ValidationException v ->
          problem(HttpStatus.BAD_REQUEST, message(v), "Validation failed", "/problems/validation");
      case ResourceNotFoundException n ->
          problem(HttpStatus.NOT_FOUND, message(n), "Not found", "/problems/not-found");
      case PermissionDeniedException p ->
          problem(
              HttpStatus.FORBIDDEN, message(p), "Permission denied", "/problems/permission-denied");
      case InternalException i ->
          problem(
              HttpStatus.INTERNAL_SERVER_ERROR,
              "An internal error occurred; contact support with the traceId.",
              "Internal error",
              "/problems/internal");
    };
  }

  private static String message(Throwable e) {
    @Nullable String message = e.getMessage();
    return message == null ? "Request failed." : message;
  }

  private ProblemDetail problem(HttpStatusCode status, String detail, String title, String type) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setType(URI.create(type));
    @Nullable Span span = tracer.currentSpan();
    if (span != null) {
      problem.setProperty("traceId", span.context().traceId());
    }
    return problem;
  }
}
