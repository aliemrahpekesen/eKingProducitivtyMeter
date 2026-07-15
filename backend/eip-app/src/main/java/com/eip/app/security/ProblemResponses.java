/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/**
 * Writes an RFC 7807 {@code application/problem+json} body directly to the response, for the Spring
 * Security entry points that run outside MVC dispatch (no {@code @RestControllerAdvice} in scope).
 * Mirrors {@code ApiExceptionHandler}'s problem shape exactly, including the {@code traceId}
 * property, so a 401/403 from the security layer is indistinguishable in shape from one thrown
 * inside a controller.
 */
final class ProblemResponses {

  private ProblemResponses() {}

  /**
   * Writes a problem+json response.
   *
   * @param response the servlet response
   * @param mapper the shared Jackson mapper
   * @param tracer the active tracer, for the {@code traceId} property
   * @param status the HTTP status
   * @param type the {@code /problems/*} type URI
   * @param title the problem title
   * @param detail the problem detail
   * @throws IOException if writing the response body fails
   */
  static void write(
      HttpServletResponse response,
      ObjectMapper mapper,
      Tracer tracer,
      HttpStatus status,
      String type,
      String title,
      String detail)
      throws IOException {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setType(URI.create(type));
    @Nullable Span span = tracer.currentSpan();
    if (span != null) {
      problem.setProperty("traceId", span.context().traceId());
    }
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    mapper.writeValue(response.getWriter(), problem);
  }
}
