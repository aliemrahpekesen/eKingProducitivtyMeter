# Technical Debt Policy

This document defines what counts as technical debt in the EIP program, how it is registered, classified, prioritized, paid down, and escalated. It is read by every engineering agent at the moment they are about to take a shortcut, by R-CR when a review finding is downgraded to MINOR, by R-TPM when planning sprint capacity, and by R-CA when debt ages past its escalation threshold. The register is `/work/debt-register.md` (entry template per [AgentCommunicationProtocol §3.8](./AgentCommunicationProtocol.md); location per [RepositoryStructure §4](./RepositoryStructure.md)) and it is the only place debt legitimately exists.

## 1. The register-or-fix rule

Every deliberate deviation from the standards in this operating system MUST be either fixed before merge or registered as a `DEBT-NNN` entry **in the same PR** that introduces it. There is no third state. An unregistered shortcut discovered later is treated as an escaped defect in the sprint review metrics ([SprintReviewGuide §4](./SprintReviewGuide.md)), attributed to the originating TASK, and registered retroactively with its origin marked `retro-discovered`.

Consequences that make the rule enforceable:

- A PR whose description declares an accepted shortcut without a DEBT id fails G8 (DoD core requires "DEBT filed for accepted shortcuts", [DefinitionOfDone.md](./DefinitionOfDone.md)).
- R-CR verdict scale ([CodeReviewChecklist.md](./CodeReviewChecklist.md)): a **MINOR** finding is "fix now or file DEBT" — the author chooses, but choosing DEBT means the entry exists before merge. A **MAJOR** finding may only be waived "with justification recorded"; a waived MAJOR MUST produce a DEBT entry naming the waiving authority.
- A waived or partially-passed gate at release (PASS-WITH-WAIVER in [ReleaseManagement §3](./ReleaseManagement.md)) MUST produce a DEBT entry (or RISK entry, per [RiskManagementPolicy §2](./RiskManagementPolicy.md)) before the go/no-go record closes.

## 2. What is NOT debt

These MUST NOT be filed in the debt register — they follow different processes, now:

| Not debt | It is | Process |
|---|---|---|
| A bug (behavior contradicts /docs or an AC) | Defect | Fix now; regression test lands before the fix commit ([DevelopmentLifecycle §7](./DevelopmentLifecycle.md)) |
| A missing or failing acceptance criterion on a task | Incomplete task | Task is not DONE; DoD is not negotiable via the register |
| A red or skipped quality gate on a PR | Gate failure | Fix or use the gate's own waiver path with authority recorded |
| A security finding (G3/RG2) | Security defect | R-SA process; only R-SA may reclassify to `security-hardening` debt |
| A missing test for shipped behavior required by TestingChecklist | Defect in DoD | Fix now — test debt (§3) covers *depth*, never *absence of required coverage* |
| An unresolved PRD §11 open question | Standing risk | [RiskManagementPolicy §5](./RiskManagementPolicy.md) |

Rule of thumb: debt is a **conscious, working, registered compromise in how something is built**. Anything broken, missing, or unknown is a defect or a risk.

## 3. Classification

Every DEBT entry carries exactly one primary class:

| Class | Meaning | Typical origin | Default paydown owner |
|---|---|---|---|
| `design` | Structure diverges from the target described in /docs or an ADR (e.g., a normalizer bypassing the module API, duplicated logic pending extraction) | timeboxed implementation | owning domain architect assigns; executed per [RefactoringPolicy](./RefactoringPolicy.md) |
| `test` | Coverage depth below TestingStrategy intent (e.g., contract test stubbed against fixtures instead of Testcontainers, missing negative cases) | schedule pressure at G2 | R-TE |
| `docs` | /docs or MODULE.md lag the accepted implementation nuance (never a silent contradiction — that violates the docs-first law, [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) §5) | G7 light-pass with recorded waiver | R-DE |
| `performance` | Known inefficiency inside NFR budgets today but with a growth trajectory (e.g., missing index acceptable at Phase-1 volumes, N+1 on an admin path) | G5 review notes | R-PE triages; R-IE executes |
| `security-hardening` | Defense-in-depth improvement beyond current SecurityChecklist requirements — only R-SA may file or accept this class | R-SA review notes | R-SA schedules |

## 4. DEBT entry template

Fields per [AgentCommunicationProtocol §3.8](./AgentCommunicationProtocol.md), in `/work/debt-register.md`, one table row per entry:

`DEBT-NNN · origin (TASK/PR) · description · risk if unpaid · size · target phase · owner`

Rules: `NNN` is zero-padded, monotonic, never reused. `description` names the class from §3 as its first word. `risk if unpaid` MUST be concrete (what breaks, when, at what scale — "code is ugly" is not a risk). `size` uses task sizing S/M/L (task-spec template, [AgentCommunicationProtocol §3.1](./AgentCommunicationProtocol.md)); an L debt MUST be split into staged tasks when scheduled. `target phase` is the phase by which paydown is committed; "never" is not a value — permanent acceptance requires the §6 escalation outcome. `owner` is a role id (R-XX).

Example row:

`DEBT-007 · TASK-0142/PR#188 · test: DLQ replay covered only by fixture-level test, no Testcontainers path · replay regression could pass CI and corrupt consumer offsets at a customer (OperationsGuide §3.1 path) · M · Phase 2 · R-TE`

