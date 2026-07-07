# AI Engineering Guide

This is the day-in-the-life operating manual for every AI engineering agent working on EIP — primarily the execution roles (R-IE, R-TE, R-RE, R-DE), and by extension any role holder running a session against this repository. Read it once per session before touching anything else except `/CLAUDE.md`. It defines the bootstrap ritual, working discipline, context hygiene, tool discipline, honesty rules, escalation triggers, and the known drift failure modes. These roles are **engineering agents** that build EIP — never the product's 18 runtime agents (FR-082, [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md)).

## 1. Session bootstrap ritual

One AI session ≈ one task claim ([DevelopmentLifecycle.md](./DevelopmentLifecycle.md)). Execute these steps in order, every session, no exceptions:

- [ ] **1. Read `/CLAUDE.md`** (repo root). It is the universal session bootstrap for every agent.
- [ ] **2. Open your task spec** `/work/tasks/TASK-NNNN.md`. Verify: state is READY (claim it) or CLAIMED by you; the spec has story ref (`P<phase>-E<epic>-S<story>`), FR/AC refs, change class(es), write-set, and a context pack. If any field is missing, the task fails G0 — return it to R-TPM; do NOT start.
- [ ] **3. Read the context pack in its listed order.** Read cited sections, not whole documents. The context pack is the ONLY required pre-reading ([ContextManagementStrategy.md](./ContextManagementStrategy.md)).
- [ ] **4. Read prior handoffs** `/work/handoffs/TASK-NNNN-*.md` (highest sequence last). The latest handoff supersedes anything you believe you remember about this task.
- [ ] **5. Verify branch state.** `git status` clean; on `feature/TASK-NNNN-slug` (or `fix/`, `refactor/`, `docs/` per [BranchingStrategy.md](./BranchingStrategy.md)); branch not stale against `main`; note the CI status of the last push. If reality disagrees with the handoff, trust reality and record the discrepancy in the task file.
- [ ] **6. Restate the contract.** Write (in the task file or first commit message) the acceptance criteria you will satisfy and the write-set you will touch. If an AC is untestable or the write-set is unclear, escalate to R-TPM (G0 defect) instead of interpreting.
- [ ] **7. Set the task to IN_PROGRESS** and begin.

## 2. Working discipline

1. **Small commits.** Conventional Commits: `type(scope): summary [TASK-NNNN]`, types `feat/fix/refactor/test/docs/build/chore/perf`, scope = module name (`eip-analytics`, `frontend`, `infra`, `docs`, `eos`). Each commit compiles and keeps tests green where feasible.
2. **Run gates locally before pushing.** Build + lint + Modulith/ArchUnit boundaries (G1), tests (G2), docs-lint (G7) — commands per [RepositoryRules.md](./RepositoryRules.md). Pushing red to find out "what CI says" wastes a review cycle; CI confirms, it does not discover.
3. **Stay in the write-set.** The declared write-set in the task spec is a boundary, not a suggestion. Out-of-scope problems become DEBT entries or proposed TASKs — never drive-by edits (single-writer rule: another agent may own those files right now).
4. **Bug fixes are red→green.** The failing regression test lands in a commit BEFORE the fix commit, with the evidence visible in CI or the PR description.
5. **PR discipline.** Target ≤ ~400 net lines (excluding generated/lock files); fill every PR template field (task ref, change class(es), what/why ≤ 10 lines, contract impact, test evidence with commands + results, docs updated, observability delta, rollback note). Declare change classes honestly — R-CR treats misclassification as a BLOCKER.
6. **Docs-first.** If the implementation must deviate from `/docs`, the doc + ADR change lands first (or in the same PR for small clarifications). Code never silently diverges (PRD §7.6 in [../docs/product/PRD.md](../docs/product/PRD.md)).
7. **Session end.** DONE means the [DefinitionOfDone.md](./DefinitionOfDone.md) checklist is complete. Any session ending short of DONE MUST write `/work/handoffs/TASK-NNNN-<seq>.md` with all template fields: state reached, commits/branch, what works/what doesn't (verified how), next 3 concrete steps, open questions, files touched, gotchas.

## 3. Context hygiene

The repository is the shared memory; your session context is disposable (L1 Canon · L2 MODULE.md charters · L3 /work state · L4 session — [ContextManagementStrategy.md](./ContextManagementStrategy.md)).

**Re-read canon (do not trust recall) when:**

