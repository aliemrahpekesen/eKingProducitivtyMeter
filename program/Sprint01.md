# SPRINT-01 — Persistence & tenancy spine

The second Phase-0 sprint: lay the database baseline with forced RLS, propagate tenant context across web/Kafka/jobs, publish the OpenAPI baseline, wire observability, and resolve the four **blocking** architecture-readiness design notes that gate all downstream work. This is the security spine every later row, event, and API call depends on. This file **sequences and packages** the sources of record; it links, never restates.

- **Phase / version:** Phase 0 (v0.1) — Foundations ([../docs/product/Roadmap.md](../docs/product/Roadmap.md) §4)
- **Position:** Phase 0 weeks 3–4 (per [./SprintCatalog.md](./SprintCatalog.md), [./DevelopmentSequence.md](./DevelopmentSequence.md))
- **Depends on:** [./Sprint00.md](./Sprint00.md) DONE (scaffold, CI, Compose stack, `eip-core` skeleton, ADR-001..020)

## 1. Objective

Make tenant isolation **structural and provable** before the first external byte is ever ingested. Ship the Flyway DB baseline with the RLS policy template and partitioning conventions; the `eip-tenancy` context propagation (web filter, Kafka consumer binding, job scope) with forced RLS enforcement; the committed OpenAPI baseline; and OTel/Micrometer observability with `traceparent` propagation HTTP→DB. In parallel, front-load the highest-blast-radius spikes: **RLS + connection-pooling correctness**, plus three decision notes (**outbox relay topology**, **consumer idempotency mechanics**, **audit hash-chain sync-vs-async**) that unblock Phase-1 eventing and the SPRINT-02 audit subsystem. Builds **eip-core** (owned infra tables), **eip-tenancy** (the security spine), **eip-app** (composition root), on **infra** ([./ModuleBuildOrder.md](./ModuleBuildOrder.md)).

## 2. Modules

| Module | State entering | Work this sprint | Owning role |
|---|---|---|---|
| **eip-core** | skeleton (Sprint 00) | Owned infra tables (outbox, processed/dedup ledger, heartbeat, Quartz) baseline per ADR-012; tenant-context types | R-BA (co-owner R-CA) |
| **eip-tenancy** | absent | Organization/tenant model, tenant-context propagation (web/Kafka/jobs), forced RLS enforcement, NFR-041 isolation harness seed | R-BA (co-owner R-PA tenancy; R-SA advises, CC-2) |
| **eip-app** | absent | Composition root: `/api/v1`, OpenAPI/springdoc, RFC 7807, cursor pagination, idempotency-key filter; OTel/Micrometer wiring | R-BA (co-owner R-SA security paths) |
| **infra** | up (Sprint 00) | Grafana starter dashboards in `/infra/grafana`; PgBouncer/pooling spike harness | R-DOA |
| DB schema & migrations | absent | Flyway V1 baseline + RLS template + partitioning conventions | R-DBA (gate owner for CC-4) |

Ownership per [../engineering-operating-system/ModuleOwnership.md](../engineering-operating-system/ModuleOwnership.md) §1. `eip-app` consumes **no Kafka** (AD-2, [ArchitectureDecisionUpdates §2](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md)).

## 3. Stories