Register file hygiene: `/work/debt-register.md` keeps two sections — **Open** (the working table, sorted by §5 score descending) and **Closed** (moved on closure with outcome appended: `paid by TASK-NNNN` / `accepted via ADR-NNN` / `reclassified as RISK-NNN or defect TASK-NNNN`). Rows are never deleted; the register is L3 work state (context layers per [ContextManagementStrategy.md](./ContextManagementStrategy.md)) and its history is evidence for sprint-review metrics.

### 4.1 Intake checklist (author files the entry, R-CR verifies at G8)

- [ ] Entry is genuinely debt per §2 (not a defect, not a gate dodge, not a risk)
- [ ] Exactly one §3 class named as the first word of the description
- [ ] `origin` cites the TASK and PR; the PR description cites the DEBT id back
- [ ] `risk if unpaid` states a concrete failure mode with a /docs or NFR reference where applicable
- [ ] `target phase` is at most 2 phases out (further = it will hit §6 escalation by construction — pick honestly)
- [ ] `owner` role accepted the entry (a filed-but-unowned entry blocks the PR)
- [ ] `security-hardening` class only if R-SA filed or countersigned it

## 5. Prioritization and capacity

- **Risk-weighted scoring:** at intake, R-TPM scores each entry likelihood(1–5) × impact(1–5) using the same scales as [RiskManagementPolicy §3](./RiskManagementPolicy.md) — likelihood that the risk-if-unpaid materializes before the target phase, impact if it does. The score orders the paydown queue; ties break toward entries on contract anchors or hot paths (CC-1/CC-3 territory).
- A debt entry scoring **≥ 15** is not debt anymore — it is promoted to a RISK entry with an active mitigation task, per RiskManagementPolicy §3.
- **Capacity reservation:** R-TPM MUST reserve **≤ 15% and normally ≥ 10%** of each sprint's capacity for debt paydown (the 15% ceiling is law 6 of [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) §5; sprints that skip paydown entirely require a recorded R-TPM justification in the sprint plan). Paydown work items are ordinary `TASK-NNNN` tasks citing the DEBT id, pass G0 DoR like any task, and count toward the reservation only when the debt entry is closed by them.
- Debt whose paydown is a structural refactor follows [RefactoringPolicy §4](./RefactoringPolicy.md) (own task, architect approval, behavior-preservation evidence).

Worked scoring example for DEBT-007 above: likelihood 3 (DLQ replay changes are planned in Phase 2 connector work, so the untested path will be exercised), impact 4 (offset corruption at a customer is an operational incident against a contract-anchor pipeline) → score 12 → top of the monitored band, scheduled within its target phase. Had it scored 16, it would leave this register for `/work/risk-register.md` with a mitigation task in the next sprint.

Scheduling rules for paydown tasks:

- Highest-score entries first; within a score, entries whose target phase is the current phase pre-empt others
- A paydown task MUST cite its DEBT id in the task spec and close the register row in the same PR that completes the work (register update is part of the write-set)
- Paydown that grows beyond its `size` estimate mid-task is re-split per the task sizing rule ([AgentCommunicationProtocol §3.1](./AgentCommunicationProtocol.md); L = must split), not silently extended

## 6. Aging and escalation

- Every entry's age is measured in **phases** from its origin phase. An entry **older than 2 phases escalates to R-CA** (law 6 of [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) §5) at the next sprint review.
- R-CA MUST resolve the escalation to exactly one outcome, recorded in the register row: (a) **schedule** — a TASK is created in the next sprint plan, bypassing the priority queue; (b) **accept permanently** — requires an ADR documenting the accepted divergence (the register row then closes with the ADR reference); or (c) **reclassify** — the entry was actually a defect or risk; it moves to the correct process.
- Debt attached to a contract anchor (anchor list: [QualityGatePolicy §2](./QualityGatePolicy.md), G4) MUST NOT be accepted permanently by anyone below R-CA + human owner.

## 7. Debt review in sprint review

Per [SprintReviewGuide.md](./SprintReviewGuide.md), every sprint review MUST cover:

- [ ] **Debt delta:** entries opened vs. closed this sprint (a persistently positive delta over 3 sprints triggers an R-TPM capacity correction in the next sprint plan)
- [ ] New entries sanity-checked against §2 (no defects hiding in the register)
- [ ] Aging report: entries ≥ 1 phase old listed; > 2 phases escalated to R-CA (§6)
- [ ] Scores ≥ 15 confirmed promoted to the risk register
- [ ] The 15% reservation: was it used, and did closed entries justify it

The DoD audit sample (random 20% of DONE tasks, [SprintReviewGuide §3](./SprintReviewGuide.md)) additionally checks that shortcut-shaped diffs have matching DEBT entries — the enforcement teeth of §1.

## Related documents

- [RiskManagementPolicy.md](./RiskManagementPolicy.md) — scoring scales, promotion of high-score debt, acceptance authority
- [RefactoringPolicy.md](./RefactoringPolicy.md) — how design debt is paid down safely
- [CodeReviewChecklist.md](./CodeReviewChecklist.md) & [QualityGatePolicy.md](./QualityGatePolicy.md) — MINOR/MAJOR verdicts and gate waivers that feed intake
- [DefinitionOfDone.md](./DefinitionOfDone.md) — "DEBT filed for accepted shortcuts" checklist item
- [SprintReviewGuide.md](./SprintReviewGuide.md) — debt delta metric and review agenda
- [ReleaseManagement.md](./ReleaseManagement.md) — release-gate waivers producing DEBT entries
- [ADRProcess.md](./ADRProcess.md) — permanent acceptance of aged debt
