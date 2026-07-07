# Dependency Matrix

The edge-level dependency DAG the whole program consumes: the module × module dependency matrix (typed per ADR-019), the story-level dependency tables for Phase 0 and Phase 1, and the enumerated set of **forbidden** dependencies the boundary tests block. This is the graph [./ParallelizationPlan.md](./ParallelizationPlan.md) and [./DevelopmentSequence.md](./DevelopmentSequence.md) read to place work in disjoint, correctly-ordered lanes. It **sequences and links** — the edges themselves are law in [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) §2, [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §3, and [../reviews/architecture-readiness/ArchitectureDecisionUpdates.md](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) (ADR-015..020).

## 1. Module × module dependency matrix

**Rows depend on columns.** Every marked edge is one of the three sanctioned cross-module access forms of [ImplementationReadinessDecision §7](../reviews/architecture-readiness/ImplementationReadinessDecision.md) principle 3 (exported API, event, or the enumerated read-only canonical grant), plus the platform substrate edge.

Legend — **A** api-dependency (exported Java package / SPI; or HTTP `/api/v1` for `frontend`) · **E** event-dependency (Kafka topic consume; at-least-once, idempotent, [EventModel §1](../docs/engineering/EventModel.md)) · **R** read-only-grant (the enumerated read-only canonical SQL grant, **ADR-019** — `eip-analytics` only) · **P** platform dependency (runs on the `infra` Compose/CI substrate; not a Modulith edge) · **·** none.

| depends ↓ / on → | infra | core | tenancy | app | frontend | connectors | ingestion | workers | analytics | ai | reports |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **infra** | · | · | · | · | · | · | · | · | · | · | · |
| **eip-core** | P | · | · | · | · | · | · | · | · | · | · |
| **eip-tenancy** | P | A | · | · | · | · | · | · | · | · | · |
| **eip-app** | P | A | A | · | · | A¹ | A¹ | ✗² | · | A¹ | A¹ |
| **frontend** | P | · | · | A | · | · | · | · | · | · | · |
| **eip-connectors** | P | A | A | · | · | · | · | · | · | · | · |
| **eip-ingestion** | P | A | A | · | · | A | · | · | · | · | · |
| **eip-workers** | P | A | A | · | · | · | A | · | A³ | A³ | A³ |
| **eip-analytics** | P | A | A | · | · | · | E R | · | · | · | · |
| **eip-ai** | P | A | A | · | · | · | E | · | A | · | · |
| **eip-reports** | P | A | A | · | · | · | · | · | · | A | · |

¹ **Phase 1+ only** — `eip-app` composes admin/query endpoints over the data-plane modules (`connectors` config, `ingestion` DLQ/checkpoint admin, `analytics` metric query, `ai`/`reports` invocation). **Absent in Sprints 00–03**, where `eip-app` depends only on `eip-core` + `eip-tenancy`.
² **✗ = forbidden** (not "none"): `eip-app` MUST NOT call `eip-workers` — app→workers is events-only (§3, AD-2/AD-13). Rendered as ✗ to make the prohibition explicit in the grid.
³ **Phase 2+** — `eip-workers` hosts `analytics`/`ai`/`reports` consumer groups only once those modules land; in Phase 1 it hosts `ingestion` consumers only. Topic ownership stays with the producing module ([EventModel §3](../docs/engineering/EventModel.md) note).

**E-edge detail** (which topic realizes each event edge, [EventModel §3](../docs/engineering/EventModel.md)): `analytics→ingestion` = the `eip.analytics.*` metric groups consuming `eip.domain.*`; `ai→ingestion` = the `eip.ai.rag-indexer` group consuming `eip.domain.workitem|scm|quality`. Each consumer binds tenancy from the envelope **before** any table access (principle 6) and dedups per [EventModel §2](../docs/engineering/EventModel.md). The single **R** edge (`analytics→ingestion`) is the dedicated read-only grant of ADR-019 — recomputation/rollups only, never `staging.raw_*`, never a write.

Every edge in this matrix MUST equal the module's Spring Modulith declaration and the ArchUnit dependency matrix; a new edge updates **this doc and the ArchUnit matrix in the same PR — they are one artifact** ([ImplementationReadinessDecision §7](../reviews/architecture-readiness/ImplementationReadinessDecision.md) principle 3).

## 2. Story-level dependencies — Phase 0 (Sprints 00–03)

Source of the `Depends-on` column: [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5.1 (do not re-derive). Sprint placement per [./SprintCatalog.md](./SprintCatalog.md) / [./Phase0.md](./Phase0.md). Owning role per [../engineering-operating-system/ModuleOwnership.md](../engineering-operating-system/ModuleOwnership.md) §1 + the Phase-0 staffing of [../engineering-operating-system/AIAgentCatalog.md](../engineering-operating-system/AIAgentCatalog.md).

| Story | Module(s) | Depends-on | Sprint | Owning role (advisor) |
|---|---|---|---|---|
| P0-E1-S1 | all (scaffolding) | — | SPRINT-00 | R-DOA (R-BA, R-CA) |
| P0-E1-S2 | infra | P0-E1-S1 | SPRINT-00 | R-DOA (R-QAA) |
| P0-E1-S3 | infra | P0-E1-S1 | SPRINT-00 | R-DOA |
| P0-E1-S4 | docs, scripts | P0-E1-S3 | SPRINT-00 | R-DE (R-DOA) |
| P0-E2-S1 | eip-core | P0-E1-S1 | SPRINT-00 | R-BA (R-CA) |
| P0-E2-S2 | eip-core, eip-app | P0-E2-S1 | SPRINT-01 | R-DBA (R-BA) |
| P0-E3-S1 | eip-tenancy | P0-E2-S2 | SPRINT-01 | R-BA (R-PA, R-SA) |
| P0-E4-S2 | eip-app | P0-E2-S1 | SPRINT-01 | R-BA |
| P0-E4-S3 | eip-app, infra | P0-E1-S3 | SPRINT-01 | R-OE (R-BA) |
| P0-E3-S2 | eip-tenancy | P0-E3-S1 | SPRINT-02 | R-BA (R-SA) |
| P0-E3-S3 | eip-tenancy, eip-app | P0-E3-S1 | SPRINT-02 | R-BA (R-SA) |
| P0-E3-S4 | eip-tenancy | P0-E3-S1 | SPRINT-02 | R-BA (R-SA) |
| P0-E4-S1 | eip-tenancy | P0-E3-S4 | SPRINT-02 | R-BA (R-SA) |
| P0-E5-S1 | frontend | P0-E3-S3 | SPRINT-03 | R-FA |
| P0-E5-S2 | frontend, eip-app | P0-E5-S1, P0-E3-S4 | SPRINT-03 | R-FA (R-BA) |

Cross-sprint critical edges: `P0-E3-S1` (tenancy) depends on `P0-E2-S2` (RLS template) → **the RLS template MUST land before any tenancy query** (SPRINT-01 barrier, [./ParallelizationPlan.md](./ParallelizationPlan.md) §2). `P0-E4-S1` (secrets) depends on `P0-E3-S4` (audit) → **audit MUST land before secrets consume it** (SPRINT-02 barrier). `P0-E5-S1` (frontend shell) depends on `P0-E3-S3` (OIDC) → frontend cannot start until authN is real.

## 3. Story-level dependencies — Phase 1 (outline, coarse resolution)

Phase 1 is out of the Sprint 00–03 bundle; this table is the DAG later sequencing consumes, at the resolution [./SprintCatalog.md](./SprintCatalog.md) carries. `Depends-on` from [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §6.1; sprint grouping from the Phase-1 outline in [./SprintCatalog.md](./SprintCatalog.md).

| Story | Module(s) | Depends-on | Sprint | Owning role (co-owner) |
|---|---|---|---|---|
| P1-E1-S1 | eip-connectors | P0-E4-S1 | SPRINT-04 | R-CNA |
| P1-E1-S2 | eip-ingestion | P1-E1-S1 | SPRINT-04 | R-CNA (R-DA) |
| P1-E1-S3 | eip-ingestion | P0-E4-S3 | SPRINT-05 | R-CNA (R-PA) |
| P1-E2-S1 | eip-ingestion, eip-core | P1-E1-S3 | SPRINT-05 | R-CNA (R-DA) |
| P1-E2-S2 | eip-core | P1-E2-S1 | SPRINT-05 | R-BA (R-DA) |
| P1-E1-S4 | eip-connectors | P1-E1-S1 | SPRINT-06 | R-CNA (R-QAA) |
| P1-E3-S1 | eip-connectors | P1-E1-S4 | SPRINT-06 | R-CNA |
| P1-E3-S2 | eip-connectors | P1-E1-S4 | SPRINT-06 | R-CNA |
| P1-E3-S3 | eip-connectors, /simulation | P1-E1-S4 | SPRINT-06 | R-CNA (R-QAA) |
| P1-E4-S1 | frontend, eip-app | P1-E1-S2, P0-E5-S2 | SPRINT-07 | R-FA (R-BA) |
| P1-E4-S2 | frontend, eip-app | P1-E2-S2 | SPRINT-07 | R-FA (R-BA) |

Phase-0→Phase-1 seam: `P1-E1-S1` (Connector SPI) depends on `P0-E4-S1` (secret vault) — connectors cannot exist before credentials can be stored; `P1-E1-S3` (Kafka pipeline) depends on `P0-E4-S3` (observability wiring) — every pipeline event carries `traceparent` from the first byte. `P1-E4-S1` depends on `P0-E5-S2` (admin console skeleton) — the connector admin UI extends the Phase-0 console.

## 4. Forbidden dependencies

These edges never exist. Each is a **BLOCKER-class** review finding "regardless of code quality" ([ImplementationReadinessDecision §3](../reviews/architecture-readiness/ImplementationReadinessDecision.md) condition 5), enforced structurally by the boundary tests, not by convention.

| # | Forbidden edge | Rule / decision it violates | How it is blocked |
|---|---|---|---|
| F1 | **Any dependency cycle** between modules; `eip-core` gaining any outbound module edge (it MUST stay a Modulith leaf) | Module boundaries are structural — the graph is a DAG ([ImplementationReadinessDecision §7](../reviews/architecture-readiness/ImplementationReadinessDecision.md) principle 3) | `ApplicationModules.verify()` + ArchUnit (G1); a cycle fails the build |
| F2 | **Cross-schema foreign key**; any FK crossing a module's schema boundary | FKs enforced **inside a schema only**; cross-context refs are plain `uuid` validated at the app layer ([DatabasePlan §1](../docs/engineering/DatabasePlan.md), DomainModel §3/§14 rule 6) | R-DBA CC-4 review (G4) + migration lint |
| F3 | **`eip-app` → `eip-workers` synchronous call** (HTTP or in-process) | app→workers is **events-only** (principle 3); coordination via `eip.ai.jobs`/`eip.reports.jobs` (outbox) + `agent_run`/`report_job` row polling/LISTEN-NOTIFY (AD-2, AD-13) | ArchUnit no-dependency rule `eip-app → eip-workers` (G1) |
| F4 | **Any Kafka consumer inside `eip-app`** | `eip-app` consumes no Kafka topic (AD-2); agent-run status is served by DB polling/LISTEN-NOTIFY | ArchUnit forbids Kafka-consumer types in `eip-app` (G1); G4 anchor review vs EventModel |
| F5 | **`eip-analytics` writing any canonical table**, or reading `staging.raw_*` | Single canonical writer = `eip-ingestion`; analytics gets only the enumerated **read-only** grant (**ADR-019**) | DB grant scope (`eip_readonly` / dedicated analytics grant, [DatabasePlan §2](../docs/engineering/DatabasePlan.md)); ArchUnit; G4 |
| F6 | **A second writer** to any table or producer to any topic it does not own; derived canonical columns written outside `eip-ingestion`'s `CanonicalEnrichmentService` | One owner and one writer per table and per topic (**ADR-019**, AD-12; [DatabasePlan §2](../docs/engineering/DatabasePlan.md), [EventModel §3](../docs/engineering/EventModel.md)) | table→module catalog is authoritative; docs-lint subset rule (G7); G4 |
| F7 | **Producing to Kafka outside the transactional outbox** (except the scoped raw-intake carve-out) | Outbox out — no producer writes Kafka outside the outbox; raw intake's direct produce is the **only** carve-out (**ADR-017**, principle 4) | outbox-relay contract tests (G2); G4 anchor review vs EventModel §9 |
| F8 | **A PostgreSQL materialized view over tenant-scoped data** | RLS cannot attach to a matview (FR-128); read models are projector-maintained plain RLS tables (**ADR-015**) | R-DBA CC-4 review (G4); RLS-integrity migration test (G2/G3) |
| F9 | **Business logic or a business table inside `eip-core` or a composition root** (`eip-app`) | Composition roots and `eip-core` own no business logic or tables (principle 3); `eip-core` owns only infra tables | `MODULE.md` invariants + ArchUnit (G1); docs-lint owned-tables subset (G7) |
| F10 | **Reaching into another module's internal package** (bypassing its exported API/event/grant) | Cross-module access only via exported APIs, events, or the read-only grant (principle 3) | Modulith named-interface enforcement (G1); this matrix ≡ ArchUnit matrix, same PR |

## Related documents

- [./ModuleBuildOrder.md](./ModuleBuildOrder.md) — the topological order this matrix details at edge level
- [./ParallelizationPlan.md](./ParallelizationPlan.md) — disjoint-write-set lanes derived from this DAG
- [./DevelopmentSequence.md](./DevelopmentSequence.md) — the task-level sequence over these dependencies
- [./RiskDrivenImplementation.md](./RiskDrivenImplementation.md) — why the highest-foreclosure edges are de-risked first
- [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) §1–§2 — schema/table ownership and the no-cross-schema-FK rule (source of record)
- [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §3 — topic ownership and delivery guarantees (source of record)
- [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5.1/§6.1 — the story `Depends-on` columns (source of record)
- [../reviews/architecture-readiness/ArchitectureDecisionUpdates.md](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) — ADR-015..020 + AD-2/AD-12/AD-13 typing these edges
- [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §7 — the inviolable principles behind §4
- [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) — the gates (G1/G4/G7) that block forbidden edges
