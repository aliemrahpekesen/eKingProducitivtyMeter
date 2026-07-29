# Implementation Roadmap — phase → version → sprint timeline

The release-train view of the program: how the six canonical phases (each cutting one version `v0.1 … v1.0`) decompose into `SPRINT-NN` increments, what gates each phase release, and where the detail lives. This document packages — it does not restate — the phase model from [Roadmap](../docs/product/Roadmap.md) and the phase exit criteria from [PRD §10](../docs/product/PRD.md). Phase 0 is shown fully sprinted; Phase 1 is outlined; Phases 2–5 are sprint-planned at their own phase kickoff. Per-sprint detail (the 11 required fields) lives in [SprintCatalog](./SprintCatalog.md) and the `Sprint00`–`Sprint03` files.

## 1. Phase → version → objective → sprints → gates

Durations are indicative planning anchors from [Roadmap §1](../docs/product/Roadmap.md); phases are **exit-criteria-gated, not date-gated** ([Roadmap §2](../docs/product/Roadmap.md)). The release gate cut at each phase boundary is `RG1–RG4` per [QualityGatePolicy §6](../engineering-operating-system/QualityGatePolicy.md).

| Phase | Version | Objective (source) | Sprints | Exit criteria | Release gate |
|---|---|---|---|---|---|
| **0 Foundations** | **v0.1** | Monorepo, CI, core platform (tenancy, RBAC, audit, secrets, OpenAPI, observability), DB baseline + RLS, Compose dev stack ([Roadmap §4.1](../docs/product/Roadmap.md), [PhaseImpl §5](../docs/implementation/PhaseBasedImplementationPlan.md)) | **`SPRINT-00`…`SPRINT-03`** (detailed below, §2) | [PRD §10 Phase 0](../docs/product/PRD.md) | RG1 · RG2 · RG4 (dry-run for the v0.1 internal milestone) |
| **1 Ingestion core** | **v0.2** | Connector SPI + sync engine, Kafka raw→canonical pipeline, normalized model v1, Jira + GitHub + simulation connectors ([Roadmap §5](../docs/product/Roadmap.md), [PhaseImpl §6](../docs/implementation/PhaseBasedImplementationPlan.md)) | `SPRINT-04`…`SPRINT-07` (outlined, §3) | [PRD §10 Phase 1](../docs/product/PRD.md) | RG1–RG4 |
| **2 Analytics & dashboards** | **v0.3** | Metric engine (flow/DORA/quality/ops/team-health), dashboard suite, remaining P1 connectors ([Roadmap §6](../docs/product/Roadmap.md), [PhaseImpl §7](../docs/implementation/PhaseBasedImplementationPlan.md)) | Sprint-planned at Phase-2 kickoff | [PRD §10 Phase 2](../docs/product/PRD.md) | RG1–RG4 (RG2 anti-surveillance release-blocking) |
| **3 AI core** | **v0.4** | LLM provider SPI, RAG, first agents + Validation Agent, delivery-risk analytics, report engine + Artifacts Library ([Roadmap §7](../docs/product/Roadmap.md), [PhaseImpl §8](../docs/implementation/PhaseBasedImplementationPlan.md)) | Sprint-planned at Phase-3 kickoff | [PRD §10 Phase 3](../docs/product/PRD.md) | RG1–RG4 (air-gapped demo from here on) |
| **4 Full agent suite + MCP** | **v0.5** | All 18 canonical agents, MCP client + server, full generated-output catalog, scheduling + notifications ([Roadmap §8](../docs/product/Roadmap.md), [PhaseImpl §9](../docs/implementation/PhaseBasedImplementationPlan.md)) | Sprint-planned at Phase-4 kickoff | [PRD §10 Phase 4](../docs/product/PRD.md) | RG1–RG4 |
| **5 Enterprise hardening** | **v1.0** | K8s/OpenShift GA, HA, performance + security certification, backup/restore + upgrade, remaining connectors ([Roadmap §9](../docs/product/Roadmap.md), [PhaseImpl §10](../docs/implementation/PhaseBasedImplementationPlan.md)) | Sprint-planned at Phase-5 kickoff | [PRD §10 Phase 5](../docs/product/PRD.md) | RG1–RG4 (E2E on the K8s reference deployment) |

**One minor version per phase** on the 0.x train; a phase's version is cut only when its exit criteria pass ([Roadmap §10](../docs/product/Roadmap.md)). `v0.1` is an **internal milestone**, not customer GA — customer GA is `v1.0` at Phase 5. From `v0.3` onward every release is demoable end-to-end from Compose + simulation packs, air-gapped ([Roadmap §10](../docs/product/Roadmap.md)).

## 2. Phase 0 — fully sprinted (`v0.1`)

