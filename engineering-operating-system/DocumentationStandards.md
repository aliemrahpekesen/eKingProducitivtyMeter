# Documentation Standards

Who reads this, when: every engineering role — documentation is part of every change, not a separate activity; R-DE when running G7; R-CR when verifying at G8 that docs match the diff. The repository is the shared memory of all engineering agents (context layers L1–L4, [ContextManagementStrategy](./ContextManagementStrategy.md)): anything not written into the right document is lost by design. This document defines the document taxonomy, the change→doc update matrix, the mandatory docs-lint, and the style rules that keep 30+ spec documents drift-free — the [DocumentationQualityReview](../docs/reviews/DocumentationQualityReview.md) is the empirical record of what happens without them (~135 findings, all from restating instead of referencing).

## 1. Document taxonomy

| Class | Location | Contains | Owner | Notes |
|---|---|---|---|---|
| Spec of record | `/docs/**` | Product, architecture, engineering, AI, infra, ops, testing truth | Owning domain architect per [ModuleOwnership](./ModuleOwnership.md); R-PO for `/docs/product` | Contract anchors ([QualityGatePolicy §2](./QualityGatePolicy.md), G4): PRD, DomainModel, EventModel, APIDesign, ConnectorFramework SPI sections, SecurityModel — changes there are CC-1 |
| EOS governance | `/engineering-operating-system/` | How we build (this document set) | R-CA | Changes are CC-6 + R-CA approval |
| ADRs | `/docs/adr/ADR-NNN-slug.md` | Decision records | Author + R-CA | Process in [ADRProcess](./ADRProcess.md); indexed in [ArchitectureOverview §7](../docs/architecture/ArchitectureOverview.md) |
| Module charters (L2) | `<module>/MODULE.md` | Purpose · owned tables · owned topics/endpoints · invariants · dependencies | Owning architect | Created with each module in Phase 0/1; see §6 |
| Work artifacts (L3) | `/work/**` | `sprints/SPRINT-NN.md`, `tasks/TASK-NNNN.md`, `handoffs/`, `debt-register.md`, `risk-register.md` | R-TPM (structure); task owners (content) | Templates in [AgentCommunicationProtocol §3](./AgentCommunicationProtocol.md) |
| Code-adjacent generated | `/docs/api/openapi.json`, `docs/architecture/generated/` (Modulith Documenter) | Machine-generated contract snapshots | CI | Never hand-edited; a dirty regeneration diff fails CI ([TestingStrategy §4, §5.2](../docs/testing/TestingStrategy.md)) |
| Session bootstrap | `/CLAUDE.md` | Universal operating manual read first by every agent | R-CA | Kept minimal; points into L1–L3 |

## 2. The prime rule: reference, never restate

A document MUST cite the anchor that owns a fact (with a relative link and the FR/NFR/section ID) instead of restating its value. Numeric NFR values, topic names, envelope fields, the RLS GUC, role names, ports, and retention windows each have exactly one home; every other mention is a link. This is DocumentationQualityReview §5 recommendation 3, made mandatory. Restating with drift found at review is a MAJOR finding; the fix is a reference, not a synchronized copy.

## 3. Update matrix — change type → documents that MUST change (same PR)

The task spec's "docs impact" field ([AgentCommunicationProtocol §3.1](./AgentCommunicationProtocol.md)) is filled from this matrix at G0; G7 verifies it was applied.

| Change | MUST update |
|---|---|
| New/changed `/api/v1` endpoint | [APIDesign §4](../docs/engineering/APIDesign.md) catalog · regenerated `/docs/api/openapi.json` (operationId + `x-eip-permission` per APIDesign §10) · owning `MODULE.md` endpoint list |
| New/changed event type or topic | [EventModel](../docs/engineering/EventModel.md) §3 catalog and/or §4 taxonomy · versioned JSON Schema under `eip-core` resources · producer and consumer `MODULE.md`s |
| New/changed product metric | Metric definitions registry entry (purpose, formula, inputs, grain, caveats/limitations, gaming risks — [ArchitectureOverview §12](../docs/architecture/ArchitectureOverview.md)) · golden dataset case ([TestingStrategy §7](../docs/testing/TestingStrategy.md)) · metric changelog |
| New platform telemetry (metric/alert/dashboard) | [ObservabilityModel](../docs/architecture/ObservabilityModel.md) §3/§6/§7 |
| New connector | [ConnectorFramework §11](../docs/engineering/ConnectorFramework.md) catalog entry (streams, incremental strategy, quirks) · config schema documented per §12 pattern · `eip-connectors` `MODULE.md` · [FeatureCatalog](../docs/product/FeatureCatalog.md) row + count if user-facing |
| Schema migration (CC-4) | `MODULE.md` owned-tables list · [DatabasePlan](../docs/engineering/DatabasePlan.md) when it changes conventions, the §2 schema map, §4 index patterns, or §10 retention |
| New permission or role change | [SecurityModel §4](../docs/architecture/SecurityModel.md) catalog · `x-eip-permission` in OpenAPI · [FrontendPlan §2](../docs/engineering/FrontendPlan.md) gates/nav if UI-visible |
| Security-relevant behavior (CC-2) | [SecurityModel](../docs/architecture/SecurityModel.md) relevant section · [SecurityChecklist](./SecurityChecklist.md) run recorded in PR |
| NFR change | [PRD](../docs/product/PRD.md) first (via ADR, [ADRProcess §1 T3](./ADRProcess.md)) · every citing doc (canonical-value grep identifies the fan-out set) |
| New FR / feature | PRD · FeatureCatalog (+ recomputed counts) · [AcceptanceCriteria](../docs/product/AcceptanceCriteria.md) · [Roadmap](../docs/product/Roadmap.md) — R-PO owns |
| New runtime dependency | [DependencyManagement](./DependencyManagement.md) record · ADR if of consequence |
| New module / dependency edge | [ArchitectureOverview §5](../docs/architecture/ArchitectureOverview.md) · [ComponentModel](../docs/architecture/ComponentModel.md) · [BackendPlan §1](../docs/engineering/BackendPlan.md) · new `MODULE.md` · ADR |
| AI behavior (CC-5): agent, prompt, routing, RAG | Relevant `/docs/ai/*` document (AgentArchitecture / RAGArchitecture / MCPArchitecture) |
| Operational behavior (ports, probes, runbook steps) | [OperationsGuide](../docs/operations/OperationsGuide.md) and/or infrastructure docs |
| Any decision-level change | ADR + ArchitectureOverview §7 index row ([ADRProcess §6](./ADRProcess.md)) |