- you are about to write any canonical identifier — topic names, event fields, table names, endpoint paths, FR/NFR IDs. Copy them from [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md), [../docs/architecture/DomainModel.md](../docs/architecture/DomainModel.md), [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md), or [../docs/product/PRD.md](../docs/product/PRD.md);
- you enter a module you have not read in THIS session (read its MODULE.md first);
- the code contradicts what you expected — assume your context is stale, not the code;
- your context was compacted, summarized, or restored.

**On context loss (compaction, crash, new session mid-task):** STOP writing code. Resume from the task file + the latest handoff + `git log` on the branch — never from memory. A handoff's "what works" claims count only where "verified how" is stated; re-verify anything you must rely on. Reconstructing intent from recollection is how incompatible halves of a feature get built.

**Before session end:** write durable knowledge into L1–L3 (doc PR, MODULE.md update, task file, handoff, DEBT/RISK entry). Anything not written down is lost by design. There is no agent-to-agent state outside the [DevelopmentLifecycle.md](./DevelopmentLifecycle.md) artifacts.

## 4. Tool discipline

1. **Read before you edit.** Never modify a file from memory of its contents; read the current version in this session first.
2. **Copy, never retype, identifiers.** Every topic, consumer group, DLQ, table, envelope field, endpoint, FR/NFR/ADR ID is pasted from its source of record. Typing `eip.domain.workitems` when [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) says `eip.domain.workitem` is a contract violation, not a typo.
3. **Run the check instead of predicting it.** "This should compile" is not a state of the world. Compile it. The same applies to tests, lint, docs-lint, and OpenAPI diff.
4. **Keep commands reproducible.** Test evidence in PRs is `command + output`, runnable by R-CR verbatim. No prose summaries of results without the command that produced them.
5. **No pasted document bodies.** Prompts, task files, and PRs cite paths + sections. Pasting spec text creates a divergent copy that will drift ([ContextManagementStrategy.md](./ContextManagementStrategy.md)).
6. **Never silence a gate.** Disabling a failing check, skipping a test, loosening a lint rule, or muting an ArchUnit rule to get green is prohibited. If a check is wrong, escalate to its gate owner (G1 R-DOA, G2 R-QAA, G3 R-SA, G7 R-DE).
7. **Generated artifacts are rebuilt, not hand-edited** (OpenAPI output, lockfiles, migration checksums). Hand-edits to generated files are BLOCKERs at review.

## 5. Honesty rules

1. **Report failures verbatim.** The exact command, the exact error text, in the handoff or PR. Paraphrased errors destroy the next session's ability to resume.
2. **Never claim untested success.** The words "should work" are prohibited in PR descriptions and handoffs. Permitted forms: "verified by `<command>` → `<result>`" or "NOT verified — <reason>".
3. **CI is the only accepted truth for "tests pass"** ([AIValidationWorkflow.md](./AIValidationWorkflow.md)). Local green is a courtesy; the claim in the PR links CI. R-CR re-runs or requires CI links; **an unverifiable claim is a BLOCKER** by rule, regardless of whether it happens to be true.
4. **Separate observation from inference.** "The endpoint returned 403" is observation; "RBAC is misconfigured" is inference. Label them as such in handoffs and escalations.
5. **Declare shortcuts.** Every accepted shortcut becomes a DEBT entry (`/work/debt-register.md`) in the same PR — the register-or-fix rule of [TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md). An undeclared shortcut found in review is a MAJOR at minimum.
6. **Uncertainty is reportable, not shameful.** "I do not know whether X holds; here is how to check" is a valid, preferred output. Confident fabrication is the one unforgivable failure class.

## 6. When to stop and escalate

Stop means: set the task to BLOCKED, write the Escalation record into the task file (blocker · options considered · recommendation · decision + decider + date), and end or switch scope. Do not keep coding "around" the blocker.

| Trigger | Escalate to | Why |
|---|---|---|
| Your task requires changing a contract anchor (PRD, DomainModel, EventModel, APIDesign, ConnectorFramework SPI, SecurityModel, published SPIs) and the task is not CC-1 | Owning domain architect → R-CA | CC-1 requires G4; silent anchor drift is the worst failure mode |
| Two documents (or a doc and the code) contradict each other | Owning architect + R-DE | Docs-first rule decides which lands first; never pick silently |
| Anything security-relevant surprises you (secrets in logs, RLS bypass, cross-tenant read, guardrail gap) | R-SA immediately | CC-2 territory; R-SA holds A4 |
| An AC is unimplementable or untestable as written | R-TPM + R-PO | G0/scope defect, not an engineering judgment call |
| Two failed attempts to fix the same gate failure | Owning domain architect | Third attempts without new information burn the session |
| Environment/CI broken, unrelated to your change | R-DOA | G1 infrastructure is R-DOA's domain |
| You are tempted to exceed the write-set "while you're here" | R-TPM (file a TASK) or DEBT entry | Single-writer rule; scope creep collides with parallel lanes |
| The task cannot fit the remaining session | Write handoff now | A good handoff at 70% beats a rushed "done" claim at 100% |

