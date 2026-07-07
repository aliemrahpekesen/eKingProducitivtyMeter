# Refactoring Policy

This document defines when and how code structure may be changed without changing behavior in the EIP codebase. It is read by every engineering agent tempted to "clean up while here", by R-RE (who executes registered refactors as their primary mission), by owning architects who approve structural refactors, and by R-CR who verifies behavior-preservation evidence. The core stance: refactoring is welcome, but it is either trivially scoped (boy-scout) or explicitly planned (its own task) — never smuggled into feature work.

## 1. Definitions

| Tier | What it is | Vehicle |
|---|---|---|
| **Boy-scout** | Local cleanups inside files you are already changing for your task: renames of locals, extracting a private method, removing dead code you touched, fixing formatting the linter missed | Same PR as the task, `refactor:` commits separated (§6) |
| **Structural refactor** | Changes to structure beyond the current task's need: moving classes between packages, extracting/merging components inside a module, reshaping internal interfaces, consolidating duplicated logic across files | Own `TASK-NNNN`, branch `refactor/TASK-NNNN-slug` ([BranchingStrategy §2](./BranchingStrategy.md)) |
| **Large-scale refactor** | Anything touching module boundaries, contract anchors, or more than one module's write-set | §5 protocol: design note → ADR if boundaries move → staged tasks |

## 2. Boy-scout rule — scoped to the task write-set

Boy-scout changes are allowed in every task, bounded by ALL of the following. Exceeding any bound means: stop, file a DEBT entry (class `design`, [TechnicalDebtPolicy §3](./TechnicalDebtPolicy.md)) or propose a structural refactor task, and keep your PR on mission.

Allowed vs. not allowed at boy-scout tier:

| Allowed in the same PR | NOT allowed — needs its own task |
|---|---|
| Rename a local variable/private method in a file you edit | Rename anything `public` used outside the file |
| Extract a private helper inside the class you edit | Extract a class into another package |
| Delete dead code your diff made unreachable | Delete "apparently unused" code elsewhere in the module |
| Inline a trivial single-use constant | Change a shared constant's value or location |
| Fix formatting/imports in touched files | Reformat untouched files ("noise diffs" break blame and review) |
| Tighten a local type (`var` → explicit, generics) | Change a method signature, even package-private ones with external callers |

Bounds (all mandatory):

- [ ] Only files already in the task's declared write-set (single-writer rule, [DevelopmentLifecycle](./DevelopmentLifecycle.md) — touching outside files can collide with a parallel task)
- [ ] Zero contract impact: no change to any `/api/v1` surface, event schema, SPI, RLS policy, Flyway migration, or MODULE.md-listed invariant
- [ ] No change-class escalation: the PR's declared CC set is identical with and without the boy-scout commits
- [ ] PR stays within the ~400 net-line target ([BranchingStrategy §4](./BranchingStrategy.md)); boy-scout lines never justify exceeding it
- [ ] Cleanup commits are separate `refactor(scope):` commits from the task's `feat`/`fix` commits (§6)
- [ ] No test semantics changed: tests may be renamed/moved mechanically, never weakened or deleted

## 3. Structural refactors — own task, explicit approval

1. A structural refactor MUST be its own `TASK-NNNN` with a task spec per [AgentCommunicationProtocol §3.1](./AgentCommunicationProtocol.md): problem statement (what structure is wrong, which /docs or ADR target it moves toward), write-set, and acceptance criteria stated as *structure* assertions (e.g., "ArchUnit rule X passes", "class Y no longer referenced from module Z") — never as behavior changes.
2. **Approval before READY (G0):** the owning domain architect (per the [ModuleOwnership](./ModuleOwnership.md) map) approves the task spec. A refactor crossing module boundaries or touching more than one module's write-set additionally requires **R-CA** approval. Approval is recorded in the task file.
3. The task is normally claimed by **R-RE**; R-IE may execute S-sized structural refactors with the same evidence requirements.
4. Refactor tasks are scheduled by R-TPM like any task, compete for capacity (debt-paydown refactors draw on the ≤ 15% debt reservation, [TechnicalDebtPolicy §5](./TechnicalDebtPolicy.md)), and MUST satisfy §4 to merge.

## 4. Behavior-preservation evidence

A structural refactor PR is merged only with all of the following evidence attached (PR template "test evidence" field, verified by R-CR at G8):

- [ ] **Full gate pass** — G1–G3, G7, G8 green; conditional gates for any declared class (a pure refactor declares CC-7; if it cannot honestly declare CC-7, it is not a pure refactor — split it)
- [ ] **Zero contract diff** — the G1 OpenAPI diff is empty; event schema registry unchanged (no file added/modified under `event-schemas/`, [EventModel §6](../docs/engineering/EventModel.md)); no Flyway migration in the PR; ArchUnit/Modulith boundary checks green
- [ ] **Before/after test parity** — the exact test suite green on the parent commit is green on the head commit; no test deleted, skipped, or assertion-weakened. Mechanical test moves/renames are listed explicitly in the PR description. Coverage does not decrease (G2 ratchet)
- [ ] **No observability regression** — metric names, log structure, and trace spans unchanged (`eip_*` naming per ObservabilityModel); dashboards keyed on them keep working
- [ ] CI links, not claims — per [AIValidationWorkflow.md](./AIValidationWorkflow.md), unverifiable "tests pass" statements are a BLOCKER

### 4.1 R-CR verification checklist for refactor PRs

R-CR MUST verify, with fresh context per [CodeReviewChecklist](./CodeReviewChecklist.md) (task spec + diff + cited docs only):