A PR whose diff implies a row here but whose docs don't change fails G7. "No docs impact" is a positive declaration in the PR description, verified by R-CR at G8.

## 4. docs-lint — definition (mandatory in CI, gate G7)

docs-lint runs on every PR and consists of exactly the four checks from [DocumentationQualityReview §5, recommendation 2](../docs/reviews/DocumentationQualityReview.md) (the scripts already exist from that review; the Phase 0 scaffolding PR set wires them into CI). Any failing check turns G7 red; merge is blocked.

| # | Check | Definition | Scope |
|---|---|---|---|
| L1 | Link resolution | Every relative link resolves to an existing file (and anchor, where one is given); zero broken links is the standing baseline (the review verified 0/65+) | `/docs`, `/engineering-operating-system`, all `MODULE.md`, `/work` |
| L2 | ID-reference resolution | Every referenced `FR-`, `NFR-`, `FEAT-`, `AC-`, `UC-`, `ADR-` ID resolves to its definition (baseline census: 104 FR, 22 NFR, 145 FEAT, 103 AC, 18 UC, 14 ADR — zero dangling); within `/work`, additionally `TASK-`, `SPRINT-`, `DEBT-`, `RISK-` | Same as L1 |
| L3 | Count reconciliation | FeatureCatalog and Roadmap stated totals equal their actual table row counts, recomputed programmatically | `/docs/product` |
| L4 | Canonical-value greps | No stale variants of load-bearing values anywhere: RLS GUC is `app.tenant_id` (never `eip.tenant_id`); DLQ pattern is `<group>.dlq` (never `<topic>.<group>.dlq`); NFR figures match the PRD (100k events/h + 3× burst NFR-003, 60 s p95 NFR-012, API p95 < 300 ms NFR-011, 16 GB Compose NFR-050, RTO/RPO per NFR-021/022); locale `en-US`; package manager `pnpm` | `/docs`, `/engineering-operating-system` |

R-DE owns the grep list for L4 and MUST extend it whenever a new canonical value is minted (a new load-bearing literal appearing in ≥ 2 documents); extending the list is a CC-6 PR against the lint config.

**Remediation rules when docs-lint fails:**

- L1/L2 failures are fixed in the failing PR — never by deleting the reference to silence the check; if the target genuinely no longer exists, the inbound citation is rewritten against the current source of record.
- L3 failures are fixed by recomputing the stated totals from the actual rows, never by adjusting rows to match a stale total.
- L4 failures mean a document restated a canonical value: replace the restatement with a reference per §2. If the *canonical value itself* is changing, that is an NFR/anchor change — ADR + PRD/anchor first ([ADRProcess §1](./ADRProcess.md)), then the L4 grep list is updated in the same PR so the new value becomes the enforced one.
- docs-lint is never skipped, waived, or marked flaky; it has no quarantine path.

## 5. Style rules for spec and EOS documents

