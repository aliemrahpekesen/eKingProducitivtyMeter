# Development Sequence — the ordered execution narrative and decision logic

The readable throughline of *why the program builds in the order it does*, and *what must be DONE before what may start*, across Phase 0–1. The underlying directed graph lives in [ModuleBuildOrder](./ModuleBuildOrder.md) (module topology) and [DependencyMatrix](./DependencyMatrix.md) (story/task edges); the risk ordering lives in [RiskDrivenImplementation](./RiskDrivenImplementation.md). This document is the prose spine that ties them together and states the **sequence invariants** every session must respect. It packages the build-order rationale from [PhaseImpl §2](../docs/implementation/PhaseBasedImplementationPlan.md) and the inviolable principles from [ImplementationReadinessDecision §7](../reviews/architecture-readiness/ImplementationReadinessDecision.md); it re-derives nothing.

## 1. Why this order — two forces

Order is the product of two forces applied together (golden rule 3, [MasterProgram §3](./MasterProgram.md)):

1. **Dependency.** A module or story cannot be built before the thing it structurally requires. The topological order of the 11 modules is fixed by the spec module map and readiness ADRs; see [ModuleBuildOrder](./ModuleBuildOrder.md). Story-level edges (the `Depends on` columns of [PhaseImpl §5.1/§6.1](../docs/implementation/PhaseBasedImplementationPlan.md)) are enumerated in [DependencyMatrix](./DependencyMatrix.md).
2. **Architectural risk.** Among what dependency permits, the highest-risk work goes first — ordered by **blast radius × uncertainty**, because a foundational spike (RLS + connection pooling, tenant-isolation proof) either forecloses or unblocks everything downstream. This is why security, tenancy, and observability precede the first ingested byte ([PhaseImpl §2](../docs/implementation/PhaseBasedImplementationPlan.md): "retrofitting isolation onto ingested data is the single most expensive mistake this class of product can make"). The risk register and spike ordering are in [RiskDrivenImplementation](./RiskDrivenImplementation.md).

The macro-order that falls out, phase by phase, is the canonical build order ([PhaseImpl §2](../docs/implementation/PhaseBasedImplementationPlan.md), [Roadmap §3](../docs/product/Roadmap.md)): **platform & tenancy (P0) → ingestion & connectors (P1) → analytics (P2) → AI (P3) → full agents + MCP + outputs (P4) → hardening (P5).**

## 2. The Phase 0 throughline (`SPRINT-00`…`SPRINT-03`)

Each sprint is a demoable vertical slice ([IncrementStrategy](./IncrementStrategy.md)); the narrative below is *what becomes possible* as each lands.

| Order | Sprint | What gets built (stories) | Why it must come here |
|---|---|---|---|
| 1 | `SPRINT-00` | Monorepo scaffolding, CI gates, Compose stack, dev docs, `eip-core` skeleton (P0-E1-S1..S4, P0-E2-S1) | **Scaffolding gates everything.** No module, gate, or migration can exist before the repo layout, CI, and the shared kernel do. `eip-core` (canonical base types, event envelope, and ownership of the infra tables — outbox, dedup ledger, heartbeat, Quartz — per AD-12 / [DatabasePlan §2](../docs/engineering/DatabasePlan.md)) carries no business logic but every later module imports it. |
| 2 | `SPRINT-01` | DB baseline + RLS template + partitioning conventions, tenancy + context propagation, OpenAPI baseline, observability wiring (P0-E2-S2, P0-E3-S1, P0-E4-S2, P0-E4-S3) | **The security spine, risk-first.** The RLS + connection-pooling spike runs in week 1 (highest blast radius × uncertainty). Tenancy must exist before any tenant-scoped table; observability must be wired before there is traffic to trace. OpenAPI and observability run as parallel lanes off the serial DB/tenancy spine. |
| 3 | `SPRINT-02` | RBAC catalog + guards, OIDC + token→tenant/role, audit subsystem, secret vault + KMS SPI + rotation (P0-E3-S2, P0-E3-S3, P0-E3-S4, P0-E4-S1) | **AuthN/Z, audit, secrets on top of tenancy.** Every story here depends on `P0-E3-S1` (tenancy). Audit precedes secrets because secret actions must be audited (the write-order below); the audit hash-chain approach decided as a note in `SPRINT-01` is implemented here in `P0-E3-S4` (SEC-02). |
| 4 | `SPRINT-03` | Frontend shell (OIDC login, tenant switcher, RBAC routing), admin console skeleton, Phase-0 exit verification + RG dry-run (P0-E5-S1, P0-E5-S2) | **The UI slice that closes v0.1.** The shell depends on OIDC (`P0-E3-S3`); the admin console depends on the shell and on audit (`P0-E3-S4`) to render the audit viewer. This sprint turns the platform into the end-to-end demo of [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md). |

