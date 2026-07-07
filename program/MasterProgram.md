# Master Program — the /program constitution and entry point

The `/program` layer sequences and packages the baselined specification (tag `spec-v1.0`, under [`../docs`](../docs/)) and the Engineering Operating System ([`../engineering-operating-system`](../engineering-operating-system/README.md)) into an executable sprint plan, so a future Claude Code session implements in the right order, in parallel where safe, with minimal context. **This is the first `/program` file every session reads.** It is a router and a rulebook, not a spec: it holds no requirement, contract, gate definition, or architecture decision of its own — those live in `/docs` and the EOS and are only ever linked.

## 1. What `/program` is — and is not

| `/program` **is** | `/program` **is not** |
|---|---|
| The SEQUENCING and PACKAGING layer over `spec-v1.0` + EOS | A second copy of the spec or the EOS |
| The order of build, the sprint boundaries, the reading lists, the lanes | The definition of what to build (that is `../docs`) or how gates work (that is the EOS) |
| Program-local **sprint objectives** (`SPRINT-00`…`SPRINT-03`) and the reading manifests | A source of new module names, story IDs, gates, change classes, roles, or ADRs |

**Non-duplication rule (normative).** `/program` MUST NOT restate spec or EOS content — it MUST link with relative paths (`../docs/…`, `../engineering-operating-system/…`, `../reviews/…`) and `./Sibling.md` for program siblings. `/program` adds **no new IDs** except program-local sprint objectives. Every phase, exit-criteria, NFR, gate, change-class, role, and ADR fact is cited to its source document, never re-derived here. This mirrors the EOS reference-not-restate law ([ContextManagementStrategy §7](../engineering-operating-system/ContextManagementStrategy.md)); the drift catalogued there is exactly what restating would reintroduce.

## 2. The planning chain: phase → sprint → story → task

`/program` occupies the middle of the EOS planning chain ([DevelopmentLifecycle §1](../engineering-operating-system/DevelopmentLifecycle.md)); it does not invent it.

| Level | Artifact | ID format | Owner | Source of record |
|---|---|---|---|---|
| **Phase** | Phases 0–5 → **v0.1 … v1.0** | `Phase 0`…`Phase 5` | R-PO (scope) + R-CA (architecture) | [PhaseBasedImplementationPlan](../docs/implementation/PhaseBasedImplementationPlan.md) · [Roadmap §1](../docs/product/Roadmap.md) · exit criteria [PRD §10](../docs/product/PRD.md) |
| **Sprint** | 2-week increment | `SPRINT-NN` | R-TPM | This layer: [ImplementationRoadmap](./ImplementationRoadmap.md) · [SprintCatalog](./SprintCatalog.md) · `Sprint00`–`Sprint03` |
| **Story** | Row in a phase epic table | `P<phase>-E<epic>-S<story>` | R-PO | [PhaseBasedImplementationPlan §5](../docs/implementation/PhaseBasedImplementationPlan.md) (Phase 0), [§6](../docs/implementation/PhaseBasedImplementationPlan.md) (Phase 1) |
| **Task** | Session-sized unit | `TASK-NNNN` | R-TPM creates; one agent executes | [DevelopmentLifecycle §2–§4](../engineering-operating-system/DevelopmentLifecycle.md) · `/work/tasks/` |

`/program` maps **phases to sprints** and **sprints to the exact stories** each carries (see [ImplementationRoadmap](./ImplementationRoadmap.md) and [SprintCatalog](./SprintCatalog.md)); R-TPM decomposes those stories into `TASK-NNNN` files in `/work/tasks/` at sprint planning. Phase 0 (`v0.1`) is `SPRINT-00`…`SPRINT-03` and carries all 15 Phase-0 stories `P0-E1-S1`…`P0-E5-S2` exactly — no story is reassigned. Vocabulary — task states `INTAKE→READY→CLAIMED→IN_PROGRESS→IN_REVIEW→MERGED→VERIFIED→DONE`, gates `G0–G8`/`RG1–RG4`, change classes `CC-1..CC-7`, roles `R-XX`, ADRs `ADR-001..020` — is the EOS's and is used exactly as defined there.

## 3. The golden rules (the constitution)

These five rules bind every session and every `/program` document. They are packaging discipline on top of the EOS's ten laws ([`../CLAUDE.md`](../CLAUDE.md) "ten laws"), not a replacement for them.

