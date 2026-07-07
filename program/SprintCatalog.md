# Sprint Catalog — the per-sprint index

**Purpose.** The one-page-per-sprint index for the whole program: for every sprint, the eleven required fields (Objective · Modules · Required Documents · Forbidden Documents · Inputs · Outputs · Deliverables · Exit Criteria · Review Gates · Estimated Context Size) plus Stories, Lanes, Risks-addressed, and Demo increment. SPRINT-00…03 (Phase 0) are catalogued in full; SPRINT-04…07 (Phase 1) in outline; Phases 2–5 are catalogued at their phase kickoff. This is the index — the deep, authoritative versions are the individual [./Sprint00.md](./Sprint00.md)…[./Sprint03.md](./Sprint03.md) files, which MUST agree with this catalog on objective, modules, and gates. Where a field cites a fact (exit criteria, NFR, gate), it links the source of record and never restates it.

## 1. All sprints at a glance

| Sprint | Phase → Version | Objective | Modules | Headline gates | Est. context |
|---|---|---|---|---|---|
| **SPRINT-00** | 0 → v0.1 | Repo & platform bootstrap | infra, eip-core (skeleton) | G0,G1,G2,G4,G7,G8 | ~35–45k |
| **SPRINT-01** | 0 → v0.1 | Persistence & tenancy spine | eip-core, eip-tenancy, eip-app, infra | G1,G2,G3,G4,G6,G7,G8 | ~50–60k |
| **SPRINT-02** | 0 → v0.1 | AuthN/Z, audit, secrets | eip-tenancy, eip-app | all G + G3 full + R-SA sign-off | ~55–65k |
| **SPRINT-03** | 0 → v0.1 | Console shell & Phase-0 close | frontend, eip-app | all G + RG1,RG2,RG4 (RG3 dry-run) | ~50–60k |
| SPRINT-04 | 1 → v0.2 | Connector SPI + sync-engine spine | eip-connectors, eip-ingestion | G1–G4,G6–G8 | catalogued at Phase-1 kickoff |
| SPRINT-05 | 1 → v0.2 | Kafka pipeline + normalizer + model v1 | eip-ingestion, eip-core, eip-workers | G1–G4,G6–G8 | catalogued at Phase-1 kickoff |
| SPRINT-06 | 1 → v0.2 | Jira + GitHub + simulation connectors + kit | eip-connectors, /simulation | G1–G3,G7,G8 (+contract kit) | catalogued at Phase-1 kickoff |
| SPRINT-07 | 1 → v0.2 | Connector admin UI + data browser + Phase-1 close | frontend, eip-app | all G + RG1,RG4 | catalogued at Phase-1 kickoff |