Phase 0 is ~2 months ([Roadmap §1](../docs/product/Roadmap.md)) = four 2-week sprints carrying **all 15 Phase-0 stories** `P0-E1-S1`…`P0-E5-S2` ([PhaseImpl §5.1](../docs/implementation/PhaseBasedImplementationPlan.md)) with no reassignment. Objectives below are program-local sprint objectives (the only new IDs `/program` mints); every other fact is cited.

| Sprint | Objective | Modules | Stories (`P-E-S`) | Review gates | Est. context |
|---|---|---|---|---|---|
| **`SPRINT-00`** Repo & platform bootstrap | Stand up the monorepo, CI, Compose stack, and the `eip-core` skeleton so every later sprint has ground to run on | infra, eip-core (skeleton) | P0-E1-S1, P0-E1-S2, P0-E1-S3, P0-E1-S4, P0-E2-S1 (+ ADR-001..020 backfill, CODEOWNERS, docs-lint CI) | G0, G1, G2, G7, G8 (+ G4 for the eip-core boundary + ADRs) | ~35–45k |
| **`SPRINT-01`** Persistence & tenancy spine | DB baseline + RLS template + tenant context propagation, proven by a cross-tenant-blocked write/read over `/api/v1` with an end-to-end trace | eip-core, eip-tenancy, eip-app, infra | P0-E2-S2, P0-E3-S1, P0-E4-S2, P0-E4-S3 (+ blocking readiness design-notes: RLS/pooling spike, outbox topology, consumer idempotency, audit hash-chain approach) | G1, G2, G3 (isolation tests), G4 (RLS = CC-1/CC-4), G6 (observability), G7, G8 | ~50–60k |
| **`SPRINT-02`** AuthN/Z, audit, secrets | RBAC catalog + guards, OIDC login with tenant/role mapping, append-only audit, and envelope-encrypted secrets with rotation — the security spine | eip-tenancy, eip-app | P0-E3-S2, P0-E3-S3, P0-E3-S4, P0-E4-S1 | All applicable + G3 full [SecurityChecklist](../engineering-operating-system/SecurityChecklist.md) + R-SA sign-off (every story CC-2) | ~55–65k |
| **`SPRINT-03`** Console shell & Phase-0 close (v0.1) | Frontend shell + admin console skeleton, then Phase-0 exit verification and the RG dry-run that cuts the v0.1 internal milestone | frontend, eip-app | P0-E5-S1, P0-E5-S2 (+ Phase-0 exit verification [PRD §10](../docs/product/PRD.md) + RG1–RG4 dry-run) | All applicable + RG1 (phase exit), RG2 (anti-surveillance + security), RG4 (operability: Phase-0 backup/restore drill) | ~50–60k (frontend-weighted) |

Full field-by-field detail is in the sprint files ([Sprint00](./Sprint00.md), [Sprint01](./Sprint01.md), [Sprint02](./Sprint02.md), [Sprint03](./Sprint03.md)) and consolidated in [Phase0](./Phase0.md) and [SprintCatalog](./SprintCatalog.md). The unambiguous starting point is the first-10-PRs sequence in [PhaseImpl §5.2](../docs/implementation/PhaseBasedImplementationPlan.md); the Phase-0 demo script is [PhaseImpl §5.3](../docs/implementation/PhaseBasedImplementationPlan.md).

## 3. Phase 1 — outlined (`v0.2`)

Phase 1 (~2.5 months, [Roadmap §1](../docs/product/Roadmap.md)) is outlined here and detailed in [SprintCatalog](./SprintCatalog.md) at lower resolution; full sprint files are authored at Phase-1 kickoff. Stories are from [PhaseImpl §6.1](../docs/implementation/PhaseBasedImplementationPlan.md).

| Sprint | Objective | Modules | Stories (`P-E-S`) |
|---|---|---|---|
| **`SPRINT-04`** | Connector SPI + sync-engine spine | eip-connectors, eip-ingestion | P1-E1-S1 (SPI), P1-E1-S2 (sync engine) |
| **`SPRINT-05`** | Kafka pipeline + normalizer + model v1 | eip-ingestion, eip-core, eip-workers | P1-E1-S3, P1-E2-S1, P1-E2-S2 |
| **`SPRINT-06`** | Jira + GitHub + simulation connectors + contract kit | eip-connectors, /simulation | P1-E3-S1, P1-E3-S2, P1-E3-S3, P1-E1-S4 |
| **`SPRINT-07`** | Connector admin UI + data browser + Phase-1 close | frontend, eip-app | P1-E4-S1, P1-E4-S2 |