1. **Read ONLY your ContextManifest.** A session reads its sprint's entry in [ContextManifest](./ContextManifest.md) — REQUIRED, then REFERENCE-ON-DEMAND only when a task needs it — and nothing else. The manifest is the sprint-level projection of the EOS context-pack law ([ContextManagementStrategy §2–§3](../engineering-operating-system/ContextManagementStrategy.md)); reading outside it wastes context and invites premature coupling. FORBIDDEN documents are forbidden for a reason (rule enforced per sprint in [ContextManifest](./ContextManifest.md)).
2. **Every sprint ships a demoable vertical slice.** Each sprint ends with a runnable, reviewable increment **through the UI on the Docker Compose stack** — never a backend-only milestone ([PhaseBasedImplementationPlan §4](../docs/implementation/PhaseBasedImplementationPlan.md) vertical-slice principle). What "increment" means per sprint is defined in [IncrementStrategy](./IncrementStrategy.md). No big-bang integration.
3. **Sequence by architectural risk.** Build order is a dependency DAG front-loaded by architectural risk (blast radius × uncertainty): the spikes that foreclose or unblock everything downstream go first. The order and its rationale are in [DevelopmentSequence](./DevelopmentSequence.md); the DAG in [ModuleBuildOrder](./ModuleBuildOrder.md) + [DependencyMatrix](./DependencyMatrix.md); the risk ordering in [RiskDrivenImplementation](./RiskDrivenImplementation.md).
4. **Single-writer lanes.** Parallel work runs in lanes bound to **disjoint write-sets** (EOS single-writer law, [`../CLAUDE.md`](../CLAUDE.md) law 4; [AIAgentCatalog §3](../engineering-operating-system/AIAgentCatalog.md)). Which lanes may run concurrently in each sprint is in [ParallelizationPlan](./ParallelizationPlan.md); cross-lane needs become dependency-linked tasks, never direct edits into another lane.
5. **Spec + EOS are law; link, never restate.** `/docs` (spec of record) and `/engineering-operating-system` (process of record) govern; on any conflict `/docs` wins (docs-first, [DevelopmentLifecycle §4](../engineering-operating-system/DevelopmentLifecycle.md)). `/program` cites them and defers to them (rule 1 of §1 above).

## 4. Map of the 15 `/program` documents

| # | Document | One-line purpose |
|---|---|---|
| **A1 — top-level program** | | |
| 1 | [MasterProgram.md](./MasterProgram.md) | This file: the `/program` constitution, golden rules, doc map, and how a session uses the program. |
| 2 | [ImplementationRoadmap.md](./ImplementationRoadmap.md) | Phase→version→sprint timeline: objective, sprints, exit-criteria pointer, and release gate per phase. |
| 3 | [DevelopmentSequence.md](./DevelopmentSequence.md) | The ordered execution narrative and decision logic — why this order, and the sequence invariants. |
| 4 | [IncrementStrategy.md](./IncrementStrategy.md) | Per-sprint "working increment" definition, feature-flag discipline, and demo-script pointers. |
| **A2 — structure & dependencies** | | |
| 5 | [ModuleBuildOrder.md](./ModuleBuildOrder.md) | Dependency-topological build order of the 11 modules and the definition of "built". |
| 6 | [DependencyMatrix.md](./DependencyMatrix.md) | The story/module dependency DAG — what must be DONE before what starts. |
| 7 | [ParallelizationPlan.md](./ParallelizationPlan.md) | Single-writer lanes per sprint bound to disjoint write-sets. |
| 8 | [RiskDrivenImplementation.md](./RiskDrivenImplementation.md) | Risk→sprint→spike→owning-role→exit-evidence, ordered by blast radius × uncertainty. |
| **A3 — context & catalog** | | |
| 9 | [ContextManifest.md](./ContextManifest.md) | Per-sprint REQUIRED / REFERENCE-ON-DEMAND / FORBIDDEN reading lists + token budgets (the critical doc). |
| 10 | [SprintCatalog.md](./SprintCatalog.md) | Catalog of all sprints (Phase 0 detailed, Phase 1 outlined) with the 11 required fields as columns. |
| 11 | [Phase0.md](./Phase0.md) | Phase-0 packaging: the four sprints, exit-criteria verification, and the v0.1 internal milestone. |
| **A4 — sprint files** | | |
| 12 | [Sprint00.md](./Sprint00.md) | `SPRINT-00` "Repo & platform bootstrap" — full sprint file (11 required fields). |
| 13 | [Sprint01.md](./Sprint01.md) | `SPRINT-01` "Persistence & tenancy spine" — full sprint file. |
| 14 | [Sprint02.md](./Sprint02.md) | `SPRINT-02` "AuthN/Z, audit, secrets" — full sprint file. |
| 15 | [Sprint03.md](./Sprint03.md) | `SPRINT-03` "Console shell & Phase-0 close (v0.1)" — full sprint file. |

Every sprint file and every `SprintCatalog` row carries the same 11 required fields: **Objective · Modules · Required Documents · Forbidden Documents · Inputs · Outputs · Deliverables · Exit Criteria · Review Gates · Estimated Context Size** (plus Stories, Lanes, Risks addressed, Demo increment).

