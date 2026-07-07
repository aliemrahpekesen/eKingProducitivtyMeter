# Context Management Strategy

This document defines how knowledge is layered, packaged, and preserved so that any engineering agent can execute any task from a cold start. It is read by R-TPM whenever curating a context pack, by every implementing role (R-IE/R-TE/R-RE) at session start, and by module owners when maintaining `MODULE.md` charters. The premise is absolute: **the repository is the shared memory**. Engineering agents have no memory between sessions; anything not written into the layers below is lost by design. (The "context layers" L1–L4 here are unrelated to the validation levels L0–L4 in [./AIValidationWorkflow.md](./AIValidationWorkflow.md) — same letters, different ladders.)

## 1. The four context layers

| Layer | Name | Contents | Location | Durability | Who writes |
|---|---|---|---|---|---|
| **L1** | Canon | Product spec, EOS, decisions of record | `/docs`, `/engineering-operating-system`, `/docs/adr` | Permanent; changes are governed (docs-first policy, CC-1 for anchors, ADRs) | Owning roles per [ModuleOwnership.md](./ModuleOwnership.md); anchors need G4 |
| **L2** | Charters | Per-module operational truth | `<module>/MODULE.md` (§6) | Permanent; updated in the same PR as the change it describes | Module owner role |
| **L3** | Work state | Sprints, tasks, handoffs, registers | `/work/sprints/`, `/work/tasks/`, `/work/handoffs/`, `/work/debt-register.md`, `/work/risk-register.md` | Sprint-to-phase horizon; append-only history | Per [./AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) §2 |
| **L4** | Session context | The material actually loaded for one task: context pack + working notes | Assembled in-session from the task spec | **Ephemeral — evaporates at session end** | The executing agent |

Rules that follow from the layering:

- L4 MUST be reconstructible from L1–L3 alone. If completing the current step depends on something only in L4, write it down (to L2 or L3) **now**, not at session end.
- L1 is cited, never restated: the cross-document drift catalogued in [../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) §2.1 (RLS GUC names, DLQ naming, webhook paths) came from restating anchors. Contract anchors are those listed in [./ArchitecturePrinciples.md](./ArchitecturePrinciples.md); changing them is CC-1.
- `/CLAUDE.md` at the repository root is the universal session bootstrap. Every engineering agent MUST read it first, before the task spec, in every session — no exceptions.

## 2. Context-pack construction rules

The context pack is the ordered reading list inside the task spec ([./AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) §3.1). R-TPM curates it at task creation; the owning domain architect SHOULD review it for CC-1..CC-5 tasks.

1. **The context pack is the ONLY required pre-reading.** If executing the task correctly requires a document not in the pack, the pack is defective — fix the pack (R-TPM), do not blame the agent.
2. **Ordered.** Entries are read top to bottom: bootstrap → task → module charter(s) → spec sections → decision records. Order encodes dependency, not importance.
3. **Path + section granularity.** Every entry cites a file path AND a section (`docs/engineering/EventModel.md §9`), never a bare file over ~200 lines. Every entry carries a one-line "why".
4. **Fits one session.** Total pack size MUST be readable and actionable within a single session alongside the implementation work. Practical ceiling: ~10 entries / ~1,500 lines of referenced material. A task whose pack cannot be trimmed to fit is size L and MUST be split (DoR, G0).
5. **Pinned.** Entries reference committed files at repo HEAD; "latest", "current draft", or out-of-repo URLs are prohibited ([./PromptEngineeringStandards.md](./PromptEngineeringStandards.md) §4).
6. **No bodies.** The pack contains pointers, never pasted content (§7).
7. **Per-task, never shared.** Each task file carries its own complete pack, even when it duplicates a sibling task's list. "Same pack as TASK-NNNN" is prohibited — it breaks single-file replayability and rots when the referenced task changes.
8. **Self-contained addressing.** Pack entries use repo-root paths (`docs/…`, `backend/…`); a pack MUST be resolvable by an agent whose only starting knowledge is the repository checkout.

## 3. Reading lists per task type

Every pack starts with the invariant prefix, then adds the task-type suffix. `MODULE.md` means the charter of every module in the task's write-set.

**Invariant prefix (all tasks):** `/CLAUDE.md` → `/work/tasks/TASK-NNNN.md` (the task itself) → `MODULE.md`(s) → ADRs named by the task.

