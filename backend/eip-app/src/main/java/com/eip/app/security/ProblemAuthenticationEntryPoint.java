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
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * {@code oidc}-mode 401 handler (SecurityModel §3): missing/invalid/expired bearer tokens, and a
 * token missing the {@code eip_tenant} claim (rejected earlier by {@link EipTenantClaimValidator},
 * surfacing here as {@code exception.getMessage()} == {@code "token carries no tenant"}), all
 * render as {@code application/problem+json}, mirroring {@code ApiExceptionHandler}'s {@code
 * NoTenantBoundException} shape ({@code /problems/unauthenticated}).
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private static final String DEFAULT_DETAIL = "Authentication is required.";

  private final ObjectMapper mapper;
  private final Tracer tracer;

  public ProblemAuthenticationEntryPoint(ObjectMapper mapper, Tracer tracer) {
    this.mapper = mapper;
    this.tracer = tracer;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    ProblemResponses.write(
        response,
        mapper,
        tracer,
        HttpStatus.UNAUTHORIZED,
        "/problems/unauthenticated",
        "Unauthenticated",
        detail(authException));
  }

  private static String detail(AuthenticationException authException) {
    @Nullable String message = authException.getMessage();
    return (message == null || message.isBlank()) ? DEFAULT_DETAIL : message;
  }
}
