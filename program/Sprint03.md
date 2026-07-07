# SPRINT-03 — Console shell & Phase-0 close (v0.1)

The final Phase-0 sprint: put a React shell and admin console over the backend built in SPRINT-00..02, run the full Phase-0 demo end-to-end **through the UI**, then close Phase 0 by verifying every PRD §10 exit criterion and rehearsing the release-gate protocol (RG1/RG2/RG4 binding, full RG1–RG4 dry-run) for the internal **v0.1** milestone. This file **sequences and packages** the sources of record; it links, never restates.

- **Phase / version:** Phase 0 (v0.1) — Foundations ([../docs/product/Roadmap.md](../docs/product/Roadmap.md) §4); v0.1 is an **internal** milestone, **not** customer GA (GA is Phase 5 / v1.0, Roadmap §10)
- **Position:** Phase 0 weeks 7–8 (per [./SprintCatalog.md](./SprintCatalog.md), [./DevelopmentSequence.md](./DevelopmentSequence.md))
- **Depends on:** [./Sprint02.md](./Sprint02.md) DONE (RBAC, OIDC, audit, secrets)

## 1. Objective

Deliver the frontend shell (OIDC login, tenant switcher, RBAC-guarded routing, i18n scaffolding, TanStack Query) and the admin-console skeleton (tenants list/create, users/roles, audit viewer), so the whole Phase-0 platform is demonstrable through a real UI on the Compose stack — the vertical-slice principle, never a backend-only milestone ([PhaseImpl §4](../docs/implementation/PhaseBasedImplementationPlan.md)). Then perform the **Phase-0 close**: verify the PRD §10 Phase-0 exit checklist item-by-item and run the RG1/RG2/RG4 release gates for v0.1. Builds **frontend** (the React shell — top of the [./ModuleBuildOrder.md](./ModuleBuildOrder.md) DAG for Phase 0) on **eip-app** (admin endpoints).

## 2. Modules

| Module | State entering | Work this sprint | Owning role |
|---|---|---|---|
| **frontend** (`/frontend`) | Vite skeleton (Sprint 00) | React shell: OIDC login, tenant switcher, RBAC-guarded routing, layout, i18n scaffolding, TanStack Query; admin console screens | R-FA |
| **eip-app** | API + auth + audit + secrets (Sprint 00–02) | Backend admin endpoints for tenants/users/roles/audit-viewer (RBAC-scoped, cursor-paginated) | R-BA (co-owner R-SA) |

Ownership per [../engineering-operating-system/ModuleOwnership.md](../engineering-operating-system/ModuleOwnership.md) §1. Frontend performance budgets (shell ≤250 KB gzip, chunks ≤200 KB, virtualization >200 rows) are CI-enforced (principle 13, [ImplementationReadinessDecision §7](../reviews/architecture-readiness/ImplementationReadinessDecision.md); [FrontendPlan §11](../docs/engineering/FrontendPlan.md)).

## 3. Stories

