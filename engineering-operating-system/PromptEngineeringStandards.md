# Prompt Engineering Standards

This document defines how **engineering prompts** — the instructions that launch an engineering agent session (R-IE, R-TE, R-RE, R-CR, and every other role in [AgentResponsibilities.md](./AgentResponsibilities.md)) — are constructed, reviewed, and kept honest. R-TPM applies it when creating tasks; every session-launching human or orchestrator applies it when kicking off an agent. **Disambiguation (per [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) §0):** this document does NOT govern the product's prompt templates — the prompts of the 18 runtime agents inside `eip-ai` are product artifacts owned by R-AIA under [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md), and changing them is a CC-5 change governed by [./AIValidationWorkflow.md](./AIValidationWorkflow.md) §6, not by this file.

## 1. The five-part prompt anatomy

Every implementation prompt MUST contain exactly these five parts, in this order. Nothing else is required; anything else is suspect (§4).

| # | Part | Content | Source of truth |
|---|---|---|---|
| 1 | **Role card ref** | Path + section of the role being assumed, e.g. `engineering-operating-system/AgentResponsibilities.md §R-IE` | [AgentResponsibilities.md](./AgentResponsibilities.md) |
| 2 | **Task spec path** | `/work/tasks/TASK-NNNN.md` — one task, exactly one | [./AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) §3.1 |
| 3 | **Context pack** | By reference: "the context pack inside the task spec" — never inlined | [./ContextManagementStrategy.md](./ContextManagementStrategy.md) §2 |
| 4 | **Output contract** | The artifacts the session MUST produce: branch name, PR per template, task state transitions, handoff note if ending before DONE | [./AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md), [./DevelopmentLifecycle.md](./DevelopmentLifecycle.md) |
| 5 | **Verification steps** | The exact commands and gates the agent runs before claiming success | task spec test plan; [./AIValidationWorkflow.md](./AIValidationWorkflow.md) §1 (L0) |

Rules:

- Parts 3–5 live **inside the task file**; the prompt points at them. A prompt that works only with extra out-of-band instructions is defective — move those instructions into the task spec first.
- One prompt = one task = one session ≈ one claim (single-writer rule, [./AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) §6).
- Prompts MUST be replayable: any competent agent given the same five parts at the same commit MUST be able to produce a compatible result. If the outcome depends on who is prompted or what they remember, the task spec is under-specified — fix it at G0.

## 2. The task spec IS the prompt payload

The durable prompt content lives in `/work/tasks/TASK-NNNN.md`; the kickoff message is a thin, disposable pointer. Canonical kickoff prompt (copy verbatim, fill the brackets):

```text
Assume role R-XX per engineering-operating-system/AgentResponsibilities.md §R-XX.
Execute /work/tasks/TASK-NNNN.md.
Read /CLAUDE.md first, then the task's context pack in the listed order. Read nothing else before starting.
Output contract: branch <type>/TASK-NNNN-<slug>; PR per AgentCommunicationProtocol.md §3.2;
update the task state and status log; write /work/handoffs/TASK-NNNN-<seq>.md if you end before DONE.
Verify per the task's test plan before opening the PR; claims without CI evidence are worthless
(AIValidationWorkflow.md §2).
```

Consequences of this rule:

