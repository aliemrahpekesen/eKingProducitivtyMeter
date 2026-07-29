/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.audit.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The command a call site passes to {@link RecordAuditEventUseCase#record}. {@code id}, {@code
 * tenantId}, {@code occurredAt}, and {@code traceId} are NOT here — they are the write path's own
 * concern (generated/derived at insert time), not the caller's (mirrors {@code ai.llm_call_audit}'s
 * {@code LlmCallAuditRepository}, which generates its own id rather than accepting one).
 *
 * <p><strong>PII minimization is normative (FR-142).</strong> {@code detail} — like every other
 * audit field — MUST contain only pseudonymous references: entity UUIDs, enum values, {@code
 * memberId}s. Never names, email addresses, secret values, or other free-text person PII. Member
 * PII lives exclusively in the dedicated identity-mapping store (SecurityModel §7); putting it here
 * instead would defeat FR-142 erasure (DatabasePlan §10.1).
 *
 * @param category the event-taxonomy category (stored in {@code detail.category})
 * @param action the dotted event id, e.g. {@code "secret.revealed"}, {@code "tenant.created"} (the
 *     {@code audit.audit_event.action} column)
 * @param outcome {@code SUCCESS} or {@code FAILURE}
 * @param actorType the acting principal's kind (stored in {@code detail.actorType})
 * @param actorMemberId the acting member's id, required iff {@code actorType == USER}; {@code null}
 *     for {@code SERVICE_TOKEN}/{@code WORKER}/{@code SYSTEM} actors (the {@code
 *     audit.audit_event.actor_member_id} column)
 * @param detail structured, PII-free context merged with {@code category}/{@code actorType} into
 *     the {@code audit.audit_event.detail} jsonb column — e.g. a {@code target} entity reference
 */
public record AuditEvent(
    AuditCategory category,
    String action,
    AuditOutcome outcome,
    AuditActorType actorType,
    @Nullable UUID actorMemberId,
    Map<String, Object> detail) {

  public AuditEvent {
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(action, "action");
    if (action.isBlank()) {
      throw new IllegalArgumentException("action must not be blank");
    }
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(actorType, "actorType");
    if (actorType == AuditActorType.USER && actorMemberId == null) {
      throw new IllegalArgumentException("actorMemberId is required when actorType is USER");
    }
    if (actorType != AuditActorType.USER && actorMemberId != null) {
      throw new IllegalArgumentException("actorMemberId must be null unless actorType is USER");
    }
    detail = detail == null ? Map.of() : Map.copyOf(detail);
  }
}
