# ADR Process

Who reads this, when: any engineering role about to make (or having discovered the need for) a decision-level change — before writing code; R-CA when approving; R-CR when verifying that a PR claiming "no ADR needed" is classified correctly. Architecture Decision Records are the durable memory of *why* the system is shaped as it is; in a repo built by many short-lived agent sessions, an unrecorded decision is a decision that will be re-litigated and drifted. The existing decisions ADR-001–ADR-014 are summarized in [ArchitectureOverview §7](../docs/architecture/ArchitectureOverview.md).

## 1. When an ADR is REQUIRED

| # | Trigger | Examples | Notes |
|---|---|---|---|
| T1 | Decision-level CC-1 change (contract anchors per [QualityGatePolicy §2](./QualityGatePolicy.md), G4) | New topic family or envelope field; SPI signature change; RLS policy model change; `/api/v1` breaking change | G4 applies regardless; the ADR records the decision behind the anchor diff. An API breaking change additionally needs the `api-breaking-change` label with ADR reference per [TestingStrategy §5.2](../docs/testing/TestingStrategy.md) |
| T2 | New dependency of consequence | Any new runtime library/service in production images beyond routine version bumps; any new infrastructure container | Also passes [DependencyManagement](./DependencyManagement.md) (license allow-list, air-gap fitness) |
| T3 | NFR tradeoff or renegotiation | Changing RTO/RPO (NFR-021/022), retention defaults (NFR-070), Compose sizing (NFR-050), lowering a coverage ratchet ([TestingStrategy §1](../docs/testing/TestingStrategy.md)) | PRD updated in the same PR; the [DocumentationQualityReview §5](../docs/reviews/DocumentationQualityReview.md) names exactly these as the decisions that must "update the PRD NFR and fan out, not fork quietly" |
| T4 | Deviation from /docs | Implementation cannot follow a documented design; a documented decision proves wrong | Docs-first: ADR + doc update land before or with the code — never after |
| T5 | Architectural structure change | New backend module; changed module dependency edge; new deployable; new Spring profile | ArchitectureOverview §5/§10 updated in the same PR |
| T6 | Technology introduction/replacement | Client state store (Redux/Zustand — [FrontendPlan §7](../docs/engineering/FrontendPlan.md) explicitly demands an ADR), WebSocket layer, replacing Quartz, CDC instead of the outbox poller | |

**NOT required** (record reasoning in the task file instead): CC-7 implementation choices inside existing patterns; bug fixes; additive endpoints following [APIDesign](../docs/engineering/APIDesign.md) conventions; version bumps within policy; behavior-preserving refactors; test-only or docs-only changes. When in doubt, ask the owning domain architect — a two-line "no ADR needed because…" in the task file is cheap; a silent decision is not.

## 2. Lifecycle

**Proposed → Accepted → Superseded.** These are the only statuses.

| Status | Meaning | Set by |
|---|---|---|
| Proposed | PR open; decision under review | Author, at file creation |
| Accepted | R-CA approval recorded in the `approver` field; PR merged | R-CA |
| Superseded | A later ADR replaces it; this file gains a pointer to the successor | Author of the superseding ADR, in the same PR |

- A rejected proposal is not a status: the PR closes unmerged and the outcome is recorded as an escalation record in the task file ([AgentCommunicationProtocol §3.5](./AgentCommunicationProtocol.md) template: blocker · options · recommendation · decision + decider + date).
- Accepted ADRs are immutable except: status change to Superseded (+ successor link), typo/link fixes, and index-row corrections. Any substantive change = a new ADR that supersedes the old one. History is never rewritten.

## 3. Template

File: `/docs/adr/ADR-NNN-slug.md`. Field list is canonical ([AgentCommunicationProtocol §3.4](./AgentCommunicationProtocol.md)); no fields added or removed.

```markdown
# ADR-NNN: <decision title, imperative>

- **Status:** Proposed | Accepted | Superseded (by ADR-MMM)
- **Approver:** R-CA (<date>)
- **Affected anchors/modules:** <contract anchors touched (G4 anchor list, QualityGatePolicy §2); eip-* modules; change class(es)>

## Context
<the forces: which drivers (D1–D8), NFR/FR IDs, and constraints are in play — cite IDs, do not restate numbers>

## Decision
<one imperative sentence, then its scope and mechanics>

## Consequences
<positive AND negative; operational cost against D6; what becomes easier/harder>

## Alternatives rejected
<at least two real alternatives, each with the concrete reason for rejection>
```

