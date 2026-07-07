# Module: eip-tenancy

- **Root package:** `com.eip.tenancy`
- **Owner:** R-BA (R-PA: tenancy/quotas)
- **Allowed dependencies:** eip-core
- **State:** Phase-0 skeleton (build wiring + package root only; no domain content yet — TASK-0001).

## Purpose

Organizations, tenants, BusinessUnits, Teams, Members, RBAC, audit log, tenant context propagation, Postgres RLS session-variable management (app.tenant_id).

## Owned tables / topics / endpoints

None yet. Ownership is declared per [DatabasePlan §2](../../docs/engineering/DatabasePlan.md) (tables) and [EventModel §3](../../docs/engineering/EventModel.md) (topics) as content lands in later phases; this charter is updated in the same PR that adds them (RepositoryStructure.md §6 invariant 4).

## Invariants

- Dependency edges are exactly those listed above; any other edge fails the Spring Modulith `ModularityTests` (wired in TASK-0005) — a compile-red event, not a review comment ([BackendPlan §1](../../docs/engineering/BackendPlan.md)).
- Package-by-module: all code sits under `com.eip.tenancy.<area>` ([CodingStandards §2.3](../../engineering-operating-system/CodingStandards.md)).

## Content roadmap

Substantive content lands per [BackendPlan §17](../../docs/engineering/BackendPlan.md) phase mapping; see [RepositoryStructure §2.1](../../engineering-operating-system/RepositoryStructure.md).
