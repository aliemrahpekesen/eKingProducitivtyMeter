# Risk Management Policy

This document defines how engineering risks are registered, scored, reviewed, mitigated, escalated, and accepted across the EIP program, and it seeds the initial register. It is read by R-TPM and R-CA (who run the sprint risk review), by R-SA (who co-owns every security-class risk), by R-RM (whose RG1 verdict consumes the register), and by any engineering agent whose task hits a blocker worth more than an escalation record. The register lives at `/work/risk-register.md` (entry template per [AgentCommunicationProtocol §3.9](./AgentCommunicationProtocol.md); location per [RepositoryStructure §4](./RepositoryStructure.md)). Risks are about the *engineering program*; product runtime risk features (delivery-risk metrics, Risk entities, FR-053) are product scope and never mix with this register.

## 1. Register and entry template

Every entry is one row in `/work/risk-register.md`, fields per [AgentCommunicationProtocol §3.9](./AgentCommunicationProtocol.md):

`RISK-NNN · description · likelihood(1-5) · impact(1-5) · owner role · mitigation · trigger/review date`

Rules:

- `NNN` is zero-padded, monotonic, never reused.
- `description` names its source (a /docs citation, PRD open question number, retro item, or incident id).
- `owner role` is a single R-XX accountable for driving mitigation — ownership never lands on "the team".
- `mitigation` names a concrete action, and for scores ≥ 15 the `TASK-NNNN` executing it.
- `trigger` is the observable condition that escalates or realizes the risk; `review date` defaults to every sprint review until the risk's target phase gate.
- File hygiene: the register keeps an **Open** table (sorted by score descending) and a **Resolved** table (closed/accepted/realized rows moved with outcome appended). Rows are never deleted — the register is L3 work state (context layers per [ContextManagementStrategy](./ContextManagementStrategy.md)) and phase-gate evidence.

## 2. What enters the register

- **PRD open questions are standing risks** until resolved by their target phase; each PRD §11 row not yet closed MUST have a register entry (§7 seeds them).
- Residual risks recorded by the documentation quality review ([DocumentationQualityReview §4](../docs/reviews/DocumentationQualityReview.md)) — seeded in §7.
- Retro outputs classified as risks rather than tasks or debt ([SprintReviewGuide §5](./SprintReviewGuide.md)).
- Escalation records in task files ([AgentCommunicationProtocol §3.5](./AgentCommunicationProtocol.md)) whose blocker outlives the task.
- Release-gate waivers with future exposure ([ReleaseManagement §3](./ReleaseManagement.md)).
- Debt entries whose risk-weighted score reaches 15 ([TechnicalDebtPolicy §5](./TechnicalDebtPolicy.md)) — promoted, with the DEBT id cross-referenced.

