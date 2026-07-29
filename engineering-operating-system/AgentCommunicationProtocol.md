# Agent Communication Protocol

This document defines how the 21 engineering roles (R-CA … R-RM, see [AgentResponsibilities.md](./AgentResponsibilities.md)) exchange information while building the Engineering Intelligence Platform (EIP). Every engineering role — AI or human — reads this before producing or consuming any inter-role message; the [Technical Program Manager (R-TPM)](./AgentResponsibilities.md) enforces it during sprint execution. Disambiguation per [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) §0: this protocol governs communication between **engineering agents that build EIP**. It has nothing to do with the product's 18 runtime AI agents ([../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md)); their inter-agent messaging is a product feature, not an engineering practice.

## 1. Core rules

1. **All inter-role communication MUST be asynchronous and artifact-mediated.** An engineering agent MUST NOT depend on another agent's live session, chat transcript, or memory. If information is not in one of the artifacts of §2, it does not exist. This is the "repo is the shared memory" rule ([./ContextManagementStrategy.md](./ContextManagementStrategy.md) — context layer L3).
2. **Every artifact uses its canonical template** (§3). Templates are field-complete: an artifact missing a mandatory field is malformed, and the recipient MUST reject it (reviewers via a BLOCKER verdict, R-TPM by returning the task to INTAKE).
3. **Every artifact that requires a response names exactly one accountable responder by role ID** (§4) and is subject to a response SLA (§5).
4. **Artifacts reference, never restate.** Cite `/docs` paths + section and FR/AC/NFR/FEAT IDs; never paste document bodies (anti-pollution rules in [./ContextManagementStrategy.md](./ContextManagementStrategy.md) §7). The recurring documentation failure mode was drift from restating — see [../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) §5.3.
5. **Nobody edits another role's message.** Authors amend their own artifacts; responders append. R-CR comments on PRs and never pushes to them; R-TPM edits task *status* fields, not the author's handoff content.

## 2. Message artifact catalog

| Artifact | Location | Author (typical) | Accountable responder | Purpose |
|---|---|---|---|---|
| Task spec | `/work/tasks/TASK-NNNN.md` | R-TPM (input from domain architects) | assigned role (R-IE/R-TE/R-RE/…) | The work order; also the prompt payload ([./PromptEngineeringStandards.md](./PromptEngineeringStandards.md) §2) |
| PR description | PR on `feature/TASK-NNNN-slug` etc. | implementing role | R-CR (+ gate owners per change class) | Merge request with evidence |
| Review comment | on the PR | R-CR; owning architect for CC-1 | PR author | Verdict-scaled findings (§3.3) |
| ADR | `/docs/adr/ADR-NNN-slug.md` | any role (usually an architect) | R-CA (approver) | Decision of record ([./ADRProcess.md](./ADRProcess.md)) |
| Escalation record | inside the task file | blocked role | next decider in the §7 chain | Blocker + options + decision trail |
| Handoff note | `/work/handoffs/TASK-NNNN-<seq>.md` | role ending a session pre-DONE | next claimant of the task | Session-to-session continuity |
| Sprint status entry | `/work/sprints/SPRINT-NN.md` task table | task owner (state), R-TPM (plan) | R-TPM | Status truth for the sprint |
| DEBT entry | `/work/debt-register.md` | any role accepting a shortcut | owner role named in the entry | Register-or-fix rule ([./TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md)) |
| RISK entry | `/work/risk-register.md` | any role spotting a hazard | owner role named in the entry | Standing risk tracking ([./RiskManagementPolicy.md](./RiskManagementPolicy.md)) |

These nine artifacts are the **complete** legal message set. Commit messages, CI logs, and code comments carry evidence but are not addressable messages.

## 3. Canonical templates

Field lists are canonical, and **this document is the canonical field-list authority** — no other document may add, remove, or rename template fields. Copy the template, fill every field; write `none` explicitly rather than deleting a field.

### 3.1 Task spec — `/work/tasks/TASK-NNNN.md`

