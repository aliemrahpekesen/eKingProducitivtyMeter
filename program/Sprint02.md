# SPRINT-02 — AuthN/Z, audit, secrets

The third Phase-0 sprint: build the RBAC catalog with route/method guards, OIDC login via Keycloak with token→tenant/role mapping and break-glass, the append-only tamper-evident audit subsystem (implementing the SPRINT-01 hash-chain decision), and the envelope-encrypted secret vault with KMS SPI and rotation. Every story here is **security-relevant (CC-2)** and gated by the full SecurityChecklist plus R-SA sign-off. This file **sequences and packages** the sources of record; it links, never restates.

- **Phase / version:** Phase 0 (v0.1) — Foundations ([../docs/product/Roadmap.md](../docs/product/Roadmap.md) §4)
- **Position:** Phase 0 weeks 5–6 (per [./SprintCatalog.md](./SprintCatalog.md), [./DevelopmentSequence.md](./DevelopmentSequence.md))
- **Depends on:** [./Sprint01.md](./Sprint01.md) DONE (DB baseline + RLS, tenancy spine, OpenAPI, observability, the N4 audit hash-chain decision note)

## 1. Objective

Turn the tenant-scoped, RLS-proven path from SPRINT-01 into a **logged-in, authorized, audited, secret-holding** platform. Ship: the RBAC roles + fine-grained permission catalog (per [Personas §1](../docs/product/Personas.md)) with route/method guards and the permission-matrix test generator; OIDC integration (Keycloak realm, token→tenant/role mapping, local-account break-glass); the audit subsystem (append-only store, `@Audited` aspect, fail-closed policy, SEC-02 hash-chain DDL from N4); and the secret vault (AES-256-GCM envelope encryption, KMS SPI env/file/Vault, rotation + re-encrypt-all, masked rendering). Builds on **eip-tenancy** (the security spine) and **eip-app** (composition root) — no new modules ([./ModuleBuildOrder.md](./ModuleBuildOrder.md)).

## 2. Modules

| Module | State entering | Work this sprint | Owning role |
|---|---|---|---|
| **eip-tenancy** | tenant context + forced RLS (Sprint 01) | RBAC catalog + guards + matrix generator; audit store + `@Audited` aspect + fail-closed; secret vault + KMS SPI + rotation | R-BA (co-owner R-PA; **R-SA advises on RBAC/audit, CC-2**) |
| **eip-app** | `/api/v1` + OpenAPI + observability (Sprint 01) | OIDC filter chain, token→tenant/role mapping, break-glass path, secret + audit endpoints (masked) | R-BA (co-owner **R-SA security filter-chain paths, CC-2**) |

Ownership per [../engineering-operating-system/ModuleOwnership.md](../engineering-operating-system/ModuleOwnership.md) §1. Audit `details` carry **pseudonymous member references only**; PII lives in a separable mapping store (AD-9, [ArchitectureDecisionUpdates §2](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md)).

## 3. Stories

