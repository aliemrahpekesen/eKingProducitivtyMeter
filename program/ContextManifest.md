# Context Manifest — the per-sprint reading list

**Purpose.** This is the single lookup a Claude Code session needs to know *what to read before it touches code*. For every Phase-0 sprint (SPRINT-00…03) it lists the EXACT documents — with section granularity — split into **REQUIRED** (read before starting), **REFERENCE-ON-DEMAND** (consult only if the task touches it), and **FORBIDDEN** (later-phase docs a Phase-0 session MUST NOT read). Phase-1 sprints (SPRINT-04…07) carry a coarse entry; their full manifests are authored at Phase-1 kickoff. This document SEQUENCES and PACKAGES the spec + EOS — it never restates them; it links.

## 0. Governing rules (normative)

1. **A session reads only its manifest, never the whole repo.** The manifest below IS the context pack for a sprint's tasks; a task file's own pack (`/work/tasks/TASK-NNNN.md`) is a further-trimmed subset per [../engineering-operating-system/ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md) §2. If executing a task correctly needs a doc not in the manifest, the manifest is defective — fix it (R-TPM), do not widen reading ad hoc.
2. **The repository is shared memory; L1 is cited, never restated** ([ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md) §1). Every entry here is a path + section pointer with a one-line "why" — never pasted content ([ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md) §7).
3. **Pack fits one session.** Practical ceiling per [ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md) §2.4: ~10 entries / ~1,500 referenced lines per task. A task whose reading cannot be trimmed to fit is size L and MUST be split (DoR/G0).
4. **FORBIDDEN means forbidden.** Reading later-phase spec docs (AI/RAG/MCP, analytics engines, reports, connector catalogs, K8s) during Phase 0 wastes context and invites premature coupling. The FORBIDDEN list per sprint is closed and explicit.
5. **Pinned, ordered, self-contained.** Entries reference committed files at repo HEAD, read top-to-bottom (bootstrap → task → charter → spec → decisions), addressable by an agent whose only prior knowledge is the checkout ([ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md) §2).

## 1. Universal always-read set (every Phase-0 sprint, every task)

The invariant prefix of [ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md) §3, extended for /program-driven sessions. Read these first, in this order, on **every** task before any sprint- or task-specific doc:

| # | Document | Why | Class |
|---|---|---|---|
| 1 | `/CLAUDE.md` ([../CLAUDE.md](../CLAUDE.md)) | Universal session bootstrap — read first, no exceptions | bootstrap |
| 2 | `/work/tasks/TASK-NNNN.md` (the task itself) | Objective, write-set, acceptance, task-local pack | bootstrap |
| 3 | `./Sprint0N.md` (the sprint you are in) | Objective, modules, gates, lanes, demo increment | program |
| 4 | [../engineering-operating-system/DevelopmentLifecycle.md](../engineering-operating-system/DevelopmentLifecycle.md) §3–§4 | Task state machine; how a task moves INTAKE→…→DONE | eos-process |
| 5 | [../engineering-operating-system/DefinitionOfReady.md](../engineering-operating-system/DefinitionOfReady.md) §1 | The G0 checklist you must satisfy to start | eos-process |
| 6 | [../engineering-operating-system/DefinitionOfDone.md](../engineering-operating-system/DefinitionOfDone.md) §1–§2 | MERGED vs VERIFIED vs DONE; the G8 DoD checklist | eos-process |
| 7 | [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) §2–§4 | Gates G0–G8, the change-class × gate matrix, waivers | eos-process |
| 8 | The in-scope `MODULE.md` charter(s) — see per-sprint | The 5-minute L2 truth for each module in the write-set | charter |

Module charters in scope — created with the module in Phase 0/1 per [ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md) §6, so these paths do not resolve until the owning sprint creates them: `../backend/eip-core/MODULE.md`, `../backend/eip-tenancy/MODULE.md`, `../backend/eip-app/MODULE.md`, `../frontend/MODULE.md`. `/infra` is a top-level path (owner R-DOA per [../engineering-operating-system/ModuleOwnership.md](../engineering-operating-system/ModuleOwnership.md)) with no `MODULE.md`; its spec homes are the infrastructure docs cited per sprint.