Not risks: active defects (fix now), pure schedule slippage without technical exposure (R-TPM's sprint plan handles it), and product-feature uncertainty owned by R-PO in the PRD itself.

## 3. Scoring

Score = likelihood × impact, each 1–5:

| Value | Likelihood (materializes before its target phase gate) | Impact (if it materializes) |
|---|---|---|
| 1 | < 10% — conceivable | Cosmetic; absorbed inside a sprint |
| 2 | 10–30% — plausible | One task's rework; no phase-scope effect |
| 3 | 30–60% — as likely as not | Multi-task rework or a missed sprint goal |
| 4 | 60–85% — expected unless acted on | Phase exit criterion (PRD §10) at risk; contract-anchor rework |
| 5 | > 85% — practically certain | Phase gate blocked, release-blocking NFR breached, or anti-goal/security exposure (FR-057, NFR-041, NFR-071) |

Bands:

| Score | Status obligation |
|---|---|
| **≥ 15** | **Active mitigation task required**: a `TASK-NNNN` in the current or next sprint plan, cited in the register row. No release train departs with an un-mitigated ≥ 15 risk targeting its phase (RG1 checklist) |
| 8–12 | Monitored: named owner, trigger defined, re-scored every sprint review |
| ≤ 6 | Watchlist: reviewed at phase boundaries only; MAY be closed by R-TPM + owner agreement |

## 4. Review cadence

- **Every sprint** (per [SprintReviewGuide.md](./SprintReviewGuide.md)): R-TPM + R-CA walk the register. **Security-classed risks are reviewed with R-SA present**; a security risk MUST NOT be re-scored downward without R-SA concurrence.
- **Every phase gate:** R-RM consults the register for RG1; entries whose target phase is the gating phase must be closed, mitigated, or explicitly accepted per §5 before GO.
- **On trigger firing:** the owner converts the entry to an incident/defect/escalation immediately — a fired trigger is not a discussion item for the next review.

### 4.1 Sprint risk-review agenda (R-TPM chairs, R-CA co-signs the outcome)

- [ ] Any entry with score ≥ 20 first — confirm human-owner visibility is current (§5)
- [ ] Every ≥ 15 entry has a live mitigation `TASK-NNNN` that is READY or beyond; a mitigation task stuck in INTAKE two sprints running is itself escalated
- [ ] Re-score every Open entry against the past sprint's evidence; record score changes with one-line rationale
- [ ] Check each `trigger` against reality; fired triggers already handled per §4, verify the row shows the outcome
- [ ] New intake since last review classified per §2 (nothing that is really a defect or debt)
- [ ] PRD §11 sweep: every still-open question has its register row, and rows for §7's deferred OQs (OQ#4–7) are created on schedule
- [ ] Close entries whose condition is gone; move rows to Resolved with outcome
- [ ] Output: updated register committed before the sprint review ends — retro items that surfaced risks become rows now, not next sprint ([SprintReviewGuide §5](./SprintReviewGuide.md))

## 5. Escalation and acceptance authority

Accepting a risk means recording, in the register row, who decided the program proceeds without (further) mitigation. Authority is bounded by severity and domain:

| Risk class | May be accepted by | Notes |
|---|---|---|
| Technical, score ≤ 12 | Owning domain architect | recorded in row |
| Technical, score 13–19 | **R-CA** | |
| Product/scope exposure (any score) | **R-PO** | e.g., accepting a Phase-N feature risk by descoping |
| Security-classed (any score) | R-SA recommends; acceptance requires R-CA **and** R-SA; R-SA's A4 block is overridable only by the human owner, recorded (escalation chain per [AgentResponsibilities.md](./AgentResponsibilities.md)) | |
| **Critical: score ≥ 20, or any risk touching NFR-041 isolation, NFR-071/FR-057 anti-surveillance, or data loss** | **Human repository owner only** | L4 checkpoint per [AIValidationWorkflow.md](./AIValidationWorkflow.md) |

Escalation path for disputed scoring or stalled mitigation follows the default escalation chain ([AgentResponsibilities.md](./AgentResponsibilities.md)): owner → domain architect → R-CA → human owner; schedule-driven deferral disputes go to R-TPM, scope disputes to R-PO.

## 6. Lifecycle

`Open → Mitigating (task in flight) → Closed (condition gone / mitigation verified) | Accepted (authority per §5, re-reviewed each phase gate) | Realized (trigger fired → incident/defect process; row records the outcome)`. Rows are never deleted; closed/realized entries stay as history with their outcome noted.

## 7. Seeded initial register

The following table is the initial content of `/work/risk-register.md` — copy it verbatim when creating the file. Sources: [DocumentationQualityReview §4](../docs/reviews/DocumentationQualityReview.md) and [PRD §11](../docs/product/PRD.md). Review dates say "sprint review" meaning every sprint until the named phase gate.

| ID | Description (source) | L | I | Score | Owner | Mitigation | Trigger / review |
|---|---|---|---|---|---|---|---|
| RISK-001 | HA targets coherent but unproven: RTO ≤ 30 min / RPO ≤ 15 min (NFR-021/022) are documented commitments; whether the Phase-5 reference topology achieves them is unknown until timed drills (DQR §4.2) | 3 | 4 | 12 | R-DOA | Fault-injection tests from Phase 2 CI (Roadmap §9.6); quarterly-drill discipline per OperationsGuide §6.3 rehearsed on staging before Phase 5; if drills miss, amend NFR-021/022 via ADR — never silent drift | Trigger: any timed drill exceeds target. Review: sprint review until Phase 5 gate |
| RISK-002 | NFR value fan-out is maintenance-sensitive: NFR figures appear in ~6 docs each; a future NFR change can silently drift (DQR §4.3) | 4 | 3 | 12 | R-DE | Phase-0 docs-lint CI job: link check, ID resolution, greps for canonical values (GUC name, DLQ pattern, NFR figures) per DQR §5.2 — scripts exist from the review | Trigger: docs-lint finds a stale NFR value. Review: sprint review until docs-lint gates G7 in CI |
| RISK-003 | FeatureCatalog/Roadmap count integrity is manual; the next feature addition can silently break stated totals (DQR §4.4) | 4 | 2 | 8 | R-DE | Add table-row recount vs. stated totals to the Phase-0 docs-lint job (DQR §5.2) | Trigger: recount mismatch. Review: closes when the CI check lands |
| RISK-004 | FR-142 (erasure/DSAR) is un-sized: vector-index deletion, artifact redaction, audit-preserving anonymization have non-trivial depth; scheduled Phase 2/P1 with no design document (DQR §4.5) | 4 | 4 | **16** | R-DA | **Active mitigation task required (§3):** write the FR-142 design note during Phase 1, before RAG indexing exists — retrofitting subject-deletion onto a populated vector store is far harder (DQR §5.4); R-SA co-reviews | Trigger: Phase-1 exit without the design note. Review: sprint review until note accepted |
| RISK-005 | Bundled embedding/reranker defaults (bge-small-en-v1.5) chosen for licensing and air-gap fit, not by evaluation; retrieval quality unvalidated (DQR §4.6) | 3 | 3 | 9 | R-AIA | Evaluate retrieval quality against the golden datasets in `../docs/testing/TestingStrategy.md` before Phase-3 RAG GA; document model-qualification results with the LLM SPI benchmarks | Trigger: golden-dataset eval below the TestingStrategy bar. Review: sprint review during Phase 3 |
| RISK-006 | Simulation packs (demo-small/demo-midsize/demo-troubled/enterprise-large) are load-bearing for Day-1, CI, and perf testing but their exact contents/volumes are unspecified; Phase-1 exit tests run against the reference pack (DQR §4.7, Roadmap §5.4) | 3 | 5 | **15** | R-QAA | **Active mitigation task required (§3):** Phase-1 task specifying pack contents/volumes (≥ 100k WorkItems, ≥ 500k events for the reference pack per Roadmap §5.4), co-owned with R-CNA; packs versioned in `/simulation` | Trigger: any Phase-1 exit AC blocked on pack definition. Review: sprint review until packs specified |
| RISK-007 | PRD OQ#1 — correlation heuristics: default key patterns (issue-key regexes, PR title conventions) and per-tenant configurability undecided (FR-036); weak correlation degrades metric trust downstream (Roadmap §5.6) | 4 | 3 | 12 | R-DA | Resolve in Phase-1 design per PRD §11; ship correlation-coverage metric as an honest caveat so exit is not blocked on heuristic quality (Roadmap §5.6) | Trigger: Phase-1 normalizer tasks reach READY without decided defaults. Review: sprint review until OQ#1 closed |
| RISK-008 | PRD OQ#2 — metric series versioning UX: how definition-version changes surface on dashboards without confusing trend readers (FR-062, FR-068) | 3 | 3 | 9 | R-FA | Resolve in Phase-2 design per PRD §11, jointly with R-DA (metric semantics); design review before any Phase-2 dashboard ships a versioned series | Trigger: FR-062 implementation task cut before UX decision. Review: sprint review during Phase 2 |
| RISK-009 | PRD OQ#3 — delay-prediction approach: statistical baseline vs learned model, explainability requirements for risk scores (FR-053) | 3 | 4 | 12 | R-DA | Resolve in Phase-3 design per PRD §11 (correctly phased with the Phase-3 delivery-risk features it gates, DQR §4.1); R-AIA consulted on model choice; explainability requirement decided before implementation tasks are cut | Trigger: Phase-3 sprint plan schedules FR-053 tasks before resolution. Review: sprint review until OQ#3 closed |
| RISK-010 | PRD OQ#8 — reference hardware for NFR benchmarks (NFR-003, NFR-010, NFR-013) unresolved. **Blocking for Phase-1 benchmarks:** no NFR-003 load-test result is meaningful until hardware is specified (DQR §4.1, §5.1 — "resolve OQ#8 first") | 5 | 4 | **20** | R-PE | **Active mitigation task required (§3), human-owner visibility (score ≥ 20, §5):** specify CPU/GPU reference tiers in Phase 0/early Phase 1, before the Phase-1 exit load test (PRD §10 Phase 1); publish with the performance baselines R-PE owns | Trigger: any benchmark reported without a hardware statement. Review: every sprint review until resolved — first agenda item |

PRD §11 OQ#4 (prompt redaction defaults), OQ#5 (MCP capability catalog), OQ#6 (air-gapped PDF/PPTX renderer), and OQ#7 (retention workflow specifics) remain standing risks per §2; R-TPM MUST add their register rows (owners: R-AIA for OQ#4/OQ#5, R-BA for OQ#6, R-DA + R-SA for OQ#7) no later than the start of the phase preceding each question's PRD §11 target phase. They are omitted from the seed only because none can fire before Phase 2.

## Related documents

- [TechnicalDebtPolicy.md](./TechnicalDebtPolicy.md) — shared 1–5 scales; promotion of ≥ 15 debt into this register
- [ReleaseManagement.md](./ReleaseManagement.md) — RG1 consumption of the register; waivers feeding intake
- [SprintReviewGuide.md](./SprintReviewGuide.md) — the sprint review where §4 happens
- [SprintExecutionGuide.md](./SprintExecutionGuide.md) — mitigation tasks entering sprint plans
- [AIValidationWorkflow.md](./AIValidationWorkflow.md) — L4 human checkpoint for critical acceptance
- [ADRProcess.md](./ADRProcess.md) — NFR amendments when a risk realizes into a target change
- [../docs/reviews/DocumentationQualityReview.md](../docs/reviews/DocumentationQualityReview.md) §4–§5 — source of seeded risks RISK-001…006
- [../docs/product/PRD.md](../docs/product/PRD.md) §11 — open questions behind RISK-007…010 and the standing-risk rule
- [../docs/operations/OperationsGuide.md](../docs/operations/OperationsGuide.md) §6.3 — drill regime referenced by RISK-001
