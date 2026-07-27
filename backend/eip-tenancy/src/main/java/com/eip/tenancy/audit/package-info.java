/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Hash-chained, tamper-evident audit logging (DEBT-024 Wave 3A; SecurityModel §11, DatabasePlan
 * §3): the {@code audit.audit_event} write path, the asynchronous per-tenant hash chainer, and the
 * chain-integrity verifier. Layering mirrors the rest of the module: {@link
 * com.eip.tenancy.audit.api} is the public contract (the event DTO/taxonomy enums and the use-case
 * ports), {@link com.eip.tenancy.audit.application} holds the service implementations, {@link
 * com.eip.tenancy.audit.persistence} is the {@code JdbcClient} adapter over {@code
 * audit.audit_event}.
 *
 * <p><strong>Wave boundary.</strong> This is Wave 3A: the write path + chainer + verifier only.
 * Retrofitting the actual auth/access/admin/secrets call sites onto {@link
 * com.eip.tenancy.audit.api.RecordAuditEventUseCase} is Wave 3B's job — no call site is wired from
 * this package.
 *
 * <h2>Schema mapping (no DDL reshape)</h2>
 *
 * SecurityModel §11 sketches the audit event's logical schema with dedicated {@code category},
 * {@code event}, {@code actor_type} fields; the as-built {@code audit.audit_event} table (V1
 * baseline — a DatabasePlan contract-anchor DDL, never reshaped here) flattens those into {@code
 * action} (the dotted event id, e.g. {@code "secret.revealed"}) plus a {@code detail jsonb} blob
 * that carries {@code category} and {@code actorType} as keys alongside any other redacted,
 * pseudonymous context ({@link com.eip.tenancy.audit.application.AuditService} adds both keys to
 * every row it writes). This package writes onto the as-built shape; the SecurityModel-vs-DDL
 * field-name drift is a documentation-reconciliation item for later, not a bug here.
 *
 * <h2>Single-writer-per-tenant without Redisson</h2>
 *
 * SecurityModel §11 and DatabasePlan §3 both describe the chainer as "a single Redisson-locked
 * runner." As of this wave, Redisson/Redis is not wired into this codebase at all — no dependency,
 * no client bean, no usage anywhere (verified by repo-wide search) — despite being a CLAUDE.md
 * canonical-facts entry. Introducing it is a new production dependency requiring its own
 * owning-architect approval and {@code DependencyManagement} review, out of scope for a "core only"
 * wave. {@link com.eip.tenancy.audit.persistence.AuditEventRepository} instead uses a Postgres
 * transaction-scoped advisory lock ({@code pg_try_advisory_xact_lock}, keyed per tenant) for the
 * same single-writer-per-tenant mutual exclusion, over infrastructure the platform already fully
 * depends on. Revisit if/when Redisson lands for another feature.
 *
 * <h2>Deferred (explicitly out of scope for Wave 3A — do not build without a new task)</h2>
 *
 * <ul>
 *   <li>WORM / object-storage chain-head anchors — needs MinIO wiring (SecurityModel §11's periodic
 *       anchor step); not built.
 *   <li>NFR-042 volume-scaling: batching/paging tuning beyond a fixed per-sweep row cap, and a
 *       chain-lag SLO with alerting thresholds. The chainer/verifier here are a correct, simple
 *       per-tenant sweep — right for MVP volumes, not yet load-tested at NFR-042 scale.
 *   <li>INSERT-only DB grants for {@code audit.audit_event} (the platform uses one DB role today;
 *       per-table least-privilege is a broader DB-hardening item). Append-only-in-spirit is
 *       enforced in code instead: {@link com.eip.tenancy.audit.persistence.AuditEventRepository}
 *       only ever INSERTs new rows, or UPDATEs {@code prev_hash}/{@code hash} on a row whose {@code
 *       hash IS NULL}.
 *   <li>NDJSON/SIEM export and the SECURITY_AUDITOR read API — a separate feature.
 *   <li>Unchained-rows-older-than-SLO counted as verifier failures (SecurityModel §11's lag-SLO
 *       clause) — this MVP's verifier only re-walks and re-hashes rows already chained.
 * </ul>
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.tenancy.audit;

import org.jspecify.annotations.NullMarked;