P-E-S IDs from [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5.1. Fixed by [./SprintCatalog.md](./SprintCatalog.md); no reassignment.

| Story | Title (PhaseImpl §5.1) | Module(s) | Size | Lane | First-PR seed (§5.2) |
|---|---|---|---|---|---|
| P0-E2-S2 | DB baseline: Flyway setup, tenant-scoped schema conventions, RLS policy template, partitioning conventions | eip-core, eip-app | M | L1 (serialized migrations) | precursor to PR-5 |
| P0-E3-S1 | Tenancy: Organization/tenant model, tenant-context propagation (web, Kafka, jobs), RLS enforcement | eip-tenancy | L | L1 | PR-5 (tenancy portion) |
| P0-E4-S2 | OpenAPI baseline: springdoc, `/api/v1` conventions, RFC 7807, cursor pagination, idempotency-key filter, committed spec + CI diff | eip-app | M | L2 (parallel) | PR-8 |
| P0-E4-S3 | Observability wiring: OTel SDK, Micrometer, structured JSON logs, `traceparent` propagation, Grafana starter dashboards | eip-app, infra | M | L3 (parallel) | PR-9 |

**Blocking design notes (must complete this sprint — they gate downstream sprints/phases; [ImplementationReadinessDecision](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §6):**

| Note | What it decides | Consumes | Feeds |
|---|---|---|---|
| N1 · RLS + connection-pooling spike | Transaction-scoped `SET LOCAL` correctness under PgBouncer/pooling; the dedicated integration-test pattern | [SecurityModel §5](../docs/architecture/SecurityModel.md), [DatabasePlan §5, §12](../docs/engineering/DatabasePlan.md) | every tenant query; N-layer isolation (principle 6) |
| N2 · Outbox relay topology | Which runtimes run `OutboxRelay`; buffer bounds (ADR-017 + FMA-03) | [EventModel §9](../docs/engineering/EventModel.md) | all Phase-1 eventing |
| N3 · Consumer idempotency mechanics | Dedup-ledger + DLQ park-index detail (EDA-04) | [EventModel §9–§10](../docs/engineering/EventModel.md) | Phase-1 normalizer/consumers |
| N4 · Audit hash-chain sync-vs-async | Chaining approach + SEC-02 DDL columns (finalized here, implemented in Sprint 02) | [SecurityModel §11](../docs/architecture/SecurityModel.md) | P0-E3-S4 (Sprint 02) |

Design notes are L1 (Canon) decisions banked as ADR refinements / `MODULE.md` invariants per [ContextManagementStrategy §4](../engineering-operating-system/ContextManagementStrategy.md); they produce documents, not application code beyond the N1 spike harness.

## 4. Required documents

Exact set owned by [./ContextManifest.md](./ContextManifest.md) (SPRINT-01 entry); this list is that entry verbatim and MUST stay in sync.

**Invariant prefix (every task):** [/CLAUDE.md](../CLAUDE.md) → `/work/tasks/TASK-NNNN.md` → `./Sprint01.md` → [../engineering-operating-system/DevelopmentLifecycle.md](../engineering-operating-system/DevelopmentLifecycle.md), [../engineering-operating-system/DefinitionOfReady.md](../engineering-operating-system/DefinitionOfReady.md), [../engineering-operating-system/DefinitionOfDone.md](../engineering-operating-system/DefinitionOfDone.md), [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) → in-scope `MODULE.md`(s): `eip-core`, `eip-tenancy`, `eip-app`.

**Sprint-specific spec set (adds to the SPRINT-00 set; add per task type):**

| Document (path + section) | Why | For story/note |
|---|---|---|
| [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) §1–§8 | schema conventions, table-ownership catalog, DDL baseline, indexing, RLS, partitioning, Flyway conventions, read-model rule | P0-E2-S2, N1 |
| [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md) §4–§5 | RBAC context + multi-tenancy isolation summary (forced RLS, `SET LOCAL`) | P0-E3-S1, N1 |
| [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) | `/api/v1` principles, endpoint catalog, RFC 7807, pagination, idempotency, springdoc conventions | P0-E4-S2 |
| [../docs/architecture/ObservabilityModel.md](../docs/architecture/ObservabilityModel.md) §1–§4 | telemetry pipeline, `eip_*` metrics, tracing model, `traceparent` | P0-E4-S3 |
| [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §9 | transactional outbox → Kafka; relay ownership | N2, N3 |
| [../docs/architecture/DataFlow.md](../docs/architecture/DataFlow.md) global invariants + §8 (Flow H audit) | cross-cutting write-path invariants the notes must honor | N2, N3, N4 |

Schema/migration and CC-2 task-type suffixes per [ContextManagementStrategy §3](../engineering-operating-system/ContextManagementStrategy.md) apply (add [TestingStrategy §3.1](../docs/testing/TestingStrategy.md), [SecurityChecklist](../engineering-operating-system/SecurityChecklist.md) for the RLS/tenancy CC-2 work).

## 5. Forbidden documents

Still forbidden for SPRINT-01 ([ContextManagementStrategy §7](../engineering-operating-system/ContextManagementStrategy.md)):

- Connector catalogs and framework: [../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md)
- All AI/RAG/MCP: [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md), [../docs/ai/RAGArchitecture.md](../docs/ai/RAGArchitecture.md), [../docs/ai/MCPArchitecture.md](../docs/ai/MCPArchitecture.md)
- Analytics/metric-engine and reports internals; dashboard catalogs (Phase 2+)
- Kubernetes/OpenShift deployment: [../docs/infrastructure/KubernetesOpenShift.md](../docs/infrastructure/KubernetesOpenShift.md)
- **Note the boundary:** read [EventModel §9](../docs/engineering/EventModel.md) for the outbox/idempotency notes only — the full connector/normalizer taxonomy (§4) and analytics topics are out of scope until Phase 1.

## 6. Inputs (DONE at sprint start)

- [./Sprint00.md](./Sprint00.md) outputs DONE: monorepo scaffold, Gradle multi-module, CI with Modulith/ArchUnit + coverage ratchet, Compose stack (`make dev-up`), `eip-core` skeleton (base types, `ExternalRef`, envelope), `/docs/adr/ADR-001..020`, CODEOWNERS, docs-lint.
- Contract anchors readable at repo HEAD: DatabasePlan §2 table-ownership catalog (AD-12), ADR-015 (read models, no MVs), ADR-016/018 (vector/object tenancy), ADR-017 (outbox scope), ADR-019 (single canonical writer).
- Readiness open questions for this sprint's notes, assigned in [ImplementationReadinessDecision §6](../reviews/architecture-readiness/ImplementationReadinessDecision.md): OQ#4 (OIDC revocation bound — Phase-0 design note), plus the decision-level items on outbox topology, idempotency mechanics, and audit hash-chain.

## 7. Outputs (concrete artifacts)

| Artifact | Location | Story/note |
|---|---|---|
| Flyway V1 baseline: schemas, tenant-scoped conventions, RLS policy template, partitioning conventions | `/backend/eip-app/src/main/resources/db/migration` | P0-E2-S2 |
| `eip-core` owned infra tables (outbox, dedup ledger, heartbeat, Quartz) migrations | eip-core resources | P0-E2-S2 |
| `eip-tenancy` module: Organization/tenant entities, tenant-context filter + Kafka/job binding, forced-RLS enforcement, `MODULE.md` | `/backend/eip-tenancy` | P0-E3-S1 |
| NFR-041 tenant-isolation test harness (cross-tenant read/write blocked; storage-prefix probe per ADR-018) | Testcontainers suites | P0-E3-S1 |
| Committed `openapi.json` + CI diff check; RFC 7807 handler; cursor pagination envelope; idempotency-key filter | `/docs/api/openapi.json`, eip-app | P0-E4-S2 |
| OTel/Micrometer config, JSON logging, trace-propagation test, Grafana starter dashboards | eip-app, `/infra/grafana` | P0-E4-S3 |
| Four design notes N1–N4 (banked as ADR refinements / MODULE.md invariants / a committed spike report) | `/docs/adr` refinements, `MODULE.md`, `/work` | N1–N4 |

## 8. Deliverables (reviewable increment)

The increment: a **first RLS-proven, trace-visible write/read through `/api/v1`** on the Compose stack — milestone M0.3 ("first RLS-proven write/read") from [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md), plus the published OpenAPI baseline and end-to-end trace HTTP→DB (integration point per §5.3). The four blocking notes are decided and banked, unblocking Phase-1 eventing and the Sprint-02 audit subsystem.

## 9. Exit criteria (testable)

Tied to story completion and Phase-0 exit lines in [../docs/product/PRD.md](../docs/product/PRD.md) §10 and [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md):

- [ ] Two seeded tenants; cross-tenant read **provably blocked** (RLS test + API probe) — the **NFR-041** isolation suite seed is green ([PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md); PRD §10 Phase-0 line 3). Isolation-test failures have **no waiver** (principle 6, [ImplementationReadinessDecision §7](../reviews/architecture-readiness/ImplementationReadinessDecision.md)).
- [ ] OpenAPI 3 published for the `/api/v1` skeleton; RFC 7807 errors; DB baseline via Flyway; committed spec drift-checked in CI (P0-E4-S2; PRD §10 Phase-0 line 3).
- [ ] OTel traces from HTTP → DB visible in the dev stack (Grafana/Tempo); `eip_*` metrics + structured JSON logs present (P0-E4-S3; PRD §10 Phase-0 line 4).
- [ ] Forced RLS via transaction-scoped `SET LOCAL` proven correct under the pooling configuration (N1 spike closed; principle 6).
- [ ] Notes N2/N3/N4 decided and banked to L1 with the decision recorded; N4 hands the SEC-02 hash-chain approach to P0-E3-S4 in [./Sprint02.md](./Sprint02.md).
- [ ] Every migration passes CC-4 review (R-DBA, expand–contract, forward-only, post-migration RLS integrity) at G4.

## 10. Review gates

Per [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md). This sprint activates the security, architecture, and observability gates:

| Gate | Applies because | Owner |
|---|---|---|
| G1 Build & Static | every PR (Modulith/ArchUnit, OpenAPI diff) | R-DOA |
| G2 Tests | every PR — unit + integration (Testcontainers: Postgres+RLS) + **tenant-isolation suites** | R-QAA |
| G3 Security | automated on every PR; **isolation/RLS probes**; tenancy work is CC-2 (full checklist + R-SA sign-off) | R-SA |
| G4 Architecture | RLS/tenancy is **CC-1** (SecurityModel anchor) and migrations are **CC-4** (R-DBA, expand–contract) | R-CA / R-DBA |
| G6 Observability | new endpoints + OTel wiring (P0-E4-S3) | R-OE (A4) |
| G7 Documentation | every PR; OpenAPI + design-note docs | R-DE |
| G8 Review & Done | every PR; CC-1 two approvals | R-CR |

G5 (performance) has no CC-3 hot-path trigger yet; EXPLAIN evidence for the new RLS-carrying queries is captured now to seed later G5 budgets. **Release gates: none** this sprint.

## 11. Estimated context size

Sprint-union budget **~50–60k tokens**, consistent with [./ContextManifest.md](./ContextManifest.md) (SPRINT-01) and [./SprintCatalog.md](./SprintCatalog.md). Per-task packs stay within the [ContextManagementStrategy §2](../engineering-operating-system/ContextManagementStrategy.md) rule-4 ceiling.

| Document class | Approx. tokens |
|---|---|
| Invariant prefix (CLAUDE.md + this file + Lifecycle + DoR + DoD + QualityGatePolicy) | ~12–15k |
| Module charters (`eip-core`, `eip-tenancy`, `eip-app` MODULE.md) | ~3–5k |
| Sprint-specific spec set (DatabasePlan §1–§8, SecurityModel §4–§5, APIDesign, ObservabilityModel §1–§4, EventModel §9, DataFlow global invariants + §8) | ~30–36k |
| CC-2/CC-4 suffix (SecurityChecklist, TestingStrategy §3.1) | ~5–7k |
| **Sprint union total** | **~50–60k** |

Context-loss recovery: [ContextManagementStrategy §5](../engineering-operating-system/ContextManagementStrategy.md).

## 12. Lanes / parallelization

Per [./ParallelizationPlan.md](./ParallelizationPlan.md). The **DB + tenancy spine is a serial lane** (single-writer on migrations and shared tenancy types); OpenAPI and observability run as **independent parallel lanes** with disjoint write-sets ([SprintExecutionGuide §2.3](../engineering-operating-system/SprintExecutionGuide.md)).

| Lane | Write-set boundary | Executing role | Ordered work |
|---|---|---|---|
| L1 (serial spine) | Flyway migrations (CC-4) → `eip-core` infra tables → `eip-tenancy` | R-IE (backend) under R-BA/R-DBA/R-PA | P0-E2-S2 → P0-E3-S1 |
| L2 (parallel) | `eip-app` API surface + `/docs/api/openapi.json` | R-IE (backend) under R-BA | P0-E4-S2 |
| L3 (parallel) | `eip-app` telemetry config + `/infra/grafana` | R-IE (infra) under R-DOA/R-OE | P0-E4-S3 |
| L4 (spike/notes) | `/work` spike report + `/docs/adr` refinements | R-DBA (N1) · R-PA (N2/N3) · R-SA (N4) | N1 → N2 → N3 → N4 |

Write-set conflict declaration: L1 owns all migrations and tenancy types; L2/L3 read those but write only their own surfaces; the committed `openapi.json` is L2-exclusive. N1's spike harness lives under `/work` and `/infra` test scaffolding — no overlap with L1's production migrations.

## 13. Risks addressed

Per [./RiskDrivenImplementation.md](./RiskDrivenImplementation.md), front-loading the highest blast-radius × uncertainty spikes from [ImplementationReadinessDecision](../reviews/architecture-readiness/ImplementationReadinessDecision.md). This is the sprint where the top architectural risks are foreclosed.

| Risk (readiness ref) | Sprint action | Owner | Exit evidence |
|---|---|---|---|
| **(1) RLS + connection-pooling correctness** — foundational to every tenant query (OQ#10, [§6](../reviews/architecture-readiness/ImplementationReadinessDecision.md); principle 6) | N1 spike in week 1 + dedicated integration-test pattern | R-DBA | `SET LOCAL` correctness proven under pooling; test pattern committed |
| **(2) Tenant isolation proof harness** — NFR-041, release-blocking (principle 6) | Build the cross-tenant sweep harness now; grows every phase | R-SA + R-QAA | isolation suite green; storage-prefix probe (ADR-018) |
| **(3) Audit hash-chain sync-vs-async** — SEC-02 ([§6](../reviews/architecture-readiness/ImplementationReadinessDecision.md)) | N4 decision note; DDL columns finalized, impl handed to Sprint 02 | R-SA | note banked; P0-E3-S4 unblocked |
| **(4) Outbox relay topology** — unblocks all Phase-1 eventing (ADR-017 + FMA-03) | N2 decision note (relay in both runtimes, buffer bounds) | R-PA | note banked; consistent with ADR-017 |
| **(5) Consumer idempotency mechanics** (EDA-04) | N3 decision note (dedup ledger + DLQ park-index) | R-PA | note banked |
| RLS + double-enforcement complexity (Roadmap §4.6) | single tenant-context abstraction in `eip-core`/`eip-tenancy`; sweep in CI from day one | R-BA | one abstraction, no per-query filter duplication |

## 14. Demo increment

Vertical slice for the SPRINT-01 review ([IncrementStrategy](./IncrementStrategy.md)) — the RLS/trace beats of the Phase-0 demo script ([PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md)):

> On `make dev-up`: seed two Organizations → issue a write/read through `/api/v1` as tenant A → show the same read as tenant B is **provably blocked** by RLS (test output + live API probe) → open Grafana/Tempo and show the trace of the request flowing HTTP → DB → open the published `openapi.json` and show the CI diff-check that guards it.

Real auth arrives in [./Sprint02.md](./Sprint02.md) (OIDC), which turns this tenant-scoped path into a logged-in, audited one; the four banked notes let Sprint 02 build audit/secrets and let Phase 1 build eventing without re-litigating foundations.

## Related documents

- [./MasterProgram.md](./MasterProgram.md), [./Phase0.md](./Phase0.md) — program constitution and Phase-0 view
- [./SprintCatalog.md](./SprintCatalog.md), [./ContextManifest.md](./ContextManifest.md) — must agree with this file on Objective/Modules/Gates/Required-Forbidden/context
- [./Sprint00.md](./Sprint00.md) (prior), [./Sprint02.md](./Sprint02.md) (next — AuthN/Z, audit, secrets)
- [./ModuleBuildOrder.md](./ModuleBuildOrder.md), [./DependencyMatrix.md](./DependencyMatrix.md), [./ParallelizationPlan.md](./ParallelizationPlan.md), [./RiskDrivenImplementation.md](./RiskDrivenImplementation.md), [./IncrementStrategy.md](./IncrementStrategy.md)
- [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5 — Phase-0 stories/PRs/exit/demo
- [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md), [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md), [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md), [../docs/architecture/ObservabilityModel.md](../docs/architecture/ObservabilityModel.md), [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §9, [../docs/architecture/DataFlow.md](../docs/architecture/DataFlow.md)
- [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §5–§7, [../reviews/architecture-readiness/ArchitectureDecisionUpdates.md](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) (ADR-015..019, AD-2/AD-9/AD-12/AD-13/AD-15)
- [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md), [../engineering-operating-system/ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md), [../engineering-operating-system/ModuleOwnership.md](../engineering-operating-system/ModuleOwnership.md)