P-E-S IDs from [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5.1. Fixed by [./SprintCatalog.md](./SprintCatalog.md); no reassignment.

| Story | Title (PhaseImpl §5.1) | Module(s) | Size | Lane |
|---|---|---|---|---|
| P0-E5-S1 | Frontend shell: login (OIDC), tenant switcher, RBAC-guarded routing, layout, i18n scaffolding, TanStack Query setup | frontend | M | L1 (serial — frontend spine) |
| P0-E5-S2 | Admin console skeleton: tenants list/create, users/roles, audit viewer | frontend, eip-app | M | L1 (frontend) ∥ L2 (backend endpoints) |

**Sprint-scoped closeout task (not a story):** Phase-0 exit-criteria verification against [../docs/product/PRD.md](../docs/product/PRD.md) §10 + the RG1–RG4 dry-run for v0.1 (§10 below). This is the phase-boundary review per [../engineering-operating-system/SprintReviewGuide.md](../engineering-operating-system/SprintReviewGuide.md) §6 and the R-RM release-gate protocol ([QualityGatePolicy §6](../engineering-operating-system/QualityGatePolicy.md)).

## 4. Required documents

Exact set owned by [./ContextManifest.md](./ContextManifest.md) (SPRINT-03 entry); this list is that entry verbatim and MUST stay in sync.

**Invariant prefix (every task):** [/CLAUDE.md](../CLAUDE.md) → `/work/tasks/TASK-NNNN.md` → `./Sprint03.md` → [../engineering-operating-system/DevelopmentLifecycle.md](../engineering-operating-system/DevelopmentLifecycle.md), [../engineering-operating-system/DefinitionOfReady.md](../engineering-operating-system/DefinitionOfReady.md), [../engineering-operating-system/DefinitionOfDone.md](../engineering-operating-system/DefinitionOfDone.md), [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) → in-scope `MODULE.md`(s): `/frontend`, `eip-app`.

**Sprint-specific spec set (frontend task-type suffix per [ContextManagementStrategy §3](../engineering-operating-system/ContextManagementStrategy.md)):**

| Document (path + section) | Why | For story/task |
|---|---|---|
| [../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) | stack, app shell, screen inventory, state rules, a11y/i18n baseline, perf budgets, frontend DoD | P0-E5-S1/S2 |
| [../docs/product/UserJourneys.md](../docs/product/UserJourneys.md) §1 (J-01) | the admin install/configure journey the shell serves | P0-E5-S1/S2 |
| [../docs/product/AcceptanceCriteria.md](../docs/product/AcceptanceCriteria.md) §2 | Platform & Tenancy ACs the console must satisfy | P0-E5-S2, closeout |
| [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) §4 | endpoint catalog the admin endpoints extend (consumed by the shell) | P0-E5-S2 |
| [../docs/operations/OperationsGuide.md](../docs/operations/OperationsGuide.md) §6 | backup/restore drill for RG4 | closeout |
| [../docs/product/PRD.md](../docs/product/PRD.md) §10 (Phase 0) | the phase exit checklist RG1 verifies | closeout |

## 5. Forbidden documents

Still forbidden for SPRINT-03 ([ContextManagementStrategy §7](../engineering-operating-system/ContextManagementStrategy.md)):

- Connector catalogs/framework: [../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md)
- All AI/RAG/MCP: [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md), [../docs/ai/RAGArchitecture.md](../docs/ai/RAGArchitecture.md), [../docs/ai/MCPArchitecture.md](../docs/ai/MCPArchitecture.md)
- Analytics/metric-engine, dashboard (data-viz) catalogs, and report internals (Phase 2+) — the console is admin-only this phase; **no dashboard/metric screens**
- Kubernetes/OpenShift deployment: [../docs/infrastructure/KubernetesOpenShift.md](../docs/infrastructure/KubernetesOpenShift.md), [../docs/architecture/DeploymentModel.md](../docs/architecture/DeploymentModel.md) K8s sections (the v0.1 close is on Compose; K8s GA is Phase 5)

## 6. Inputs (DONE at sprint start)

- [./Sprint02.md](./Sprint02.md) outputs DONE: RBAC catalog + guards + matrix generator, OIDC (Keycloak realm, token→tenant/role, break-glass), audit subsystem (SEC-02 hash chain, fail-closed), secret vault (envelope encryption, KMS SPI, rotation, masking) — all CC-2 with R-SA sign-off.
- SPRINT-00/01 foundations: Compose stack (`make dev-up`), CI gates, `eip-core`, DB baseline + RLS, tenancy spine + NFR-041 isolation harness, committed OpenAPI, OTel observability.
- The Phase-0 exit checklist ([PRD §10](../docs/product/PRD.md), [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md), [Roadmap §4.4](../docs/product/Roadmap.md)) and the RG protocol ([QualityGatePolicy §6](../engineering-operating-system/QualityGatePolicy.md)) as the closeout inputs.

## 7. Outputs (concrete artifacts)

| Artifact | Location | Story/task |
|---|---|---|
| React shell: OIDC login, tenant switcher, RBAC-guarded router, layout, i18n scaffolding, TanStack Query setup; perf-budget CI check | `/frontend` | P0-E5-S1 |
| Admin console screens: tenants list/create, users/roles management, audit viewer | `/frontend` | P0-E5-S2 |
| RBAC-scoped, cursor-paginated admin endpoints (tenants/users/roles/audit) with OpenAPI update | `/backend/eip-app`, `/docs/api/openapi.json` | P0-E5-S2 |
| Phase-0 exit-criteria verification record (per-item demonstration notes) | `/work` release record | closeout |
| RG1/RG2/RG4 verdicts + RG3 dry-run notes; backup/restore drill record; anti-surveillance contract-test run | release record | closeout |
| v0.1 tag candidate on `main` (phase-exit tag; `release/v0.1` cut lazily only if a patch is needed — [PhaseImpl §4](../docs/implementation/PhaseBasedImplementationPlan.md)) | `main` | closeout |

## 8. Deliverables (reviewable increment)

The increment: the **entire Phase-0 platform driven through the UI end-to-end**, then a passed Phase-0 gate. This is the phase-boundary review ([SprintReviewGuide §6](../engineering-operating-system/SprintReviewGuide.md)): the demo script from [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md) run live from `main` on a clean environment, plus RG sign-offs. Phase 0 exits and v0.1 is cut only when every check passes ([Roadmap §2](../docs/product/Roadmap.md)).

## 9. Exit criteria (testable)

Tied to story completion and the **complete** Phase-0 exit checklist in [../docs/product/PRD.md](../docs/product/PRD.md) §10, [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md), and [Roadmap §4.4](../docs/product/Roadmap.md):

- [ ] Frontend shell: OIDC login, tenant switcher, RBAC-guarded routing, i18n scaffolding, TanStack Query live (P0-E5-S1); frontend perf budgets CI-enforced ([FrontendPlan §11, §14](../docs/engineering/FrontendPlan.md)).
- [ ] Admin console: create tenants, invite/manage users + roles, browse the audit viewer — all RBAC-scoped (P0-E5-S2; AC set in [AcceptanceCriteria §2](../docs/product/AcceptanceCriteria.md)).
- [ ] **Cross-tenant sweep test covers 100% of shipped endpoints with zero leaks** (now including the admin endpoints) — [Roadmap §4.4](../docs/product/Roadmap.md); NFR-041.
- [ ] All Phase-0 exit lines re-verified as a set: Compose ≤15 min/16 GB (**NFR-050**); CI gates + coverage ratchet; two tenants + RLS block; RBAC matrix generator; secret create/rotate audited+masked; OTel HTTP→DB traces (PRD §10 Phase 0; [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md)).
- [ ] The full Phase-0 demo script runs live through the UI from `main` on a clean clone ([PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md); [Roadmap §4.5](../docs/product/Roadmap.md)).
- [ ] RG1, RG2, RG4 pass for v0.1; RG3 dry-run rehearsed with no open blocker; no open critical defect or unwaived critical security finding against Phase-0 scope ([Roadmap §2](../docs/product/Roadmap.md)).

## 10. Review gates

Per [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md). Merge gates as prior sprints, **plus the release-gate protocol** for the v0.1 close.

**Merge gates (per PR):** G1, G2, G3 (frontend CC-2 where auth/RBAC routing touched; automated otherwise), G4 (CC-1 if API anchor touched by admin endpoints; CC-4 if migrations), G6 (new admin endpoints), G7, G8 — full definitions in [QualityGatePolicy §2](../engineering-operating-system/QualityGatePolicy.md).

**Release gates (per phase, R-RM collects all four — [QualityGatePolicy §6](../engineering-operating-system/QualityGatePolicy.md)):**

| Gate | Verifies (Phase-0 scope) | Verdict owner | v0.1 status |
|---|---|---|---|
| **RG1** | Phase-0 exit criteria demonstrably met — every [PRD §10](../docs/product/PRD.md) Phase-0 checklist item | R-PO (scope) + R-TPM (status) | **binding** |
| **RG2** | Security certification checklist green **and** the NFR-071 anti-surveillance review (no individual-ranking surface, FR-057) — release-blocking | **R-SA (A4)** | **binding** |
| RG3 | Performance at phase scale | R-PE (A4) | **dry-run** — Phase 0 has no ingestion/analytics perf-scale target (NFR-003 first measured Phase 2); the only Phase-0 measurable is the NFR-050 boot budget, checked under RG1/RG4 |
| **RG4** | Operability: **backup/restore drill of the Phase-0 Compose stack** passes ([OperationsGuide §6](../docs/operations/OperationsGuide.md); AC-010, FR-141); runbooks + docs current | R-DOA + R-OE attest; R-DE docs | **binding** |

The full RG1–RG4 protocol is rehearsed as a **dry-run** so the machinery is proven before the higher-stakes Phase-1+ releases; RG1/RG2/RG4 are the load-bearing binding gates for the v0.1 internal milestone.

## 11. Estimated context size

Sprint-union budget **~50–60k tokens** (frontend-weighted), consistent with [./ContextManifest.md](./ContextManifest.md) (SPRINT-03) and [./SprintCatalog.md](./SprintCatalog.md). Per-task packs stay within the [ContextManagementStrategy §2](../engineering-operating-system/ContextManagementStrategy.md) rule-4 ceiling.

| Document class | Approx. tokens |
|---|---|
| Invariant prefix (CLAUDE.md + this file + Lifecycle + DoR + DoD + QualityGatePolicy) | ~12–15k |
| Module charters (`/frontend`, `eip-app` MODULE.md) | ~3–4k |
| Sprint-specific spec set (FrontendPlan, UserJourneys §1, AcceptanceCriteria §2, APIDesign §4) | ~22–28k |
| Closeout set (PRD §10, OperationsGuide §6, SprintReviewGuide §6, QualityGatePolicy §6) | ~10–13k |
| **Sprint union total** | **~50–60k** |

Context-loss recovery: [ContextManagementStrategy §5](../engineering-operating-system/ContextManagementStrategy.md).

## 12. Lanes / parallelization

Per [./ParallelizationPlan.md](./ParallelizationPlan.md). The **frontend shell is a serial lane** (the router/auth spine gates the admin screens); backend admin endpoints run as a **parallel lane** with a disjoint write-set ([SprintExecutionGuide §2.3](../engineering-operating-system/SprintExecutionGuide.md)).

| Lane | Write-set boundary | Executing role | Ordered work |
|---|---|---|---|
| L1 (serial — frontend spine) | `/frontend` | R-IE (frontend) under R-FA | P0-E5-S1 → P0-E5-S2 (screens) |
| L2 (parallel) | `eip-app` admin endpoints + `/docs/api/openapi.json` | R-IE (backend) under R-BA | P0-E5-S2 (backend endpoints) |
| L3 (closeout) | `/work` release record + docs currency | R-TPM + R-RM (gates); R-DE (docs) | exit verification → RG1/RG2/RG4 |

Write-set conflict declaration: L1 (frontend) and L2 (backend) are disjoint; the admin screens (L1) depend on the endpoints (L2) via a recorded contract stub so the frontend can build against the OpenAPI before endpoints merge ([SprintExecutionGuide §2.4](../engineering-operating-system/SprintExecutionGuide.md)). L3 runs after L1/L2 are VERIFIED.

## 13. Risks addressed

Per [./RiskDrivenImplementation.md](./RiskDrivenImplementation.md).

| Risk (readiness ref) | Sprint action | Owner | Exit evidence |
|---|---|---|---|
| Anti-surveillance drift — an individual-ranking surface leaking in through any change (principle 8; FR-057/NFR-071; release-blocking) | RG2 anti-surveillance contract-test run over the admin console; no individual-scoring view ships | R-SA | RG2 anti-surveillance run green |
| Isolation regression as the endpoint surface grows (principle 6; NFR-041) | Cross-tenant sweep extended to the new admin endpoints; failures are S1, no waiver | R-SA + R-QAA | 100%-endpoint sweep zero leaks |
| Reversibility asserted-not-drilled (principle 15; RG4) | Actually run the backup/restore drill on the Compose stack, timed | R-DOA + R-OE | RG4 drill record |
| Compose/K8s drift (principle 15; [PhaseImpl §13](../docs/implementation/PhaseBasedImplementationPlan.md)) | NFR-050 boot budget re-verified at exit on a clean 16 GB host | R-DOA | RG1/RG4 boot-time evidence |
| Frontend budget creep (principle 13; [FrontendPlan §11](../docs/engineering/FrontendPlan.md)) | Shell/chunk gzip budgets + virtualization enforced in CI | R-FA | perf-budget CI check green |

## 14. Demo increment

Vertical slice for the SPRINT-03 review — the **complete** Phase-0 demo script run **through the UI** ([PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md); [Roadmap §4.5](../docs/product/Roadmap.md); [SprintReviewGuide §6](../engineering-operating-system/SprintReviewGuide.md)):

> Clean clone → `make dev-up` → log in as Deniz via Keycloak → create a tenant, invite a `TENANT_ADMIN`, log in as a second org's admin (tenant switcher) → store a secret, rotate it → show the masked value, the audit-viewer entries, and the request trace in Grafana/Tempo → show CI on a PR failing a Modulith boundary violation, then passing → close with the RG1/RG2/RG4 sign-offs and the backup/restore drill for v0.1.

This is the Phase-0 close. Phase 1 (SPRINT-04..07, [SprintCatalog](./SprintCatalog.md)) begins from this v0.1 floor: Connector SPI + sync engine (P1-E1-*), consuming the tenancy/audit/secret foundations and the four SPRINT-01 design notes (outbox topology, idempotency, RLS+pooling) without re-litigating them.

## Related documents

- [./MasterProgram.md](./MasterProgram.md), [./Phase0.md](./Phase0.md) — program constitution and Phase-0 view
- [./SprintCatalog.md](./SprintCatalog.md), [./ContextManifest.md](./ContextManifest.md) — must agree with this file on Objective/Modules/Gates/Required-Forbidden/context
- [./Sprint02.md](./Sprint02.md) (prior) — the backend this shell drives
- [./ParallelizationPlan.md](./ParallelizationPlan.md), [./RiskDrivenImplementation.md](./RiskDrivenImplementation.md), [./IncrementStrategy.md](./IncrementStrategy.md), [./ImplementationRoadmap.md](./ImplementationRoadmap.md)
- [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5 — Phase-0 stories/exit/demo
- [../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md), [../docs/product/UserJourneys.md](../docs/product/UserJourneys.md) §1, [../docs/product/AcceptanceCriteria.md](../docs/product/AcceptanceCriteria.md) §2, [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) §4, [../docs/operations/OperationsGuide.md](../docs/operations/OperationsGuide.md) §6
- [../docs/product/PRD.md](../docs/product/PRD.md) §10, [../docs/product/Roadmap.md](../docs/product/Roadmap.md) §2/§4/§10 — phase exit + release train
- [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) §6 (RG1–RG4), [../engineering-operating-system/SprintReviewGuide.md](../engineering-operating-system/SprintReviewGuide.md) §6, [../engineering-operating-system/ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md)
- [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §7 (principles 6/8/13/15)