- **Improving a prompt = editing the task file** (before CLAIMED; after CLAIMED, changes require the assignee's acknowledgment in the status log). Prompt history is git history.
- R-CR reviews against the task spec — the same text the author executed. There is no second, private version of the instructions (reviewer isolation, [./AIValidationWorkflow.md](./AIValidationWorkflow.md) §3).
- A defect traced to a bad instruction is fixed in the task file and, if the pattern generalizes, in this standard — never by whispering corrections into the next session.

## 3. Annotated example: a well-formed TASK file

A realistic Phase-1 task: one slice of story `P1-E3-S1` (Jira connector, [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md) §6.1). Annotations are the `<!-- ✎ … -->` comments; a real task file omits them. IDs of dependency tasks are illustrative; every doc path, section, FR/AC/K-case ID is real.

```markdown
# TASK-0147 — Jira connector: per-stream checkpoint persistence and resume

- **Sprint:** SPRINT-06
- **Story ref:** P1-E3-S1
  <!-- ✎ The story is size L in the plan, so it is delivered as task slices; every slice cites it. -->
- **FR/AC refs:** FR-002, FR-010, AC-025, AC-026
  <!-- ✎ FR-010 = checkpoint-per-connector+stream + resume (PRD §5.1); AC-025/AC-026 are the
       binding Given/When/Then in docs/product/AcceptanceCriteria.md §4. IDs, never paraphrases:
       docs-lint verifies they resolve. -->
- **Change class(es):** CC-7
  <!-- ✎ Classification reasoning R-CR will re-verify (misclassification = BLOCKER):
       not CC-1 — implements against the existing Connector SPI, no anchor changes;
       not CC-4 — core.connector_checkpoint already exists (DatabasePlan), no migration;
       not CC-3 — checkpoint writes are once per committed batch (control plane), not the
       per-event hot path; not CC-2 — no authn/z, secrets, or tenancy-policy surface touched. -->
- **Module(s) / write-set:** eip-connectors — backend/eip-connectors/src/{main,test,integrationTest}/java/**/jira/**
  <!-- ✎ Globs, not vibes. R-TPM checked this is disjoint from every other SPRINT-06 task;
       R-CR checks the final diff stays inside it. -->
- **Assigned role:** R-IE
- **Size:** M
- **Dependencies:** TASK-0139 (sync engine + checkpoint table, P1-E1-S2 — DONE),
  TASK-0145 (Jira stream fetchers — DONE)
- **State:** READY

## Context pack (ordered — read top to bottom, nothing else required)
1. /CLAUDE.md — session bootstrap.
2. /backend/eip-connectors/MODULE.md — module invariants and gotchas.
3. docs/engineering/ConnectorFramework.md §2 — Checkpoint/CheckpointStore SPI you must call.
4. docs/engineering/ConnectorFramework.md §4 — per-stream checkpoint + commit-after-staging rules.
5. docs/engineering/DatabasePlan.md — core.connector_checkpoint DDL and the SPI-field mapping note.
6. docs/engineering/EventModel.md §9 — outbox flow the checkpoint commit must sequence after.
7. docs/product/AcceptanceCriteria.md — AC-025, AC-026 (the behavior contract).
8. docs/testing/TestingStrategy.md §5.3 (kit cases K4, K6) and §18 (checkpoint-resume property).
   <!-- ✎ Path + section + why, per ContextManagementStrategy.md §2. Eight entries, one session.
        No pasted bodies. -->

## Problem statement
The Jira connector (TASK-0145) fetches its streams but does not persist per-stream checkpoints,
so any interruption forces a full re-fetch, violating FR-010. Wire the projects, issues, sprints,
and boards streams through the CheckpointStore per ConnectorFramework §4: independent checkpoints
per stream, committed only after the corresponding raw records are durably staged and published.
  <!-- ✎ ≤ 10 lines, cites the docs, restates nothing that the cited sections already define. -->

## Acceptance criteria (testable)
- [ ] Each Jira stream persists an independent row in core.connector_checkpoint keyed
      (tenant_id, connector_id, stream) after each committed batch.
- [ ] Worker killed mid-`incrementalSync` + restart ⇒ resume from last committed checkpoint,
      zero loss, zero duplicate canonical writes — kit case K4 green for the Jira connector (AC-025).
- [ ] Incremental sync fetches only changed/new entities (asserted via WireMock request counts)
      and each stream's watermark advances monotonically (AC-026).
- [ ] Checkpoint commit is sequenced strictly after raw staging + outbox publication
      (ConnectorFramework §4); an integration test asserts the ordering.
- [ ] `eip.connector.checkpoint.lag_seconds` emits per Jira stream (ConnectorFramework §9).
  <!-- ✎ Every criterion is mechanically checkable and traceable to a doc or kit case.
       "Works correctly" would be rejected at G0. -->

## Test plan
- Extend JiraConnectorContractTest (subclass of ConnectorContractTestKit): K4, K6 green.
- jqwik property on the issues stream: sync(prefix) + resume(suffix) ≡ sync(whole)
  (TestingStrategy §18).
- Integration test writes as TENANT_A, asserts checkpoint invisibility as TENANT_B
  (TestingStrategy §3 RLS pattern), using dataset builders only (§13).
- Local: ./gradlew connectorKit --tests '*Jira*' && ./gradlew integrationTest check
  <!-- ✎ Exact suites and commands from TestingStrategy §17 — these are also the prompt's
       verification steps (anatomy part 5). -->

## Observability plan
none — justified: the sync engine already emits eip.connector.checkpoint.lag_seconds; this task
only verifies per-stream tags appear for jira streams (assertion included in the test plan).

## Docs impact
none — justified: pure conformance to ConnectorFramework §4/§12; no behavior beyond spec.
Update /backend/eip-connectors/MODULE.md Gotchas if resume ordering yields a non-obvious trap.

## Out of scope
Webhook-offset gap-detection checkpoints; backfill-window chunking (separate task);
all non-Jira connectors.
  <!-- ✎ Out-of-scope is what keeps a single-writer task from swelling into a neighbor's write-set. -->

## DoR check (G0)
- [x] Story ref + FR/AC refs present and resolvable
- [x] Acceptance criteria testable
- [x] Write-set declared and disjoint (SPRINT-06 table verified)
- [x] Context pack fits one session
- [x] Dependencies DONE or stubbed
- [x] Size ≤ M

## Escalation records (append-only)

## Status log (append-only: date · role · state change · note)
- 2026-07-06 · R-TPM · INTAKE→READY · G0 passed
```

## 4. Prohibited patterns

R-TPM MUST reject these at G0; R-CR treats their downstream symptoms as MAJOR or BLOCKER.

| # | Prohibited pattern | Example (bad) | Required form |
|---|---|---|---|
| P1 | **Vague objective** | "Improve connector reliability" | Testable ACs traced to FR/AC IDs, as in §3 |
| P2 | **Pasted document bodies** | Copying ConnectorFramework §4 into the task | Path + section + one-line "why" ([./ContextManagementStrategy.md](./ContextManagementStrategy.md) §7) |
| P3 | **Unpinned references** | "follow the latest event model", external URLs | In-repo path at HEAD; IDs (FR-xxx, ADR-NNN) |
| P4 | **Multi-task prompts** | "do TASK-0147 and, if time allows, start TASK-0148" | One prompt = one task; sequencing belongs to R-TPM in SPRINT-NN.md |
| P5 | **Out-of-band instructions** | Kickoff message adds requirements absent from the task file | Edit the task file first; the prompt stays a thin pointer (§2) |
| P6 | **Restated numbers** | "keep ingest under 100k events/hour" | Cite the ID: "within the NFR-003 budget" ([../docs/product/PRD.md](../docs/product/PRD.md)) |
| P7 | **Implied permissions** | "refactor anything that looks off along the way" | Write-set is the boundary; structural refactors are separate tasks ([./RefactoringPolicy.md](./RefactoringPolicy.md)) |
| P8 | **Unverifiable success criteria** | "make sure it performs well" | Named command/gate/threshold per [./AIValidationWorkflow.md](./AIValidationWorkflow.md) |

## 5. Prompt review is part of DoR (G0)

A task MUST NOT move INTAKE → READY until R-TPM has run this checklist (it extends [DefinitionOfReady.md](./DefinitionOfReady.md); the DoR fields themselves are in the task template):

- [ ] All five anatomy parts resolvable from the task file + canonical kickoff prompt (§1–§2)
- [ ] Every FR/AC/FEAT/ADR ID cited actually resolves (same check docs-lint runs, per [../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) §5)
- [ ] Context pack obeys [./ContextManagementStrategy.md](./ContextManagementStrategy.md) §2 (ordered, path+section, ≤ 1 session, pinned)
- [ ] No prohibited pattern P1–P8 present
- [ ] Change class(es) declared with defensible reasoning (annotation style of §3 encouraged)
- [ ] Verification steps are copy-runnable commands from [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §17 or CI stage names (§14)
- [ ] Output contract implies all mandatory artifacts (branch, PR template, state updates, handoff-on-incomplete)
- [ ] For CC-1..CC-5: the conditional gate owners are named so the assignee knows whose sign-off to await

## Related documents

- [AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) — task spec / PR / handoff templates
- [ContextManagementStrategy.md](./ContextManagementStrategy.md) — context packs and anti-pollution rules
- [AIValidationWorkflow.md](./AIValidationWorkflow.md) — what "verified" means; CI-is-truth
- [AgentResponsibilities.md](./AgentResponsibilities.md), [AIAgentCatalog.md](./AIAgentCatalog.md) — role cards referenced by prompts
- [DefinitionOfReady.md](./DefinitionOfReady.md), [DevelopmentLifecycle.md](./DevelopmentLifecycle.md), [SprintExecutionGuide.md](./SprintExecutionGuide.md)
- [AIEngineeringGuide.md](./AIEngineeringGuide.md) — day-in-the-life usage of these prompts
- [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md) — the product prompt templates this document explicitly does NOT govern
- [../docs/implementation/PhaseBasedImplementationPlan.md](../docs/implementation/PhaseBasedImplementationPlan.md), [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md)
