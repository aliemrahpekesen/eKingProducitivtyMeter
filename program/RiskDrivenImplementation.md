# Risk-Driven Implementation

The risk-ordered plan for Sprints 00–03: the top architectural risks, ranked by **blast radius × uncertainty**, each mapped to the sprint that de-risks it, the mitigating spike or story, the owning role, and the exit evidence that closes it. It **sequences** the risks, conditions, and accepted-risk ceilings of [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) into Phase-0 work — it does not restate them.

## 0. Ordering principle

> **Highest-foreclosure-risk spikes go first.** A risk is sequenced early to the extent that getting it wrong forecloses or unblocks everything downstream — i.e. by **(blast radius × uncertainty)**, not by module order. Five of the seven Phase-0 risks are de-risked in **SPRINT-01** because Phase 0 is the foundation every later phase inherits ([../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §2).

Scope note: risks anchored to the reference-hardware benchmark (PRD OQ#8 → NFR-003/010/013) are **out of scope for Sprints 00–03** — they resolve in Phase-1 design per [ImplementationReadinessDecision §3](../reviews/architecture-readiness/ImplementationReadinessDecision.md) condition 2. The seven below are the Phase-0 foreclosure risks.

## 1. Risk ranking (blast radius × uncertainty)

| Rank | Risk | Blast radius | Uncertainty | Blast × Uncertainty | Readiness map |
|---|---|---|---|---|---|
| **R1** | RLS + connection pooling correctness (`SET LOCAL` tenant context under PgBouncer transaction pooling) | **High** — every tenant query on every path, all modules | **High** — a known sharp edge; transaction-pooling can leak session GUCs | **Highest** | OQ#10 (pooler placement); principle §7.6; [PhaseBasedImplementationPlan §5.3](../docs/implementation/PhaseBasedImplementationPlan.md) risk; NFR-041 |
| **R2** | Tenant-isolation proof harness (NFR-041) | **High** — release-blocking; every endpoint | **Medium** — mechanics known, **coverage completeness** is the risk | **High** | principle §7.6; §3 condition 3; accepted risk MTR-10; ADR-018 storage-prefix test |
| **R3** | Audit hash-chain: sync-vs-async chaining (SEC-02) | **High** — an unaudited mutation cannot commit (principle §7.11) | **Med-High** — sync-chain correctness vs write-path throughput | **High** | SEC-02 (readiness §6, finalized in P0-E3-S4); AD-9 (pseudonymous refs); principle §7.11 |
| **R4** | Outbox relay topology (ADR-017) | **High** — unblocks **all** Phase-1 domain/analytics/job eventing | **Medium** — both-runtime relay + raw-intake carve-out to settle | **High** | ADR-017 / AD-3 (readiness §6); FMA-03 buffer bounds; principle §7.4 |
| **R5** | Consumer idempotency mechanics (EDA-04) | **Med-High** — every consumer group | **Medium** — ledger-vs-structural choice per group; DLQ park-index | **Medium** | EDA-04 (readiness §6); AD-15 failure taxonomy; principle §7.4 |
| **R6** | Secret KMS SPI + rotation & compromise recovery (SEC-01) | **Med-High** — all stored credentials (connectors, LLM providers depend on it) | **Medium** — rotation/re-encrypt-all + master-key **compromise** ≠ rotation | **Medium** | SEC-01 (readiness §6); accepted risk SEC-10 (air-gap file KMS); principle §7.10 |
| **R7** | OIDC / Keycloak integration (OQ#4) | **Medium** — authN for all users/tenants | **Med-Low** — well-trodden; edge cases in token→tenant/role + revocation | **Medium** | OQ#4 (readiness §6, R-SA Phase-0 design note); principle §7.7 |

## 2. Mitigation, ownership, and exit evidence

Keyed by rank. Owning role per [../engineering-operating-system/ModuleOwnership.md](../engineering-operating-system/ModuleOwnership.md) + [../engineering-operating-system/AIAgentCatalog.md](../engineering-operating-system/AIAgentCatalog.md); gates per [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md).

| Rank | De-risked in | Mitigating spike / story | Owning role (support) | Exit evidence (what closes it) |
|---|---|---|---|---|
| **R1** | **SPRINT-01, spike week 1** | RLS+PgBouncer transaction-pooling **spike**, then P0-E2-S2 RLS policy template + a dedicated Testcontainers integration-test pattern | R-DBA (R-BA, R-PA) | Cross-tenant write/read **provably blocked** through `/api/v1` under the pooled-connection profile; the reusable RLS integration-test pattern committed; pooling-mode decision recorded — gated by G3 isolation suite + G4 (CC-4) |
| **R2** | **SPRINT-01** (grows every phase) | Cross-tenant **sweep-test generator** over all existing endpoints (Phase-0 exit criterion) | R-SA (R-QAA) | NFR-041 isolation suite green with **100% shipped-endpoint coverage, zero leaks**; isolation-test failures carry **no waiver** and are S1 fail-closed — gated by G3 + RG2 |
| **R3** | **SPRINT-01 approach note → SPRINT-02 impl** | Audit hash-chain **decision note** (L1-N) feeding P0-E3-S4 audit subsystem (SEC-02 DDL) | R-SA (R-BA) | Append-only, tamper-evident `audit.audit_event` with hash-chain columns; a **fail-closed audited mutation** demoed; `detail` carries pseudonymous member refs only (AD-9) — gated by G3 (CC-2) + R-SA sign-off |
| **R4** | **SPRINT-01 decision note** (impl Phase 1) | Outbox relay **topology note** (L1-N): both-runtime relay, raw-intake carve-out, buffer bounds | R-PA (R-BA) | Decision note locking: outbox **mandatory** for domain/analytics/job events; `eip-app` + `eip-workers` each relay **their own** writes; raw intake produces directly with `raw_*` durability + `webhook_intake_buffer` bound (ADR-017) — no Phase-1 eventing starts until locked |
| **R5** | **SPRINT-01 decision note** | Consumer-idempotency **mechanics note** (L1-N) | R-BA (R-DA) | `core.processed_events` ledger design: **ack-after-write in the same transaction**; per-group ledger-vs-structural choice; ledger retention > topic retention + replay window; failure taxonomy adopted (AD-15) — feeds Phase-1 normalizer/consumer stories |
| **R6** | **SPRINT-02** | P0-E4-S1 secret vault: AES-256-GCM envelope, KMS SPI (env/file/Vault), rotation + re-encrypt-all, masked rendering | R-SA (R-BA) | Store→rotate→masked-value with audit trail demoed; **master-key compromise recovery distinct from rotation** (SEC-01); air-gap file-provider at-rest strength documented (SEC-10) — gated by G3 (CC-2) + R-SA sign-off |
| **R7** | **SPRINT-02** | P0-E3-S3 OIDC: Keycloak realm, token→tenant/role mapping, local-account break-glass | R-SA (R-BA) | Login via Keycloak with a tenant-scoped token; break-glass path verified; **OIDC revocation-propagation bound documented** (denylist vs ≤15-min token expiry, OQ#4) — gated by G3 (CC-2) |

## 3. How the sequence composes

- **SPRINT-01 carries the foreclosure load.** R1 (spike), R2 (harness), and the R3/R4/R5 decision notes all land here — the tenant-isolation and eventing decisions that every later module inherits are settled before the data plane exists. The notes are docs write-sets in lane **L1-N** ([./ParallelizationPlan.md](./ParallelizationPlan.md) §2), disjoint from the code lanes.
- **SPRINT-02 implements the security-heavy risks** (R3 audit, R6 secrets, R7 OIDC) whose **shape** was decided in SPRINT-01. Every story is CC-2 → full G3 SecurityChecklist + R-SA sign-off; audit (R3) lands before secrets (R6) consume it (the SPRINT-02 barrier).
- **SPRINT-03 verifies, not introduces.** R1/R2 are re-exercised by the Phase-0 cross-tenant sweep and the RG2 anti-surveillance review; R6/R7 by the end-to-end demo (login → store → rotate → masked + audit + trace). Security features are Phase-0 foundations — later phases verify them ([../docs/product/Roadmap.md](../docs/product/Roadmap.md) §9.6).
- **Accepted-risk ceilings stay live.** Each mitigation respects its documented ceiling in [ImplementationReadinessDecision §5](../reviews/architecture-readiness/ImplementationReadinessDecision.md) (e.g. single-writer PostgreSQL, storage prefix-scoping ADR-018); crossing a ceiling's revisit trigger without action is a G4/RG failure (condition 3).

## Related documents

- [./MasterProgram.md](./MasterProgram.md) — "sequence by risk" is one of the program's golden rules
- [./ParallelizationPlan.md](./ParallelizationPlan.md) — the SPRINT-01 spike/note lanes and the audit→secrets barrier
- [./DependencyMatrix.md](./DependencyMatrix.md) — the forbidden edges these risks protect (RLS, single writer, outbox)
- [./SprintCatalog.md](./SprintCatalog.md) · [./Sprint01.md](./Sprint01.md) · [./Sprint02.md](./Sprint02.md) — where each mitigation is scheduled and gated
- [../reviews/architecture-readiness/ImplementationReadinessDecision.md](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §3/§5/§6/§7 — conditions, accepted risks, open questions, inviolable principles (source of record)
- [../reviews/architecture-readiness/ArchitectureDecisionUpdates.md](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) — ADR-017 (outbox), ADR-018 (storage tenancy), AD-9/AD-15, SEC-01/SEC-02
- [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5.3 — Phase-0 risks & mitigations (source of record)
- [../engineering-operating-system/QualityGatePolicy.md](../engineering-operating-system/QualityGatePolicy.md) — G3/G4 and RG2 that gate each exit evidence
