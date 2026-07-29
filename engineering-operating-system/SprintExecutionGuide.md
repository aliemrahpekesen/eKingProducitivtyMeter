# Sprint Execution Guide

This document defines how R-TPM plans and runs a sprint and how every engineering agent reports status inside one. R-TPM reads it before every sprint planning; implementing roles read §4 (daily status duties) and §5 (what happens when plans change). Sprints are the execution wrapper around [./DevelopmentLifecycle.md](./DevelopmentLifecycle.md): they decide WHICH ready tasks run WHEN and IN PARALLEL WITH WHAT — nothing in a sprint overrides a gate or a law.

## 1. Cadence and calendar

- Sprints are **2 weeks (10 working days)**, identified `SPRINT-NN` (monotonic, zero-padded), with the plan at `/work/sprints/SPRINT-NN.md`.
- Phases ([../docs/product/Roadmap.md](../docs/product/Roadmap.md) §1) are exit-criteria-gated, not date-gated; sprints tile a phase. Every sprint belongs to exactly one phase and states it in its header.
- Fixed calendar: **Day 1** planning (this guide §2–§3) · **Days 1–10** execution with daily status (§4) · **Day 10** sprint review ([./SprintReviewGuide.md](./SprintReviewGuide.md)).
- The sprint goal MUST be a single demoable sentence tied to the phase's demo milestone (e.g., for Phase 1, an increment of the demo script in [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §6.2). "Progress on several fronts" is not a sprint goal.

## 2. Sprint planning mechanics (Day 1, R-TPM runs it)

**Inputs (all MUST be consulted):** the current phase's committed story table ([../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5–§10) · carry-over and PARKED tasks from the previous sprint · `/work/debt-register.md` · `/work/risk-register.md` (mitigation tasks for scores ≥ 15 are mandatory) · retro-generated tasks from the last review.

### 2.1 Capacity

- The planning unit is the **session** (one AI session ≈ one task claim). Task sizes: S = ½ session, M = 1 session; L tasks do not exist in a sprint — they are split first.
- Raw capacity = (number of concurrently active engineering agents) × (sessions per agent per day) × 10 days. R-TPM MUST state the number used and its basis in the sprint file.
- Committed load MUST be ≤ 80% of raw capacity. The remaining ≥ 20% absorbs review rework (G8 returns), VERIFIED follow-ups, and escalations — it is never pre-allocated.
- Within the committed load, **≤ 15% is reserved for DEBT paydown** ([./TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md)) and this reserve SHOULD NOT be raided for features; skipping it two sprints running is a retro item.

### 2.2 Selection rules

