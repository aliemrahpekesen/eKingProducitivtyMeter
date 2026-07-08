# ADR-019: Make eip-ingestion the single writer of canonical tables, with enrichment via an exported service and read-only analytics access

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [DomainModel](../architecture/DomainModel.md) (derived fields), [EventModel](../engineering/EventModel.md), [ComponentModel](../architecture/ComponentModel.md), [ArchitectureOverview §5](../architecture/ArchitectureOverview.md); `eip-ingestion`, `eip-analytics`, `eip-ai`; CC-1.

## Context

Recorded by the architecture readiness review, 2026-07-06 ([ArchitectureDecisionUpdates §1](../../reviews/architecture-readiness/ArchitectureDecisionUpdates.md), forced by SBR-05 + SBR-02). DomainModel assigned derived-field writes to `eip-analytics`, but the module rules forbade analytics writing canonical tables; separately, analytics read access to canonical schemas was simultaneously forbidden (ArchitectureOverview) and prescribed (BackendPlan/EventModel) — two contradictions about who may touch canonical data.

## Decision

`eip-ingestion` owns all writes to canonical tables. Derived fields (`work_item.blocked`, `deployment.caused_incident`, `release.readiness_score`) and agent-raised `Risk` rows are written through an exported `CanonicalEnrichmentService` in `eip-ingestion`'s API package; enrichment writes touch only enrichment-owned columns and normalizer upserts never clear them. `eip-analytics` additionally receives read-only SQL access to the canonical schemas (never `staging.raw_*`, never writes) via a dedicated grant.

## Consequences

- **Positive:** a single writer eliminates enrichment-versus-normalizer write conflicts and races; the in-process service call becomes a clean API call when `eip-ingestion` is extracted to a service; analytics read access is explicit and grant-scoped.
- **Negative:** `eip-analytics` and `eip-ai` must call the enrichment service rather than writing canonical rows directly (indirection); the column-ownership split and the read-only grant must be maintained as new derived fields land.

## Alternatives rejected

- **Let `eip-analytics` write derived fields directly to canonical tables** — rejected: breaks the single-writer rule and races the normalizer upserts that also touch those rows.
- **Forbid analytics read access to canonical schemas entirely** — rejected: full recomputation and rollups require canonical reads; the prescribed access won, narrowed to a read-only grant that excludes `staging.raw_*`.
