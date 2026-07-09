/**
 * API-tier observability wiring (TASK-0012, ObservabilityModel §3–§4): the RED-metrics + structured
 * request-log filter over the {@code /api/v1} surface. Metrics use the {@code eip_*} names from the
 * ObservabilityModel catalog; the request log carries {@code tenantId}/{@code traceId}/{@code
 * spanId} (from the tenant filter + OTel scope) plus bounded request context. Tracing/metric
 * infrastructure itself is Spring Boot auto-configuration driven by {@code application.yaml}.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.app.observability;

import org.jspecify.annotations.NullMarked;
