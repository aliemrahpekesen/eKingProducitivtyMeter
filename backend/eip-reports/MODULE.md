# Module: eip-reports

- **Root package:** `com.eip.reports`
- **Owner:** R-BA (R-AIA: composition)
- **Allowed dependencies:** eip-core, eip-tenancy, eip-analytics, eip-ai
- **State:** Phase-0 skeleton (build wiring + package root only; no domain content yet — TASK-0001).

## Purpose

Report/template engine, versioned MinIO-backed artifact library, exports (md/pdf/html/json/csv/pptx/png/svg), scheduled report generation.

## Owned tables / topics / endpoints

None yet. Ownership is declared per [DatabasePlan §2](../../docs/engineering/DatabasePlan.md) (tables) and [EventModel §3](../../docs/engineering/EventModel.md) (topics) as content lands in later phases; this charter is updated in the same PR that adds them (RepositoryStructure.md §6 invariant 4).

## Invariants

- Dependency edges are exactly those listed above; any other edge fails the Spring Modulith `ModularityTests` (wired in TASK-0005) — a compile-red event, not a review comment ([BackendPlan §1](../../docs/engineering/BackendPlan.md)).
- Package-by-module: all code sits under `com.eip.reports.<area>` ([CodingStandards §2.3](../../engineering-operating-system/CodingStandards.md)).

## Content roadmap

Substantive content lands per [BackendPlan §17](../../docs/engineering/BackendPlan.md) phase mapping; see [RepositoryStructure §2.1](../../engineering-operating-system/RepositoryStructure.md).