- [ ] Task spec exists, is approved per §3.2, and the diff stays inside its declared write-set
- [ ] Every mechanical commit is verifiably mechanical: rename detection shows moves, not rewrites (§6)
- [ ] The PR description lists each mechanical test move/rename; no assertion text changed in any test diff hunk
- [ ] CI evidence links present for both the parent-commit and head-commit test runs (parity claim is checkable, not asserted)
- [ ] OpenAPI diff artifact from G1 is empty; no file under `event-schemas/`, no `V<seq>__*.sql` in the changeset
- [ ] Declared change class is CC-7; any hint of CC-1/2/4/5 surface in the diff → BLOCKER (misclassification rule, [QualityGatePolicy §3](./QualityGatePolicy.md))

## 5. Large-scale refactor protocol

For refactors that move module boundaries, touch a contract anchor, or exceed one session of work:

1. **Design note first:** a short note in the task file (or `/docs/adr` draft) stating current structure, target structure, migration path, and blast radius. The owning architect(s) and R-CA review it before any task is cut.
2. **ADR if boundaries move:** any change to module boundaries, module ownership, or a contract anchor ([QualityGatePolicy §2](./QualityGatePolicy.md), G4 anchor list) requires an ADR (Proposed → Accepted by R-CA) per [ADRProcess.md](./ADRProcess.md) — the refactor is CC-1 and gets G4 + two-reviewer G8.
3. **Staged tasks:** the work is split into tasks of size ≤ M, each independently mergeable, each leaving `main` releasable, each carrying §4 evidence. "Big-bang" refactor branches living longer than 5 working days violate BranchingStrategy and MUST be re-staged.
4. Interim states (e.g., a temporarily duplicated class during a move) are acceptable only when registered: the final stage's task id is recorded against a DEBT entry that the last stage closes.

## 6. Rename/move rules — mechanical commits stay mechanical

- Renames, file moves, and package restructures MUST be in commits containing **only** the mechanical change (IDE-generated or scripted), typed `refactor(scope): …` — never mixed with logic edits. This keeps `git diff --follow` reviewable and lets R-CR verify a move commit by its emptiness under rename detection.
- Logic adjustments forced by a move (import fixes are mechanical; signature adaptations are not) go in a follow-up commit in the same PR, explicitly labeled.
- Public-surface renames are not renames: anything visible through an SPI, `/api/v1`, event schema, or DB identifier is a contract change and follows [VersioningStrategy](./VersioningStrategy.md) §3–§6, not this section.
- Commit granularity rule of thumb: a reviewer must be able to approve every mechanical commit in seconds and spend their attention only on the non-mechanical ones.

## 7. Concurrency rule — refactors never race features

A structural refactor MUST NOT be scheduled in the same sprint as feature work in the same module unless the sprint plan declares an explicit ordering (refactor merges first, feature rebases). R-TPM enforces this through the sprint plan's disjoint write-sets ([DevelopmentLifecycle](./DevelopmentLifecycle.md)); the single-writer rule makes an undeclared overlap a planning defect, not a merge race. Boy-scout changes are exempt because they are confined to the task's own write-set by §2.

## 8. Decision flow — "may I refactor this now?"

Answer in order; the first "no" routes you:

1. Is every file you would touch inside your current task's write-set? — No → §3 (own task) or DEBT entry; never touch it from this task.
2. Would any contract surface (API/event/SPI/RLS/migration/MODULE.md invariant) change, even cosmetically? — Yes → it is not a refactor; follow [VersioningStrategy](./VersioningStrategy.md) and the CC-1/CC-4 process.
3. Would the change alter your PR's declared change class or push it past the size target? — Yes → DEBT entry now, refactor task later.
4. Is feature work scheduled in this module this sprint (check the sprint plan write-sets)? — Yes → the refactor waits or the sprint plan declares ordering (§7).
5. All "no"/"clear" → proceed at the matching tier (§2 boy-scout in-PR, or the approved refactor task you are holding).

## 9. Prohibited refactors

- Editing an applied Flyway migration in any way ([VersioningStrategy §6](./VersioningStrategy.md)) — corrections are new migrations
- "Refactoring" a contract anchor without the CC-1/G4/ADR path — including drive-by renames in event payloads, OpenAPI schemas, or SPI interfaces
- Prompt, model-routing, or eval-affecting changes in `eip-ai` presented as refactoring — they are CC-5 and require the AI eval suite + R-AIA sign-off regardless of intent ([QualityGatePolicy §3](./QualityGatePolicy.md))
- Rewriting tests to fit refactored code (tests are the behavior oracle; if a test must change semantically, the change is not behavior-preserving — reclassify the task)
- Refactoring code owned by another module's write-set "because it was easier from here" — escalate to the owning architect instead (escalation chain per [AgentResponsibilities.md](./AgentResponsibilities.md))

## Related documents

- [TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md) — design-debt intake and the paydown capacity refactors draw on
- [ADRProcess.md](./ADRProcess.md) — boundary-moving refactors
- [ArchitecturePrinciples.md](./ArchitecturePrinciples.md) & [ModuleOwnership.md](./ModuleOwnership.md) — target structure and approval owners
- [BranchingStrategy.md](./BranchingStrategy.md) — `refactor/` branches, 5-day limit, PR size target
- [QualityGatePolicy.md](./QualityGatePolicy.md) — gates supplying behavior-preservation evidence
- [CodeReviewChecklist.md](./CodeReviewChecklist.md) — R-CR verification of mechanical commits and evidence
- [SprintExecutionGuide.md](./SprintExecutionGuide.md) — write-set declaration and ordering in sprint plans
- [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §6 — schema registry that must show zero diff
