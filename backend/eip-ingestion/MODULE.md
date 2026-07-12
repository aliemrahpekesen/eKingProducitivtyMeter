# Module: eip-ingestion

- **Root package:** `com.eip.ingestion`
- **Owner:** R-CNA (R-DA: normalization)
- **Allowed dependencies:** eip-core, eip-tenancy, eip-connectors
- **State:** Active (TASK-0016): raw staging + normalization + single canonical writer for the friction slice.
- **Layering:** `api` (ports: `IngestSimulationDataUseCase`, `NormalizeStagedDataUseCase` — Modulith named interface) → `application` (@Service use cases; connector fetch outside the transaction) → `persistence` (@Repository JdbcClient/JdbcTemplate batch `ON CONFLICT` upserts). Tenant-bound transactions via eip-tenancy's `TenantTransactionRunner`.
- **Owned tables (TASK-0016):** `staging.raw_simulation`, `staging.raw_ingest_errors`; canonical write side of `work.work_item(+_transition)`, `scm.pull_request`/`code_review`, `cicd.build`, `quality.quality_gate`, `core.external_ref` anchors (ADR-019).

## Purpose

Sync engine, checkpointing, raw staging (raw_* JSONB), normalizers to the canonical model, dedup, DLQ handling, the single canonical writer (ADR-019).

## Owned tables / topics / endpoints

None yet. Ownership is declared per [DatabasePlan §2](../../docs/engineering/DatabasePlan.md) (tables) and [EventModel §3](../../docs/engineering/EventModel.md) (topics) as content lands in later phases; this charter is updated in the same PR that adds them (RepositoryStructure.md §6 invariant 4).

## Invariants

- Dependency edges are exactly those listed above; any other edge fails the Spring Modulith `ModularityTests` (wired in TASK-0005) — a compile-red event, not a review comment ([BackendPlan §1](../../docs/engineering/BackendPlan.md)).
- Package-by-module: all code sits under `com.eip.ingestion.<area>` ([CodingStandards §2.3](../../engineering-operating-system/CodingStandards.md)).

## Content roadmap

Substantive content lands per [BackendPlan §17](../../docs/engineering/BackendPlan.md) phase mapping; see [RepositoryStructure §2.1](../../engineering-operating-system/RepositoryStructure.md).
