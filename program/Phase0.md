# Phase 0 — Foundations (v0.1) charter

**Purpose.** The phase-level wrapper above the four Phase-0 sprint files. It states the Phase-0 objective, groups the 15 Phase-0 stories by epic, shows how they distribute across SPRINT-00…03, and points at the exit criteria, the RG1–RG4 v0.1 internal milestone, the demo script, and the entry conditions to Phase 1. It SEQUENCES and PACKAGES — every objective, exit criterion, and NFR is cited to its source of record, never restated.

## 1. Objective

Establish the monorepo, CI, and the core platform every later phase builds on — tenancy, RBAC, audit, secrets, OpenAPI-governed API, observability, a database baseline with RLS, and a one-command Docker Compose dev stack — such that a new engineer is productive in under a day and the quality gates are enforced from day one. Source of record: [../docs/product/Roadmap.md](../docs/product/Roadmap.md) §4.1 and [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5. Phase → version: **Phase 0 → v0.1** ([Roadmap §1](../docs/product/Roadmap.md)).

The build-order rationale — tenancy, security, and observability **before** the first external byte is ingested — is [PhaseImpl §2](../docs/implementation/PhaseBasedImplementationPlan.md): retrofitting isolation onto ingested data is the single most expensive mistake this class of product can make. No connector, analytics, or AI code ships in Phase 0 (strict scope, [Roadmap §4.6](../docs/product/Roadmap.md)).

## 2. The 15 stories, grouped by epic

Story table of record: [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5.1. Grouped here by epic for the phase view; sizes (S/M/L) and dependencies are in §5.1 and are not restated.

| Epic | Theme | Stories |
|---|---|---|
| **E1** | Scaffolding & infra | P0-E1-S1 (monorepo scaffolding), P0-E1-S2 (CI pipeline), P0-E1-S3 (Docker Compose dev stack), P0-E1-S4 (developer docs / `make dev-up`) |
| **E2** | Core kernel & DB | P0-E2-S1 (`eip-core` domain skeleton), P0-E2-S2 (DB baseline: Flyway, RLS template, partitioning) |
| **E3** | Tenancy / RBAC / OIDC / audit | P0-E3-S1 (tenancy + context propagation + RLS enforcement), P0-E3-S2 (RBAC catalog + guards + matrix generator), P0-E3-S3 (OIDC Keycloak + break-glass), P0-E3-S4 (audit subsystem) |
| **E4** | Secrets / OpenAPI / observability | P0-E4-S1 (secret vault + KMS SPI + rotation), P0-E4-S2 (OpenAPI baseline), P0-E4-S3 (observability wiring) |
| **E5** | Frontend | P0-E5-S1 (frontend shell: OIDC login, tenant switcher, RBAC routing, i18n), P0-E5-S2 (admin console skeleton) |

Total: 15 stories (E1 ×4, E2 ×2, E3 ×4, E4 ×3, E5 ×2). Phase-0 feature scope (FEAT-*) is [Roadmap §4.2](../docs/product/Roadmap.md).

## 3. Story → sprint distribution

Phase 0 = SPRINT-00…03 exactly. The mapping is fixed; stories are not reassigned. Details per sprint: [./SprintCatalog.md](./SprintCatalog.md) §2–§5; reading per [./ContextManifest.md](./ContextManifest.md) §4–§7.

| Sprint | Objective | Stories | Non-story tasks landed here |
|---|---|---|---|
| **SPRINT-00** Repo & platform bootstrap | ground to run on | P0-E1-S1, P0-E1-S2, P0-E1-S3, P0-E1-S4, P0-E2-S1 | ADR-001..020 backfill to `/docs/adr`; CODEOWNERS; docs-lint CI job (readiness condition #4) |
| **SPRINT-01** Persistence & tenancy spine | the security spine | P0-E2-S2, P0-E3-S1, P0-E4-S2, P0-E4-S3 | Blocking design-notes: RLS+PgBouncer pooling spike; outbox-relay topology; consumer idempotency mechanics; audit hash-chain approach |
| **SPRINT-02** AuthN/Z, audit, secrets | close the security platform | P0-E3-S2, P0-E3-S3, P0-E3-S4, P0-E4-S1 | Implements the Sprint-01 audit hash-chain decision (SEC-02 DDL) |
| **SPRINT-03** Console shell & Phase-0 close | put a UI on it and exit | P0-E5-S1, P0-E5-S2 | Phase-0 exit-criteria verification; RG1–RG4 v0.1 dry-run |

Story count per sprint: 5 + 4 + 4 + 2 = 15. The distribution follows the dependency DAG ([./ModuleBuildOrder.md](./ModuleBuildOrder.md), [./DependencyMatrix.md](./DependencyMatrix.md)) and the first-10-PRs sequence in [PhaseImpl §5.2](../docs/implementation/PhaseBasedImplementationPlan.md).

## 4. Phase-0 exit criteria

A phase is exited — and its version cut — only when every checklist item passes ([Roadmap §2](../docs/product/Roadmap.md) gate process). The Phase-0 checklists of record, cited not restated:

- [../docs/product/PRD.md](../docs/product/PRD.md) §10 **Phase 0** — scaffolding/CI green; tenancy+RBAC+audit+secrets operational with isolation tests passing; OpenAPI 3 for `/api/v1` skeleton + RFC 7807 + Flyway DB baseline; OTel→Prometheus/Grafana self-observability live; Compose dev stack meets NFR-050.
- [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5.3 — Compose core+observability boots healthy ≤15 min on a 16 GB host (NFR-050); CI enforces unit+integration+Modulith gates with coverage ratchet; two seeded tenants with cross-tenant read provably blocked; RBAC matrix generator over all endpoints; secret create/rotate audited and masked; OTel traces HTTP→DB visible.
- [../docs/product/Roadmap.md](../docs/product/Roadmap.md) §4.4 — the AC-* acceptance criteria (AC-001–011, AC-013, AC-016–018, AC-085–088, AC-091–095, AC-097) pass in CI; cross-tenant sweep covers 100% of shipped endpoints with zero leaks; Spring Modulith boundary verification enforced on every merge.

Which sprint carries each exit criterion is in [./SprintCatalog.md](./SprintCatalog.md): the Compose/CI/Modulith items close in SPRINT-00; RLS/isolation/OpenAPI/observability in SPRINT-01; RBAC/OIDC/audit/secrets in SPRINT-02; the full checklist verification and the demo in SPRINT-03.

## 5. The v0.1 internal milestone (RG1–RG4)

SPRINT-03 runs the release-gate protocol for v0.1 as an **internal milestone**, not a customer GA — GA is Phase 5 / v1.0 ([Roadmap §1](../docs/product/Roadmap.md)). R-RM collects all four verdicts per [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) §6:

| Gate | What it checks at v0.1 | Owner |
|---|---|---|
| **RG1** | Every Phase-0 checklist item of [PRD §10](../docs/product/PRD.md) demonstrably met | R-PO + R-TPM; R-RM records |
| **RG2** | Security certification subset green + the NFR-071 anti-surveillance review (no individual-ranking surface exists — FR-057); release-blocking | R-SA (A4) |
| **RG3** | Performance dry-run baseline established (full targets are later-phase; RG3 is a baseline, not a pass/fail, at v0.1) | R-PE (A4) |
| **RG4** | Operability: the Phase-0 backup/restore drill passes ([OperationsGuide §6](../docs/operations/OperationsGuide.md)); runbooks + docs current | R-DOA + R-OE; R-DE confirms docs |

A gate with unwaived failing items is FAIL and blocks the v0.1 tag ([QualityGatePolicy §6](../engineering-operating-system/QualityGatePolicy.md)).

## 6. Phase-0 demo script

Run live from `main` on a clean Compose stack at phase review ([Roadmap §2](../docs/product/Roadmap.md), [PhaseImpl §11](../docs/implementation/PhaseBasedImplementationPlan.md)). The script of record is [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5.3 (cross-referenced by [../docs/product/Roadmap.md](../docs/product/Roadmap.md) §4.5): clean clone → `make dev-up` → log in as Deniz via Keycloak → create a tenant, invite a `TENANT_ADMIN` → store a secret, rotate it → show the masked value, the audit entries, and the request trace in Grafana/Tempo → show CI blocking a Modulith-boundary-violating PR, then passing. Each SPRINT-00…03 also ships its own demo increment ([./SprintCatalog.md](./SprintCatalog.md) "Demo increment"), which compose into this end-to-end script — no big-bang integration ([./IncrementStrategy.md](./IncrementStrategy.md)).

## 7. Entry conditions to Phase 1

Phase 1 (Ingestion core, v0.2 — [PhaseImpl §6](../docs/implementation/PhaseBasedImplementationPlan.md)) may begin only when:

1. **Phase-0 exit criteria pass** (§4) and the v0.1 milestone gates (§5) are green — the demo runs live from `main`.
2. **The blocking design-notes are resolved** so Phase-1 eventing is unblocked: outbox-relay topology (ADR-017), consumer idempotency mechanics (EDA-04), audit hash-chain (SEC-02 — implemented in SPRINT-02), RLS+pooling (verified in SPRINT-01). Sources: [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §6, [../reviews/architecture-readiness/ArchitectureDecisionUpdates.md](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) §1.
3. **The platform contracts consumed by Phase 1 exist and are tested**: the secret vault + KMS SPI (P0-E4-S1 — connectors need credentials on day one), the audit store (compliance), the tenant context + RLS (every ingested row carries `tenantId`), the OpenAPI conventions, and the observability wiring (traceparent through the envelope). Build-order rationale: [PhaseImpl §2](../docs/implementation/PhaseBasedImplementationPlan.md).
4. **The readiness conditions binding Phase 0/1 are honored** (§8).

## 8. Readiness conditions binding Phase 0

Per [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §3 (verdict: READY WITH CONDITIONS):

- **Condition #4** — the Phase-0 backfill task creates `/docs/adr/ADR-001..020` and the CODEOWNERS/docs-lint automation (landed in SPRINT-00).
- **Condition #1** — the 112 RECOMMENDED findings land before Sprint 3; R-TPM converts them to tasks during SPRINT-01/02 planning; none blocks Phase-0/1 start.
- **Condition #2** — PRD OQ#8 (reference hardware) is resolved during Phase-1 design; code may start without it, but no benchmark may be published with it open.
- **Condition #5** — the 15 inviolable architecture principles ([ImplementationReadinessDecision §7](../reviews/architecture-readiness/ImplementationReadinessDecision.md)) bind every implementation task; violations are BLOCKER-class review findings regardless of code quality. The Phase-0-load-bearing ones: principle 3 (module boundaries structural), 5 (PostgreSQL sole system of record), 6 (tenant isolation ≥2 layers deep), 7 (deny by default / fail closed), 8 (no individual surveillance — RG2), 10 (secrets/PII protected at write time), 11 (audit completely or refuse to act).

Phase-0 design-note open questions assigned here: OIDC revocation-propagation bound (OQ#4, SPRINT-02) and the audit hash-chain finalization (SEC-02, SPRINT-01 note → SPRINT-02 impl) — [ImplementationReadinessDecision §6](../reviews/architecture-readiness/ImplementationReadinessDecision.md).

## Related documents

- [./SprintCatalog.md](./SprintCatalog.md) — the per-sprint index (SPRINT-00…03 in full)
- [./ContextManifest.md](./ContextManifest.md) — the per-sprint reading lists
- [./Sprint00.md](./Sprint00.md) · [./Sprint01.md](./Sprint01.md) · [./Sprint02.md](./Sprint02.md) · [./Sprint03.md](./Sprint03.md) — the deep sprint files
- [./MasterProgram.md](./MasterProgram.md) · [./ImplementationRoadmap.md](./ImplementationRoadmap.md) · [./DevelopmentSequence.md](./DevelopmentSequence.md) · [./ModuleBuildOrder.md](./ModuleBuildOrder.md) · [./DependencyMatrix.md](./DependencyMatrix.md) · [./ParallelizationPlan.md](./ParallelizationPlan.md) · [./RiskDrivenImplementation.md](./RiskDrivenImplementation.md) · [./IncrementStrategy.md](./IncrementStrategy.md)
- [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5 · [../docs/product/Roadmap.md](../docs/product/Roadmap.md) §4 · [../docs/product/PRD.md](../docs/product/PRD.md) §10 · [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) · [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) §6