Phase 0 = SPRINT-00…03 exactly, per the story mapping in [./Phase0.md](./Phase0.md) and [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5. Sequence/dependencies: [./DevelopmentSequence.md](./DevelopmentSequence.md); build order: [./ModuleBuildOrder.md](./ModuleBuildOrder.md); lanes: [./ParallelizationPlan.md](./ParallelizationPlan.md); risks: [./RiskDrivenImplementation.md](./RiskDrivenImplementation.md).

## 2. SPRINT-00 — Repo & platform bootstrap

| Field | Value |
|---|---|
| **Objective** | Stand up the monorepo, CI, and Compose dev stack so every later sprint has ground to run on; land the `eip-core` shared-kernel skeleton and the ADR-001..020 backfill. |
| **Modules** | `infra` (Compose stack, CI), `eip-core` (skeleton only — no business logic) |
| **Stories** | P0-E1-S1 (scaffolding), P0-E1-S2 (CI), P0-E1-S3 (Compose stack), P0-E1-S4 (dev docs), P0-E2-S1 (eip-core skeleton) + tasks: ADR-001..020 backfill to `/docs/adr`, CODEOWNERS, docs-lint CI job |
| **Required Documents** | [ContextManifest §4](./ContextManifest.md#4-sprint-00--repo--platform-bootstrap) — always-read set + PhaseImpl §5, EOS RepositoryStructure/RepositoryRules/BranchingStrategy/CodingStandards/ADRProcess, LocalDevelopment, DockerCompose §1–§4/§10/§13, ArchitectureOverview §5/§7, DomainModel §1–§2 |
| **Forbidden Documents** | All [../docs/ai/](../docs/ai/); ConnectorFramework; EventModel; DatabasePlan DDL; FrontendPlan; SecurityModel deep; KubernetesOpenShift / DeploymentModel K8s; PhaseImpl §7–§10 (see [ContextManifest §4](./ContextManifest.md#4-sprint-00--repo--platform-bootstrap)) |
| **Inputs** | Baselined spec (tag spec-v1.0) + EOS; [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §8 (Phase-0 start actions) + §3 condition #4 (backfill); a clean 16 GB host |
| **Outputs** | Running Compose stack; green CI on empty modules; `/docs/adr/ADR-001..020`; CODEOWNERS; docs-lint job; nine module skeletons with `MODULE.md` + Modulith verification harness |
| **Deliverables** | Compose dev stack ([../docs/infrastructure/DockerCompose.md](../docs/infrastructure/DockerCompose.md)); `make dev-up` ([../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md)); CI stages 1–7; `eip-core` kernel skeleton (WorkItem supertype, ExternalRef, event-envelope types, UUIDv7/Instant conventions); ADR backfill; onboarding README |
| **Exit Criteria** | CI green (build, unit, Modulith/ArchUnit) on empty modules — M0.1, [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md); Compose core+observability profile boots healthy ≤15 min on 16 GB (NFR-050, [DockerCompose §10](../docs/infrastructure/DockerCompose.md)); a Modulith-boundary-violating PR is blocked then passes; ADRs browsable under `/docs/adr` |
| **Review Gates** | G0 (DoR), G1 (build/static), G2 (tests), G4 (eip-core kernel/SPI surface is CC-1 anchor territory + ADR files), G7 (docs), G8 (review). No RG this sprint. Per [QualityGatePolicy §2–§4](../engineering-operating-system/QualityGatePolicy.md) |
| **Estimated Context Size** | ~35–45k tokens ([ContextManifest §3](./ContextManifest.md#3-token-budget-model)) |
| **Lanes** | Serial gate first (scaffolding P0-E1-S1 blocks all), then three parallel lanes: infra-CI (P0-E1-S2/S3) ∥ eip-core skeleton (P0-E2-S1) ∥ ADR-backfill + CODEOWNERS/docs-lint. Disjoint write-sets per [./ParallelizationPlan.md](./ParallelizationPlan.md) |
| **Risks addressed** | Over-engineering the platform (mitigation: everything must be consumed by a later story or cut, [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md)); Compose/K8s drift class-1 (same images from Phase 0, [ImplementationReadinessDecision §5](../reviews/architecture-readiness/ImplementationReadinessDecision.md)) |
| **Demo increment** | Clean clone → `make dev-up` boots the stack; CI green on empty modules; open a PR that violates a Modulith boundary → CI blocks it → fix → passes; browse `/docs/adr` |

## 3. SPRINT-01 — Persistence & tenancy spine

| Field | Value |
|---|---|
| **Objective** | Lay the security spine: DB baseline with the RLS template and partitioning conventions, tenant context propagation across web/Kafka/jobs with RLS enforcement, plus the OpenAPI and observability lanes — and resolve the blocking readiness design-notes that gate all later work. |
| **Modules** | `eip-core`, `eip-tenancy` (THE security spine), `eip-app`, `infra` |
| **Stories** | P0-E2-S2 (DB baseline + RLS template + partitioning), P0-E3-S1 (tenancy + context propagation + RLS enforcement), P0-E4-S2 (OpenAPI baseline — lane), P0-E4-S3 (observability wiring — lane) + design-notes: RLS+PgBouncer transaction-pooling spike, outbox-relay topology, consumer idempotency mechanics, audit hash-chain approach |
| **Required Documents** | [ContextManifest §5](./ContextManifest.md#5-sprint-01--persistence--tenancy-spine) — always-read set + DatabasePlan §1–§8, SecurityModel §4–§5, APIDesign, ObservabilityModel §1–§4, EventModel §9, DataFlow (invariants + §8), ImplementationReadinessDecision §6/§7, ArchitectureDecisionUpdates ADR-017/019 |
| **Forbidden Documents** | ConnectorFramework; all [../docs/ai/](../docs/ai/); analytics/reports; FrontendPlan; KubernetesOpenShift; PhaseImpl §7–§10 |
| **Inputs** | SPRINT-00 outputs (Compose stack, `eip-core` skeleton, CI); the readiness §6 blocking OQs; ADR-004 (RLS), ADR-016/018 (isolation), ADR-017 (outbox) |
| **Outputs** | Flyway V1 baseline + RLS policy template; tenant context filter (web/Kafka/jobs); NFR-041 isolation test harness; published OpenAPI `/api/v1`; OTel HTTP→DB traces in Grafana/Tempo; four decision notes committed |
| **Deliverables** | DB baseline (Flyway, tenant-scoped schema, RLS template, partitioning); tenancy + RLS enforcement; OpenAPI baseline (RFC 7807, cursor pagination, idempotency-key filter, committed spec + CI diff); observability wiring (OTel SDK, Micrometer, JSON logs, traceparent, Grafana starter dashboards) |
| **Exit Criteria** | Two seeded tenants; cross-tenant read provably blocked (RLS test + API probe) — M0.3, [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md); OTel traces HTTP→DB visible; OpenAPI published for `/api/v1` skeleton with RFC 7807 ([PRD §10 Phase 0](../docs/product/PRD.md)); NFR-041 isolation suite runs in CI |
| **Review Gates** | G1, G2, G3 (isolation suites — RLS probes), G4 (RLS = CC-1 anchor + CC-4 migration, R-DBA), G6 (observability — new endpoints/consumers), G7, G8. Per [QualityGatePolicy §2–§3](../engineering-operating-system/QualityGatePolicy.md) |
| **Estimated Context Size** | ~50–60k tokens ([ContextManifest §3](./ContextManifest.md#3-token-budget-model)) |
| **Lanes** | Serial spine (eip-core/DB → tenancy/RLS, single write-set) ∥ OpenAPI lane (P0-E4-S2) ∥ observability lane (P0-E4-S3). Design-note spikes run ahead of impl. [./ParallelizationPlan.md](./ParallelizationPlan.md) |
| **Risks addressed** | (1) RLS + connection-pooling correctness — spike wk 1, foundational to every tenant query ([ImplementationReadinessDecision §6 OQ#10](../reviews/architecture-readiness/ImplementationReadinessDecision.md), §5 single-writer ceiling); (2) tenant-isolation proof harness — NFR-041, release-blocking; (4) outbox-relay topology (ADR-017/AD-3); (5) consumer idempotency mechanics (EDA-04); note-ahead for (3) audit hash-chain (SEC-02) |
| **Demo increment** | Via `/api/v1`: a write/read as tenant A, then the same read as tenant B provably blocked by RLS; the request's OTel trace shown HTTP→DB in Grafana/Tempo; the published OpenAPI browsable |

## 4. SPRINT-02 — AuthN/Z, audit, secrets

| Field | Value |
|---|---|
| **Objective** | Complete the security platform: RBAC catalog with route/method guards and a permission-matrix generator, OIDC via Keycloak with token→tenant/role mapping and break-glass, the audit subsystem (implementing the Sprint-01 hash-chain decision, SEC-02 DDL), and the secret vault with KMS SPI and rotation. |
| **Modules** | `eip-tenancy`, `eip-app` |
| **Stories** | P0-E3-S2 (RBAC catalog + guards + matrix generator), P0-E3-S3 (OIDC Keycloak + token→tenant/role + break-glass), P0-E3-S4 (audit subsystem + SEC-02 audit DDL), P0-E4-S1 (secret vault AES-256-GCM + KMS SPI + rotation/re-encrypt + masking) |
| **Required Documents** | [ContextManifest §6](./ContextManifest.md#6-sprint-02--authnz-audit-secrets) — always-read set + SecurityModel (full), SecurityChecklist, DomainModel §2.3/§4, Personas §1, ImplementationReadinessDecision §6 OQ#4/§7, ArchitectureDecisionUpdates ADR-018 |
| **Forbidden Documents** | ConnectorFramework; all [../docs/ai/](../docs/ai/); analytics/reports; dashboards (FrontendPlan §4); KubernetesOpenShift; PhaseImpl §7–§10 |
| **Inputs** | SPRINT-01 outputs (tenancy, RLS, DB baseline, the audit hash-chain decision note); ADR-008 (Keycloak/OIDC), ADR-014 (secrets envelope + KMS SPI); the SEC-02 audit DDL decision |
| **Outputs** | RBAC role/permission catalog + guards + permission-matrix generator over all endpoints; Keycloak realm + token mapping + break-glass fallback; append-only, tamper-evident audit store + API + viewer stub; secret vault with rotation/re-encrypt-all + masked rendering |
| **Deliverables** | RBAC (per [Personas §1](../docs/product/Personas.md)); OIDC integration; audit subsystem (fail-closed for audited actions); secret vault (KMS SPI env/file/Vault) — all four stories CC-2 |
| **Exit Criteria** | Login via Keycloak yields a tenant-scoped token — M0.2, [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md); RBAC matrix test generator runs over all existing endpoints; secret create/rotate flows audited and masked end-to-end — M0.4; audit trail complete for every mutation ([PRD §10 Phase 0](../docs/product/PRD.md), FR-120–FR-123/FR-128) |
| **Review Gates** | All G + G3 **full** SecurityChecklist manual portion with **R-SA sign-off** on every CC-2 story; G4 for CC-4 audit/secret migrations (R-DBA). Per [QualityGatePolicy §2–§4](../engineering-operating-system/QualityGatePolicy.md) |
| **Estimated Context Size** | ~55–65k tokens ([ContextManifest §3](./ContextManifest.md#3-token-budget-model)) |
| **Lanes** | RBAC (P0-E3-S2) ∥ OIDC (P0-E3-S3) in parallel; then audit (P0-E3-S4) → secrets (P0-E4-S1) serial (secrets consumes the audit funnel). [./ParallelizationPlan.md](./ParallelizationPlan.md) |
| **Risks addressed** | (3) audit hash-chain sync-vs-async — SEC-02, finalized in P0-E3-S4 ([ImplementationReadinessDecision §6](../reviews/architecture-readiness/ImplementationReadinessDecision.md)); (6) secret KMS SPI + rotation (SEC-01, ADR-014); (7) OIDC/Keycloak integration + revocation-propagation bound (OQ#4) |
| **Demo increment** | Log in via Keycloak with a tenant-scoped token → store a secret → rotate it → show the masked value, the audit-trail entries for both actions, and the request trace |

## 5. SPRINT-03 — Console shell & Phase-0 close (v0.1)

| Field | Value |
|---|---|
| **Objective** | Put a real UI on the platform (OIDC login, tenant switcher, RBAC routing, i18n, admin console skeleton), verify the Phase-0 exit criteria, and run the RG1–RG4 dry-run for the v0.1 internal milestone. |
| **Modules** | `frontend`, `eip-app` |
| **Stories** | P0-E5-S1 (frontend shell: OIDC login, tenant switcher, RBAC routing, i18n, TanStack Query), P0-E5-S2 (admin console skeleton: tenants, users/roles, audit viewer) + Phase-0 exit verification + RG1–RG4 dry-run |
| **Required Documents** | [ContextManifest §7](./ContextManifest.md#7-sprint-03--console-shell--phase-0-close-v01) — always-read set + FrontendPlan §1–§3/§8/§11, UserJourneys J-01, AcceptanceCriteria §2, PRD §10, OperationsGuide §6, PhaseImpl §5.3 |
| **Forbidden Documents** | ConnectorFramework; all [../docs/ai/](../docs/ai/); analytics dashboards (FrontendPlan §4); KubernetesOpenShift / K8s deployment; PhaseImpl §6–§10 |
| **Inputs** | SPRINT-00…02 outputs (stack, tenancy/RLS, RBAC, OIDC, audit, secrets, OpenAPI, observability); [../docs/product/PRD.md](../docs/product/PRD.md) §10 checklist; the generated OpenAPI client |
| **Outputs** | React shell (login, tenant switcher, RBAC-guarded routing, i18n, TanStack Query); admin console (tenants list/create, users/roles, audit viewer); Phase-0 exit-criteria evidence; RG1–RG4 dry-run record; v0.1 tag candidate |
| **Deliverables** | Frontend shell ([FrontendPlan §2](../docs/engineering/FrontendPlan.md)); admin console skeleton; the live end-to-end Phase-0 demo; RG dry-run for v0.1 (internal milestone, not customer GA — GA is Phase 5) |
| **Exit Criteria** | Full Phase-0 checklist green — [PRD §10 Phase 0](../docs/product/PRD.md) + [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md) + [Roadmap §4.4](../docs/product/Roadmap.md); cross-tenant sweep covers 100% of shipped endpoints with zero leaks; the demo runs live from `main` on a clean Compose stack |
| **Review Gates** | All G + **RG1** (phase exit criteria, [QualityGatePolicy §6](../engineering-operating-system/QualityGatePolicy.md)), **RG2** (R-SA anti-surveillance NFR-071 + security certification), **RG4** (operability — Phase-0 backup/restore drill, [OperationsGuide §6](../docs/operations/OperationsGuide.md)); RG3 (perf) run as a dry-run baseline |
| **Estimated Context Size** | ~50–60k tokens, frontend-weighted ([ContextManifest §3](./ContextManifest.md#3-token-budget-model)) |
| **Lanes** | Frontend serial (shell P0-E5-S1 → admin console P0-E5-S2, single `/frontend` write-set) ∥ backend admin endpoints in `eip-app`. [./ParallelizationPlan.md](./ParallelizationPlan.md) |
| **Risks addressed** | Phase-0 exit not demonstrable through the UI (mitigation: vertical-slice principle, [PhaseImpl §4](../docs/implementation/PhaseBasedImplementationPlan.md)); isolation gap discovered late (mitigation: cross-tenant sweep grows to 100% of endpoints, [Roadmap §2](../docs/product/Roadmap.md)) |
| **Demo increment** | The full Phase-0 demo script end-to-end through the UI: log in as Deniz → create tenant + invite TENANT_ADMIN → store & rotate a secret → masked value, audit entries, request trace — all from one `docker compose up` ([PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md), [Roadmap §4.5](../docs/product/Roadmap.md)) |

## 6. SPRINT-04…07 — Phase 1 outline (v0.2)

Coarse per the spine; full catalog entries (all fields) authored at Phase-1 kickoff. Modules/stories per [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §6; exit per [PRD §10 Phase 1](../docs/product/PRD.md); reading per [ContextManifest §8](./ContextManifest.md#8-sprint-0407-phase-1--coarse-entry).

| Sprint | Objective | Modules | Stories | Key gates | Demo increment |
|---|---|---|---|---|---|
| SPRINT-04 | Connector SPI + sync-engine spine | eip-connectors, eip-ingestion | P1-E1-S1, P1-E1-S2 | G1–G4,G6–G8 | SPI validate/testConnection/healthCheck against a stub; sync engine schedules + checkpoints |
| SPRINT-05 | Kafka pipeline + normalizer + model v1 | eip-ingestion, eip-core, eip-workers | P1-E1-S3, P1-E2-S1, P1-E2-S2 | G1–G4,G6–G8 | raw→domain event flows through Kafka; normalized WorkItem persisted; `eip-workers` deployed |
| SPRINT-06 | Jira + GitHub + simulation connectors + kit | eip-connectors, /simulation | P1-E3-S1, P1-E3-S2, P1-E3-S3, P1-E1-S4 | G1–G3,G7,G8 + contract kit K1–K7 | three connectors pass kit K1–K7; `demo-small` simulation pack loads |
| SPRINT-07 | Connector admin UI + data browser + Phase-1 close | frontend, eip-app | P1-E4-S1, P1-E4-S2 | all G + RG1,RG4 | onboarding wizard (configure→validate→test→enable); data browser; kill-worker-mid-sync resumes from checkpoint |

## 7. Phases 2–5

Not catalogued here. Each phase's sprints are catalogued at that phase's kickoff (spine rule), reading from [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §7–§10, [../docs/product/Roadmap.md](../docs/product/Roadmap.md), and [../docs/product/PRD.md](../docs/product/PRD.md) §10. Phase→version: 2→v0.3, 3→v0.4, 4→v0.5, 5→v1.0 ([Roadmap §1](../docs/product/Roadmap.md)).

## Related documents

- [./ContextManifest.md](./ContextManifest.md) — the Required/Forbidden document detail behind every "Required Documents" / "Forbidden Documents" cell
- [./Phase0.md](./Phase0.md) — the Phase-0 charter wrapping SPRINT-00…03
- [./Sprint00.md](./Sprint00.md) · [./Sprint01.md](./Sprint01.md) · [./Sprint02.md](./Sprint02.md) · [./Sprint03.md](./Sprint03.md) — the deep, authoritative sprint files this index must agree with
- [./MasterProgram.md](./MasterProgram.md) · [./ImplementationRoadmap.md](./ImplementationRoadmap.md) · [./DevelopmentSequence.md](./DevelopmentSequence.md) — program-level sequencing
- [./ModuleBuildOrder.md](./ModuleBuildOrder.md) · [./DependencyMatrix.md](./DependencyMatrix.md) · [./ParallelizationPlan.md](./ParallelizationPlan.md) · [./RiskDrivenImplementation.md](./RiskDrivenImplementation.md) · [./IncrementStrategy.md](./IncrementStrategy.md)
- [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) · [../docs/product/PRD.md](../docs/product/PRD.md) §10 · [../docs/product/Roadmap.md](../docs/product/Roadmap.md) · [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md)