- **IDs are stable, never renumbered.** FR/NFR/FEAT/AC/UC/ADR/TASK/DEBT/RISK IDs are append-only; retired IDs leave gaps (the PRD's ID-gap policy note governs). Renumbering to "tidy up" is forbidden — inbound references would silently rot.
- **Relative links only** — `../docs/...` from EOS, `./Sibling.md` between EOS docs, `../<dir>/...` within /docs. No absolute repo paths, no bare "§12" references to *other* files without a link (self-references may use bare §).
- **Tables for enumerable rules.** Any set of more than three parallel rules is a valid GFM table (docs-lint parses them; L3 counts them). Prose walls are a review finding.
- **No placeholders.** No TBD, TODO, "coming soon", or empty sections in merged docs. An unresolved question becomes a `RISK-NNN` entry in `/work/risk-register.md` with a target phase — the doc states the current decision and links the risk.
- **Normative language.** MUST/SHOULD/MAY per RFC 2119 in EOS docs and normative spec sections; "consider"/"ideally" are banned in normative text.
- **Structure.** One H1; a one-paragraph purpose stating who reads it and when; "Related documents" section at the end. Section headers are stable — renaming/renumbering a section that other docs cite requires updating every inbound reference in the same PR (L1 catches linked ones).
- **Language.** All repo documentation is written in English (`en-US` — the same source-locale decision as [FrontendPlan §8](../docs/engineering/FrontendPlan.md)).
- **Checklists** use `- [ ]` items, grouped by change class where relevant (change classes per [QualityGatePolicy §3](./QualityGatePolicy.md)).

## 6. MODULE.md charters (L2)

Every backend module, `/frontend`, and `/infra` carries a `MODULE.md` with exactly these sections (context layer L2, [ContextManagementStrategy §6](./ContextManagementStrategy.md)):

1. **Purpose** — one paragraph, aligned with [ArchitectureOverview §5](../docs/architecture/ArchitectureOverview.md).
2. **Owned tables** — schema-qualified, matching [DatabasePlan §2](../docs/engineering/DatabasePlan.md).
3. **Owned topics & endpoints** — produced/consumed topics + consumer groups per [EventModel §3](../docs/engineering/EventModel.md); exposed REST paths; exported named interfaces.
4. **Invariants** — the module-local MUST-hold facts (e.g. "all consumers dedup on `eventId`", "no plaintext secrets ever materialize outside `com.eip.core.secrets`").
5. **Dependencies** — allowed module dependencies (mirror of the Modulith `package-info.java`).

MODULE.md is updated in the same PR as any change to what it declares; a stale charter is a G7 failure. Charters cite anchors rather than restating them (§2 rule applies).

## 7. Documentation in the gates

| Gate | Documentation obligation |
|---|---|
| G0 (Ready) | Task spec declares docs impact from the §3 matrix; context pack cites paths + sections, never pastes document bodies ([ContextManagementStrategy §2](./ContextManagementStrategy.md)) |
| G7 (Documentation) | Automated: docs-lint L1–L4 green. Manual (R-DE, A4): declared docs impact actually applied; §5 style rules held; MODULE.md current |
| G8 (Review & Done) | R-CR verifies docs match the behavior in the diff — a doc claiming what the code doesn't do is a BLOCKER; "no docs impact" declarations verified |
| CC-6 (docs-only PRs) | G7 + light G8; spec-of-record edits still need the owning architect; contract-anchor edits are CC-1, not CC-6 |
| RG4 (release) | Runbooks and operational docs current for the phase (R-RM verdict) |
| Sprint review | docs-lint violation count is a standing sprint metric ([SprintReviewGuide §4](./SprintReviewGuide.md)) |

## 8. Reviewer checklist (G7 manual portion + G8 doc verification)

R-DE at G7, R-CR at G8:

- [ ] docs-lint L1–L4 green (automated; verify the run is on the PR head commit).
- [ ] Every §3 matrix row implied by the diff has its documents changed in this PR; "no docs impact" declarations are true.
- [ ] Changed docs reference anchors instead of restating values (§2); no new copy of an NFR figure, topic name, GUC, role token, or port.
- [ ] No TBD/placeholder text; unresolved points landed as `RISK-NNN` entries with target phases.
- [ ] IDs untouched except appends; section headers cited elsewhere unchanged, or all inbound references updated.
- [ ] MODULE.md charters current for every module whose tables/topics/endpoints/invariants moved (§6).
- [ ] Generated artifacts (`openapi.json`, Modulith Documenter output) regenerated, not hand-edited.
- [ ] New/changed docs end with "Related documents"; purpose paragraph states who reads it and when.
- [ ] Doc content matches the code in the same PR — a doc describing unbuilt behavior is a BLOCKER unless explicitly marked as a phase-planned design in its own section.

## Related documents

- [ADRProcess.md](./ADRProcess.md) — decision records and their propagation
- [EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) — gates, change classes, context layers
- [ContextManagementStrategy.md](./ContextManagementStrategy.md) — how documents feed session context packs
- [ArchitecturePrinciples.md](./ArchitecturePrinciples.md) (AP-4 docs-first) · [CodingStandards.md](./CodingStandards.md) (comment policy) · [QualityGatePolicy.md](./QualityGatePolicy.md) · [ModuleOwnership.md](./ModuleOwnership.md) · [RepositoryStructure.md](./RepositoryStructure.md) · [SprintReviewGuide.md](./SprintReviewGuide.md)
- Spec of record: [DocumentationQualityReview](../docs/reviews/DocumentationQualityReview.md) · [ArchitectureOverview](../docs/architecture/ArchitectureOverview.md) · [APIDesign](../docs/engineering/APIDesign.md) · [EventModel](../docs/engineering/EventModel.md) · [DatabasePlan](../docs/engineering/DatabasePlan.md) · [SecurityModel](../docs/architecture/SecurityModel.md) · [TestingStrategy](../docs/testing/TestingStrategy.md)
