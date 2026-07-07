# SPRINT-00 Execution Order

The topological claim order and parallel waves for SPRINT-00, derived from [TaskDependencyGraph.md](./TaskDependencyGraph.md). Maximizes parallelism after the L0 prerequisite while honoring the single-writer rule.

## Waves

| Wave | Tasks (parallel within a wave) | Precondition | Lanes |
|---|---|---|---|
| **W0** | **TASK-0001** | none — the serial prerequisite | L0 |
| **W1** | TASK-0002, TASK-0005, TASK-0006, TASK-0007 | TASK-0001 MERGED | L1, L2, L3, L3 |
| **W2** | TASK-0003, TASK-0008 | TASK-0002 MERGED (TASK-0008 also wants TASK-0006/0007 MERGED to lint real content) | L1, L3 |
| **W3** | TASK-0004 | TASK-0003 MERGED | L3 |

Up to **four tasks run concurrently in W1** — the sprint's max parallelism ([Sprint00.md §12](../../program/Sprint00.md)). W1 covers all three post-scaffolding lanes at once: CI (L1), eip-core (L2), ADRs + CODEOWNERS (L3).

## Ordered claim sequence

1. **TASK-0001** — Monorepo scaffolding. **Claim first.** Merge before anything else; it creates the tree every other task writes into.
2. Once TASK-0001 is MERGED, open W1 in parallel:
   - **TASK-0002** — CI pipeline (unblocks TASK-0003, TASK-0008).
   - **TASK-0005** — eip-core skeleton (CC-1; needs R-CA approach pre-approval before READY→CLAIMED).
   - **TASK-0006** — ADR backfill.
   - **TASK-0007** — CODEOWNERS.
3. When TASK-0002 is MERGED: **TASK-0003** (Compose); and **TASK-0008** (docs-lint) once TASK-0006/0007 are also MERGED so it lints real content.
4. When TASK-0003 is MERGED: **TASK-0004** (developer docs — documents `make dev-up`).

## Why this order

- **Risk-first within the floor:** TASK-0002 (CI/gates) lands in W1 so every subsequent PR is gated from the first merge — the boundary-erosion mitigation ([Sprint00.md §13](../../program/Sprint00.md)). TASK-0005's Modulith leaf test is the boundary-guard demonstration.
- **Governance not deferred:** TASK-0006/0007 (ADRs, CODEOWNERS) sit in W1, satisfying readiness condition 4 early rather than at sprint end ([ImplementationReadinessDecision §3](../../reviews/architecture-readiness/ImplementationReadinessDecision.md)).
- **Docs last:** TASK-0004 depends on the real `make dev-up` from TASK-0003, and TASK-0008 lints the real ADRs/CODEOWNERS — both naturally trail their inputs.

## Blocked/parked handling

A task blocked > 1 session escalates per [DevelopmentLifecycle](../../engineering-operating-system/DevelopmentLifecycle.md) (owning domain architect → R-CA). If TASK-0002 slips, W2/W3 wait but W1's TASK-0005/0006/0007 continue unaffected (disjoint). No task in a later wave may CLAIM before its hard deps are MERGED — DONE-or-stubbed is the DoR bar.

## Related documents

- [TaskDependencyGraph.md](./TaskDependencyGraph.md) · [Sprint00Backlog.md](./Sprint00Backlog.md) · [TaskSpecs.md](./TaskSpecs.md)
- [../../program/ParallelizationPlan.md](../../program/ParallelizationPlan.md) · [../../engineering-operating-system/DevelopmentLifecycle.md](../../engineering-operating-system/DevelopmentLifecycle.md)
