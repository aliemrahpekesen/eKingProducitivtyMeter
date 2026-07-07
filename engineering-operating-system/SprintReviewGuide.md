# Sprint Review Guide

This document defines the sprint review held on Day 10 of every sprint: the demo against phase exit criteria, the DoD audit, the sprint metrics review, and the retro whose outputs become TASK/DEBT/RISK entries. R-TPM chairs; R-PO, R-CA, R-QAA, and R-CR attend as verdict-holders; every role that executed sprint work attends for its items. The review's outputs are written into the sprint file's Review/Exit section and the `/work` registers the same day — an unrecorded review did not happen.

## 1. Agenda (timeboxed, in order)

| # | Segment | Timebox | Led by | Output |
|---|---|---|---|---|
| 1 | Demo against phase exit criteria | 30 min | R-TPM (drives), R-PO (accepts) | Accepted/rejected demo items; PRD §10 checklist deltas |
| 2 | DoD audit report | 15 min | R-QAA | Audit verdicts; remediation TASKs |
| 3 | Sprint metrics review | 15 min | R-TPM | Metric table in sprint file; threshold breaches → retro topics |
| 4 | Debt & risk register review | 15 min | R-TPM + R-CA | Register updates; mandatory mitigation TASKs |
| 5 | Retro | 30 min | R-TPM | Only TASK/DEBT/RISK entries — see §5 |

## 2. Demo protocol