Phase-1 exit criteria are [PRD §10 Phase 1](../docs/product/PRD.md) and [PhaseImpl §6.2](../docs/implementation/PhaseBasedImplementationPlan.md); the Phase-1 demo script is [PhaseImpl §6.2](../docs/implementation/PhaseBasedImplementationPlan.md).

## 4. Phases 2–5 — sprint-planned at phase kickoff

Phases 2–5 stay at phase resolution here by design: they are decomposed into `SPRINT-NN` files at their own phase kickoff, once the preceding phase's retro has fed the next phase's risk list ([PhaseImpl §11](../docs/implementation/PhaseBasedImplementationPlan.md)). Until then, the authoritative sources are:

| Phase | Objectives & stories | Exit criteria | Later-phase spec docs (FORBIDDEN before their phase) |
|---|---|---|---|
| 2 (`v0.3`) | [Roadmap §6](../docs/product/Roadmap.md) · [PhaseImpl §7](../docs/implementation/PhaseBasedImplementationPlan.md) | [PRD §10 Phase 2](../docs/product/PRD.md) | analytics/metrics, dashboards catalogs |
| 3 (`v0.4`) | [Roadmap §7](../docs/product/Roadmap.md) · [PhaseImpl §8](../docs/implementation/PhaseBasedImplementationPlan.md) | [PRD §10 Phase 3](../docs/product/PRD.md) | AI/RAG/agent, reports catalogs |
| 4 (`v0.5`) | [Roadmap §8](../docs/product/Roadmap.md) · [PhaseImpl §9](../docs/implementation/PhaseBasedImplementationPlan.md) | [PRD §10 Phase 4](../docs/product/PRD.md) | MCP, generated-output catalogs |
| 5 (`v1.0`) | [Roadmap §9](../docs/product/Roadmap.md) · [PhaseImpl §10](../docs/implementation/PhaseBasedImplementationPlan.md) | [PRD §10 Phase 5](../docs/product/PRD.md) | K8s/OpenShift deployment, HA/perf-cert docs |

The FORBIDDEN column is the concrete cost of golden rule 1: reading a later-phase catalog during Phase 0–1 wastes context and invites premature coupling ([ContextManifest](./ContextManifest.md) makes this per-sprint and normative).

## 5. Release gate per phase (`RG1–RG4`)

Every phase boundary collects all four release-gate verdicts before the `vX.Y.Z` tag; a failing RG blocks the release ([QualityGatePolicy §6](../engineering-operating-system/QualityGatePolicy.md), [Roadmap §2](../docs/product/Roadmap.md)). Definitions are the EOS's; this table only records which are load-bearing per phase.

| Gate | Verifies (owner) | Phase 0 (v0.1 dry-run) | Notes for later phases |
|---|---|---|---|
| **RG1** | Phase exit criteria met — every [PRD §10](../docs/product/PRD.md) item for the phase (R-PO + R-TPM; R-RM records) | Yes | Every phase |
| **RG2** | Security certification + NFR-071 anti-surveillance review, release-blocking (**R-SA A4**) | Yes (security posture of the Phase-0 stack) | Anti-surveillance becomes fully load-bearing once metrics/agents exist (Phase 2+) |
| **RG3** | Performance at phase-scale targets vs baselines (**R-PE A4**) | Not load-bearing at v0.1 (no hot paths yet) | Load-bearing from Phase 2 (nightly Gatling baseline established there) |
| **RG4** | Operability: backup/restore or the phase-relevant drill; runbooks current (R-DOA + R-OE + R-DE) | Yes (Phase-0 stack backup/restore drill) | Every phase; full HA drills at Phase 5 |

## Related documents

- [MasterProgram.md](./MasterProgram.md) — the program constitution and entry point
- [DevelopmentSequence.md](./DevelopmentSequence.md) — why this order; the sequence invariants
- [IncrementStrategy.md](./IncrementStrategy.md) — the per-sprint demoable increment
- [SprintCatalog.md](./SprintCatalog.md) · [Phase0.md](./Phase0.md) — full per-sprint fields and Phase-0 packaging
- [Sprint00.md](./Sprint00.md) · [Sprint01.md](./Sprint01.md) · [Sprint02.md](./Sprint02.md) · [Sprint03.md](./Sprint03.md) — Phase-0 sprint files
- [`../docs/product/Roadmap.md`](../docs/product/Roadmap.md) · [`../docs/product/PRD.md`](../docs/product/PRD.md) §10 — phase/version and exit criteria (source of record)
- [`../docs/implementation/PhaseBasedImplementationPlan.md`](../docs/implementation/PhaseBasedImplementationPlan.md) — P-E-S stories, first-10-PRs, phase demos
- [`../engineering-operating-system/QualityGatePolicy.md`](../engineering-operating-system/QualityGatePolicy.md) §6 — RG1–RG4 protocol