- [ ] Only tasks in READY state (G0 passed) enter the committed table. INTAKE tasks are planned only as "stretch, pending G0" and MUST NOT occupy committed capacity.
- [ ] Every committed task cites its P-E-S story; stories outside the phase table enter only via scope control ([../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §11).
- [ ] A task whose dependencies are neither DONE nor scheduled strictly earlier in this sprint is not committed.
- [ ] Bug fixes for escaped defects and risk mitigations (score ≥ 15) are committed before new feature tasks.

### 2.3 Parallel lanes and disjoint write-sets

Parallelism is organized in **lanes**. A lane is an ordered queue of tasks bound to a write-set boundary — normally one module per [./ModuleOwnership.md](./ModuleOwnership.md) (e.g., `eip-analytics`, `/frontend`, `/infra`).

- Tasks running in parallel (different lanes, overlapping days) MUST have disjoint write-sets. This is the single-writer law applied at sprint scale.
- Two tasks that MUST touch the same files are placed in the same lane, strictly ordered — or an explicit ordering across lanes is declared in the sprint file's conflict section ("TASK-0141 merges before TASK-0143 starts").
- A lane has at most **1 task IN_PROGRESS** at a time; an engineering agent holds at most **1 CLAIMED task** at a time.
- Cross-cutting write-sets (e.g., Flyway migrations under CC-4, shared `eip-core` types) get their own serialized lane; nothing else touches those paths that sprint.

**Write-set conflict check (R-TPM, before committing the plan):**

- [ ] List every committed task's declared write-set (modules + notable shared paths: migrations, OpenAPI spec, event schemas, `eip-core`).
- [ ] Verify pairwise disjointness across lanes; where overlap exists, record the explicit ordering.
- [ ] Verify no task's write-set touches a contract anchor without the task being classed CC-1 (and gated G4).

### 2.4 Dependency ordering

Represent dependencies as edges in the committed-tasks table (`Depends on` column). Rules: no cycles (split the tasks otherwise); a dependency scheduled in the same sprint MUST sit earlier in the same lane or in a lane with declared ordering; a dependency on another author's incomplete work is only acceptable with an agreed stub recorded in both task specs (DoR rule).

## 3. The sprint file — template

`/work/sprints/SPRINT-NN.md` MUST follow this structure exactly (sections may grow, never disappear):

```markdown
# SPRINT-07 — Metric engine computes flow metrics from simulation data

- **Dates:** 2026-11-16 → 2026-11-27 (10 working days)
- **Phase:** Phase 2 (v0.3) — Analytics & dashboards (Roadmap §6)
- **Sprint goal:** Sam's sprint dashboard shows cycle time and WIP from the
  demo-small pack, live from `main`.
- **Capacity:** 4 agents × 1 session/day × 10 d = 40 sessions raw;
  committed 31 (≤ 80%); debt reserve 4 (≤ 15% of committed).

## Lanes
| Lane | Write-set boundary | Executing role | Ordered tasks |
|---|---|---|---|
| L1 | eip-analytics | R-IE (backend) | TASK-0140 → TASK-0141 → TASK-0145 |
| L2 | /frontend | R-IE (frontend) | TASK-0142 → TASK-0146 |
| L3 | Flyway migrations (serialized) | R-IE (backend) | TASK-0139 |
| L4 | /docs + docs-lint | R-DE | TASK-0147 |

## Committed tasks
| Task | Title | Story | FR/AC | Size | Depends on | Lane | State |
|---|---|---|---|---|---|---|---|
| TASK-0139 | metric_series table + RLS | P2-E1-S1 | FR-058 | M | — | L3 | READY |
| TASK-0140 | Metric registry core | P2-E1-S1 | FR-056 | M | TASK-0139 | L1 | READY |

## Write-set conflict declaration
All lanes disjoint except: TASK-0139 (L3) merges before TASK-0140 (L1) starts.

## Debt & risk work this sprint
| Item | Register ref | Task |
|---|---|---|
| Dedup ledger retention check | DEBT-012 | TASK-0144 |

## Daily log
### Day 3 — 2026-11-18
- TASK-0140: IN_PROGRESS → IN_REVIEW (PR #88, G1–G7 green, G8 pending)
- TASK-0142: BLOCKED — metric API contract unclear; escalation filed to R-BA
- Replanning: none

## Replan log
| Date | Change | Reason | Decider |
|---|---|---|---|

## Exit (filled at review)
DONE: … · Carried over: … · Parked: … · Review record: see Review section.
```

## 4. Daily status updates

- Every engineering agent MUST append its task's state transitions, PR links, and blockers to the sprint file's daily log **at session end** — the same moment handoff notes are written. No separate status channel exists; the sprint file is the status.
- R-TPM MUST reconcile the daily log against the task files once per working day. **Task files are the truth; the sprint log is the aggregate.** A discrepancy is a status defect R-TPM fixes immediately — status truth is R-TPM's mission.
- Status vocabulary is the state machine of [./DevelopmentLifecycle.md](./DevelopmentLifecycle.md) §3 — no "90% done", no "almost". A task is in exactly one state.
- Any BLOCKED entry MUST name the escalation target and its SLA clock ([./DevelopmentLifecycle.md](./DevelopmentLifecycle.md) §5).

## 5. Mid-sprint replanning

The plan changes only through the replan log — silent drift is prohibited. R-TPM decides; R-PO joins when scope is affected; the owning architect joins when the change touches their domain.

| Trigger | Allowed responses |
|---|---|
| Task BLOCKED > 1 session with escalation pending | Park the task, pull the next READY task of equal-or-smaller size into the lane |
| Task discovered bigger than M mid-flight | Stop; split via R-TPM (remainder returns to INTAKE); never push a >M task through a session |
| Gate failure loop (2+ G8 returns on one PR) | Owning architect joins review; task MAY be parked pending a design decision |
| New S1 defect (e.g., isolation test failure — never quarantined, per [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §13) | Preempts committed work; equal-size committed task is parked to compensate |
| Dependency slipped (upstream task parked/blocked) | Re-order lanes or park dependents; never start a dependent on an unmerged assumption |
| Capacity loss (agent unavailable) | Reduce committed scope; record removals in the replan log |

Hard rules:

- Additions require an equal-size removal — sprint capacity never inflates mid-sprint (mirror of the phase scope-control rule, [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §11).
- Removed/parked tasks keep their files and re-enter through planning; nothing silently vanishes.
- The sprint goal MAY only be changed by R-TPM + R-PO together, recorded in the replan log; a changed goal is automatically a retro topic.
- Replanning MUST NOT be used to dodge a gate: a task parked while IN_REVIEW keeps its PR open or closes it explicitly with a handoff note.

## 6. Sprint invariants (recap)

- [ ] Single-writer: one agent per CLAIMED task; disjoint write-sets across parallel lanes or declared ordering.
- [ ] Only READY (G0) tasks consume committed capacity.
- [ ] ≤ 80% commitment; ≤ 15% of committed capacity to DEBT paydown.
- [ ] Sprint file updated daily; task files remain the source of truth.
- [ ] Every plan change goes through the replan log with a decider.
- [ ] Sprint ends with the review protocol in [./SprintReviewGuide.md](./SprintReviewGuide.md) — no review, no sprint close.

## Related documents

- [./DevelopmentLifecycle.md](./DevelopmentLifecycle.md) — task states, escalation SLAs, per-task flow
- [./SprintReviewGuide.md](./SprintReviewGuide.md) — Day 10: demo, DoD audit, metrics, retro
- [./DefinitionOfReady.md](./DefinitionOfReady.md) — what READY means (G0)
- [./ModuleOwnership.md](./ModuleOwnership.md) — lane/write-set boundaries and owning roles
- [./TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md), [./RiskManagementPolicy.md](./RiskManagementPolicy.md) — the registers feeding planning
- [./AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) — why the sprint file is the only status channel
- [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) — story tables (§5–§10), scope control and burnup (§11)
- [../docs/product/Roadmap.md](../docs/product/Roadmap.md) — phase/version mapping and phase gate process (§2)
