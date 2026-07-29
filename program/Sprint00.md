# SPRINT-00 — Repo & platform bootstrap

The first Phase-0 sprint: stand up the monorepo, CI, the Docker Compose dev stack, the `eip-core` skeleton, and the governance backfill (ADR-001..020, CODEOWNERS, docs-lint) so every later sprint has a green, gated, dependency-topological floor to build on. This file **sequences and packages** the sources of record for execution; it never restates them — it links. See [./MasterProgram.md](./MasterProgram.md) for the program constitution and [./Phase0.md](./Phase0.md) for the phase view.

- **Phase / version:** Phase 0 (v0.1) — Foundations ([../docs/product/Roadmap.md](../docs/product/Roadmap.md) §4)
- **Position:** Phase 0 weeks 1–2 (per [./SprintCatalog.md](./SprintCatalog.md) and [./DevelopmentSequence.md](./DevelopmentSequence.md))
- **Cadence:** 2-week sprint, task states INTAKE→READY→CLAIMED→IN_PROGRESS→IN_REVIEW→MERGED→VERIFIED→DONE ([../engineering-operating-system/DevelopmentLifecycle.md](../engineering-operating-system/DevelopmentLifecycle.md) §3)

## 1. Objective

Deliver a monorepo where a new engineer is productive in under a day and CI enforces the [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) gates from the first PR. At close, `make dev-up` boots the full Compose stack healthy, CI is green on empty modules, a Spring Modulith boundary violation is provably blocked by CI then fixed, and `/docs/adr/ADR-001..020` are browsable. This sprint builds **infra** and the **eip-core** skeleton — the bottom two layers of the [./ModuleBuildOrder.md](./ModuleBuildOrder.md) DAG — and lands the Phase-0 backfill task mandated by [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §3 condition 4 / §8.

## 2. Modules

| Module | State entering | Work this sprint | Owning role |
|---|---|---|---|
| **infra** (`/infra`, `/.github`, `/Makefile`, `/scripts`) | absent | Compose stack, CI pipeline, docs-lint job, CODEOWNERS | R-DOA (co-owner R-SA: pipeline secrets) |
| **eip-core** (`/backend/eip-core`) | absent | Skeleton only: shared-kernel base types, `WorkItem` supertype, `ExternalRef`, event-envelope types, UUIDv7/`Instant` conventions, Modulith boundary test + `MODULE.md`. **No business logic, no owned tables yet.** | R-BA (co-owner R-CA: shared-kernel/SPI is contract-anchor territory) |

Module ownership per [../engineering-operating-system/ModuleOwnership.md](../engineering-operating-system/ModuleOwnership.md) §1. `eip-core` is and stays a leaf module (its invariant).

## 3. Stories

P-E-S IDs from [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5.1. Story reassignment is forbidden — this set is fixed by [./SprintCatalog.md](./SprintCatalog.md).

| Story | Title (see PhaseImpl §5.1) | Module(s) | Size | First-PR seed (§5.2) |
|---|---|---|---|---|
| P0-E1-S1 | Monorepo scaffolding (Gradle multi-module `/backend`, Vite `/frontend`, `/infra`, `/scripts`) | all | S | PR-1 |
| P0-E1-S2 | CI pipeline: static, unit, integration, Modulith/ArchUnit stages; coverage gates | infra | M | PR-2 (first cut) |
| P0-E1-S3 | Docker Compose dev stack (Postgres 16+pgvector, Kafka KRaft, Redis 7, MinIO, Keycloak, OTel Collector, Prometheus, Grafana) | infra | M | PR-3 |
| P0-E1-S4 | Developer docs: README, onboarding guide, `make dev-up` one-command bootstrap | docs, scripts | S | PR-10 |
| P0-E2-S1 | `eip-core` domain skeleton (shared kernel, canonical base types, `ExternalRef`, `Instant`/UUIDv7) | eip-core | M | PR-4 |

**Sprint-scoped governance task (not a story — condition-4 backfill, [ImplementationReadinessDecision](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §8):** create `/docs/adr/ADR-001..020` files (ADR-001–014 backfill + ADR-015–020 from the readiness review) per [../engineering-operating-system/ADRProcess.md](../engineering-operating-system/ADRProcess.md) §4; commit the ready-to-use CODEOWNERS from [../engineering-operating-system/ModuleOwnership.md](../engineering-operating-system/ModuleOwnership.md) §4; wire the docs-lint CI job (links, IDs, canonical-value drift). ADR content is the summary set in [../docs/architecture/ArchitectureOverview.md](../docs/architecture/ArchitectureOverview.md) §7 and [../reviews/architecture-readiness/ArchitectureDecisionUpdates.md](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) §1.

## 4. Required documents

The exact per-task reading set is owned by [./ContextManifest.md](./ContextManifest.md) (SPRINT-00 entry); this list is that entry verbatim and MUST stay in sync with it. An agent reads **only** its manifest, never the whole repo ([../engineering-operating-system/ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md) §2).

**Invariant prefix (every task, in order — ContextManagementStrategy §3):**
1. [/CLAUDE.md](../CLAUDE.md) — universal session bootstrap
2. `/work/tasks/TASK-NNNN.md` — the task itself
3. this file, `./Sprint00.md`
4. [../engineering-operating-system/DevelopmentLifecycle.md](../engineering-operating-system/DevelopmentLifecycle.md), [../engineering-operating-system/DefinitionOfReady.md](../engineering-operating-system/DefinitionOfReady.md), [../engineering-operating-system/DefinitionOfDone.md](../engineering-operating-system/DefinitionOfDone.md), [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md)
5. the in-scope `MODULE.md`(s): `/backend/eip-core/MODULE.md` (from PR-4 onward)

**Sprint-specific spec set (add per task type):**

| Document (path + section) | Why | For story |
|---|---|---|
| [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5 | Phase-0 epics, first-10-PRs, exit criteria, demo | all |
| [../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md) | `make dev-up`, day-1 journey, make-target catalog | P0-E1-S3/S4 |
| [../docs/infrastructure/DockerCompose.md](../docs/infrastructure/DockerCompose.md) | service catalog, profiles, NFR-050 boot budget, smoke checklist | P0-E1-S3 |
| [../docs/architecture/ArchitectureOverview.md](../docs/architecture/ArchitectureOverview.md) §5, §7 | module map + dependency rules; ADR index for the backfill | P0-E2-S1, ADR task |
| [../docs/architecture/DomainModel.md](../docs/architecture/DomainModel.md) §1–§2 | modeling principles + identity strategy (UUIDv7, `ExternalRef`) | P0-E2-S1 |
| [../engineering-operating-system/RepositoryStructure.md](../engineering-operating-system/RepositoryStructure.md), [../engineering-operating-system/RepositoryRules.md](../engineering-operating-system/RepositoryRules.md), [../engineering-operating-system/BranchingStrategy.md](../engineering-operating-system/BranchingStrategy.md), [../engineering-operating-system/CodingStandards.md](../engineering-operating-system/CodingStandards.md) | canonical tree, branch names, coding conventions | P0-E1-S1/S2 |
| [../engineering-operating-system/ADRProcess.md](../engineering-operating-system/ADRProcess.md) §4 | ADR file format for the backfill | ADR task |

## 5. Forbidden documents

Reading later-phase spec docs wastes context and invites premature coupling ([ContextManagementStrategy](../engineering-operating-system/ContextManagementStrategy.md) §7). For SPRINT-00 the following MUST NOT be in any task's context pack:

- All AI/RAG/MCP docs: [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md), [../docs/ai/RAGArchitecture.md](../docs/ai/RAGArchitecture.md), [../docs/ai/MCPArchitecture.md](../docs/ai/MCPArchitecture.md)
- Analytics/reports/dashboards specifics; connector catalogs: [../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md)
- The tenancy/security/API/DB/observability internals arrive in SPRINT-01/02 — **not now**: [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md), [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md), [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md), [../docs/architecture/ObservabilityModel.md](../docs/architecture/ObservabilityModel.md)
- Kubernetes/OpenShift deployment sections: [../docs/infrastructure/KubernetesOpenShift.md](../docs/infrastructure/KubernetesOpenShift.md), [../docs/architecture/DeploymentModel.md](../docs/architecture/DeploymentModel.md) K8s sections (Compose only this phase)

## 6. Inputs (DONE at sprint start)

- Baselined specification (tag **spec-v1.0**) under `/docs` and the [../engineering-operating-system/](../engineering-operating-system/) Engineering Operating System.
- [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md): verdict **READY WITH CONDITIONS** — "the project may proceed to Sprint Planning immediately" (§1); the four Phase-0 first tasks named in §8.
- [../reviews/architecture-readiness/ArchitectureDecisionUpdates.md](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) §1 (ADR-015..020 text) and [../docs/architecture/ArchitectureOverview.md](../docs/architecture/ArchitectureOverview.md) §7 (ADR-001..020 summary index) as the ADR-backfill source.
- No application code yet — the repository is `/docs` + EOS only. This is the greenfield start.

## 7. Outputs (concrete artifacts)

| Artifact | Location | Story |
|---|---|---|
| Gradle multi-module backend + Vite frontend skeletons, editorconfig, licenses | `/backend`, `/frontend` | P0-E1-S1 |
| CI pipeline (build, unit, integration, Modulith/ArchUnit stages, coverage ratchet), branch protection | `/.github` (or Jenkins), branch rules | P0-E1-S2 |
| Compose dev stack + healthchecks + `make dev-up` | `/infra/docker-compose`, `/Makefile` | P0-E1-S3 |
| README, onboarding guide, contribution rules incl. gates | `/README.md`, `/docs` | P0-E1-S4 |
| `eip-core` module: base types, `WorkItem` supertype, `ExternalRef`, envelope types, Modulith verification test, `MODULE.md` charter | `/backend/eip-core` | P0-E2-S1 |
| `ADR-001..020` files | `/docs/adr/` | governance task |
| CODEOWNERS; docs-lint CI job | `/CODEOWNERS`, CI | governance task |

## 8. Deliverables (reviewable increment)

The sprint-review increment ([../engineering-operating-system/SprintReviewGuide.md](../engineering-operating-system/SprintReviewGuide.md) §2): a clean clone that reaches a green, bootable platform floor — Compose stack up via one command, CI enforcing gates on empty modules, `eip-core` shipping its Modulith boundary test, and the full ADR set browsable. This is the "M0.1 CI green on empty modules" milestone from [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md).

## 9. Exit criteria (testable)

Tied to story completion and the Phase-0 exit-criteria lines in [../docs/product/PRD.md](../docs/product/PRD.md) §10 (Phase 0) and [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md):

- [ ] Docker Compose core + observability profile boots healthy in ≤ 15 min from a clean 16 GB host (**NFR-050**, per [../docs/infrastructure/DockerCompose.md](../docs/infrastructure/DockerCompose.md) §10/§13); `make dev-up` boots the infra dev stack (P0-E1-S3/S4). *(Phase-0 exit line 4, PRD §10.)*
- [ ] CI enforces unit + integration + Modulith gates; coverage ratchet active (P0-E1-S2). *(Phase-0 exit line 1, PRD §10.)*
- [ ] A PR introducing a Spring Modulith boundary violation is **blocked** by CI, then passes once fixed (`eip-core` `ApplicationModules.verify()` + committed Documenter diff — G1).
- [ ] `eip-core` ships base types + Modulith boundary test; `MODULE.md` present and accurate (P0-E2-S1).
- [ ] `/docs/adr/ADR-001..020` exist and docs-lint is green over links/IDs; CODEOWNERS covers every path (codeowners-coverage check) — satisfies condition 4 of [ImplementationReadinessDecision](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §3.
- [ ] Every merged PR passed its required gates (§10) — no gate bypass, evidence is CI links not prose ([QualityGatePolicy](../engineering-operating-system/QualityGatePolicy.md) §5).

## 10. Review gates

Per [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) §2–§3. Merge gates applied this sprint:

| Gate | Applies because | Owner |
|---|---|---|
| G0 Ready | every task before READY (DoR, [DefinitionOfReady](../engineering-operating-system/DefinitionOfReady.md)) | R-TPM |
| G1 Build & Static | every PR — incl. Modulith `verify()` + ArchUnit + license allow-list | R-DOA |
| G2 Tests | every PR — unit + integration (Testcontainers) + coverage ratchet | R-QAA |
| G4 Architecture | `eip-core` shared-kernel is contract-anchor (CC-1); ADR files land under `/docs/adr` | R-CA (with R-BA) |
| G7 Documentation | every PR — README/onboarding/ADRs; docs-lint | R-DE |
| G8 Review & Done | every PR — R-CR verdict; CC-1 needs two approvals | R-CR |

G3 runs its automated portion on every PR (gitleaks, dependency scan) but no CC-2 manual review is expected this sprint. G5/G6 have no triggers yet (no hot paths, no endpoints/consumers/jobs). **Release gates: none** — RG1–RG4 first apply at the Phase-0 close in [./Sprint03.md](./Sprint03.md).

## 11. Estimated context size

Sprint-union budget **~35–45k tokens**, consistent with [./ContextManifest.md](./ContextManifest.md) (SPRINT-00) and [./SprintCatalog.md](./SprintCatalog.md). Per-task packs stay within the [ContextManagementStrategy](../engineering-operating-system/ContextManagementStrategy.md) §2 rule-4 ceiling (~10 entries / ~1,500 referenced lines); the figure below is the union across the sprint's tasks, not any single pack.

| Document class | Approx. tokens |
|---|---|
| Invariant prefix (CLAUDE.md + this file + Lifecycle + DoR + DoD + QualityGatePolicy) | ~12–15k |
| Module charter (`eip-core/MODULE.md`, small skeleton) | ~1–2k |
| Sprint-specific spec set (PhaseImpl §5, LocalDevelopment, DockerCompose, ArchOverview §5/§7, DomainModel §1–§2, RepoStructure/Rules/Branching/CodingStandards, ADRProcess §4) | ~20–26k |
| **Sprint union total** | **~35–45k** |

Context-loss recovery: an agent resuming cold follows [ContextManagementStrategy](../engineering-operating-system/ContextManagementStrategy.md) §5 (bootstrap → task → highest handoff → branch/CI truth) and MUST NOT write code before step 7.

## 12. Lanes / parallelization

Per [./ParallelizationPlan.md](./ParallelizationPlan.md) and the disjoint-write-set law ([../engineering-operating-system/SprintExecutionGuide.md](../engineering-operating-system/SprintExecutionGuide.md) §2.3). SPRINT-00 is **mostly serial** because scaffolding (P0-E1-S1 / PR-1) gates everything; once the tree exists three lanes run concurrently.

| Lane | Write-set boundary | Executing role | Ordered work |
|---|---|---|---|
| L0 (serial prerequisite) | repo root scaffolding, `settings.gradle.kts`, `buildSrc` | R-IE (backend) under R-BA | P0-E1-S1 (PR-1) — merges before all lanes start |
| L1 | `/infra`, `/.github`, `/Makefile` | R-IE (infra) under R-DOA | P0-E1-S2 (PR-2) → P0-E1-S3 (PR-3) |
| L2 | `/backend/eip-core` | R-IE (backend) under R-BA/R-CA | P0-E2-S1 (PR-4) |
| L3 | `/docs/adr`, `/CODEOWNERS`, docs-lint job, `/docs` onboarding | R-CA + R-DE | ADR backfill · CODEOWNERS · docs-lint · P0-E1-S4 (PR-10) |

Write-set conflict declaration: L0 merges first; L1/L2/L3 are pairwise disjoint thereafter. No task touches a contract anchor except L2 (CC-1, gated G4) and the ADR files in L3.

## 13. Risks addressed

Per [./RiskDrivenImplementation.md](./RiskDrivenImplementation.md), ordered by blast radius × uncertainty from [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md).

| Risk | Mitigation this sprint | Exit evidence |
|---|---|---|
| Over-engineering the foundation ([PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md); Roadmap §4.6) | Skeleton-only `eip-core`, no business logic; everything must be consumed by a later Phase-0/1 story or it is cut | `eip-core` diff is base types + envelope only |
| Compose/K8s drift, "works in demo, fails in prod" ([PhaseImpl §13](../docs/implementation/PhaseBasedImplementationPlan.md); principle 15, [ImplementationReadinessDecision](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §7) | Same images / probes / config surface from day 0; SCC-compatible image standards set now | Compose smoke checklist green ([DockerCompose §13](../docs/infrastructure/DockerCompose.md)) |
| Governance/automation debt (readiness condition 4, §3) | ADR-001..020 + CODEOWNERS + docs-lint land in this sprint, not deferred | docs-lint + codeowners-coverage green |
| Boundary erosion (principle 3, [ImplementationReadinessDecision](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §7) | Modulith + ArchUnit matrix wired into G1 from the first module | Failing-then-fixed boundary PR demonstrated |

## 14. Demo increment

Vertical slice for the SPRINT-00 review ([IncrementStrategy](./IncrementStrategy.md); vertical-slice principle [PhaseImpl §4](../docs/implementation/PhaseBasedImplementationPlan.md)) — the opening beats of the Phase-0 demo script ([PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md)):

> Clean clone → `make dev-up` → the full Compose stack (Postgres+pgvector, Kafka KRaft, Redis, MinIO, Keycloak, OTel Collector, Prometheus, Grafana) reaches healthy within the NFR-050 budget → open a PR that violates a Modulith boundary and watch CI block it at G1, push the fix, watch it pass → browse `/docs/adr/ADR-001..020`.

This slice is real (real Compose stack, real CI gates, real ADRs) even though no user-facing UI feature exists yet; the UI slice arrives once auth lands ([./Sprint03.md](./Sprint03.md)). Sprint hands off to [./Sprint01.md](./Sprint01.md), which consumes the `eip-core` skeleton and the booted stack to build the persistence + tenancy spine.

## Related documents

- [./MasterProgram.md](./MasterProgram.md) — program constitution and 15-doc map
- [./Phase0.md](./Phase0.md) — Phase-0 view across SPRINT-00..03
- [./SprintCatalog.md](./SprintCatalog.md) — sprint index (Objective/Modules/Gates/context — must agree with this file)
- [./ContextManifest.md](./ContextManifest.md) — exact per-sprint reading lists (owns the Required/Forbidden sets)
- [./ModuleBuildOrder.md](./ModuleBuildOrder.md), [./DependencyMatrix.md](./DependencyMatrix.md) — where infra + eip-core sit in the DAG
- [./ParallelizationPlan.md](./ParallelizationPlan.md), [./RiskDrivenImplementation.md](./RiskDrivenImplementation.md) — lanes and risk ordering
- [./IncrementStrategy.md](./IncrementStrategy.md), [./DevelopmentSequence.md](./DevelopmentSequence.md), [./ImplementationRoadmap.md](./ImplementationRoadmap.md)
- [./Sprint01.md](./Sprint01.md) — next sprint (persistence & tenancy spine)
- [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5 — source of record for Phase-0 stories, PRs, exit, demo
- [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md), [../engineering-operating-system/DevelopmentLifecycle.md](../engineering-operating-system/DevelopmentLifecycle.md), [../engineering-operating-system/ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md)
- [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md), [../reviews/architecture-readiness/ArchitectureDecisionUpdates.md](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) — ADR backfill source