## 3. The Phase 1 throughline (`SPRINT-04`…`SPRINT-07`)

Phase 1 proves the data spine. Order follows the connector→ingestion→normalization→UI dependency chain ([PhaseImpl §6.1](../docs/implementation/PhaseBasedImplementationPlan.md)).

| Order | Sprint | What gets built | Why it must come here |
|---|---|---|---|
| 5 | `SPRINT-04` | Connector SPI, sync engine + checkpoints (P1-E1-S1/S2) | The SPI is the contract every connector binds; the sync engine (leases, backoff, rate limiting) is the substrate. Both depend on the secret vault (`P0-E4-S1`) for credentials. |
| 6 | `SPRINT-05` | Kafka raw→domain pipeline, normalizer framework, normalized model v1 (P1-E1-S3, P1-E2-S1/S2) | Eventing depends on observability wiring (`P0-E4-S3`) and the outbox topology decided in `SPRINT-01`. **Normalized model v1 gates all analytics** — no metric may compute before it exists. |
| 7 | `SPRINT-06` | Jira, GitHub, simulation connectors + contract kit K1–K7 (P1-E3-S1/S2/S3, P1-E1-S4) | Every connector implementation depends on the kit (`P1-E1-S4`), which depends on the SPI. Simulation ships in the same phase as the SPI — it is the test substrate for everything downstream ([PhaseImpl §2](../docs/implementation/PhaseBasedImplementationPlan.md)). |
| 8 | `SPRINT-07` | Connector admin UI, canonical data browser, Phase-1 close (P1-E4-S1/S2) | The admin UI depends on the sync engine and the Phase-0 admin console; the data browser depends on model v1. Closes `v0.2`. |

## 4. Gating relationships — what must be DONE before what starts

A dependency edge means the upstream task must be **DONE** (or explicitly stubbed, per [DevelopmentLifecycle §2](../engineering-operating-system/DevelopmentLifecycle.md)) before the downstream task passes `G0`. The critical edges across Phase 0–1 (full graph in [DependencyMatrix](./DependencyMatrix.md)):

| Downstream (blocked) | Requires DONE first | Reason |
|---|---|---|
| P0-E1-S2 CI, P0-E1-S3 Compose, P0-E2-S1 eip-core | P0-E1-S1 scaffolding | Nothing exists before the monorepo layout |
| P0-E2-S2 DB baseline + RLS template | P0-E2-S1 eip-core | Migrations need the shared kernel + infra-table conventions |
| P0-E3-S1 tenancy | P0-E2-S2 RLS template | Tenant context enforces against the RLS policy template |
| P0-E3-S2 RBAC · P0-E3-S3 OIDC · P0-E3-S4 audit | P0-E3-S1 tenancy | All authz/identity/audit hang off the tenant model |
| P0-E4-S1 secret vault | P0-E3-S4 audit | Secret create/rotate must be audited (write-order §5) |
| P0-E4-S3 observability | P0-E1-S3 Compose | OTel Collector/Grafana live in the stack |
| P0-E5-S1 frontend shell | P0-E3-S3 OIDC | Login is the shell's first screen |
| P0-E5-S2 admin console | P0-E5-S1 shell + P0-E3-S4 audit | Renders users/roles/audit viewer |
| P1-E1-S1 Connector SPI | P0-E4-S1 secret vault | Connectors read credentials only via the vault |
| P1-E1-S3 Kafka pipeline | P0-E4-S3 observability | `traceparent` propagates through the envelope |
| P1-E3-S1/S2/S3 connectors | P1-E1-S4 contract kit | A connector is not "built" until it is kit-certified |
| P1-E2-S2 normalized model v1 | P1-E2-S1 normalizer | Canonical persistence follows the mapping framework |
| P1-E4-S1 connector admin UI | P1-E1-S2 sync engine + P0-E5-S2 admin console | UI drives the engine, inside the console shell |

## 5. Sequence invariants (normative)

These MUST hold at all times; a violation is a `G4` (architecture) or `G3` (security) BLOCKER. Each is a packaging of an inviolable principle ([ImplementationReadinessDecision §7](../reviews/architecture-readiness/ImplementationReadinessDecision.md)) or an ADR ([ArchitectureDecisionUpdates](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md), [ArchitectureOverview §7](../docs/architecture/ArchitectureOverview.md)); the citation is the enforcing authority, never restated here.

