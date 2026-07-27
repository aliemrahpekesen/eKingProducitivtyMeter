/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.audit.application;

import com.eip.tenancy.audit.api.AuditCategory;
import com.eip.tenancy.audit.api.AuditEvent;
import com.eip.tenancy.audit.api.RecordAuditEventUseCase;
import com.eip.tenancy.audit.persistence.AuditEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes one {@code audit.audit_event} row per {@link #record} call, on the caller's ALREADY
 * tenant-bound transaction ({@code prev_hash}/{@code hash} are left {@code NULL} — the async
 * chainer sets them, SecurityModel §11). {@code detail.category}/{@code detail.actorType} are added
 * here so every future call site (Wave 3B) only supplies the taxonomy enums, never hand-builds
 * those keys; {@code trace_id} is read from the active OTel span, mirroring {@code
 * com.eip.app.security.ProblemResponses}'s exact {@link Tracer#currentSpan()} idiom — callers never
 * pass it.
 *
 * <p>A failed INSERT is caught here and never propagated: logging/auditing a business action must
 * never be able to fail that action. The failure is still surfaced — as a WARN log and the {@code
 * eip_audit_write_failures_total} counter — so a broken audit path is observable, per {@code
 * EipAuditSilence}'s alert on {@code eip_audit_events_total} going quiet (ObservabilityModel §7).
 */
@Service
public class AuditService implements RecordAuditEventUseCase {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  private final AuditEventRepository repository;
  private final Tracer tracer;
  private final Map<AuditCategory, Counter> writesByCategory;
  private final Counter writeFailures;

  /**
   * Creates the service.
   *
   * @param repository the audit-event repository
   * @param tracer the active tracer, for {@code trace_id}
   * @param registry the Micrometer registry
   */
  public AuditService(AuditEventRepository repository, Tracer tracer, MeterRegistry registry) {
    this.repository = repository;
    this.tracer = tracer;
    this.writesByCategory = new EnumMap<>(AuditCategory.class);
    for (AuditCategory category : AuditCategory.values()) {
      writesByCategory.put(
          category,
          Counter.builder("eip.audit.events")
              .description(
                  "Audit events written, by category (silence indicates a broken audit path)")
              .tag("category", category.wireValue())
              .register(registry));
    }
    this.writeFailures =
        Counter.builder("eip.audit.write.failures")
            .description(
                "Audit event INSERTs that failed and were suppressed rather than propagated to"
                    + " the caller's business transaction")
            .register(registry);
  }

  @Override
  public void record(AuditEvent event) {
    Map<String, Object> detail = new LinkedHashMap<>(event.detail());
    detail.put("category", event.category().wireValue());
    detail.put("actorType", event.actorType().name());
    try {
      repository.insert(
          event.action(), event.outcome(), event.actorMemberId(), currentTraceId(), detail);
      // Populated for every AuditCategory value in the constructor; never actually null.
      Objects.requireNonNull(writesByCategory.get(event.category())).increment();
    } catch (RuntimeException e) {
      writeFailures.increment();
      log.error(
          "audit event insert failed for action \"{}\" (category={}); suppressed from the"
              + " caller's transaction",
          event.action(),
          event.category().wireValue(),
          e);
    }
  }

  private @Nullable String currentTraceId() {
    @Nullable Span span = tracer.currentSpan();
    return span == null ? null : span.context().traceId();
  }
}
