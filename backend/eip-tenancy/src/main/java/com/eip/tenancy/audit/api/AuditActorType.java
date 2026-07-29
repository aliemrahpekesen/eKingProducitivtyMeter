/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.audit.api;

/**
 * The kind of principal an audited action originates from. Stored in {@code detail.actorType} (V1's
 * {@code audit.audit_event} has no dedicated column, per this module's root package-info
 * field-mapping note); {@code actor_member_id} is populated only for {@link #USER}.
 *
 * <p>SecurityModel §11's actor taxonomy names a fifth kind, {@code AGENT}, for AI-agent-initiated
 * actions. It is deliberately absent here until {@code eip-ai} wires its own audit calls (an
 * AI-behavior change class, reviewed on its own) — not a placeholder gap, just not yet claimed by
 * any wave.
 */
public enum AuditActorType {
  USER,
  SERVICE_TOKEN,
  WORKER,
  SYSTEM
}
