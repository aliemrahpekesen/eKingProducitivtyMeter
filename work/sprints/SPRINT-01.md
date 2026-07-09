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
| TASK-0009 | P0-E2-S2 | L0 | M | CC-4 | MERGED | R-IE (backend) under R-DBA |
| TASK-0010 | P0-E3-S1 | L0 | M | CC-1 | MERGED | R-IE (backend) under R-BA + R-CA |
| TASK-0011 | P0-E4-S2 | L1 | M | CC-1 | PLANNED | R-IE (backend) under R-CA |
| TASK-0012 | P0-E4-S3 | L1 | M | CC-7 | PLANNED | R-IE (infra) under R-OE |

TASK-0009 (DB baseline + RLS template) is the serial prerequisite: the tenancy-context enforcement (TASK-0010), OpenAPI (TASK-0011), and observability (TASK-0012) build on the persistence spine. Design-note spikes (outbox-relay topology, consumer idempotency, audit hash-chain approach) run ahead per SprintCatalog §3.

## Lanes / write-set disjointness

L0 (persistence + tenancy spine, `/backend/eip-core` + `/backend/eip-tenancy` + `/backend/eip-app` migrations) runs first as the serial spine; then L1 (OpenAPI in `eip-app`, observability wiring) runs on top. Disjoint write-sets per [SprintCatalog §3](../../program/SprintCatalog.md).

## Daily log

- **2026-07-08** — Sprint-01 opened. Founder decisions recorded ([../../reviews/product-planning/FounderDecisions.md](../../reviews/product-planning/FounderDecisions.md), commit `7fc06ad`) — decisions 2/3/4 confirm the existing architecture (LLM provider SPI, vendor-neutral Connector SPI, DC/Cloud handling); decision 1 refines the target segment. No spec/architecture/roadmap change. TASK-0009 (DB + RLS baseline) CLAIMED and started (R-IE under R-DBA): branch `feature/TASK-0009-db-rls-baseline`; de-risking RLS first (the crux), then the tenancy-core schema.
- **2026-07-08** — TASK-0009 increment 1 (`2f0ed26`): persistence wiring + tenancy-core + RLS mechanism + Testcontainers isolation test (4/4). Increment 2: control-plane infra + `core.connector` + `audit.audit_event` + `analytics` metric tables (Friction surface) + canonical schemas + `work.work_item` + partitioned tables + §15 schema-lint; **6/6 RLS+lint tests green**, full `./gradlew check` green. **Product-first ordering** applied (connector + Friction-metric tables first; feature-driven RAG/full-canonical/hash-chainer deferred — nothing consumes them yet). TASK-0009 Phase-0 control-plane baseline **complete**; awaiting R-CR + R-DBA review. Not pushed. Next per product-first rule: expose the persistence via the thinnest visible slice (P0-E3-S1 tenant-context filter + a read-only `/api/v1` session/connectors endpoint + committed OpenAPI contract).
- **2026-07-08** — TASK-0009 reviewed by R-CR + **R-DBA G4** ([../reviews/TASK-0009-review.md](../reviews/TASK-0009-review.md)): **APPROVED** (0 BLOCKER, 0 MAJOR, 1 MINOR, 2 NIT). Independently re-run: `:eip-tenancy:test`+`:eip-app:test --rerun-tasks` 43/43; 18 tenant-scoped tables all under RLS, 3 platform exceptions correct, FORCE RLS + no-default GUC, §15 unique-constraint invariant, pgvector, `event_outbox`=BackendPlan §6, `audit_event` hash-chain+partitioning, 3 partitioned parents; product-first deferrals ratified as valid (feature-driven, not dropped). MINOR-1 (DatabasePlan §7/§14 incremental-V1 wording) → [DEBT-008](../debt-register.md). Merged into `integration/SPRINT-00` (local `--no-ff`, merge `3aea8ca`). State → **MERGED**. **RLS tenancy spine — the #1 technical risk — proven and landed.** P0-E3-S1 (first visible increment) opened next. Not pushed.
- **2026-07-08** — TASK-0010 (P0-E3-S1, **first customer-visible slice**) IMPLEMENTED on `feature/TASK-0010-tenant-api` (off `integration/SPRINT-00`). Tenant-context web filter (`HeaderTenantResolver` dev seam, OIDC-swappable in SPRINT-02) → `TenantContextHolder` (fail-closed) → `TenantScopedJdbc` binds `RlsTenantBinder` per tx; two read endpoints `GET /api/v1/{session,connectors}` served from the RLS DB, RFC 7807 errors, committed OpenAPI snapshot (`eip-app/openapi/eip-openapi-v1.json`), `demo`-profile simulation seeder. **RLS proven through the HTTP surface**: `TenantApiIntegrationTest` (Testcontainers, app as NOBYPASSRLS `eip_app`) — tenant A never sees tenant B's connectors, unbound request → 401 problem+json. `./gradlew check` GREEN all modules. State → **IN_REVIEW** (awaiting R-CR + G4 R-CA). Not pushed, not merged.
- **2026-07-09** — TASK-0010 reviewed R-CR + **G4 R-CA** ([../reviews/TASK-0010-review.md](../reviews/TASK-0010-review.md)) via an independent adversarial workflow (5 dimensions, every finding re-verified, 18 agents, 0 refuted). All 9 required points PASS; initial verdict **CHANGES REQUESTED — 2 MAJOR, 8 MINOR, 3 NIT**. **MAJOR-2** (bare-array `/connectors` vs APIDesign §1.4 pagination-envelope contract anchor) **fixed** — `PageView` envelope + keyset cursor pagination; **MAJOR-1** (G6 observability unmet+unwaived) **waived** via [DEBT-009](../debt-register.md) → TASK-0012. Contract MINORs fixed (`/problems/*` type URIs; `HeaderTenantResolver` `@Profile("!prod")`); rest → [DEBT-010/011/012](../debt-register.md). Re-verify `./gradlew check` GREEN (7 integration + holder unit). **Final verdict APPROVED (0 BLOCKER, 0 MAJOR).** Merged into `integration/SPRINT-00` (local `--no-ff`, merge `50fe7c2`). State → **MERGED**. **The platform is now callable: first tenant-isolated `/api/v1` contracts landed.** Next per product-first rule: P0-E4-ish visible increment — the first **Engineering Friction** deterministic metric + `GET /api/v1/friction/summary` (dashboard-card-ready). Not pushed.

## Replan log

(none)

## Exit

Sprint exit criteria: [SprintCatalog §3](../../program/SprintCatalog.md) — two seeded tenants, cross-tenant read provably blocked (RLS test + API probe), OTel HTTP→DB traces, OpenAPI `/api/v1` skeleton, NFR-041 isolation suite in CI. Not yet met — sprint in progress.
