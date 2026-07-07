# SPRINT-00 Backlog

The ready-to-claim task backlog. Full specs: [TaskSpecs.md](./TaskSpecs.md). All eight tasks pass G0 ([Sprint00DefinitionOfReadyCheck.md](./Sprint00DefinitionOfReadyCheck.md)) and are **READY** — none is CLAIMED yet. Claim order: [Sprint00ExecutionOrder.md](./Sprint00ExecutionOrder.md).

## Backlog table

| Task | Title | Story/gov | Lane | Size | CC | Depends on | State |
|---|---|---|---|---|---|---|---|
| [TASK-0001](./TaskSpecs.md#task-0001) | Monorepo scaffolding | P0-E1-S1 | L0 | S | CC-7 | — | READY |
| [TASK-0002](./TaskSpecs.md#task-0002) | CI pipeline + gate stages | P0-E1-S2 | L1 | M | CC-7 | TASK-0001 | READY |
| [TASK-0003](./TaskSpecs.md#task-0003) | Compose dev stack + `make dev-up` | P0-E1-S3 | L1 | M | CC-7 | TASK-0001 (after 0002 in L1) | READY |
| [TASK-0004](./TaskSpecs.md#task-0004) | Developer docs / onboarding | P0-E1-S4 | L3 | S | CC-6 | TASK-0003 | READY |
| [TASK-0005](./TaskSpecs.md#task-0005) | `eip-core` skeleton | P0-E2-S1 | L2 | M | **CC-1** | TASK-0001 (+ R-CA pre-approval) | READY* |
| [TASK-0006](./TaskSpecs.md#task-0006) | ADR-001..020 backfill | governance | L3 | M | CC-6 | TASK-0001 | READY |
| [TASK-0007](./TaskSpecs.md#task-0007) | CODEOWNERS | governance | L3 | S | CC-6 | TASK-0001 | READY |
| [TASK-0008](./TaskSpecs.md#task-0008) | docs-lint CI automation | governance | L3 | M | CC-7 | TASK-0002 (lints 0006/0007) | READY |

\* TASK-0005 is READY pending the CC-1 R-CA approach pre-approval note ([DefinitionOfReady §2](../../engineering-operating-system/DefinitionOfReady.md)); recorded in its task file before CLAIMED.

## Story coverage

All five Phase-0 Epic-1/2 stories opened this sprint are covered, plus the readiness-condition-4 governance backfill:

| Story | Task | | Governance | Task |
|---|---|---|---|---|
| P0-E1-S1 | TASK-0001 | | ADR-001..020 backfill | TASK-0006 |
| P0-E1-S2 | TASK-0002 | | CODEOWNERS | TASK-0007 |
| P0-E1-S3 | TASK-0003 | | docs-lint | TASK-0008 |
| P0-E1-S4 | TASK-0004 | | | |
| P0-E2-S1 | TASK-0005 | | | |

Remaining Phase-0 stories (E3 tenancy/RBAC/OIDC/audit, E4 secrets/OpenAPI/observability, E5 frontend) belong to [SPRINT-01](../../program/Sprint01.md)–03 — **not** this sprint ([Phase0.md](../../program/Phase0.md)).

## Materialization note

At claim time each spec is copied into `/work/tasks/TASK-000N.md` (the DoR-required task-file location, [DevelopmentLifecycle §4](../../engineering-operating-system/DevelopmentLifecycle.md)) and the sprint plan line is created in `/work/sprints/SPRINT-00.md`. Creating `/work/` is the **first execution action** of Sprint 00 — it is not done during planning (this directory is planning-only).

## Related documents

- [Sprint00Plan.md](./Sprint00Plan.md) · [TaskSpecs.md](./TaskSpecs.md) · [Sprint00ExecutionOrder.md](./Sprint00ExecutionOrder.md) · [TaskDependencyGraph.md](./TaskDependencyGraph.md)
- [../../program/Sprint00.md](../../program/Sprint00.md) · [../../program/Phase0.md](../../program/Phase0.md)
