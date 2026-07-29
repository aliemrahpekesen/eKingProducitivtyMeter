# Definition of Ready (DoR) — Gate G0

This document is the complete G0 checklist: the conditions a task MUST satisfy before R-TPM moves it INTAKE → READY, and therefore before any engineering agent may claim it. R-TPM owns G0 and applies this checklist to every task in `/work/tasks/TASK-NNNN.md`; every item is verifiable from the task file alone — if verifying an item requires asking the task author, the item fails. A task failing any item stays INTAKE (G0 has no waiver, per [QualityGatePolicy.md](./QualityGatePolicy.md) §4). This gate exists so that one AI session ≈ one task claim can succeed without mid-task archaeology.

## 1. The G0 checklist

Each item states what R-TPM checks and how.

### Traceability
- [ ] **Story reference present.** The task cites its `P<phase>-E<epic>-S<story>` story from [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md). *Verify:* the story ID exists in that document and belongs to the current or an approved future phase.
- [ ] **FR/AC references present.** At least one FR-xxx from [../docs/product/PRD.md](../docs/product/PRD.md) §5, plus every applicable AC-xxx from [../docs/product/AcceptanceCriteria.md](../docs/product/AcceptanceCriteria.md). *Verify:* each ID resolves; the FR's phase matches the sprint's phase.
- [ ] **Change class(es) declared** (CC-1..7 per [QualityGatePolicy.md](./QualityGatePolicy.md) §3). *Verify:* the declared class is consistent with the write-set (e.g., a Flyway file in the write-set forces CC-4).

