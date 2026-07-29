# ADR-015: Maintain read models as projector-built RLS tables and forbid materialized views for tenant-scoped data

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [DataFlow](../architecture/DataFlow.md), [DatabasePlan](../engineering/DatabasePlan.md) (read models), [SecurityModel](../architecture/SecurityModel.md) (RLS); `eip-analytics`; CC-1.

## Context

Recorded by the architecture readiness review, 2026-07-06 ([ArchitectureDecisionUpdates §1](../../reviews/architecture-readiness/ArchitectureDecisionUpdates.md), forced by DAR-02 + SCL-03). Two source documents described incompatible read-model architectures: DataFlow §4 specified projector-maintained tables, while DatabasePlan described `reports.mv_*` materialized views. Materialized views cannot carry Row-Level Security, so the MV variant would silently bypass tenant isolation (FR-128) — the release-blocking property ADR-004 protects.

## Decision

Read models are projector-maintained plain tables with RLS enabled (`rm_*` / `metric_facts` plus watermarks), rebuildable from topic replay and canonical recompute. PostgreSQL materialized views are forbidden for tenant-scoped data.

## Consequences

- **Positive:** RLS attaches to plain tables, so tenant isolation holds on the read path (FR-128); read models remain rebuildable from replay (ADR-011); one consistent read-model mechanism instead of two contradictory ones.
- **Negative:** the incremental-refresh convenience a materialized view offers must instead be built as projector logic with watermarks — more code to write and operate than `REFRESH MATERIALIZED VIEW`.

## Alternatives rejected

- **PostgreSQL materialized views for dashboard reads** — rejected: RLS cannot attach to a materialized view, so tenant-scoped reads would bypass isolation (FR-128).
- **Keeping both architectures** — rejected: the contradiction itself was the defect; one of the two silently violated the isolation guarantee, so a single answer was mandatory.
