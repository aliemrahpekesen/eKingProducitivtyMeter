/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Security-filter-level 403 handler (SecurityModel §4), matching {@code
 * /problems/permission-denied}. In practice, {@link PermissionEnforcementInterceptor} is the RBAC
 * enforcement point (it throws {@code PermissionDeniedException}, mapped by {@code
 * ApiExceptionHandler}); this handler is defense-in-depth for a denial raised at the
 * security-filter layer itself, so the response shape is identical either way.
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper mapper;
  private final Tracer tracer;

  public ProblemAccessDeniedHandler(ObjectMapper mapper, Tracer tracer) {
    this.mapper = mapper;
    this.tracer = tracer;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    ProblemResponses.write(
        response,
        mapper,
        tracer,
        HttpStatus.FORBIDDEN,
        "/problems/permission-denied",
        "Permission denied",
        "The caller is authenticated but lacks the permission required for this action.");
  }
}
