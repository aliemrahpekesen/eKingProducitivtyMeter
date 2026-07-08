# SPRINT-01 — Persistence & tenancy spine

- **Phase / version:** Phase 0 (v0.1) · **Position:** Phase 0 weeks 3–4 · **Cadence:** 2-week
- **Plan of record:** [../../program/SprintCatalog.md](../../program/SprintCatalog.md) §3 · execution intent: [../../reviews/Sprint01ExecutionPlan.md](../../reviews/Sprint01ExecutionPlan.md) (E1)
- **Objective:** Lay the security spine — DB baseline with the RLS template + partitioning conventions, tenant context propagation with RLS enforcement, plus the OpenAPI and observability lanes. **De-risk the #1 technical risk (RLS correctness under transaction-scoped `SET LOCAL`) first.**
- **Founder constraints (frozen):** target 500–5000-engineer regulated enterprises; on-prem, deterministic-first, AI-assistive; vendor-neutral connectors (DC+Cloud) behind the Connector SPI; mandatory AI provider abstraction behind the LLM provider SPI ([../../reviews/product-planning/FounderDecisions.md](../../reviews/product-planning/FounderDecisions.md)). Optimize every decision for the first enterprise-ready vertical slice.

## Execution posture (per the approved E1 sequence)

Leaned per [Sprint01ExecutionPlan](../../reviews/Sprint01ExecutionPlan.md): protect the persistence + RLS spine (full rigor); keep OpenAPI minimal and observability thin; defer heavy partitioning to volume. Architecture, module boundaries, tenancy model, and governance are unchanged — only build order/depth is optimized.

## Committed tasks

| Task | Story/gov | Lane | Size | CC | State | Owner |
|---|---|---|---|---|---|---|
| TASK-0009 | P0-E2-S2 | L0 | M | CC-4 | IN_PROGRESS | R-IE (backend) under R-DBA |
| TASK-0010 | P0-E3-S1 | L0 | M | CC-1 | PLANNED | R-IE (backend) under R-BA + R-CA |
| TASK-0011 | P0-E4-S2 | L1 | M | CC-1 | PLANNED | R-IE (backend) under R-CA |
| TASK-0012 | P0-E4-S3 | L1 | M | CC-7 | PLANNED | R-IE (infra) under R-OE |

TASK-0009 (DB baseline + RLS template) is the serial prerequisite: the tenancy-context enforcement (TASK-0010), OpenAPI (TASK-0011), and observability (TASK-0012) build on the persistence spine. Design-note spikes (outbox-relay topology, consumer idempotency, audit hash-chain approach) run ahead per SprintCatalog §3.

## Lanes / write-set disjointness

L0 (persistence + tenancy spine, `/backend/eip-core` + `/backend/eip-tenancy` + `/backend/eip-app` migrations) runs first as the serial spine; then L1 (OpenAPI in `eip-app`, observability wiring) runs on top. Disjoint write-sets per [SprintCatalog §3](../../program/SprintCatalog.md).

## Daily log

- **2026-07-08** — Sprint-01 opened. Founder decisions recorded ([../../reviews/product-planning/FounderDecisions.md](../../reviews/product-planning/FounderDecisions.md), commit `7fc06ad`) — decisions 2/3/4 confirm the existing architecture (LLM provider SPI, vendor-neutral Connector SPI, DC/Cloud handling); decision 1 refines the target segment. No spec/architecture/roadmap change. TASK-0009 (DB + RLS baseline) CLAIMED and started (R-IE under R-DBA): branch `feature/TASK-0009-db-rls-baseline`; de-risking RLS first (the crux), then the tenancy-core schema.

## Replan log

(none)

## Exit

Sprint exit criteria: [SprintCatalog §3](../../program/SprintCatalog.md) — two seeded tenants, cross-tenant read provably blocked (RLS test + API probe), OTel HTTP→DB traces, OpenAPI `/api/v1` skeleton, NFR-041 isolation suite in CI. Not yet met — sprint in progress.