## 5. How a session uses the program

A cold-start session reads in this exact order, then stops reading and starts working:

1. **[`../CLAUDE.md`](../CLAUDE.md)** — the universal bootstrap (EOS session ritual; read every session, no exceptions).
2. **This file** ([MasterProgram.md](./MasterProgram.md)) — to learn the golden rules and locate its sprint.
3. **[ContextManifest.md](./ContextManifest.md)** — the entry for its sprint: the exact REQUIRED files (and sections), the token budget, and the FORBIDDEN list. **This replaces "read the repo."**
4. **Its sprint file** — [Sprint00.md](./Sprint00.md) / [Sprint01.md](./Sprint01.md) / [Sprint02.md](./Sprint02.md) / [Sprint03.md](./Sprint03.md) — for objective, deliverables, lanes, exit criteria, and review gates.
5. **Its task** — `/work/tasks/TASK-NNNN.md` and the task's own context pack (the sprint manifest, narrowed to one task).

Then the session executes the EOS per-task flow ([DevelopmentLifecycle §4](../engineering-operating-system/DevelopmentLifecycle.md)): claim → read pack → branch → implement to spec → pass gates → review (G8) → merge → verify → close. A session that has lost context recovers via [ContextManagementStrategy §5](../engineering-operating-system/ContextManagementStrategy.md) (task file → highest handoff → branch inspection → CI-is-truth) before writing any code.

## 6. Sources of record `/program` sequences

| Source | Role | Enters `/program` via |
|---|---|---|
| [`../docs`](../docs/) (`spec-v1.0`) | Product & architecture spec of record | Per-sprint Required/Forbidden document sets ([ContextManifest](./ContextManifest.md)) |
| [`../engineering-operating-system`](../engineering-operating-system/README.md) | Process, gates, roles, lifecycle | Golden rules (§3), gate mapping in every sprint file |
| [PhaseBasedImplementationPlan](../docs/implementation/PhaseBasedImplementationPlan.md) | P-E-S stories, first-10-PRs, phase demos | Sprint→story mapping, demo pointers |
| [Roadmap](../docs/product/Roadmap.md) · [PRD §10](../docs/product/PRD.md) | Phase/version + phase exit criteria | [ImplementationRoadmap](./ImplementationRoadmap.md), `RG1` |
| [ImplementationReadinessDecision](../reviews/architecture-readiness/ImplementationReadinessDecision.md) · [ArchitectureDecisionUpdates](../reviews/architecture-readiness/ArchitectureDecisionUpdates.md) | Blocking questions, accepted risks, inviolable principles, ADR-015..020 | [DevelopmentSequence](./DevelopmentSequence.md) invariants, [RiskDrivenImplementation](./RiskDrivenImplementation.md) |
| [ArchitectureOverview §7](../docs/architecture/ArchitectureOverview.md) | ADR-001..020 index | Referenced, never restated |

## Related documents

- [ImplementationRoadmap.md](./ImplementationRoadmap.md) · [DevelopmentSequence.md](./DevelopmentSequence.md) · [IncrementStrategy.md](./IncrementStrategy.md) — the rest of the A1 bundle
- [ContextManifest.md](./ContextManifest.md) · [SprintCatalog.md](./SprintCatalog.md) · [Phase0.md](./Phase0.md) — context and catalog
- [ModuleBuildOrder.md](./ModuleBuildOrder.md) · [DependencyMatrix.md](./DependencyMatrix.md) · [ParallelizationPlan.md](./ParallelizationPlan.md) · [RiskDrivenImplementation.md](./RiskDrivenImplementation.md) — structure, deps, risk
- [Sprint00.md](./Sprint00.md) · [Sprint01.md](./Sprint01.md) · [Sprint02.md](./Sprint02.md) · [Sprint03.md](./Sprint03.md) — the Phase-0 sprint files
- [`../CLAUDE.md`](../CLAUDE.md) — universal session bootstrap
- [`../engineering-operating-system/DevelopmentLifecycle.md`](../engineering-operating-system/DevelopmentLifecycle.md) · [`../engineering-operating-system/QualityGatePolicy.md`](../engineering-operating-system/QualityGatePolicy.md) · [`../engineering-operating-system/ContextManagementStrategy.md`](../engineering-operating-system/ContextManagementStrategy.md) — EOS law
- [`../docs/implementation/PhaseBasedImplementationPlan.md`](../docs/implementation/PhaseBasedImplementationPlan.md) · [`../docs/product/Roadmap.md`](../docs/product/Roadmap.md) · [`../docs/product/PRD.md`](../docs/product/PRD.md) — spec of record
