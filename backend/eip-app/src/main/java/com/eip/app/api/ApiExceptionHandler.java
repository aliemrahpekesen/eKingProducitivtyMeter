/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.api;

import com.eip.tenancy.context.TenantContextHolder.NoTenantBoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates errors into RFC 7807 problem+json (APIDesign §7). Spring renders {@link ProblemDetail}
 * as {@code application/problem+json}. The catalogue grows with the API surface.
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
    problem.setType(URI.create("urn:eip:error:tenant-required"));
    return problem;
  }
}