```markdown
# TASK-NNNN — <title>

- **Sprint:** SPRINT-NN
- **Story ref:** P<phase>-E<epic>-S<story>   (PhaseBasedImplementationPlan)
- **FR/AC refs:** FR-xxx, AC-xxx, …
- **Change class(es):** CC-n [, CC-n]
- **Module(s) / write-set:** <modules and path globs this task may write>
- **Assigned role:** R-XX
- **Size:** S | M | L        (S ≤ ½ session, M ≤ 1 session, L = must split)
- **Dependencies:** TASK-NNNN, …  (each DONE or explicitly stubbed)
- **State:** INTAKE | READY | CLAIMED | IN_PROGRESS | IN_REVIEW | MERGED | VERIFIED | DONE | BLOCKED | PARKED

## Context pack (ordered — read top to bottom, nothing else required)
1. <path> §<section> — <why>
2. …

## Problem statement
<what is wrong/missing, in ≤ 10 lines, citing docs — never restating them>

## Acceptance criteria (testable)
- [ ] <observable, mechanically checkable outcome>

## Test plan
<test layers, named suites/commands per ../docs/testing/TestingStrategy.md>

## Observability plan
<metrics/traces/logs delta per ObservabilityRequirements.md — or "none — justified: <reason>">

## Docs impact
<paths to update, or "none — justified: <reason>">

## Out of scope
<explicit exclusions>

## DoR check (G0)
- [ ] Story ref + FR/AC refs present and resolvable
- [ ] Acceptance criteria testable
- [ ] Write-set declared and disjoint from parallel tasks (or ordering declared in SPRINT-NN.md)
- [ ] Context pack fits one session
- [ ] Dependencies DONE or stubbed
- [ ] Size ≤ M

## Escalation records (append-only; see §3.5)

## Status log (append-only: date · role · state change · note)
```

### 3.2 PR description

```markdown
**Task ref:** TASK-NNNN
**Change class(es):** CC-n [, CC-n]

**What / why (≤ 10 lines):**
<summary; cite FR/AC IDs>

**Contract impact:** none | <anchor diff summary + ADR-NNN ref>
**Test evidence:** <exact commands + CI run links/results — see ./AIValidationWorkflow.md §2>
**Docs updated:** <paths> | none — justified: <reason>
**Observability delta:** <metrics/dashboards/alerts touched> | none — justified: <reason>
**Rollback note:** <how to revert safely; migration expand–contract stage if CC-4>
```

### 3.3 Review comment (R-CR, and architects on CC-1)

```markdown
**[BLOCKER | MAJOR | MINOR | NIT]** <file:line or PR section>
**Requirement:** <doc §/ID the diff violates, e.g. ../docs/engineering/EventModel.md §10, FR-016>
**Problem:** <what is wrong>
**Required action:** <mandatory for BLOCKER/MAJOR; suggestion for MINOR/NIT>
```

Verdict semantics ([./CodeReviewChecklist.md](./CodeReviewChecklist.md)): **BLOCKER** = must fix, gate G8 fails · **MAJOR** = fix, or waive with justification recorded in the PR · **MINOR** = fix now or file a DEBT entry · **NIT** = author's discretion. The review MUST end with a summary verdict: `APPROVE` or `REQUEST-CHANGES (<n> BLOCKER, <n> MAJOR, …)`.

### 3.4 ADR — `/docs/adr/ADR-NNN-slug.md`

```markdown
# ADR-NNN — <title>

- **Status:** Proposed | Accepted | Superseded (by ADR-NNN)
- **Approver:** R-CA — <date>
- **Affected anchors/modules:** <contract anchors and modules touched>

## Context
## Decision
## Consequences
## Alternatives rejected
```

ADR-001…014 are backfilled from [../docs/architecture/ArchitectureOverview.md](../docs/architecture/ArchitectureOverview.md) §7, which remains the index.

### 3.5 Escalation record (appended inside the task file)