## 7. Top-10 drift failure modes and countermeasures

Drift = many agents making individually reasonable choices that are collectively incompatible. These ten modes account for the drift this EOS is designed to prevent.

| # | Failure mode | Countermeasure |
|---|---|---|
| 1 | **Inventing names** — typing a "plausible" topic/table/field/endpoint instead of reading EventModel/DomainModel/APIDesign | Copy identifiers from the source of record (§4.2). New names require a doc PR first (docs-first). R-CR checks every identifier in the diff against the cited docs |
| 2 | **Restating instead of linking** — paraphrasing spec content into task files, comments, or new docs | Cite path + section. Paraphrases become stale forks of the truth; docs-lint and G7 reject uncited restatements of canonical facts |
| 3 | **Fixing out-of-scope code** — "improving" files outside the write-set | Write-set is a hard boundary (§2.3); file DEBT/TASK instead. Parallel lanes mean those files may be mid-edit by another agent |
| 4 | **Claiming untested success** — "implemented and working" without a run | §5.2/§5.3: verified-by-command or NOT-verified, CI as truth, unverifiable claim = BLOCKER |
| 5 | **Parallel concepts** — building a second event envelope, a local Result type, a bespoke retry helper because finding the existing one costs a search | Search `eip-core` (shared kernel) and the MODULE.md of touched modules before creating any cross-cutting type; duplication of a kernel concept is a MAJOR |
| 6 | **Silent contract change** — editing an endpoint/envelope/schema/SPI in a task declared CC-7 | Classify before coding; if the diff touches an anchor, the class is CC-1/CC-4 and G4 applies. R-CR verifies declaration against the diff |
| 7 | **Working from memory after context loss** — resuming from recollection instead of task file + handoff | §3 resume ritual: STOP, re-read task + latest handoff + `git log`, re-verify claims. Never from memory |
| 8 | **Cross-module shortcuts** — reading another module's tables or repositories because the interface is missing a method | Dependency rules ([../docs/architecture/ArchitectureOverview.md](../docs/architecture/ArchitectureOverview.md) §5): exported `api` interfaces or Kafka topics only. Missing method → escalate to owning architect for an interface change |
| 9 | **Tenancy amnesia** — new table without `tenant_id`+RLS+migration, new Redis key/MinIO prefix without tenant prefix, query bypassing RLS | Apply the ArchitectureOverview §12 checklist to every schema/storage touch; FR-128/NFR-041 make this release-blocking, and G3 isolation tests will catch it late — the checklist catches it early |
| 10 | **ID vandalism** — renumbering FR/NFR/ADR/gate/CC IDs, "fixing" ID gaps, renaming roles or states | IDs are law and gaps are intentional (PRD §5.1 note). Never renumber; new items take new IDs. Docs-lint checks ID integrity; R-DE blocks at G7 |

When you catch yourself mid-failure-mode: stop, revert the drifting edit, and do the countermeasure. Catching your own drift is expected behavior, not an incident.

## Related documents

- [AIAgentCatalog.md](./AIAgentCatalog.md) — the 21 engineering roles, topology, staffing
- [AgentResponsibilities.md](./AgentResponsibilities.md) — your role card: read it every session
- [DevelopmentLifecycle.md](./DevelopmentLifecycle.md) — task states, templates (task spec, PR, handoff, escalation)
- [ContextManagementStrategy.md](./ContextManagementStrategy.md) — L1–L4 layers, context packs, reading lists per task type
- [AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) — artifact-only communication rules
- [AIValidationWorkflow.md](./AIValidationWorkflow.md) — L0–L4 validation ladder; CI-as-truth
- [QualityGatePolicy.md](./QualityGatePolicy.md) — G0–G8, RG1–RG4
- [DefinitionOfReady.md](./DefinitionOfReady.md) / [DefinitionOfDone.md](./DefinitionOfDone.md) — entry/exit checklists
- [CodingStandards.md](./CodingStandards.md), [TestingChecklist.md](./TestingChecklist.md), [CodeReviewChecklist.md](./CodeReviewChecklist.md) — the standards the gates enforce
- [../docs/product/PRD.md](../docs/product/PRD.md) — FR/NFR source of record cited throughout
