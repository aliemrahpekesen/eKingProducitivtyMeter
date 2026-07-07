# SPRINT-00 Plan — Repo & platform bootstrap

Execution-ready plan for SPRINT-00. This file is the working plan; it does **not** restate the program — the source of record is [../../program/Sprint00.md](../../program/Sprint00.md) and its [ContextManifest §"SPRINT-00"](../../program/ContextManifest.md). No application code is written here — this directory holds the **ready-to-claim task backlog** only.

- **Phase / version:** Phase 0 (v0.1) — Foundations
- **Position:** Phase 0, weeks 1–2
- **Cadence:** 2-week sprint; task states INTAKE→READY→CLAIMED→IN_PROGRESS→IN_REVIEW→MERGED→VERIFIED→DONE ([DevelopmentLifecycle §3](../../engineering-operating-system/DevelopmentLifecycle.md))
- **Modules:** `infra`, `eip-core` (skeleton only)

## Objective

Stand up the monorepo, CI enforcing the [QualityGatePolicy](../../engineering-operating-system/QualityGatePolicy.md) gates from PR-1, the Docker Compose dev stack (`make dev-up`), the `eip-core` skeleton, and the governance backfill (ADR-001..020, CODEOWNERS, docs-lint). At close: `make dev-up` boots the stack healthy within the NFR-050 budget, CI is green on empty modules, a Modulith boundary violation is provably blocked then fixed, and `/docs/adr/ADR-001..020` are browsable. Full objective + exit criteria: [Sprint00.md §1, §9](../../program/Sprint00.md).

## Task backlog (8 tasks)

| Task | Story / governance | Lane | Size | Change class | First claim? |
|---|---|---|---|---|---|
| [TASK-0001](./TaskSpecs.md#task-0001) | P0-E1-S1 Monorepo scaffolding | L0 (serial prerequisite) | S | CC-7 | **YES — gates all** |
| [TASK-0002](./TaskSpecs.md#task-0002) | P0-E1-S2 CI pipeline | L1 | M | CC-7 | after TASK-0001 |
| [TASK-0003](./TaskSpecs.md#task-0003) | P0-E1-S3 Compose dev stack | L1 | M | CC-7 | after TASK-0002 |
| [TASK-0004](./TaskSpecs.md#task-0004) | P0-E1-S4 Developer docs / `make dev-up` guide | L3 | S | CC-6 | after TASK-0003 |
| [TASK-0005](./TaskSpecs.md#task-0005) | P0-E2-S1 `eip-core` skeleton | L2 | M | **CC-1** | after TASK-0001 |
| [TASK-0006](./TaskSpecs.md#task-0006) | ADR-001..020 backfill | L3 | M | CC-6 (G4) | after TASK-0001 |
| [TASK-0007](./TaskSpecs.md#task-0007) | CODEOWNERS | L3 | S | CC-6 | after TASK-0001 |
| [TASK-0008](./TaskSpecs.md#task-0008) | docs-lint CI automation | L3 | M | CC-7 | after TASK-0002 |

Full specs: [TaskSpecs.md](./TaskSpecs.md). Backlog states: [Sprint00Backlog.md](./Sprint00Backlog.md). Order: [Sprint00ExecutionOrder.md](./Sprint00ExecutionOrder.md).

## Lanes / parallelization

Per [Sprint00.md §12](../../program/Sprint00.md) and the disjoint-write-set law ([SprintExecutionGuide §2.3](../../engineering-operating-system/SprintExecutionGuide.md)). SPRINT-00 is **serial at L0** (scaffolding gates everything), then three concurrent lanes:

| Lane | Write-set boundary | Role | Tasks |
|---|---|---|---|
| L0 | repo root, `settings.gradle.kts`, `buildSrc` | R-IE (backend) / R-BA | TASK-0001 |
| L1 | `/infra`, `/.github`, `/Makefile` | R-IE (infra) / R-DOA | TASK-0002 → TASK-0003 |
| L2 | `/backend/eip-core` | R-IE (backend) / R-BA+R-CA | TASK-0005 |
| L3 | `/docs/adr`, `/CODEOWNERS`, `/docs`, docs-lint CI | R-CA + R-DE | TASK-0006, TASK-0007, TASK-0008, TASK-0004 |

Write-sets are pairwise disjoint after TASK-0001 merges. See [TaskDependencyGraph.md](./TaskDependencyGraph.md).

## Review gates (this sprint)

Per [QualityGatePolicy §2–§3](../../engineering-operating-system/QualityGatePolicy.md): **G0** (every task before READY), **G1** (build/static incl. Modulith `verify()` + ArchUnit + license allow-list), **G2** (unit + integration + coverage ratchet), **G4** (TASK-0005 eip-core CC-1; TASK-0006 ADR files — R-CA), **G7** (docs + docs-lint), **G8** (R-CR verdict; CC-1 needs two approvals). G3 runs its automated portion (gitleaks, dependency scan) on every PR; no CC-2 manual review this sprint. G5/G6 have no triggers (no hot paths, endpoints, consumers, or jobs). **Release gates: none** (RG1–RG4 first apply at Phase-0 close, [Sprint03](../../program/Sprint03.md)). Per-task gate map: [Sprint00ReviewChecklist.md](./Sprint00ReviewChecklist.md).

## Estimated context size

Sprint-union budget **~35–45k tokens** ([Sprint00.md §11](../../program/Sprint00.md)). Each task's own context pack stays within the [ContextManagementStrategy §2](../../engineering-operating-system/ContextManagementStrategy.md) rule-4 ceiling — a session reads only its task's `Required documents`, never the whole repo. Per-task budgets are in [TaskSpecs.md](./TaskSpecs.md).

## Demo increment

Clean clone → `make dev-up` → full Compose stack healthy within NFR-050 → open a Modulith-violating PR, watch CI block it at G1, push fix, watch it pass → browse `/docs/adr/ADR-001..020`. This is the "M0.1 CI green on empty modules" milestone ([PhaseImpl §5.3](../../docs/implementation/PhaseBasedImplementationPlan.md)). Hands off to [Sprint01](../../program/Sprint01.md).

## Related documents

- [Sprint00Backlog.md](./Sprint00Backlog.md) · [TaskSpecs.md](./TaskSpecs.md) · [TaskDependencyGraph.md](./TaskDependencyGraph.md)
- [Sprint00DefinitionOfReadyCheck.md](./Sprint00DefinitionOfReadyCheck.md) · [Sprint00ExecutionOrder.md](./Sprint00ExecutionOrder.md) · [Sprint00ReviewChecklist.md](./Sprint00ReviewChecklist.md)
- Program: [../../program/Sprint00.md](../../program/Sprint00.md) · [../../program/ContextManifest.md](../../program/ContextManifest.md) · [../../program/Phase0.md](../../program/Phase0.md)
- EOS: [DevelopmentLifecycle](../../engineering-operating-system/DevelopmentLifecycle.md) · [DefinitionOfReady](../../engineering-operating-system/DefinitionOfReady.md) · [DefinitionOfDone](../../engineering-operating-system/DefinitionOfDone.md) · [QualityGatePolicy](../../engineering-operating-system/QualityGatePolicy.md)
