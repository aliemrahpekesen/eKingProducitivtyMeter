# SPRINT-00 — Repo & platform bootstrap

- **Phase / version:** Phase 0 (v0.1) · **Position:** Phase 0 weeks 1–2 · **Cadence:** 2-week
- **Plan of record:** [../../program/Sprint00.md](../../program/Sprint00.md) · [../../sprints/sprint-00/Sprint00Plan.md](../../sprints/sprint-00/Sprint00Plan.md)
- **Objective:** Monorepo + CI gates + Compose dev stack + `eip-core` skeleton + governance backfill (ADR-001..020, CODEOWNERS, docs-lint). Full objective: [Sprint00.md §1](../../program/Sprint00.md).

## Committed tasks

| Task | Story/gov | Lane | Size | CC | State | Owner |
|---|---|---|---|---|---|---|
| TASK-0001 | P0-E1-S1 | L0 | S | CC-7 | MERGED | R-IE (backend) |
| TASK-0002 | P0-E1-S2 | L1 | M | CC-7 | READY | R-IE (infra) |
| TASK-0003 | P0-E1-S3 | L1 | M | CC-7 | READY | R-IE (infra) |
| TASK-0004 | P0-E1-S4 | L3 | S | CC-6 | READY | R-DE |
| TASK-0005 | P0-E2-S1 | L2 | M | CC-1 | READY* | R-IE (backend) |
| TASK-0006 | ADR-001..020 backfill | L3 | M | CC-6 | READY | R-CA + R-DE |
| TASK-0007 | CODEOWNERS | L3 | S | CC-6 | READY | R-CA |
| TASK-0008 | docs-lint CI | L3 | M | CC-7 | READY | R-DE + R-DOA |

\* TASK-0005 pending CC-1 R-CA approach pre-approval before CLAIMED.

## Lanes / write-set disjointness

L0 (TASK-0001, root scaffolding) merges first; then L1 (`/infra`, `/.github`), L2 (`/backend/eip-core`), L3 (`/docs/adr`, `/CODEOWNERS`, docs-lint) run concurrently with pairwise-disjoint write-sets. See [../../sprints/sprint-00/TaskDependencyGraph.md](../../sprints/sprint-00/TaskDependencyGraph.md).

## Execution order

Wave W0: TASK-0001 → W1: TASK-0002, TASK-0005, TASK-0006, TASK-0007 → W2: TASK-0003, TASK-0008 → W3: TASK-0004. Full order: [../../sprints/sprint-00/Sprint00ExecutionOrder.md](../../sprints/sprint-00/Sprint00ExecutionOrder.md).

## Conflict declaration

No two in-flight tasks share a write-set path. The only shared file `/.github/workflows/ci.yml` is created by TASK-0002, extended by TASK-0008 — sequenced by hard dependency, never concurrent.

## Daily log

- **2026-07-07** — TASK-0001 CLAIMED and started (R-IE): branch `feature/TASK-0001-monorepo-scaffolding`; scaffolding the monorepo tree per RepositoryStructure. Reconciliation recorded: Gradle build root lives under `/backend` per RepositoryStructure §2 (the TaskSpecs file list said repo root; AC-3 binds to RepositoryStructure, which wins — docs-first).
- **2026-07-07** — TASK-0001 reviewed by R-CR ([../reviews/TASK-0001-review.md](../reviews/TASK-0001-review.md)): **APPROVED** (0 BLOCKER, 0 MAJOR, 2 MINOR, 3 NIT); all ACs independently re-run green. Merged into `integration/SPRINT-00` (local `--no-ff`). State → **MERGED**. Two MINORs filed as [DEBT-001/DEBT-002](../debt-register.md). Full `MERGED → VERIFIED → DONE` completes once TASK-0002 provides CI and re-verifies on the integration head. TASK-0002 opened next.

## Replan log

(none)

## Exit

Sprint exit criteria: [Sprint00.md §9](../../program/Sprint00.md). Not yet met — sprint in progress.