```markdown
### Escalation <date> — raised by R-XX
- **Blocker:** <what stops progress>
- **Options considered:** 1) … 2) … 3) …
- **Recommendation:** <raiser's preferred option + why>
- **Decision:** <chosen option> — **decider:** R-XX / human owner — **date:** <date>
```

### 3.6 Handoff note — `/work/handoffs/TASK-NNNN-<seq>.md`

```markdown
# Handoff TASK-NNNN-<seq>

- **Task ref:** TASK-NNNN — **state reached:** <lifecycle state>
- **Commits / branch:** <branch name, last commit SHA>

## What works / what doesn't (and how it was verified)
## Next 3 concrete steps
1. …  2. …  3. …
## Open questions
## Files touched
## Gotchas
```

### 3.7 Sprint status entry (one row per task in `/work/sprints/SPRINT-NN.md`)

```markdown
| TASK-NNNN | <title> | R-XX | <state> | <blocker or —> | <gates pending> | <next action> | <updated> |
```

### 3.8 DEBT entry (one row in `/work/debt-register.md`)

```markdown
| DEBT-NNN | <origin TASK/PR> | <description> | <risk if unpaid> | S/M/L | <target phase> | R-XX |
```

### 3.9 RISK entry (one row in `/work/risk-register.md`)

```markdown
| RISK-NNN | <description> | <likelihood 1-5> | <impact 1-5> | R-XX | <mitigation> | <trigger/review date> |
```

The initial register is seeded from [../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) §4 and [../docs/product/PRD.md](../docs/product/PRD.md) §11 open questions.

## 4. Addressing

- Every artifact that requires an action MUST name its target with a **role ID** (`R-XX`), never a session, model, or personal name. Role IDs resolve via [AIAgentCatalog.md](./AIAgentCatalog.md).
- Task specs address via `Assigned role`; PRs address R-CR implicitly plus the conditional gate owners implied by the declared change class(es) (CC-1 → owning architect + R-CA; CC-2 → R-SA; CC-3 → R-PE; CC-4 → R-DBA; CC-5 → R-AIA per [./QualityGatePolicy.md](./QualityGatePolicy.md)).
- A request awaiting a specific role MUST be visible in the sprint status entry's blocker column as `AWAITING R-XX since <date>` so R-TPM can police SLAs.
- Broadcast is prohibited: an artifact addressed to "everyone" has no accountable responder and MUST be rewritten. Information intended for all future agents belongs in context layer L1/L2 ([./ContextManagementStrategy.md](./ContextManagementStrategy.md)), not in a message.

## 5. Response SLAs (measured in sessions)

One session ≈ one task claim (session convention per [DevelopmentLifecycle.md](./DevelopmentLifecycle.md) §4); for scheduling purposes 1 session ≈ ½ working day. R-TPM audits SLA breaches at each sprint status update; a breached SLA auto-escalates one level up the §7 chain.

| Request | Responder | SLA |
|---|---|---|
| PR opened (task IN_REVIEW) | R-CR | verdict within **1 session** |
| CC-1 architect approval | owning architect + R-CA | within **2 sessions** |
| CC-2 / CC-4 / CC-5 sign-off | R-SA / R-DBA / R-AIA | within **2 sessions** |
| Escalation record awaiting decision | decider at current chain level | within **1 session**, else it moves up one level automatically |
| Handoff note on an unclaimed task | R-TPM (reassign or park) | within **2 sessions** |
| ADR in Proposed status | R-CA | accept/reject within **1 sprint** |
| DEBT / RISK entry filed | R-TPM (triage into sprint planning) | next sprint planning |
| BLOCKED task | R-TPM | unblock plan or PARKED decision within **2 sessions** |

## 6. Single-writer rule enforcement

The single-writer rule ([DevelopmentLifecycle.md](./DevelopmentLifecycle.md)): a task is CLAIMED by exactly one engineering agent, and tasks scheduled in parallel within a sprint MUST have disjoint write-sets or an explicit ordering declared in `/work/sprints/SPRINT-NN.md`.