1. **No tenant-scoped table before the RLS template exists.** No migration may create a tenant-scoped table until `P0-E2-S2` has landed the RLS policy template + partitioning conventions. Every tenant-owned table ships `tenant_id` + forced RLS; the isolation suite runs with app filters disabled. (Principle 6; ADR-004/016/018; NFR-041)
2. **No connector before the Connector SPI + contract kit.** No connector implementation (`P1-E3-*`, and every later connector) may start before `P1-E1-S1` (SPI) and `P1-E1-S4` (kit K1–K7) are DONE; a connector is "built" only when kit-certified. Connectors act only through `SyncContext` — never direct Kafka/Postgres/Redis/MinIO. (Principle 12; ConnectorFramework)
3. **No analytics before normalized model v1.** No metric, read model, or risk score may compute before `P1-E2-S2` (normalized model v1) exists. Analytics reads canonical schemas via the read-only grant only; it never writes canonical tables. (Principle 3/5; ADR-019)
4. **Single canonical writer.** `eip-ingestion` owns all writes to canonical tables; derived fields and agent-raised `Risk` rows flow through its exported `CanonicalEnrichmentService`. No other module writes canonical tables — ever. (ADR-019; Principle 5)
5. **Outbox out, idempotent in.** No producer writes Kafka outside the transactional outbox (raw intake's direct-produce carve-out exactly as scoped by ADR-017); the outbox topology decision (`SPRINT-01` note) precedes any domain/analytics/job eventing. Consumers dedup and ack only after the idempotent write commits. (Principle 4; ADR-010/017)
6. **`eip-app` consumes no Kafka.** The composition root serves agent-run/report-job status from database rows via polling/LISTEN-NOTIFY; there is no SSE-on-Kafka bridge. (AD-2; ArchitectureOverview §10)
7. **Secrets and audit are write-time, not retrofit.** Envelope encryption via the KMS SPI is the only secret storage path (`P0-E4-S1`); an unaudited mutation cannot commit, so the audit subsystem (`P0-E3-S4`) precedes secret-vault consumption of audit. (Principles 10 & 11; ADR-014; SEC-02)
8. **Dashboards never read canonical tables at request time.** Read models are projector-maintained plain tables with RLS; PostgreSQL materialized views are forbidden for tenant-scoped data. This constrains Phase 2 but is stated now because the read-model seam is designed into the Phase-1 event model. (Principle 13; ADR-011/015)
9. **Identity is EIP-minted.** Internal identity is UUIDv7; source identity lives only in `ExternalRef` (`externalId` immutable, `externalKey` for human-readable keys). No connector or normalizer may key canonical rows on a source's mutable key. (Principle 5; AD-14)
10. **Air-gap purity from day one.** No telemetry, phone-home, license check, or runtime download — including bundled components' defaults. This shapes every dependency and image choice starting in `SPRINT-00`. (Principle 9; NFR-051)

## 6. Where the graph and the risk order live

This document is the narrative; the machine-checkable structure is elsewhere and is the source of truth on conflict:

- **Module topology + "built" definition** → [ModuleBuildOrder](./ModuleBuildOrder.md)
- **Story/task dependency edges (the DAG)** → [DependencyMatrix](./DependencyMatrix.md)
- **Which lanes run concurrently under single-writer rules** → [ParallelizationPlan](./ParallelizationPlan.md)
- **Risk → sprint → spike → owning role → exit evidence, ordered by blast radius × uncertainty, each mapped to a readiness open question** → [RiskDrivenImplementation](./RiskDrivenImplementation.md)

## Related documents

- [MasterProgram.md](./MasterProgram.md) — golden rules (rule 3: sequence by risk)
- [ImplementationRoadmap.md](./ImplementationRoadmap.md) — phase→version→sprint timeline
- [IncrementStrategy.md](./IncrementStrategy.md) — the demoable slice each sprint ships
- [ModuleBuildOrder.md](./ModuleBuildOrder.md) · [DependencyMatrix.md](./DependencyMatrix.md) · [ParallelizationPlan.md](./ParallelizationPlan.md) · [RiskDrivenImplementation.md](./RiskDrivenImplementation.md) — the underlying DAG, lanes, and risk order
- [`../docs/implementation/PhaseBasedImplementationPlan.md`](../docs/implementation/PhaseBasedImplementationPlan.md) §2, §5.1, §6.1 — build-order rationale and story dependencies
- [`../reviews/architecture-readiness/ImplementationReadinessDecision.md`](../reviews/architecture-readiness/ImplementationReadinessDecision.md) §7 — inviolable principles
- [`../reviews/architecture-readiness/ArchitectureDecisionUpdates.md`](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) · [`../docs/architecture/ArchitectureOverview.md`](../docs/architecture/ArchitectureOverview.md) §7 — ADR-015..020