## 2. Per-task-type reading lists

On top of the always-read set, add the suffix for the task's type, per [../engineering-operating-system/ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md) §3 (sections chosen per task; do not read whole large files):

| Task type | Add, in order |
|---|---|
| **Backend** | [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) · [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) · [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) · [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) if surface touched |
| **Frontend** | [../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) · [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) (consumed endpoints) · [../docs/product/Personas.md](../docs/product/Personas.md) + [../docs/product/UserJourneys.md](../docs/product/UserJourneys.md) (the journey served) · [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §9 |
| **Schema / migration** | [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) (owned tables + RLS) · [../docs/architecture/DomainModel.md](../docs/architecture/DomainModel.md) (entities touched) · [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §3.1 · expand–contract rule in [../engineering-operating-system/RepositoryRules.md](../engineering-operating-system/RepositoryRules.md) |
| **Infra / CI** | [../docs/infrastructure/DockerCompose.md](../docs/infrastructure/DockerCompose.md) · [../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md) · [../docs/operations/OperationsGuide.md](../docs/operations/OperationsGuide.md) (affected runbooks) · [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §14 |

**Change-class riders** (add regardless of type, per [ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md) §3): **CC-2** security-relevant → [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md) (relevant sections) + [../engineering-operating-system/SecurityChecklist.md](../engineering-operating-system/SecurityChecklist.md); **CC-3** perf → the NFR budget rows in [../docs/product/PRD.md](../docs/product/PRD.md) §6 + [../engineering-operating-system/PerformanceChecklist.md](../engineering-operating-system/PerformanceChecklist.md); **CC-4** schema → [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) §2 G4 (R-DBA expand–contract). Connector/AI task types exist but are FORBIDDEN in Phase 0 (§7).

## 3. Token budget model

Estimates are for the manifest reading only (not the implementation work that runs alongside). Budgets are by document class; per-sprint totals match the spine's per-sprint context sizing.

| Document class | Typical span | Est. tokens |
|---|---|---|
| bootstrap (`/CLAUDE.md`, task file, `./Sprint0N.md`) | full | 6–7k |
| eos-process (DevelopmentLifecycle, DoR, DoD, QualityGatePolicy — cited sections) | ~sections | 9–11k |
| charter (each `MODULE.md`) | full | 1–2k each |
| spec-doc (per cited section) | 1 section | 1.5–3k each |
| decision (ADR summary / readiness section) | cited rows | 1–2k each |

| Sprint | bootstrap | eos-process | charters | spec-docs | decisions | **Total (spine target)** |
|---|---|---|---|---|---|---|
| SPRINT-00 | ~6.5k | ~10k | ~1.5k | ~12k | ~2k | **~35–45k** |
| SPRINT-01 | ~6.5k | ~10k | ~4k | ~18k | ~4k | **~50–60k** |
| SPRINT-02 | ~6.5k | ~10k | ~3k | ~20k | ~4k | **~55–65k** |
| SPRINT-03 | ~6.5k | ~10k | ~3k | ~18k | ~3k | **~50–60k** |

**Phase-0 grand total across the four sprints: ~190–230k tokens** — but no single session ever loads more than its own sprint's budget (rule §0.1). A session that finds itself needing more than its sprint budget has a task that is too large (split it) or is reading outside its manifest (stop).

## 4. SPRINT-00 — Repo & platform bootstrap

Modules: `infra`, `eip-core` (skeleton). Stories: P0-E1-S1..S4, P0-E2-S1 + ADR-backfill task. Catalog entry: [./SprintCatalog.md](./SprintCatalog.md) · Deep file: [./Sprint00.md](./Sprint00.md).

### REQUIRED (read before starting)
Always-read set (§1) **plus**:

| Document | Section | Why |
|---|---|---|
| [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) | §5 (esp. §5.1 stories, §5.2 first-10-PRs, §5.3 exit) | The exact Phase-0 story/PR sequence this sprint opens |
| [../engineering-operating-system/RepositoryStructure.md](../engineering-operating-system/RepositoryStructure.md) | full | Canonical monorepo tree; where every path lives |
| [../engineering-operating-system/RepositoryRules.md](../engineering-operating-system/RepositoryRules.md) | full | Trunk-based rules, expand–contract, docs-as-code merge gate |
| [../engineering-operating-system/BranchingStrategy.md](../engineering-operating-system/BranchingStrategy.md) | full | Branch/PR naming (TASK-NNNN), protection |
| [../engineering-operating-system/CodingStandards.md](../engineering-operating-system/CodingStandards.md) | full | Java/TS standards CI enforces from PR-1 |
| [../engineering-operating-system/ADRProcess.md](../engineering-operating-system/ADRProcess.md) | full | How the ADR-001..020 backfill files are written |
| [../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md) | full | `make dev-up` one-command bootstrap contract |
| [../docs/infrastructure/DockerCompose.md](../docs/infrastructure/DockerCompose.md) | §1–§4, §10 (sizing/NFR-050), §13 (smoke) | Compose stack spec + the ≤15 min/16 GB budget |
| [../docs/architecture/ArchitectureOverview.md](../docs/architecture/ArchitectureOverview.md) | §5 (module map + dep rules), §7 (ADR-001..020 summary) | Module boundaries the Modulith test enforces; ADR index to backfill |
| [../docs/architecture/DomainModel.md](../docs/architecture/DomainModel.md) | §1 (principles), §2 (identity: UUIDv7, ExternalRef) | The `eip-core` skeleton (WorkItem supertype, envelope, IDs) |

### REFERENCE-ON-DEMAND (consult only if the task touches it)
- [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §1 (Gradle module table), §3 (Modulith usage), §17 (phase mapping) — only when scaffolding the nine module skeletons.
- [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §1 (gate philosophy), §16 (CI gate map) — when wiring CI stages (P0-E1-S2).
- [../engineering-operating-system/CodeReviewChecklist.md](../engineering-operating-system/CodeReviewChecklist.md) — for the deliberate Modulith-violation demo PR.
- [../reviews/architecture-readiness/ArchitectureDecisionUpdates.md](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) §1 (ADR-015..020) + [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §8 — source text for the ADR backfill (condition #4).
- [../engineering-operating-system/ModuleOwnership.md](../engineering-operating-system/ModuleOwnership.md) — for CODEOWNERS content.

### FORBIDDEN (do not read this sprint)
All later-phase spec: [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md), [../docs/ai/RAGArchitecture.md](../docs/ai/RAGArchitecture.md), [../docs/ai/MCPArchitecture.md](../docs/ai/MCPArchitecture.md); [../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md); [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) (Sprint-01+); [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) DDL (Sprint-01); [../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) (Sprint-03); [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md) deep (Sprint-01/02); [../docs/infrastructure/KubernetesOpenShift.md](../docs/infrastructure/KubernetesOpenShift.md) and [../docs/architecture/DeploymentModel.md](../docs/architecture/DeploymentModel.md) K8s sections (Phase 5); PhaseImpl §7–§10.

## 5. SPRINT-01 — Persistence & tenancy spine

Modules: `eip-core`, `eip-tenancy`, `eip-app`, `infra`. Stories: P0-E2-S2, P0-E3-S1, P0-E4-S2 (lane), P0-E4-S3 (lane) + blocking readiness design-notes (RLS+PgBouncer spike, outbox topology, consumer idempotency, audit hash-chain approach). Catalog: [./SprintCatalog.md](./SprintCatalog.md) · Deep file: [./Sprint01.md](./Sprint01.md).

### REQUIRED
Always-read set (§1) **plus**:

| Document | Section | Why |
|---|---|---|
| [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) | §1–§8 (conventions, ownership catalog, DDL, indexing, **RLS §5**, partitioning, Flyway, read models) | DB baseline, RLS policy template, partitioning conventions (P0-E2-S2) |
| [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md) | §4 (RBAC), §5 (multi-tenancy isolation) | Tenant isolation model + RLS (P0-E3-S1); this is CC-1/CC-2 territory |
| [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) | full | `/api/v1`, RFC 7807, cursor pagination, idempotency (P0-E4-S2) |
| [../docs/architecture/ObservabilityModel.md](../docs/architecture/ObservabilityModel.md) | §1–§4 (principles, pipeline, metrics, tracing) | OTel/Micrometer/traceparent wiring (P0-E4-S3) |
| [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) | §9 (transactional outbox → Kafka) | Input to the outbox-relay-topology decision note |
| [../docs/architecture/DataFlow.md](../docs/architecture/DataFlow.md) | intro (global invariants) + §8 (Flow H audit) | Cross-cutting invariants every write path must honor |
| [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) | §6 (blocking OQs), §7 (principles 3,4,5,6) | The RLS/pooling, isolation, outbox, idempotency, audit-chain design notes originate here |
| [../reviews/architecture-readiness/ArchitectureDecisionUpdates.md](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) | ADR-017 (outbox scope), ADR-019 (single canonical writer) | Bind the topology/idempotency notes to decisions of record |

### REFERENCE-ON-DEMAND
- [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §3 (topic catalog), §8 (consumer conventions), §10 (DLQ) — only when the idempotency note needs consumer detail.
- [../docs/architecture/DomainModel.md](../docs/architecture/DomainModel.md) §4 (Organization & Tenancy context) — tenant/org entity detail.
- [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §3 (Testcontainers), §3.1 (migration/RLS integrity) — the NFR-041 isolation suite harness.
- [../reviews/architecture-readiness/MultiTenancyReview.md](../reviews/architecture-readiness/MultiTenancyReview.md), [../reviews/architecture-readiness/DataArchitectureReview.md](../reviews/architecture-readiness/DataArchitectureReview.md) — deeper risk rationale for the pooling/RLS spike.
- [../docs/infrastructure/DockerCompose.md](../docs/infrastructure/DockerCompose.md) §5 (`.env` contract) — for the PgBouncer pooling spike wiring.

### FORBIDDEN
[../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md); all [../docs/ai/](../docs/ai/) docs; analytics/reports material (metric engine, report engine); [../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md); [../docs/infrastructure/KubernetesOpenShift.md](../docs/infrastructure/KubernetesOpenShift.md); PhaseImpl §7–§10.

## 6. SPRINT-02 — AuthN/Z, audit, secrets

Modules: `eip-tenancy`, `eip-app`. Stories: P0-E3-S2 (RBAC), P0-E3-S3 (OIDC), P0-E3-S4 (audit — implements the Sprint-01 hash-chain note, SEC-02 DDL), P0-E4-S1 (secrets). Security-heavy; every story CC-2. Catalog: [./SprintCatalog.md](./SprintCatalog.md) · Deep file: [./Sprint02.md](./Sprint02.md).

### REQUIRED
Always-read set (§1) **plus**:

| Document | Section | Why |
|---|---|---|
| [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md) | full (esp. §3 authn, §4 RBAC, §6 secrets, §11 audit) | The entire sprint is the security spine; CC-2 manual G3 draws on this |
| [../engineering-operating-system/SecurityChecklist.md](../engineering-operating-system/SecurityChecklist.md) | full | The G3 manual portion + R-SA sign-off gate for every story |
| [../docs/architecture/DomainModel.md](../docs/architecture/DomainModel.md) | §2.3 (Member resolution), §4 (Org & Tenancy, roles) | Member/RBAC entity model for the permission matrix generator |
| [../docs/product/Personas.md](../docs/product/Personas.md) | §1 (RBAC roles referenced by personas) | The role catalog RBAC guards enforce (P0-E3-S2) |
| [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) | §2 (authn/authz on the API surface) | OIDC + RBAC guards land on `/api/v1` (P0-E3-S3) |
| [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) | §6 OQ#4 (OIDC revocation note), §7 principles 7,10,11 | OIDC revocation-propagation bound; deny-by-default; audit-completely; secrets-at-write-time |
| [../reviews/architecture-readiness/ArchitectureDecisionUpdates.md](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) | ADR-018 (object-storage tenancy — masked-blob prefixing) | Where secret/artifact scoping is enforced |

### REFERENCE-ON-DEMAND
- [../docs/architecture/ArchitectureOverview.md](../docs/architecture/ArchitectureOverview.md) §7 ADR-008 (Keycloak/OIDC), ADR-014 (AES-256-GCM envelope + KMS SPI) — decision context for OIDC & secrets.
- [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) §2 (audit table ownership), §5 (RLS on tenancy tables) — the SEC-02 audit DDL and RLS for new tables (CC-4).
- [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md) §2 (STRIDE) — threat framing for the checklist review.
- [../reviews/architecture-readiness/SecurityArchitectureReview.md](../reviews/architecture-readiness/SecurityArchitectureReview.md) — deeper rationale for key-compromise recovery and audit hash-chain (SEC-01/02/03).

(The REQUIRED table above lists `APIDesign.md` §2 — the security filter chain and error contract — because OIDC and the RBAC guards land on the API surface this sprint.)

### FORBIDDEN
[../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md); all [../docs/ai/](../docs/ai/) docs; analytics/reports material; dashboards spec ([../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §4 dashboard architecture); [../docs/infrastructure/KubernetesOpenShift.md](../docs/infrastructure/KubernetesOpenShift.md); PhaseImpl §7–§10.

## 7. SPRINT-03 — Console shell & Phase-0 close (v0.1)

Modules: `frontend`, `eip-app`. Stories: P0-E5-S1 (frontend shell), P0-E5-S2 (admin console skeleton) + Phase-0 exit verification + RG1–RG4 dry-run for the v0.1 internal milestone. Catalog: [./SprintCatalog.md](./SprintCatalog.md) · Deep file: [./Sprint03.md](./Sprint03.md) · Phase wrapper: [./Phase0.md](./Phase0.md).

### REQUIRED
Always-read set (§1) **plus**:

| Document | Section | Why |
|---|---|---|
| [../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) | §1 (stack), §2 (app shell), §3 (screen inventory), §8 (a11y/i18n), §11 (perf budgets) | The React shell, OIDC login, RBAC routing, TanStack Query, i18n (P0-E5-S1/S2) |
| [../docs/product/UserJourneys.md](../docs/product/UserJourneys.md) | §1 (J-01 admin install/configure) | The journey the admin-console skeleton serves |
| [../docs/product/AcceptanceCriteria.md](../docs/product/AcceptanceCriteria.md) | §2 (Platform & Tenancy ACs) | The tenancy ACs the console must satisfy; feeds RG1 |
| [../docs/product/PRD.md](../docs/product/PRD.md) | §10 (Phase 0 release criteria) | The phase-exit checklist RG1 verifies |
| [../docs/operations/OperationsGuide.md](../docs/operations/OperationsGuide.md) | §6 (backup and restore) | The Phase-0 backup/restore drill for RG4 operability |
| [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) | §5.3 (demo script + exit criteria) | The end-to-end Phase-0 demo script this sprint runs live |
| [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) | §4 (endpoint catalog) | The admin endpoints the console consumes/extends (P0-E5-S2) |

### REFERENCE-ON-DEMAND
- [../docs/product/Personas.md](../docs/product/Personas.md) §2.1 (Deniz — Platform Administrator) — the persona driving the demo.
- [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §9 (frontend testing), §10 (E2E) — for the console's tests and the demo E2E.
- [../docs/product/Roadmap.md](../docs/product/Roadmap.md) §4.4–§4.5 (Phase-0 exit + demo milestone) — cross-check against PRD §10.
- [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) §6 (RG1–RG4 protocol) — the release-gate dry-run choreography.

### FORBIDDEN
[../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md); all [../docs/ai/](../docs/ai/) docs; analytics dashboards ([../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) §4 dashboard architecture — the admin shell is not a metrics dashboard); [../docs/infrastructure/KubernetesOpenShift.md](../docs/infrastructure/KubernetesOpenShift.md) and K8s deployment sections; PhaseImpl §6–§10.

## 8. SPRINT-04…07 (Phase 1) — coarse entry

Full manifests are authored at Phase-1 kickoff (rule: catalogued at phase kickoff, not now). The always-read set (§1) and per-task-type lists (§2) still apply. Coarse REQUIRED additions and the still-FORBIDDEN set:

| Sprint | Modules | REQUIRED adds (coarse) | Still FORBIDDEN |
|---|---|---|---|
| SPRINT-04 Connector SPI + sync-engine spine | eip-connectors, eip-ingestion | ConnectorFramework §2–§10; EventModel §2–§4; DataFlow Flows A/B; BackendPlan (connectors/ingestion); TestingStrategy §5.3 (kit K1–K7), §6 | AI/RAG/MCP; analytics metric engine; reports; K8s |
| SPRINT-05 Kafka pipeline + normalizer + model v1 | eip-ingestion, eip-core, eip-workers | EventModel full; DatabasePlan (canonical tables); DomainModel §5–§8; DataFlow Flow C; ADR-017/019 | AI/RAG/MCP; analytics; reports; K8s |
| SPRINT-06 Jira + GitHub + simulation connectors + kit | eip-connectors, /simulation | ConnectorFramework §11 catalog entries (Jira/GitHub); TestingStrategy §7–§8; simulation pack spec | AI/RAG/MCP; analytics; reports; K8s |
| SPRINT-07 Connector admin UI + data browser + Phase-1 close | frontend, eip-app | FrontendPlan §3 (integration screens); UserJourneys J-01; PRD §10 (Phase 1); OperationsGuide §3.1 (DLQ replay) for RG4 | AI/RAG/MCP; analytics dashboards; reports; K8s |

## 9. Context-loss recovery

A session that starts (or restarts) with no memory and an uncertain task state MUST follow the exact recovery order in [../engineering-operating-system/ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md) §5 — read `/CLAUDE.md`, then the task spec's state/status log, then the highest-`<seq>` handoff note in `/work/handoffs/`, inspect the branch and CI (CI is truth), re-read only the manifest entries relevant to the next step, and **write no code before step 7**. If reconstructed state is ambiguous, set the task BLOCKED and escalate to R-TPM rather than guessing. A non-DONE task with no handoff note is a protocol violation: treat the branch as untrusted and re-verify every claim against CI.

## Related documents

- [./MasterProgram.md](./MasterProgram.md) — program constitution and the map of all 15 /program docs
- [./SprintCatalog.md](./SprintCatalog.md) — the per-sprint index (Required/Forbidden columns link back here)
- [./Phase0.md](./Phase0.md) — the Phase-0 charter wrapping SPRINT-00…03
- [./Sprint00.md](./Sprint00.md) · [./Sprint01.md](./Sprint01.md) · [./Sprint02.md](./Sprint02.md) · [./Sprint03.md](./Sprint03.md) — the deep sprint files
- [../engineering-operating-system/ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md) — source of record for context layers, pack rules, per-task-type lists, and recovery
- [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) · [../engineering-operating-system/DefinitionOfReady.md](../engineering-operating-system/DefinitionOfReady.md) · [../engineering-operating-system/DefinitionOfDone.md](../engineering-operating-system/DefinitionOfDone.md) · [../engineering-operating-system/DevelopmentLifecycle.md](../engineering-operating-system/DevelopmentLifecycle.md)