- **Claim protocol:** claiming = one commit that sets the task file state to CLAIMED with role ID and date in the status log. First commit wins; a second claimant MUST back off and notify R-TPM via the sprint status entry.
- **G0 enforcement:** a task cannot reach READY without a declared write-set (DoR check, [./DefinitionOfReady.md](./DefinitionOfReady.md)). R-TPM MUST verify pairwise disjointness of write-sets across all tasks scheduled concurrently before publishing the sprint plan.
- **Registers and shared files:** `/work/debt-register.md`, `/work/risk-register.md`, and sprint files are append-only for non-owners; each row has exactly one owner. IDs (TASK/DEBT/RISK) are allocated monotonically by R-TPM to prevent collisions.
- **Violation handling:** discovering an undeclared write-set overlap mid-sprint sets the later-claimed task to BLOCKED with an escalation record; R-TPM re-sequences. Merging a PR that writes outside the declared write-set is a BLOCKER at G8 (R-CR checks the diff against the task's write-set).
- Docs ownership follows [ModuleOwnership.md](./ModuleOwnership.md); writes to `/docs` contract anchors are additionally CC-1 and follow the docs-first policy ([./RepositoryRules.md](./RepositoryRules.md)).

## 7. Conflict resolution

Default escalation chain ([AgentResponsibilities.md](./AgentResponsibilities.md)): **R-IE/R-TE → owning domain architect → R-CA → human repository owner.** Every escalation step MUST leave an escalation record (§3.5) in the task file — undocumented resolutions do not exist.

| Dispute type | Arbiter | Notes |
|---|---|---|
| Technical disagreement inside a domain | owning domain architect (A2) | binding within domain |
| Disagreement between two architects | R-CA (A1) | binding platform-wide |
| Scope / priority ("should we build this") | R-PO | guards anti-goals, incl. FR-057 ([../docs/product/PRD.md](../docs/product/PRD.md)) |
| Schedule / sequencing / task ownership | R-TPM | includes SLA-breach arbitration |
| Security dispute | R-SA (A4 block) | override only by human owner, recorded in an escalation record |
| Review deadlock (author vs R-CR after one rebuttal round) | owning domain architect, then R-CA | R-CR's BLOCKER stands until overturned in writing |
| Release go/no-go | R-RM (A4) | with R-SA (RG2) and R-PE (RG3) verdicts |
| Anything unresolved above architect level | human repository owner | final; decision recorded |

Rebuttals are allowed once: the author MAY contest a verdict with cited evidence (doc §/ID or CI run) appended to the review thread; after one round, unresolved disagreement escalates per the table — never re-litigated in place.

## Related documents

- [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) — constitution and §0 disambiguation
- [AgentResponsibilities.md](./AgentResponsibilities.md), [AIAgentCatalog.md](./AIAgentCatalog.md) — role cards and catalog
- [ContextManagementStrategy.md](./ContextManagementStrategy.md) — context layers, anti-pollution rules
- [PromptEngineeringStandards.md](./PromptEngineeringStandards.md) — task spec as prompt payload
- [AIValidationWorkflow.md](./AIValidationWorkflow.md) — evidence rules referenced by PR template
- [DevelopmentLifecycle.md](./DevelopmentLifecycle.md), [SprintExecutionGuide.md](./SprintExecutionGuide.md) — task lifecycle and sprint mechanics
- [DefinitionOfReady.md](./DefinitionOfReady.md), [DefinitionOfDone.md](./DefinitionOfDone.md), [CodeReviewChecklist.md](./CodeReviewChecklist.md), [QualityGatePolicy.md](./QualityGatePolicy.md)
- [ADRProcess.md](./ADRProcess.md), [TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md), [RiskManagementPolicy.md](./RiskManagementPolicy.md), [ModuleOwnership.md](./ModuleOwnership.md)
- [../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) — why reference-not-restate is law