P-E-S IDs from [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5.1. Fixed by [./SprintCatalog.md](./SprintCatalog.md); no reassignment. **All four are CC-2.**

| Story | Title (PhaseImpl §5.1) | Module(s) | Size | Lane | First-PR seed (§5.2) |
|---|---|---|---|---|---|
| P0-E3-S2 | RBAC: roles + fine-grained permissions catalog (per Personas §1), method/route guards, permission test-matrix generator | eip-tenancy | L | L1 | extends PR-5 skeleton |
| P0-E3-S3 | OIDC integration: Keycloak realm, token→tenant/role mapping, local-account break-glass fallback | eip-tenancy, eip-app | M | L2 | P0-E3-S3 |
| P0-E3-S4 | Audit subsystem: append-only store, audit API + viewer stub, fail-closed policy for audited actions | eip-tenancy | M | L3 | PR-6 |
| P0-E4-S1 | Secret vault: AES-256-GCM envelope encryption, KMS SPI (env/file/Vault), rotation + re-encrypt-all, masked rendering | eip-tenancy | L | L3 | PR-7 |

Dependency edge: **P0-E3-S4 audit → P0-E4-S1 secrets** (secret create/rotate must be audited; audit exists before the vault consumes it) — enforced by lane ordering (§12). P0-E3-S4 implements the N4 hash-chain decision banked in SPRINT-01.

## 4. Required documents

Exact set owned by [./ContextManifest.md](./ContextManifest.md) (SPRINT-02 entry); this list is that entry verbatim and MUST stay in sync.

**Invariant prefix (every task):** [/CLAUDE.md](../CLAUDE.md) → `/work/tasks/TASK-NNNN.md` → `./Sprint02.md` → [../engineering-operating-system/DevelopmentLifecycle.md](../engineering-operating-system/DevelopmentLifecycle.md), [../engineering-operating-system/DefinitionOfReady.md](../engineering-operating-system/DefinitionOfReady.md), [../engineering-operating-system/DefinitionOfDone.md](../engineering-operating-system/DefinitionOfDone.md), [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) → in-scope `MODULE.md`(s): `eip-tenancy`, `eip-app`.

**Sprint-specific spec set (adds to SPRINT-01; CC-2 suffix applies to every task here — [ContextManagementStrategy §3](../engineering-operating-system/ContextManagementStrategy.md)):**

| Document (path + section) | Why | For story |
|---|---|---|
| [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md) (full) | authentication §3, RBAC §4, secret management §6, audit logging §11, security ACs §15 | all |
| [../engineering-operating-system/SecurityChecklist.md](../engineering-operating-system/SecurityChecklist.md) | the G3 CC-2 manual review — tenancy §3.1, authz §3.2, secrets §3.3, audit §3.4, crypto §3.7 | all (G3) |
| [../docs/architecture/DomainModel.md](../docs/architecture/DomainModel.md) §4 (RBAC/Member) | Organization & tenancy context, role/member identity model | P0-E3-S2/S3/S4 |
| [../docs/product/Personas.md](../docs/product/Personas.md) §1 | the RBAC role model personas reference | P0-E3-S2 |
| [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) §2 | authentication & authorization on the API surface | P0-E3-S3 |

The SPRINT-01 spec set (DatabasePlan, ObservabilityModel §1–§4) remains available on demand for the audit/secret DDL and the observability-of-security-events work; the audit-hash-chain N4 note (banked L1) is a required read for P0-E3-S4.

## 5. Forbidden documents

Still forbidden for SPRINT-02 ([ContextManagementStrategy §7](../engineering-operating-system/ContextManagementStrategy.md)):

- Connector catalogs/framework: [../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md)
- All AI/RAG/MCP: [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md), [../docs/ai/RAGArchitecture.md](../docs/ai/RAGArchitecture.md), [../docs/ai/MCPArchitecture.md](../docs/ai/MCPArchitecture.md) — note SecurityModel §8/§9 (AI/MCP security) is **out of scope** this sprint; read §3/§4/§6/§11 only
- Analytics/metric-engine, reports, and dashboard catalogs (Phase 2+)
- Kubernetes/OpenShift deployment: [../docs/infrastructure/KubernetesOpenShift.md](../docs/infrastructure/KubernetesOpenShift.md)

## 6. Inputs (DONE at sprint start)

- [./Sprint01.md](./Sprint01.md) outputs DONE: Flyway DB baseline + RLS template, `eip-tenancy` tenant-context propagation + forced RLS, NFR-041 isolation harness, committed OpenAPI baseline + CI diff, OTel/Micrometer observability HTTP→DB, and the four design notes — critically **N4 (audit hash-chain sync-vs-async + SEC-02 DDL columns)**.
- Keycloak already running in the Compose stack (Sprint 00); pre-built realm export target in `/infra` (mitigation per [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md)).
- Readiness OQ#4 (OIDC revocation-propagation bound — denylist vs ≤15-min token expiry) assigned to R-SA as a Phase-0 design note ([ImplementationReadinessDecision §6](../reviews/architecture-readiness/ImplementationReadinessDecision.md)); resolve during P0-E3-S3.
- SEC-01 (master-key **compromise** recovery, distinct from rotation) and SEC-10 (air-gap file-provider KMS at-rest strength ceiling) documented in the readiness review ([§5](../reviews/architecture-readiness/ImplementationReadinessDecision.md)) as inputs to the vault design.

## 7. Outputs (concrete artifacts)

| Artifact | Location | Story |
|---|---|---|
| RBAC role + permission catalog; method/route guards; permission-matrix test generator over all existing endpoints | `/backend/eip-tenancy`, eip-app guards | P0-E3-S2 |
| Keycloak realm export; OIDC filter chain; token→tenant/role mapper; local-account break-glass path | `/infra`, `/backend/eip-app` | P0-E3-S3 |
| Append-only audit store (SEC-02 hash-chain DDL); `@Audited` aspect; fail-closed policy; audit API + viewer stub | `/backend/eip-tenancy`, eip-app | P0-E3-S4 |
| Secret vault: AES-256-GCM envelope encryption; KMS SPI (env/file/Vault impls); rotation + re-encrypt-all; masked-rendering API | `/backend/eip-tenancy`, eip-app | P0-E4-S1 |
| OQ#4 OIDC-revocation design note; SEC-01 key-compromise recovery runbook stub | `/docs/adr` refinement / `/work` | P0-E3-S3, P0-E4-S1 |
| `MODULE.md` updates for `eip-tenancy`/`eip-app` owned tables, topics(none), endpoints | module charters | all |

## 8. Deliverables (reviewable increment)

The increment: **log in via Keycloak with a tenant-scoped token → store and rotate a secret → see the masked value, the audit trail, and the request trace** — milestones M0.2 ("login via Keycloak with tenant-scoped token") and M0.4 ("secret stored/rotated with audit trail") from [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md). Every audited action is fail-closed: an unaudited mutation cannot commit (principle 11, [ImplementationReadinessDecision §7](../reviews/architecture-readiness/ImplementationReadinessDecision.md)).

## 9. Exit criteria (testable)

Tied to story completion and Phase-0 exit lines in [../docs/product/PRD.md](../docs/product/PRD.md) §10 and [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md):

- [ ] RBAC matrix test generator runs over all existing endpoints; the fast authz-matrix subset is green in CI (P0-E3-S2; PRD §10 Phase-0 line 2). Deny-by-default: no endpoint without a declared permission (principle 7).
- [ ] Login via Keycloak yields a tenant-scoped, role-mapped token; break-glass local account works when the IdP is down (P0-E3-S3).
- [ ] Secret create/rotate flows are **audited and masked end-to-end**; rotation re-encrypts existing secrets ([PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md); PRD §10 Phase-0 line 2). Envelope encryption via the KMS SPI is the only storage path (principle 10).
- [ ] Audit trail is append-only + tamper-evident (SEC-02 hash chain), pseudonymous references only, fail-closed (P0-E3-S4; principle 11).
- [ ] **Full SecurityChecklist review + R-SA sign-off recorded on every CC-2 PR** (G3 manual portion, [SecurityChecklist §3](../engineering-operating-system/SecurityChecklist.md)).
- [ ] OQ#4 (OIDC revocation bound) resolved and documented.

## 10. Review gates

Per [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md). This is the **security-heavy** sprint: every story triggers the CC-2 manual security path.

| Gate | Applies because | Owner |
|---|---|---|
| G1 Build & Static | every PR | R-DOA |
| G2 Tests | every PR — unit + integration + **authz-matrix subset** + isolation suites | R-QAA |
| G3 Security | **every story is CC-2 → full [SecurityChecklist](../engineering-operating-system/SecurityChecklist.md) + R-SA A4 sign-off** (overridable only by the human repository owner, recorded) | **R-SA** |
| G4 Architecture | RBAC/audit/secret schema changes touch SecurityModel anchor (CC-1) and add migrations (CC-4) | R-CA / R-DBA |
| G6 Observability | new audit/secret endpoints; security-event metrics/traces/logs (no secrets/PII in logs, AC-093) | R-OE (A4) |
| G7 Documentation | every PR; security docs + OQ#4 note | R-DE |
| G8 Review & Done | every PR; CC-1 two approvals where anchors touched | R-CR |

The R-SA verdict is an A4 block (§G3, [QualityGatePolicy §2](../engineering-operating-system/QualityGatePolicy.md)). **Release gates: none** this sprint — the Phase-0 security certification and anti-surveillance review (RG2) run at close in [./Sprint03.md](./Sprint03.md).

## 11. Estimated context size

Sprint-union budget **~55–65k tokens**, consistent with [./ContextManifest.md](./ContextManifest.md) (SPRINT-02) and [./SprintCatalog.md](./SprintCatalog.md). The full SecurityModel + SecurityChecklist push this above SPRINT-01. Per-task packs stay within the [ContextManagementStrategy §2](../engineering-operating-system/ContextManagementStrategy.md) rule-4 ceiling.

| Document class | Approx. tokens |
|---|---|
| Invariant prefix (CLAUDE.md + this file + Lifecycle + DoR + DoD + QualityGatePolicy) | ~12–15k |
| Module charters (`eip-tenancy`, `eip-app` MODULE.md) | ~3–4k |
| Sprint-specific spec set (SecurityModel full, DomainModel §4, Personas §1, APIDesign §2) | ~28–34k |
| CC-2 mandatory suffix (SecurityChecklist full) | ~10–12k |
| **Sprint union total** | **~55–65k** |

Context-loss recovery: [ContextManagementStrategy §5](../engineering-operating-system/ContextManagementStrategy.md).

## 12. Lanes / parallelization

Per [./ParallelizationPlan.md](./ParallelizationPlan.md). **RBAC ∥ OIDC** run in parallel, then **audit → secrets** are serialized because the vault consumes the audit subsystem ([SprintExecutionGuide §2.3–§2.4](../engineering-operating-system/SprintExecutionGuide.md)).

| Lane | Write-set boundary | Executing role | Ordered work |
|---|---|---|---|
| L1 (parallel) | RBAC catalog + guards in `eip-tenancy` (+ eip-app guard wiring) | R-IE (backend) under R-BA/R-SA | P0-E3-S2 |
| L2 (parallel) | OIDC filter chain in `eip-app` + realm export in `/infra` | R-IE (backend) under R-BA/R-SA | P0-E3-S3 |
| L3 (serialized) | audit + secret tables/aspects in `eip-tenancy` | R-IE (backend) under R-SA | P0-E3-S4 → P0-E4-S1 |
| L4 (serialized migrations) | Flyway migrations (CC-4) for RBAC/audit/secret tables | R-IE (backend) under R-DBA | migrations feeding L1/L3 |

Write-set conflict declaration: L1 and L2 both touch `eip-app` filter-chain paths — declare explicit ordering ("RBAC guard wiring merges before OIDC filter registration") or split the `eip-app` edits into disjoint files; L3 is strictly ordered (audit before secrets); all migrations funnel through the serialized L4 CC-4 lane so nothing else touches migration paths this sprint ([SprintExecutionGuide §2.3](../engineering-operating-system/SprintExecutionGuide.md)).

## 13. Risks addressed

Per [./RiskDrivenImplementation.md](./RiskDrivenImplementation.md), continuing the risk order from [ImplementationReadinessDecision](../reviews/architecture-readiness/ImplementationReadinessDecision.md).

| Risk (readiness ref) | Sprint action | Owner | Exit evidence |
|---|---|---|---|
| **(6) Secret KMS SPI + rotation** (SEC-01 key-compromise; SEC-10 file-provider ceiling, [§5](../reviews/architecture-readiness/ImplementationReadinessDecision.md)) | Envelope encryption + KMS SPI (env/file/Vault) + rotation/re-encrypt-all; compromise-recovery runbook distinct from rotation | R-SA | rotate + re-encrypt + masked render demoed; recovery note banked |
| **(7) OIDC / Keycloak integration** (OQ#4, [§6](../reviews/architecture-readiness/ImplementationReadinessDecision.md); Roadmap §4.6) | Pre-built realm export; token→tenant/role mapper; local break-glass; revocation bound decided | R-SA | login + break-glass green; OQ#4 note |
| **(3) Audit hash-chain** (SEC-02) | Implement the N4 decision: append-only, tamper-evident chain, pseudonymous refs, fail-closed | R-SA | hash-chain verification test; unaudited mutation cannot commit |
| Authorization gaps / privilege escalation (principle 7, deny-by-default) | Permission-matrix generator over every endpoint; fast authz-matrix subset in G3 | R-SA + R-QAA | matrix generator green; zero undeclared-permission endpoints |
| PII in audit/telemetry (AD-9; principle 10; AC-093) | Pseudonymous audit `details`; no secrets/PII/prompt bodies in logs | R-SA + R-OE | G6 log-hygiene check green |

## 14. Demo increment

Vertical slice for the SPRINT-02 review ([IncrementStrategy](./IncrementStrategy.md)) — the auth/secret/audit core of the Phase-0 demo script ([PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md)):

> `make dev-up` → log in via Keycloak as an org admin, receiving a tenant-scoped, role-mapped token → attempt an action the role lacks: denied by an RBAC guard → store a secret, then rotate it → show the **masked** value in the API, the **audit entries** for create+rotate, and the **trace** of the request in Grafana/Tempo → pull the IdP and log in via the break-glass local account.

This closes the backend security foundation. [./Sprint03.md](./Sprint03.md) puts a React shell + admin console on top of exactly these endpoints and runs the full Phase-0 demo through the UI, then executes the RG1/RG2/RG4 close of Phase 0 (v0.1).

## Related documents

- [./MasterProgram.md](./MasterProgram.md), [./Phase0.md](./Phase0.md) — program constitution and Phase-0 view
- [./SprintCatalog.md](./SprintCatalog.md), [./ContextManifest.md](./ContextManifest.md) — must agree with this file on Objective/Modules/Gates/Required-Forbidden/context
- [./Sprint01.md](./Sprint01.md) (prior), [./Sprint03.md](./Sprint03.md) (next — console shell & Phase-0 close)
- [./ParallelizationPlan.md](./ParallelizationPlan.md), [./RiskDrivenImplementation.md](./RiskDrivenImplementation.md), [./DependencyMatrix.md](./DependencyMatrix.md), [./IncrementStrategy.md](./IncrementStrategy.md)
- [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5 — Phase-0 stories/PRs/exit/demo
- [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md), [../engineering-operating-system/SecurityChecklist.md](../engineering-operating-system/SecurityChecklist.md), [../docs/architecture/DomainModel.md](../docs/architecture/DomainModel.md) §4, [../docs/product/Personas.md](../docs/product/Personas.md) §1, [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) §2
- [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §5–§7 (SEC-01/02/10, OQ#4, principles 7/10/11), [../reviews/architecture-readiness/ArchitectureDecisionUpdates.md](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) (AD-9 pseudonymous audit)
- [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) §G3, [../engineering-operating-system/ModuleOwnership.md](../engineering-operating-system/ModuleOwnership.md), [../engineering-operating-system/ContextManagementStrategy.md](../engineering-operating-system/ContextManagementStrategy.md)