| Task type | Add, in order (sections chosen per task) |
|---|---|
| Backend | [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) · [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) · [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) · [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) if surface touched |
| Frontend | [../docs/engineering/FrontendPlan.md](../docs/engineering/FrontendPlan.md) · [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) (consumed endpoints) · [../docs/product/Personas.md](../docs/product/Personas.md) + [../docs/product/UserJourneys.md](../docs/product/UserJourneys.md) (the journey served) · [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §9 |
| Connector | [../docs/engineering/ConnectorFramework.md](../docs/engineering/ConnectorFramework.md) §2–§10 + the connector's §11 catalog entry · [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §2–§4 · [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §5.3 (kit K1–K7), §6 |
| AI (eip-ai) | [../docs/ai/AgentArchitecture.md](../docs/ai/AgentArchitecture.md) · [../docs/ai/RAGArchitecture.md](../docs/ai/RAGArchitecture.md) · [../docs/ai/MCPArchitecture.md](../docs/ai/MCPArchitecture.md) (relevant sections only) · [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §8 · [AIEngineeringGuide.md](./AIEngineeringGuide.md) |
| Schema / migration | [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) (owned tables + RLS) · [../docs/architecture/DomainModel.md](../docs/architecture/DomainModel.md) (entities touched) · [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §3.1 · expand–contract rule in [./RepositoryRules.md](./RepositoryRules.md) |
| Infra / CI | [../docs/infrastructure/DockerCompose.md](../docs/infrastructure/DockerCompose.md) or [../docs/infrastructure/KubernetesOpenShift.md](../docs/infrastructure/KubernetesOpenShift.md) · [../docs/infrastructure/LocalDevelopment.md](../docs/infrastructure/LocalDevelopment.md) · [../docs/operations/OperationsGuide.md](../docs/operations/OperationsGuide.md) (affected runbooks) · [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §14 |
| Docs (CC-6) | [DocumentationStandards.md](./DocumentationStandards.md) · the target doc(s) in full · [../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) §5 (lint rules rationale) |

Security-relevant (CC-2) tasks of any type additionally include [../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md) (relevant sections) and [SecurityChecklist.md](./SecurityChecklist.md). Perf-relevant (CC-3) tasks add the NFR budget rows from [../docs/product/PRD.md](../docs/product/PRD.md) §NFR and [PerformanceChecklist.md](./PerformanceChecklist.md).

## 4. The durable-knowledge rule

Before ending any session, an engineering agent MUST bank everything another agent would need, into the correct layer:

| Knowledge produced this session | Destination | Vehicle |
|---|---|---|
| Architectural or contract decision | L1 | ADR via [./ADRProcess.md](./ADRProcess.md); doc change first (docs-first policy) |
| Spec clarification (doc was ambiguous/wrong) | L1 | Doc fix PR (small clarifications may ride the same PR) |
| New invariant, gotcha, owned table/topic/endpoint | L2 | `MODULE.md` update in the same PR |
| Task progress, partial results, dead ends | L3 | Handoff note `/work/handoffs/TASK-NNNN-<seq>.md` — MANDATORY for every session ending before DONE |
| Accepted shortcut | L3 | DEBT entry (register-or-fix rule) |
| Discovered hazard | L3 | RISK entry |
| State change of the task | L3 | Task file status log + sprint status entry |

A merged PR whose learnings exist only in the PR discussion has violated this rule; R-CR SHOULD flag missing `MODULE.md`/docs updates as MAJOR at G8, and G7 catches missing doc impact.

## 5. Context-loss recovery procedure

When an agent starts (or restarts) with no session memory and an uncertain task state, it MUST recover in this exact order — and MUST NOT write code before step 7:

1. Read `/CLAUDE.md` (bootstrap).
2. Read the task spec `/work/tasks/TASK-NNNN.md` — state field and status log first.
3. Read the highest-`<seq>` handoff note in `/work/handoffs/` for the task, if any.
4. Inspect the branch: `git log --oneline main..feature/TASK-NNNN-<slug>` and the diff versus the declared write-set.
5. Check CI status for the branch's latest commit; CI is truth ([./AIValidationWorkflow.md](./AIValidationWorkflow.md) §2), not the handoff's claims — trust but re-verify what the handoff says "works".
6. Read the context pack entries relevant to the next step (not necessarily all again).
7. Write a status-log line recording resumption; if the reconstructed state is ambiguous (e.g., branch contents contradict the handoff), set the task BLOCKED with an escalation record to R-TPM instead of guessing.

If no handoff note exists for a non-DONE task whose session ended, that is a protocol violation: R-TPM reassigns the task, and the recovery agent treats the branch as untrusted — it MUST re-verify every claim from CI before building on it.

## 6. MODULE.md charter template

Every module (the 9 backend modules and `/frontend`) MUST carry a `MODULE.md` at its root, created with the module in Phase 0/1 and owned by the module's owner role per [ModuleOwnership.md](./ModuleOwnership.md). It is the L2 charter — the 5-minute truth an agent needs before touching the module. Copyable template:

```markdown
# MODULE — <module name>

**Owner:** R-XX (per ModuleOwnership.md) · **Spec home:** <primary /docs file(s)>

## Purpose
<2–4 sentences: what this module is for and what it is explicitly NOT for>

## Owned surface
- **Tables:** <schema.table, …>            (must match ../docs/engineering/DatabasePlan.md)
- **Topics:** <eip.… produced / consumed>   (must match ../docs/engineering/EventModel.md §3)
- **Endpoints:** </api/v1/… owned>          (must match ../docs/engineering/APIDesign.md)
- **Published APIs/SPIs:** <types other modules may use>

## Invariants
<numbered, testable statements — e.g. "all upserts keyed on ExternalRef (FR-016)";
 each SHOULD name the test or ArchUnit rule that enforces it>

## Dependencies
- **Uses:** <modules/published APIs consumed>
- **Used by:** <modules that consume this one>

## Gotchas
<hard-won operational truths: ordering traps, fail-open behaviors, migration quirks —
 dated, initialed with role ID, pruned when obsolete>
```

Charter discipline: `MODULE.md` MUST change in the same PR that changes the owned surface (new table/topic/endpoint) — R-CR verifies at G8; drift between a charter and `/docs` is resolved in favor of `/docs` and fixed immediately (charters summarize L1, they never override it).

## 7. Rules against context pollution

1. **Never paste document bodies** into prompts, task specs, PR descriptions, handoffs, or review comments. Link path + section. Pasted copies fork the truth and rot instantly — the documented root cause of the pre-review drift ([../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) §5.3). Quoting ≤ 3 lines verbatim for a review finding is the only exception, and MUST carry the source path.
2. **Never restate numbers.** NFR values live in [../docs/product/PRD.md](../docs/product/PRD.md) and fan out by ID (NFR-003, NFR-012, …). Write "the NFR-012 budget", not "60 seconds" — docs-lint greps for drifted canonical values.
3. **No unpinned references.** "The latest EventModel", "as discussed" and out-of-repo links are prohibited in all artifacts.
4. **No transcript archaeology.** Session transcripts, chat logs, and author reasoning are not artifacts; nothing may depend on them, and R-CR is explicitly forbidden from reading them ([./AIValidationWorkflow.md](./AIValidationWorkflow.md) §3).
5. **Keep L3 lean.** Task files and handoffs state conclusions and pointers, not exploration logs. A handoff over ~100 lines SHOULD be pruned to the template's fields.

## 8. Layer maintenance and audit

Context that is not maintained decays into misinformation. Maintenance duties, by layer:

| Layer | Cadence | Duty | Owner |
|---|---|---|---|
| L1 | every PR | docs-lint green (links, IDs, canonical values); docs impact declared at G7 | R-DE |
| L1 | per ADR | Superseded ADRs marked, never deleted; index in [../docs/architecture/ArchitectureOverview.md](../docs/architecture/ArchitectureOverview.md) §7 current | R-CA |
| L2 | same PR as surface change | Charter updated with the change (§6); R-CR blocks drift at G8 | module owner |
| L2 | sprint review | DoD audit sample (random 20% of DONE tasks) includes charter-freshness check | R-TPM + R-QAA |
| L3 | sprint close | Every non-DONE task has a current status log; every abandoned session has a handoff; registers triaged | R-TPM |
| L3 | phase exit | PARKED tasks re-justified or closed; DEBT entries older than 2 phases escalated to R-CA ([./TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md)) | R-TPM |

Staleness rules:

- **Stale pack:** if any document cited by a READY task's context pack changes before the task is CLAIMED, R-TPM MUST re-validate the pack (sections may have moved or been renumbered) before the claim. Claiming against a known-stale pack is a G0 violation.
- **Stale gotcha:** `MODULE.md` Gotchas entries are dated and role-initialed (§6); an entry that no longer reproduces is deleted in the next PR touching the module, not left to mislead.
- **Stale handoff:** a handoff note is superseded by any later `<seq>` for the same task; recovery (§5) always starts from the highest sequence number and treats older notes as history only.

## Related documents

- [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) — constitution; §0 disambiguation
- [AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) — the artifacts that carry L3
- [PromptEngineeringStandards.md](./PromptEngineeringStandards.md) — how packs appear in prompts
- [AIValidationWorkflow.md](./AIValidationWorkflow.md) — CI-is-truth; reviewer isolation
- [ModuleOwnership.md](./ModuleOwnership.md), [RepositoryStructure.md](./RepositoryStructure.md), [RepositoryRules.md](./RepositoryRules.md)
- [DefinitionOfReady.md](./DefinitionOfReady.md) — pack-fits-one-session is a G0 criterion
- [DocumentationStandards.md](./DocumentationStandards.md), [ADRProcess.md](./ADRProcess.md)
- [../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) — evidence for reference-not-restate
