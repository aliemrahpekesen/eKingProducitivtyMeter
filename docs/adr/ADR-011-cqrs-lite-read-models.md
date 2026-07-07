# ADR-011: Adopt CQRS-lite — projector-maintained read-model tables for dashboards over the canonical write model

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [DataFlow](../architecture/DataFlow.md), [DatabasePlan](../engineering/DatabasePlan.md) (read-model tables); `eip-analytics`; CC-1.

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). Dashboard read latency must stay within budget (NFR-010) independently of ingestion load, and the read side must be rebuildable after a logic change rather than requiring a costly online migration.

## Decision

Dashboards read from projector-maintained read-model tables; the canonical model remains the single write side. Read models are rebuildable from Kafka topic replay plus canonical recompute. The read-model tables are plain RLS-enabled tables — materialized views are forbidden for tenant-scoped data (ADR-015).

## Consequences

- **Positive:** dashboard latency is decoupled from ingestion load (NFR-010); a projector change is applied by rebuilding from replay, not by blocking migrations; read shapes can be tuned independently of the write model.
- **Negative:** read models are eventually consistent with the canonical model; projectors and their watermarks are additional components to operate and monitor.

## Alternatives rejected

- **Querying the canonical/OLTP tables directly for dashboards** — rejected: couples dashboard latency to ingestion write load and misses the dashboard budget (NFR-010) under burst.
- **PostgreSQL materialized views as read models** — rejected: RLS cannot attach to materialized views, so they would bypass tenant isolation (settled in ADR-015).
