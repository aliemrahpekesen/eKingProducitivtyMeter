# Module Build Order

The dependency-topological order in which the eleven EIP modules are first built, with each module's dependencies, the phase/sprint it is first built in, its owning engineering role, its owned tables and topics, and the objective "module is built" bar. This document **sequences** the module map defined in the specification and the Engineering Operating System — it does not restate their content; it links. [./DependencyMatrix.md](./DependencyMatrix.md) turns this order into the edge-level DAG; [./ParallelizationPlan.md](./ParallelizationPlan.md) turns it into concurrent lanes.

The build order is a **dependency DAG, not a strict line**: modules on disjoint branches build concurrently (see [./ParallelizationPlan.md](./ParallelizationPlan.md)). The numbering below is a valid topological linearization, not a mandate to build one module at a time.

## 1. The eleven-module build order

Owning roles are from [../engineering-operating-system/ModuleOwnership.md](../engineering-operating-system/ModuleOwnership.md) §1 (roles defined in [../engineering-operating-system/AIAgentCatalog.md](../engineering-operating-system/AIAgentCatalog.md)). Owned tables cite [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) §2 (the single table→schema→owning-module catalog); owned topics cite [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §3. First-built phase/version is per [../docs/product/Roadmap.md](../docs/product/Roadmap.md) §1; Phase-0 sprint placement is per [./SprintCatalog.md](./SprintCatalog.md) and the sprint mapping in [./Phase0.md](./Phase0.md). Sprints 00–03 are in scope for the program's first bundle; later-phase modules (analytics/AI/reports) are placed here for topological completeness only and are **out of scope** for Sprints 00–03.

| # | Module | Tier | Depends on (modules) | First built | Owning role (co-owner) | Owned tables — [DatabasePlan §2](../docs/engineering/DatabasePlan.md) | Owned topics — [EventModel §3](../docs/engineering/EventModel.md) |
|---|---|---|---|---|---|---|---|
| 1 | `infra` | T0 platform | — | **P0 / SPRINT-00** | R-DOA (R-SA: pipeline secrets) | none (owns Compose stack, CI, `/infra`, `/.github`, `/Makefile`, `/scripts`) | none (provisions the Kafka broker + topic bootstrap; owns no topic schema) |
| 2 | `eip-core` | T1 kernel | `infra` (platform) | **P0 / SPRINT-00** | R-BA (R-CA: shared-kernel/SPI surface) | `core.event_outbox`, `core.processed_events`, `core.worker_heartbeat`, `quartz.qrtz_*` — infra-owned, accessed via core services only | none (owns the event-envelope types, not any topic) |
| 3 | `eip-tenancy` | T2 security spine | `eip-core` | **P0 / SPRINT-01** | R-BA (R-PA: tenancy/quotas; R-SA advises RBAC/audit, CC-2) | `core.tenant`, `core.organization`, `core.business_unit`, `core.team`, `core.role`, `core.member`, `core.member_identity`, `core.secret`, `audit.audit_event` | none |
| 4 | `eip-app` | T3 composition root | `eip-core`, `eip-tenancy` | **P0 / SPRINT-01** (skeleton; grows every phase) | R-BA (R-SA: security filter-chain paths, CC-2) | none — composition root owns no business tables; hosts the Flyway migration dir (`.../db/migration`, gate-owned by R-DBA) | none — **`eip-app` consumes no Kafka** (AD-2) |
| 5 | `frontend` | T3 composition root | `eip-app` (HTTP `/api/v1` + OpenAPI) | **P0 / SPRINT-03** (shell) | R-FA | none (owns `/frontend`) | none |
| 6 | `eip-connectors` | T4 data plane | `eip-core`, `eip-tenancy` | **P1 / SPRINT-04** | R-CNA | `core.connector`, `core.connector_checkpoint` | none (produces only through `eip-ingestion`'s `SyncContext`) |
| 7 | `eip-ingestion` | T4 data plane | `eip-core`, `eip-connectors`, `eip-tenancy` | **P1 / SPRINT-04–05** | R-CNA (R-DA: normalization/canonical mapping) | `core.external_ref`, `core.entity_link`; canonical write side of `work.*`, `scm.*`, `cicd.*`, `quality.*`, `ops.*`; `staging.*` | `eip.raw.<connector>`, `eip.domain.workitem`, `eip.domain.scm`, `eip.domain.cicd`, `eip.domain.quality`, `eip.domain.ops` |
| 8 | `eip-workers` | T4 data plane | `eip-ingestion` (+ later `eip-analytics`, `eip-ai`, `eip-reports`) | **P1 / SPRINT-05** (first deployed as a separate process, per [./SprintCatalog.md](./SprintCatalog.md)) | R-BA (R-DOA: deployment/runtime topology) | none (deployable runtime; hosts consumers + the workers-side `OutboxRelay`) | none (hosts consumer groups; topic ownership stays with the producing module) |
| 9 | `eip-analytics` | T5 analytics | `eip-core`; consumes `eip.domain.*`; **read-only grant** to canonical schemas (ADR-019) | **P2–P3** (out of Sprint 00–03) | R-DA (R-BA: runtime/query side) | `analytics.metric_definition`, `analytics.metric_fact`, `analytics.rm_*`, `analytics.risk_assessment`, `analytics.analytics_watermark` | `eip.analytics.metrics` |
| 10 | `eip-ai` | T5 AI | `eip-core`, `eip-tenancy`, `eip-analytics` (query API) | **P3–P4** (out of Sprint 00–03) | R-AIA | `ai.rag_*`, `ai.llm_provider`, `ai.llm_call_audit`, `ai.agent_run`, `ai.agent_step`, `ai.mcp_capability_grant` | `eip.ai.jobs`, `eip.ai.results` |
| 11 | `eip-reports` | T5 reports | `eip-core`, `eip-ai` (agent outputs) | **P3–P4** (out of Sprint 00–03) | R-BA (R-AIA: report composition via agents) | `reports.report_template`, `reports.report_schedule`, `reports.report_subscription`, `reports.report_job`, `reports.generated_report` | `eip.reports.jobs` |

Table→module and topic→module assignments above are **subsets quoted from** [DatabasePlan §2](../docs/engineering/DatabasePlan.md) and [EventModel §3](../docs/engineering/EventModel.md); each module's `MODULE.md` "Owned tables" section MUST be a subset of its row (docs-lint rule, [ModuleOwnership §3](../engineering-operating-system/ModuleOwnership.md)). Where the two disagree, the spec docs win.

## 2. Definition: "the module is built"

A module counts as **built** — the bar every module row above is measured against, and the RG-attestable deliverable per [ModuleOwnership §2.1](../engineering-operating-system/ModuleOwnership.md) — only when all four hold:

1. **`MODULE.md` charter exists** with exactly the sections of [ModuleOwnership §3](../engineering-operating-system/ModuleOwnership.md): Purpose · Owner · Owned tables · Owned topics/consumer groups · Owned endpoints · Invariants (numbered, testable) · Dependencies (mirroring the Modulith declaration). No empty placeholders ([ModuleOwnership §7](../engineering-operating-system/ModuleOwnership.md)).
2. **Flyway migrations exist for its owned tables** per [DatabasePlan §2](../docs/engineering/DatabasePlan.md), expand–contract and forward-only, RLS attached on every tenant-scoped table (the enumerated no-RLS exceptions of DatabasePlan §2 excepted). Migrations live in `eip-app`'s migration dir under R-DBA's CC-4 gate ([DatabasePlan §7](../docs/engineering/DatabasePlan.md), [QualityGatePolicy G4](../engineering-operating-system/QualityGatePolicy.md)).
3. **Modulith boundary test passes** — `ApplicationModules.verify()` + the committed Documenter output diff + the ArchUnit dependency matrix, all green in G1 ([QualityGatePolicy G1](../engineering-operating-system/QualityGatePolicy.md)); the module declares only the dependencies in its [./DependencyMatrix.md](./DependencyMatrix.md) row.
4. **Gate coverage is met** for the module's change classes: G1/G2/G7/G8 on every PR, plus the conditional gates its first stories trigger — G3 (CC-2 for `eip-tenancy`/`eip-app` security paths), G4 (CC-1/CC-4 for `eip-core` boundary + schema), G6 (new endpoints/consumers/jobs). Gate ownership per [QualityGatePolicy §2](../engineering-operating-system/QualityGatePolicy.md).

"Built" is per-module and incremental: `eip-app` is *first* built (skeleton) in SPRINT-01 and re-attested every phase as it grows — the bar is re-met, not met once.

## 3. Tier rationale — why this order

The order is forced by the inviolable principles of [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §7 (module boundaries are structural; tenant isolation ≥2 layers deep on every path; PostgreSQL is the sole system of record) and the build-order rationale of [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §2.

| Tier | Modules | Why it comes when it does |
|---|---|---|
| **T0 platform** | `infra` | Everything runs on it — no module compiles, tests, or boots without the Compose stack and CI. It carries no business code, so it can and MUST land first (SPRINT-00). NFR-050 (Compose ≤15 min / 16 GB) is a SPRINT-00/03 exit gate ([Roadmap §4.4](../docs/product/Roadmap.md)). |
| **T1 kernel** | `eip-core` | The shared kernel and Modulith leaf: canonical base types, `WorkItem` supertype, `ExternalRef`, the event-envelope types, UUIDv7/Instant conventions, and the infra tables (outbox/dedup/heartbeat/Quartz). Every other module imports it, so it must exist before them; it **owns no business logic or business tables** and stays a leaf ([ImplementationReadinessDecision §7](../reviews/architecture-readiness/ImplementationReadinessDecision.md) principle 3). |
| **T2 security spine** | `eip-tenancy` | Isolation, RBAC, audit, and the secret vault/KMS SPI must exist **before the first external byte is ingested** — retrofitting tenant isolation onto ingested data is the single most expensive mistake this product class can make ([PhaseBasedImplementationPlan §2](../docs/implementation/PhaseBasedImplementationPlan.md)). Every downstream row carries `tenant_id` + forced RLS; connectors need the vault and audit on day one. Built entirely in Phase 0, ahead of any data plane. |
| **T3 composition root** | `eip-app`, `frontend` | The composition roots own no tables and no business logic ([ImplementationReadinessDecision §7](../reviews/architecture-readiness/ImplementationReadinessDecision.md) principle 3); they compose the modules below into `/api/v1` and the React shell. They can only be built once the kernel and security spine expose APIs — `eip-app` skeleton in SPRINT-01, `frontend` shell in SPRINT-03. `eip-app` consumes no Kafka (AD-2). |
| **T4 data plane** | `eip-connectors`, `eip-ingestion`, `eip-workers` | The ingestion spine: SPI → sync engine → single canonical writer (ADR-019), with `eip-workers` as the first separately-deployed runtime hosting consumers. Depends on the entire security spine (vault, audit, tenant context) being real. Phase 1, out of the Sprint 00–03 bundle. |
| **T5 analytics / AI / reports** | `eip-analytics`, `eip-ai`, `eip-reports` | Read-side consumers of the canonical model and domain event stream. `eip-analytics` gets only the enumerated **read-only canonical grant** and never writes canonical tables (ADR-019); AI and reports narrate over trustworthy metrics ([PhaseBasedImplementationPlan §2](../docs/implementation/PhaseBasedImplementationPlan.md), "analytics before AI"). Phases 2–4, out of the Sprint 00–03 bundle. |

**Do not read ahead:** the specification documents for T5 modules (AI/RAG/MCP, analytics, reports, connector catalogs) are **FORBIDDEN reading in Sprints 00–03** ([./ContextManifest.md](./ContextManifest.md), [../engineering-operating-system/ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md)). Their placement here is topological only.

## Related documents

- [./MasterProgram.md](./MasterProgram.md) — program constitution and the map of all 15 /program docs
- [./DependencyMatrix.md](./DependencyMatrix.md) — the edge-level module × module DAG this order linearizes
- [./ParallelizationPlan.md](./ParallelizationPlan.md) — concurrent build lanes over this order
- [./DevelopmentSequence.md](./DevelopmentSequence.md) — the task-level sequence that consumes this order
- [./SprintCatalog.md](./SprintCatalog.md) · [./Phase0.md](./Phase0.md) — sprint placement of each module's first build
- [../engineering-operating-system/ModuleOwnership.md](../engineering-operating-system/ModuleOwnership.md) — owning roles, `MODULE.md` charter contract, CODEOWNERS
- [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) §2 — table→schema→owning-module catalog (source of record)
- [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §3 — topic→owning-module catalog (source of record)
- [../reviews/architecture-readiness/ArchitectureDecisionUpdates.md](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) — ADR-015..020 driving ownership and writer rules
- [../docs/architecture/ArchitectureOverview.md](../docs/architecture/ArchitectureOverview.md) §7 — ADR-001..020 index
