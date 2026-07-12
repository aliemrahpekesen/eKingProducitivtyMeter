# Module: eip-analytics

- **Root package:** `com.eip.analytics`
- **Owner:** R-DA (R-BA: runtime)
- **Allowed dependencies:** eip-core, eip-tenancy
- **State:** Active (TASK-0016): Engineering Friction v0.1 compute + projection + read side.
- **Layering:** `api` (ports + DTOs: `ComputeFrictionUseCase`, `GetFrictionSummaryQuery`, `GetFrictionEvidenceQuery` — Modulith named interface) → `application` (@Service) → `friction` (pure framework-free engine: `FlowTimeline`, `FrictionCalculator`, `FrictionDefinition`) → `persistence` (@Repository set-based loads + batch upserts). Canonical schemas read-only (ADR-019).
- **Owned tables (TASK-0016):** `analytics.flow_correlation`, `analytics.rm_team_friction_current`, `analytics.metric_definition`/`metric_fact` friction rows.

## Purpose

Metric engines (flow, DORA, quality, delivery risk, ops, team health), metric definitions registry, risk scoring, forecasts, JdbcClient read-only query side.

## Owned tables / topics / endpoints

None yet. Ownership is declared per [DatabasePlan §2](../../docs/engineering/DatabasePlan.md) (tables) and [EventModel §3](../../docs/engineering/EventModel.md) (topics) as content lands in later phases; this charter is updated in the same PR that adds them (RepositoryStructure.md §6 invariant 4).

## Invariants

- Dependency edges are exactly those listed above; any other edge fails the Spring Modulith `ModularityTests` (wired in TASK-0005) — a compile-red event, not a review comment ([BackendPlan §1](../../docs/engineering/BackendPlan.md)).
- Package-by-module: all code sits under `com.eip.analytics.<area>` ([CodingStandards §2.3](../../engineering-operating-system/CodingStandards.md)).

## Content roadmap

Substantive content lands per [BackendPlan §17](../../docs/engineering/BackendPlan.md) phase mapping; see [RepositoryStructure §2.1](../../engineering-operating-system/RepositoryStructure.md).