### Specification
- [ ] **Problem statement is one paragraph and decision-free.** It states what is wrong/missing, not how to fix it (the approach belongs to the assigned role within documented constraints). *Verify:* no unresolved design questions remain in the text.
- [ ] **Acceptance criteria are testable.** Each task-level AC is written so a test can pass or fail it — concrete inputs, observable outputs, no "should work correctly". *Verify:* for each AC, R-TPM can name the layer that would test it (unit / integration / contract / E2E per [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §1).
- [ ] **Test plan present.** Names the suites/commands to be extended or run (e.g., `./gradlew goldenTest`, kit K1–K7 for connector work). *Verify:* commands exist in TestingStrategy §17.
- [ ] **Observability plan present, or "none — justified".** New endpoints/consumers/jobs require the G6 deltas named up front. *Verify:* consistency with the write-set.
- [ ] **Docs impact declared.** Which `/docs` files change, or explicitly "none". *Verify:* consistency with the FR refs (behavior change ⇒ docs impact).
- [ ] **Out-of-scope stated.** At least one explicit exclusion, so the session doesn't scope-creep. *Verify:* present and non-empty.

### Executability
- [ ] **Write-set declared** — files/modules the task will modify. *Verify:* disjoint from every other task scheduled in parallel in `/work/sprints/SPRINT-NN.md`, or an explicit ordering is declared there (single-writer rule).
- [ ] **Assigned role matches module ownership** per [ModuleOwnership.md](./ModuleOwnership.md). *Verify:* the write-set's owning role is the assignee or has delegated in the sprint plan.
- [ ] **Context pack is complete and session-sized.** Ordered reading list with paths + sections (never pasted bodies), starting from `/CLAUDE.md` and the task spec, following the per-task-type reading lists in [ContextManagementStrategy.md](./ContextManagementStrategy.md). *Verify:* every path resolves; total volume fits a single session.
- [ ] **Dependencies resolved.** Every listed `TASK-NNNN` dependency is DONE, or a stub/interface is in place and named. *Verify:* dependency states in `/work/tasks/`.
- [ ] **Size is S or M.** S ≤ ½ session, M ≤ 1 session. L MUST be split before READY. *Verify:* R-TPM's judgment against the write-set and test plan; when in doubt, split.

## 2. DoR additions for special task types

| Task type | Additional G0 requirement | Recorded where |
|---|---|---|
| **CC-1** (contract-anchor) | R-CA pre-approval of the *approach* before READY — not the full G4 review, but a recorded "this direction is sound" note; ADR drafted if decision-level | Escalation-record block in the task file, with R-CA + date |
| **CC-4** (schema/migration) | R-DBA pre-consult: intended table/index/RLS shape and expand–contract plan reviewed before READY | Pre-consult note in the task file, with R-DBA + date |
| **CC-2** (security-relevant) | [SecurityChecklist.md](./SecurityChecklist.md) added to the context pack; R-SA notified at READY so the G3 manual review is not a merge-time surprise | Context pack + task file note |
| **CC-5** (AI-behavior) | Eval impact declared: which eval-harness cases/golden prompts change; `scripts/run-eval-harness --fake` in the test plan (TestingStrategy §8, §17) | Test plan section |
| Connector tasks | The connector's catalog entry in [../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md) in the context pack; kit K1–K7 in the test plan | Context pack + test plan |
| Bug-fix tasks | Reproduction steps in the problem statement; test plan names the failing-first regression test (commit-before-fix rule, [QualityGatePolicy.md](./QualityGatePolicy.md) §5) | Problem statement + test plan |
| Refactoring tasks | Registered per [RefactoringPolicy.md](./RefactoringPolicy.md); behavior-preservation evidence plan (tests green before/after, zero contract diff) | Test plan section |

## 3. Worked example

### READY: TASK-0231 — Compute scope churn metric (passes G0)

```
id: TASK-0231 · sprint: SPRINT-07 · story: P2-E1-S2
FR/AC: FR-050, FR-056, FR-060 · AC-041 · change class: CC-3
module(s)/write-set: eip-analytics (metric engine formula pkg + registry),
  /simulation/golden/flow/scope-churn, /docs (metric definition)
assigned role: R-IE (backend)
problem: Scope churn is in the canonical flow set (FR-050) but not computed;
  sprint dashboards cannot show churn (AC-041).
acceptance criteria:
  1. Given the AC-041 fixture (20 pts committed, 5 added, 3 removed),
     churn = (5+3)/20 = 40%.
  2. Golden case flow/scope-churn (3 changed / 10 committed → 30%) passes.
  3. Metric definition complete per FR-056 and served via the definition API.
  4. Partial inputs set the FR-060 uncertainty indicator.
test plan: ./gradlew goldenTest + unit tests on formula pkg; EXPLAIN for the
  new sprint-composition query (G5, CC-3).
observability plan: eip_metric_compute_* metrics already cover the engine; no
  new endpoint/consumer/job — none beyond existing, justified.
docs impact: metric definition page. out-of-scope: dashboard widget (TASK-0232…).
dependencies: TASK-0198 (sprint normalization) — DONE. size: M.
```
*Why it passes:* every ID resolves; ACs carry numbers a test can assert; write-set is single-module and disjoint in SPRINT-07; context pack (omitted above) lists paths+sections only; CC-3 declared so G5 is expected, not discovered.

### NOT READY: TASK-0232 — "Improve dashboard performance" (stays INTAKE)

| Problem | Violated item |
|---|---|
| No story ref, no FR/AC IDs ("dashboards feel slow") | Traceability — untestable against NFR-010 unless cited |
| AC: "dashboards should load fast" | Not testable — no budget, no fixture, no layer |
| No write-set ("wherever the slowness is") | Single-writer rule unenforceable; ownership unknown |
| No change class; likely CC-3 but undeclared | G5 would surface at merge time instead of planning time |
| Size L ("investigate + fix + verify at NFR-002 volumes") | Must split: measure task (S) → targeted fix tasks (M) |

*R-TPM action:* return to INTAKE with a note; spawn a measurement task citing NFR-010/NFR-002 with a Gatling-based test plan, then fix tasks per finding.

## 4. Common G0 failure modes

R-TPM SHOULD screen for these recurring anti-patterns before running the full checklist:

| Anti-pattern | Why it fails G0 |
|---|---|
| "Implement FR-050" as the whole spec | An FR is a requirement family, not a task; ACs and write-set are underivable |
| Context pack pastes document bodies | Violates cite-paths-only; bloats the session; drifts from L1 canon |
| Acceptance criteria restate the problem ("churn is computed correctly") | Not testable; the golden/fixture numbers are the AC |
| Two parallel tasks both list `eip-analytics` registry files | Single-writer violation; sequence them or merge the tasks |
| Dependency "TASK-0198 (in progress, should finish soon)" | Dependencies MUST be DONE or stubbed at READY, not forecast |
| CC-4 task with no R-DBA pre-consult note | Special-type DoR item (§2) missing; schema shape decided mid-session |

## 5. Recording the verdict

R-TPM records the outcome in the task file's `DoR check` field: the checklist result, date, and any special-type pre-consult references. Only then may the task move to READY and be claimed. If a claimed task turns out to violate DoR in practice (e.g., context pack insufficient), the session writes a handoff note, the task returns to INTAKE, and the DoR failure is raised in the sprint retro as a G0 escaped defect.

## Related documents

- [QualityGatePolicy.md](./QualityGatePolicy.md) — G0's place among the gates; waiver rules (none for G0)
- [DefinitionOfDone.md](./DefinitionOfDone.md) — the other end of the task lifecycle
- [SprintExecutionGuide.md](./SprintExecutionGuide.md) — sprint planning, single-writer scheduling
- [ContextManagementStrategy.md](./ContextManagementStrategy.md) — context-pack construction rules
- [ModuleOwnership.md](./ModuleOwnership.md) — write-set → owning role mapping
- [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) — story catalog (source of record)
- [../docs/product/PRD.md](../docs/product/PRD.md) / [../docs/product/AcceptanceCriteria.md](../docs/product/AcceptanceCriteria.md) — FR/AC IDs (source of record)