- The demo MUST run **from `main` on a clean environment** — the Docker Compose stack with simulation packs ([../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §13: `demo-small` for speed, `enterprise-large` when performance is the story). Slides are not demos; local branches are not demos. This is the reality check that cannot be faked ([../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §11).
- Every demo item MUST map to the P-E-S stories and AC IDs it advances, and to a line of the current phase's release-criteria checklist in [../docs/product/PRD.md](../docs/product/PRD.md) §10. The sprint file records which PRD §10 boxes moved from "not started" to "demonstrated".
- Demos are **vertical slices through the UI** where the phase plan demands it ([../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §4): real auth, real data path, real audit — thin is fine, fake is not. Simulation-first: never depend on external credentials ([../docs/product/Roadmap.md](../docs/product/Roadmap.md) §2).
- R-PO accepts or rejects each item against its cited ACs. A rejected item's task is not DONE — whatever its checklist claims — and returns via R-TPM as remediation work.
- Feature-flagged work is demoed honestly: flags toggled live, incompleteness stated.

## 3. DoD audit — 20% sample

The audit verifies that DONE means done, catching checklist theater before it compounds.

- **Sample:** a random 20% of the tasks that reached DONE this sprint, minimum 2 (all of them if fewer than 2 exist). Selection MUST be deterministic and cherry-pick-proof: order DONE task IDs ascending, seed a PRNG with the sprint number, record the selected IDs in the sprint file.
- **Auditor:** R-QAA leads. The auditor MUST NOT audit a task it implemented; R-CR MAY assist but does not audit PRs it approved alone — pair with R-QAA on those.
- **Per-task audit checklist:**
  - [ ] Every applicable gate (per declared change class) has linked, green CI evidence — claims without CI links fail (CI-is-truth law).
  - [ ] Change class was declared correctly (spot-check the diff against CC-1…CC-7 definitions in [./QualityGatePolicy.md](./QualityGatePolicy.md)).
  - [ ] Acceptance criteria have demonstration evidence (demo, test, or recorded run) — not just assertions.
  - [ ] Tests exist, run in CI, and cover the task's test plan; bug-fix tasks show the regression-test-before-fix commit order.
  - [ ] Observability delta present or "none — justified" ([./ObservabilityRequirements.md](./ObservabilityRequirements.md)).
  - [ ] `/docs` updated in the same PR where behavior changed; docs-lint green.
  - [ ] Shortcuts have DEBT entries; task file and handoffs are consistent with what merged.
- **Outcomes:** PASS, or FAIL with a remediation TASK created the same day (INTAKE) and the finding counted as an **escaped defect** in §4. Two or more FAILs in the sample widen the audit to 100% of the sprint's DONE tasks and make gate discipline a mandatory retro topic.

## 4. Sprint metrics

R-TPM computes and records this exact set every sprint (the canonical set; add, never remove):

| Metric | Definition | Source | Action threshold |
|---|---|---|---|
| Gate pass rate | Per gate G1–G8: % of executions green on first attempt | CI runs + PR review records | Any gate < 85% first-pass → retro topic naming the gate |
| Escaped defects | Bugs found post-merge attributable to this or earlier sprints, + DoD audit FAILs | `fix/` TASKs with origin refs; §3 findings | > 0 → each gets root-cause + regression test; recurring origin module → owning architect action |
| Coverage trend | Coverage delta vs. the ratchet | CI coverage gates ([../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §1, §14) | Ratchet blocks decreases by construction; flat coverage with rising line count 2 sprints running → retro topic |
| PR cycle time | Claim → merge, p50 and p90 | Task files + git history | p90 > 5 working days breaches branch-lifetime policy → split-tasks retro topic |
| Docs-lint violations | Violations on `main` during the sprint | docs-lint CI job (per [../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) §5.2) | MUST be 0 at review; any non-zero → immediate fix TASK |
| Debt delta | DEBT opened − DEBT closed, plus aging profile | `/work/debt-register.md` | Positive delta 3 sprints running → R-CA review; any item > 2 phases old → escalate to R-CA (register-or-fix policy) |

Monthly (every second review), add the leading indicators from [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §11: CI duration vs. the 20-minute PR budget ([../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §14), flaky-test count and quarantine age (§15), and feature-flag debt count ([./DevelopmentLifecycle.md](./DevelopmentLifecycle.md) §6).

## 5. Retro protocol

Timebox 30 minutes. Inputs: the metric table, threshold breaches, blocker/escalation records from task files, DoD-audit findings, and the replan log. Format: each attendee contributes at most one item per column — *kept us fast* / *slowed us down* / *change proposal*.

**The output rule is absolute: a retro outcome exists only as a TASK, DEBT, or RISK entry, filed before the retro closes.** Free-floating action items, "let's try to…", and unowned intentions are prohibited — anything not written down is lost by design.

| Retro outcome type | Becomes | Where | Owner |
|---|---|---|---|
| Process/tooling change, doc fix, automation | `TASK-NNNN` (INTAKE; R-TPM sequences it) | `/work/tasks/` | Proposing role |
| Accepted shortcoming with a payoff plan | `DEBT-NNN` (origin · description · risk if unpaid · size · target phase · owner) | `/work/debt-register.md` | Owning role |
| Uncertainty or threat to track | `RISK-NNN` (description · likelihood 1–5 · impact 1–5 · owner role · mitigation · trigger/review date) | `/work/risk-register.md` | Owner role |

Register review (segment 4 feeds this): R-TPM + R-CA review the risk register every sprint; any risk with likelihood×impact ≥ 15 MUST have an active mitigation task in the next sprint plan. PRD open questions ([../docs/product/PRD.md](../docs/product/PRD.md) §11) are standing risks until resolved by their target phase; the register was seeded from [../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) §4 and PRD §11.

## 6. Phase-boundary reviews

When the sprint is the last of a phase, the review extends into the phase gate ([../docs/product/Roadmap.md](../docs/product/Roadmap.md) §2), owned by R-RM:

- [ ] **RG1** — phase exit criteria ([../docs/product/PRD.md](../docs/product/PRD.md) §10) demonstrably met; the phase's full demo script ([../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §5–§10) runs live from `main` on a clean environment — air-gapped from Phase 3 onward.
- [ ] **RG2** — security certification checklist + NFR-071 anti-surveillance review; R-SA holds the A4 verdict.
- [ ] **RG3** — performance at phase scale targets; R-PE holds the A4 verdict.
- [ ] **RG4** — operability: backup/restore or drill relevant to the phase; docs/runbooks current.
- [ ] **L4 human checkpoint** ([./AIValidationWorkflow.md](./AIValidationWorkflow.md)): the human repository owner signs the phase exit; the version is cut per the release train (Phase N → v0.N, Phase 5 → v1.0, [../docs/product/Roadmap.md](../docs/product/Roadmap.md) §10) via [./ReleaseManagement.md](./ReleaseManagement.md).
- [ ] The phase retro additionally seeds the next phase's risk list ([../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §11) — same TASK/DEBT/RISK output rule.

A phase gate that fails does not slip silently: the failing criteria become named TASKs, and the phase's next sprint plans against them first.

## 7. Review record template

The review's durable output is a `## Review` section appended to `/work/sprints/SPRINT-NN.md` in exactly this shape (two independent chairs following it MUST produce comparable records):

```markdown
## Review — 2026-11-27

### Demo
| Item | Story / AC | PRD §10 line advanced | Verdict (R-PO) |
|---|---|---|---|
| Sprint dashboard live from webhook | P2-E2-S2 / AC-049 | Phase 2, dashboards line | ACCEPTED |

### DoD audit
Sample (seed = 07): TASK-0140, TASK-0142 of 9 DONE (2/9 ≥ 20%).
| Task | Verdict | Finding | Remediation |
|---|---|---|---|
| TASK-0142 | FAIL | observability delta missing | TASK-0151 filed; counted as escaped defect |

### Metrics
| Metric | Value | Threshold breach? |
|---|---|---|
| Gate pass rate (G2) | 78% first-pass | yes → retro topic |
| Escaped defects | 1 | root-caused, regression test in TASK-0151 |
| Coverage trend | +0.4 pt | no |
| PR cycle time | p50 1.2 d / p90 4 d | no |
| Docs-lint violations | 0 | no |
| Debt delta | +1 (DEBT-014 opened, none closed) | watch |

### Retro outputs (all filed)
| Item | Filed as | Owner |
|---|---|---|
| G2 flakiness in Kafka tests | TASK-0152 | R-QAA |
| Metric API contract ambiguity risk | RISK-009 (L3×I4=12) | R-BA |

### Exit
DONE: 8 · Carried over: TASK-0145 (handoff written) · Parked: none.
```

## Related documents

- [./SprintExecutionGuide.md](./SprintExecutionGuide.md) — the sprint this review closes; sprint file template
- [./DevelopmentLifecycle.md](./DevelopmentLifecycle.md) — DONE/VERIFIED semantics the audit checks
- [./DefinitionOfDone.md](./DefinitionOfDone.md) — the checklist under audit
- [./QualityGatePolicy.md](./QualityGatePolicy.md) — gates and change classes referenced by the audit
- [./ReleaseManagement.md](./ReleaseManagement.md) — RG1–RG4 execution and version cuts
- [./TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md), [./RiskManagementPolicy.md](./RiskManagementPolicy.md) — where retro outputs live
- [./AIValidationWorkflow.md](./AIValidationWorkflow.md) — L4 human checkpoints at phase boundaries
- [../docs/product/PRD.md](../docs/product/PRD.md) §10–§11 — release criteria and open questions
- [../docs/product/Roadmap.md](../docs/product/Roadmap.md) §2, §10 — phase gate process and release train
- [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §11 — measuring the plan itself
