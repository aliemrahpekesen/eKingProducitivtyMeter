/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.observability;

import com.eip.tenancy.context.TenantContextHolder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records the RED metric and one structured log line per API request (ObservabilityModel §3–§4).
 * Runs inside {@link com.eip.app.tenant.TenantContextFilter} (higher order) so the tenant is still
 * bound and its {@code tenantId} MDC value is present when this filter measures and logs, and
 * inside the OTel server-observation scope so {@code traceId}/{@code spanId} are in the MDC too.
 *
 * <p>Cardinality is bounded on purpose: the {@code route} tag is the matched handler
 * <em>template</em> (never the raw path), and the tenant is recorded only as a {@code
 * tenant_present} boolean — never the tenant id, which would be an unbounded, per-tenant-explosive
 * label (ObservabilityModel §3).
 */
@Component
@Order(20)
public class ApiObservabilityFilter extends OncePerRequestFilter {

  /** RED latency histogram for {@code /api/v1} — {@code eip_api_request_duration_seconds}. */
  static final String METRIC_REQUEST_DURATION = "eip.api.request.duration";

  /** Saturation gauge — {@code eip_api_requests_inflight}. */
  static final String METRIC_INFLIGHT = "eip.api.requests.inflight";

  /** Bucket for requests that matched no handler template, to bound {@code route} cardinality. */
  static final String ROUTE_UNMATCHED = "UNMATCHED";

  private static final Logger log = LoggerFactory.getLogger("com.eip.app.observability.access");

  private final MeterRegistry registry;
  private final AtomicInteger inflight = new AtomicInteger();

  public ApiObservabilityFilter(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder(METRIC_INFLIGHT, inflight, AtomicInteger::doubleValue)
        .description("In-flight API requests")
        .register(registry);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // Instrument the application API only — not the actuator scrape surface or the OpenAPI doc.
    String path = request.getRequestURI();
    return path.startsWith("/actuator") || path.startsWith("/v3/api-docs");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long startNanos = System.nanoTime();
    inflight.incrementAndGet();
    @Nullable Throwable error = null;
    try {
      chain.doFilter(request, response);
    } catch (ServletException | IOException | RuntimeException e) {
      error = e;
      throw e;
    } finally {
      inflight.decrementAndGet();
      record(request, response, System.nanoTime() - startNanos, error);
    }
  }

  private void record(
      HttpServletRequest request,
      HttpServletResponse response,
      long durationNanos,
      @Nullable Throwable error) {
    String method = request.getMethod();
    int status = response.getStatus();
    String route = route(request);
    boolean tenantPresent = TenantContextHolder.current().isPresent();

    Timer.builder(METRIC_REQUEST_DURATION)
        .description("API request latency (RED)")
        .tag("method", method)
        .tag("route", route)
        .tag("status", Integer.toString(status))
        .tag("tenant_present", Boolean.toString(tenantPresent))
        .register(registry)
        .record(durationNanos, TimeUnit.NANOSECONDS);

    long durationMs = TimeUnit.NANOSECONDS.toMillis(durationNanos);
    @Nullable String errorType = error == null ? null : error.getClass().getName();

    // Structured request log. tenantId/traceId/spanId are already in the MDC; add bounded,
    // allow-listed request context (no secrets, no PII — ObservabilityModel §4 redaction rules).
    MDC.put("method", method);
    MDC.put("route", route);
    MDC.put("status", Integer.toString(status));
    MDC.put("durationMs", Long.toString(durationMs));
    if (errorType != null) {
      MDC.put("errorType", errorType);
    }
    try {
      if (status >= 500 || error != null) {
        log.error("api.request");
      } else if (status >= 400) {
        log.warn("api.request");
      } else {
        log.info("api.request");
      }
    } finally {
      MDC.remove("method");
      MDC.remove("route");
      MDC.remove("status");
      MDC.remove("durationMs");
      MDC.remove("errorType");
    }
  }

  /** The matched handler template (low cardinality), or {@link #ROUTE_UNMATCHED} for no match. */
  private static String route(HttpServletRequest request) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return (pattern instanceof String template) ? template : ROUTE_UNMATCHED;
  }
}