## 4. Numbering and storage

- Storage: `/docs/adr/`, one file per ADR, named `ADR-NNN-slug.md` — NNN is three digits, zero-padded, monotonic, never reused or renumbered ("ADR-0001"-style four-digit forms are invalid; the DocumentationQualityReview fixed exactly that drift).
- **Numbering continues from ADR-014. The next new decision is ADR-015.**
- **Phase 0 backfill task:** ADR-001…ADR-014 are backfilled from the [ArchitectureOverview §7](../docs/architecture/ArchitectureOverview.md) summary table. R-TPM creates a Phase 0 `TASK-NNNN` (CC-6); R-DE authors the fourteen files using the §3 template with status Accepted, content expanded from the §7 rationale column plus the underlying spec sections (e.g. ADR-010 from [EventModel §1/§9](../docs/engineering/EventModel.md), ADR-014 from [SecurityModel §6](../docs/architecture/SecurityModel.md)); each carries a context note "Backfilled in Phase 0 from ArchitectureOverview §7"; R-CA approves. The §7 table remains the index and each row gains a relative link to its file.
- Number collisions from parallel branches are resolved at merge by the later PR renumbering its **own new** file before merge (single-writer rule makes this rare).

## 5. Review flow

| Step | Actor | SLA |
|---|---|---|
| 1. Draft ADR on branch `adr/TASK-NNNN-slug` ([BranchingStrategy §2](./BranchingStrategy.md); or the task's feature branch when the ADR rides a CC-1 change); commit `docs(docs): add ADR-NNN <slug> [TASK-NNNN]` | Author (any engineering role; typically R-IE who hit the decision, or the domain architect) | — |
| 2. Review by the owning domain architect (A2) for the affected domain per [ModuleOwnership](./ModuleOwnership.md) | R-BA/R-FA/R-AIA/R-DA/R-DBA/R-CNA/R-DOA/R-SA/R-PA/R-QAA as applicable | 2 sessions |
| 3. Approval by R-CA (A1); name + date recorded in the `approver` field | R-CA | 2 sessions |
| 4. Merge via normal PR gates; CC-1 ADRs require two G8 approvals (R-CR + owning architect + R-CA per [QualityGatePolicy §2](./QualityGatePolicy.md), G8) | R-CR | — |

- Security-relevant ADRs (CC-2 territory) additionally require R-SA, who holds an A4 block — overridable only by the human repository owner, recorded.
- SLA breach (no verdict within 2 sessions) escalates up the chain: owning architect → R-CA → human repository owner.
- The author never merges without approvals; R-CA disagreement with the owning architect is decided by R-CA (A1), recorded in the ADR's context.

## 6. Propagation

An ADR is not done when Accepted — it is done when nothing in the repo contradicts it:

1. **Same-PR doc updates (docs-first).** Every /docs file affected by the decision changes in the same PR as the ADR. The [DocumentationStandards §3](./DocumentationStandards.md) update matrix identifies the set; the ADR's "Affected anchors/modules" field must match the PR's actual diff (R-CR verifies).
2. **Index row.** A new row is added to ArchitectureOverview §7 (ADR id, decision, status, rationale one-liner) linking to the file. Superseding updates the old row's status too.
3. **MODULE.md.** Modules whose invariants change get their charter updated in the same PR.
4. **Downstream tasks.** If the decision invalidates open tasks, the author flags R-TPM, who re-scopes or PARKs them; if it creates follow-up work, DEBT/TASK entries are filed — an ADR MUST NOT rely on "someone will notice".
5. **docs-lint (G7)** verifies the links and ID references resolve; a dangling `ADR-NNN` reference anywhere in /docs fails the gate.

## 7. ADR quality checklist

R-CA applies this before approving; R-CR applies it at G8. Any unchecked box blocks Accepted status.

- [ ] Title is an imperative decision, not a topic ("Use Quartz clustered JDBC store", not "Scheduling").
- [ ] Status, approver + date, affected anchors/modules and change class(es) filled; ID format `ADR-NNN`.
- [ ] Context cites drivers (D1–D8) and FR/NFR IDs; no restated numeric values — NFR figures appear as IDs with links.
- [ ] Decision is one sentence plus scope; a reader can tell exactly what is now mandatory/forbidden.
- [ ] At least two genuine alternatives with concrete rejection reasons (no strawmen); "do nothing" considered where meaningful.
- [ ] Consequences include the negative ones and the on-prem operational cost (D6).
- [ ] All affected /docs updated in the same PR; ArchitectureOverview §7 row added; docs-lint green.
- [ ] Superseded ADRs (if any) updated with successor pointers.
- [ ] The decision is at the right altitude: implementation details belong in /docs plans, not the ADR; the ADR records the choice and the why.

## 8. Worked classification examples

Calibration table for the §1 triggers — when two agents disagree on "does this need an ADR?", they check here first, then ask the owning architect.

| Proposed change | ADR? | Why |
|---|---|---|
| Add `eip.domain.workitem` consumer in `eip-reports` within the allowed events-only edge | No | Existing pattern, allowed edge; task-file note suffices (CC-7) |
| Add a new topic family `eip.notifications.*` | Yes — T1 | New contract-anchor surface in [EventModel §3](../docs/engineering/EventModel.md); CC-1, G4 |
| Bump Resilience4j within the version catalog | No | Routine version bump under [DependencyManagement](./DependencyManagement.md) weekly batch |
| Introduce Zustand for a wizard's client state | Yes — T6 | [FrontendPlan §7](../docs/engineering/FrontendPlan.md) explicitly reserves this for an ADR |
| Replace the outbox poller with Debezium CDC | Yes — T6 | Reverses the documented decision in [BackendPlan §6](../docs/engineering/BackendPlan.md); new stateful infra vs D6 |
| Relax raw-staging retention from 90 to 30 days | Yes — T3 | NFR-070 renegotiation; PRD + [SecurityModel §7](../docs/architecture/SecurityModel.md) retention table fan-out |
| Add an optional field to a connector's JSON Schema config | No | Additive, connector-versioned per [ConnectorFramework §2.1](../docs/engineering/ConnectorFramework.md); CC-7/CC-2 gates as applicable |
| Split `eip-ingestion` normalizers into a new Gradle module | Yes — T5 | Module map change; ArchitectureOverview §5 + ComponentModel + MODULE.md updates |

## 9. Operating notes

- **One decision per ADR.** A PR bundling several decisions files several ADRs; shared context may be cross-linked, never merged into an omnibus record.
- **ADRs vs escalation records.** Task-local blockers resolved within existing patterns are escalation records in the task file ([AgentCommunicationProtocol §3.5](./AgentCommunicationProtocol.md)); only decisions with cross-task consequence become ADRs. R-TPM promotes recurring escalation themes to ADR candidates at sprint review.
- **Reading discipline.** Context packs for tasks touching a module MUST list that module's relevant ADRs ([ContextManagementStrategy](./ContextManagementStrategy.md)); an agent that contradicts an Accepted ADR without superseding it produces a BLOCKER at G8, regardless of code quality.
- **Backfill is the floor, not the ceiling.** Decisions made during Phase 0 implementation (e.g. resolving PRD §11 open questions like OQ#6 air-gapped PDF/PPTX renderer or OQ#8 reference hardware) get real-time ADRs numbered from ADR-015 — the backfill task covers only ADR-001…014.

## Related documents

- [ArchitecturePrinciples.md](./ArchitecturePrinciples.md) — the standing invariants ADRs may refine or amend
- [DocumentationStandards.md](./DocumentationStandards.md) — update matrix, docs-lint, taxonomy
- [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) — gates, change classes, roles
- [DevelopmentLifecycle.md](./DevelopmentLifecycle.md) · [QualityGatePolicy.md](./QualityGatePolicy.md) · [DependencyManagement.md](./DependencyManagement.md) · [ModuleOwnership.md](./ModuleOwnership.md) · [RepositoryRules.md](./RepositoryRules.md)
- Spec of record: [ArchitectureOverview §7](../docs/architecture/ArchitectureOverview.md) (ADR index) · [TestingStrategy](../docs/testing/TestingStrategy.md) · [DocumentationQualityReview](../docs/reviews/DocumentationQualityReview.md)
