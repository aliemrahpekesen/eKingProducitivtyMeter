# Phase-2 Analytics Enhancements — Future Planning Parking Lot

> **Status: PLANNING INPUT ONLY — not backlog, not a sprint item, not a commitment.**
> This document is an evolving parking lot of *ideas* for future Phase-2 analytics planning, captured from the accepted [Product Vision Alignment Check](../../reviews/product-vision-alignment/ProductVisionAlignmentCheck.md). It contains **ideas only**: no implementation tasks, no epics, no user stories, no estimates, no solution designs, no architecture changes, and **no PRD modifications**. Nothing here changes the roadmap, the architecture, or Sprint-00 scope. Sprint-00 remains frozen. When Phase 2 is actually scoped, R-PO/R-DA/R-TPM decide which (if any) of these become real requirements through the normal PRD → FeatureCatalog → sprint process.

## Framing constraints (apply to every idea below)

Every idea here MUST, if ever taken forward, conform to the existing product vision — it is captured *because* it fits, not as a new direction:

- **Deterministic-first.** Each idea is grounded in deterministic, already-modeled canonical data — never inferred individual judgement. AI (Phase 3+) may later *explain* these metrics, never *fabricate* them.
- **Team-level, anti-surveillance (FR-057 / NFR-071).** Every metric family is expressed at team / flow / system level. None ranks, scores, or surfaces an individual. "AnalystEx / TesterEx / DevEx" mean *experience of the role's work system*, not per-person productivity.
- **Correlate, then compute.** Metrics operate on the correlated canonical model (`WorkItem`, `PullRequest`, `Commit`, `Deployment`, `Incident`, `Sprint`, `WorkflowState`, `WorkItemTransition`, `CodeReview`, quality/ops signals), never single-tool feeds.
- **Definition-complete.** Any metric that ever ships carries purpose, formula, inputs, grain, caveats, and gaming risks (per the existing metric-definition standard).

The canonical data sources referenced below are those already in (or planned for) the canonical model and connector set — Jira/ADO (work items, sprints, workflow states, transitions), GitHub/GitLab/Bitbucket (PRs, reviews, commits, branches), CI/CD (builds, pipelines, deployments), SonarQube (quality gates, findings), Kubernetes/OpenShift + Prometheus/Grafana/OTel (environments, incidents, alerts, SLOs), Confluence/documents (knowledge). No new data source is assumed.

---

## Idea catalog

### 1. Analyst Experience (AnalystEx)
- **Problem statement:** Requirement/analysis work (epics, features, stories, refinement) is largely invisible in delivery metrics, which start at "in progress." Teams cannot see whether upstream analysis is a source of friction (churn, late readiness, ambiguous specs) before code even starts.
- **Business value:** Surfaces where delivery is bottlenecked *before* engineering — reducing rework, mid-sprint scope churn, and "not ready to start" stalls; helps PMO/BA leads improve requirement flow at team level.
- **Deterministic data sources:** `WorkItem` (type EPIC/FEATURE/STORY), `WorkItemTransition` (refinement→ready→in-progress timings), `WorkflowState` categories, description/acceptance-criteria completeness signals, `EntityLink` (requirement→PR realization), Sprint scope-change deltas.
- **Why it fits the vision:** Extends the deterministic flow model upstream into the analysis phase; team-level; correlation-based; directly serves "SDLC friction" and "requirement flow" the product exists to measure.

### 2. Tester Experience (TesterEx)
- **Problem statement:** Testing/QA flow (test cycle time, defect turnaround, rework, escaped defects) is not represented as a first-class flow lens, so QA friction and quality-feedback latency are hard to see.
- **Business value:** Shows where the quality feedback loop is slow or leaky (long defect lead time, high reopen/rework, escaped defects reaching production) so teams can shorten feedback and reduce late-stage surprises.
- **Deterministic data sources:** `WorkItem` (type BUG/INCIDENT_TICKET), `WorkItemTransition` (open→verified→closed, reopen events), SonarQube quality gates/findings, CI test-stage pass/fail signals, `Deployment`↔`Incident` correlation for escaped-defect detection.
- **Why it fits the vision:** Deterministic, team-level quality-flow measurement over the correlated graph; complements existing DORA/quality metrics with a QA-flow perspective; no individual attribution.

### 3. Developer Experience (DevEx)
- **Problem statement:** "Team Health" covers load/review/bus-factor, but the day-to-day friction developers experience — wait time for reviews, CI/build waits, context-switching, flow interruptions — is not aggregated into an explicit experience surface.
- **Business value:** Gives engineering leaders a deterministic, non-survey signal of where the development system creates friction, targeting the highest-leverage DevEx improvements (e.g., review latency, slow pipelines).
- **Deterministic data sources:** `PullRequest` (open→first-review→merge waits), `CodeReview` timing, CI/CD build durations and queue waits, `WorkItemTransition` blocked-time, deployment lead time.
- **Why it fits the vision:** Purely deterministic timings aggregated at team/flow level; serves "DevEx improvement opportunities"; anti-surveillance (system friction, not person output).

### 4. Engineering Friction Index
- **Problem statement:** Friction signals are scattered across many metrics; there is no single composite that says "where is this team's delivery system creating the most drag?"
- **Business value:** A single, drill-downable index that points leadership at the biggest systemic constraint (review, CI, environments, requirements) without cherry-picking charts.
- **Deterministic data sources:** Composite of the wait/blocked/rework signals above — PR review waits, CI queue/build time, environment waits, blocked-state durations, rework/reopen rates — all already deterministic in the canonical model.
- **Why it fits the vision:** A correlation-native composite of deterministic inputs, always drillable to evidence (per "trust before insight"); team-level; explicitly a *system* index, never a person index.

### 5. Delivery Intelligence
- **Problem statement:** Delivery-risk signals (flow, dependencies, scope churn, readiness) exist individually but are not synthesized into a continuous, evidence-linked view of "how is delivery actually going and what's driving it."
- **Business value:** Continuous, drill-to-evidence delivery health for PMO/leadership, replacing hand-assembled status decks.
- **Deterministic data sources:** `Sprint`, `WorkItem`/`WorkItemTransition` flow, `Dependency` edges, `Release`↔`WorkItem` (SHIPS) links, deployment/pipeline data, correlation coverage.
- **Why it fits the vision:** Squarely the product's core — correlated, deterministic delivery insight; AI (later) narrates it, never invents it.

### 6. Organizational Health Score
- **Problem statement:** Executives lack a portfolio-level, evidence-based roll-up of delivery/flow/quality/ops posture across many teams that is not a vanity number.
- **Business value:** A transparent, decomposable org-level posture indicator for investment and risk conversations, every component traceable to team metrics.
- **Deterministic data sources:** Aggregation of already-computed team-level flow/DORA/quality/ops/risk metrics across `Team`/`BusinessUnit`/`Organization`, weighted by documented, viewable formulas.
- **Why it fits the vision:** Roll-up of deterministic team metrics with stated formula/caveats; strictly aggregate (never re-derives to individuals); serves the CTO/VP-Eng persona.

### 7. Cross-team Bottleneck Detection
- **Problem statement:** Delays frequently originate at team boundaries (cross-team dependencies, hand-offs, shared-service queues), which single-team dashboards miss.
- **Business value:** Pinpoints where inter-team dependencies stall delivery, enabling structural fixes (ownership, sequencing) rather than blaming individual teams.
- **Deterministic data sources:** `Dependency` edges with `crossTeam` flag, `WorkItemTransition` blocked-time attributable to cross-team waits, `EntityLink` across team-owned entities, shared-repo `PullRequest` review waits.
- **Why it fits the vision:** Correlation-native (the join across teams is the value); deterministic; team/system level; directly serves "delivery bottlenecks."

### 8. Requirement Flow Intelligence
- **Problem statement:** The path from idea → refined requirement → ready → realized-in-code is not measured, so requirement readiness and churn stay invisible until they cause mid-sprint disruption.
- **Business value:** Improves predictability by exposing requirement readiness rates, refinement lead time, and spec churn before commitment.
- **Deterministic data sources:** `Initiative`→`Epic`→`Story` hierarchy, `WorkItemTransition` through refinement/ready states, description/acceptance-criteria change history, `EntityLink REALIZES/RELATES_TO` (requirement→PR).
- **Why it fits the vision:** Extends deterministic flow analytics into the requirement domain; team-level; supports AnalystEx (idea 1) with the same sources.

### 9. Review Queue Intelligence
- **Problem statement:** Code review is a frequent, high-variance delivery constraint, but review-queue dynamics (wait time, queue depth, reviewer load balance, stale PRs) are not surfaced as a flow lens.
- **Business value:** Directly targets one of the most common delivery bottlenecks; helps teams rebalance review load and cut PR cycle time.
- **Deterministic data sources:** `PullRequest` state timings, `CodeReview` request/response timestamps, PR age/staleness, review round-trips, team-level reviewer distribution (aggregate, not ranked).
- **Why it fits the vision:** Deterministic PR/review timings; team-level load balance (explicitly not individual leaderboards); serves DevEx + delivery flow.

### 10. Release Pipeline Intelligence
- **Problem statement:** Release/deploy pipeline health (lead time for changes, deployment frequency, change-failure rate, pipeline stage waits, rollback frequency) is partially covered by DORA but not decomposed to actionable pipeline-stage constraints.
- **Business value:** Shows exactly which pipeline stages slow releases or cause failures, guiding CI/CD and release-process investment.
- **Deterministic data sources:** CI/CD `Build`/`Pipeline` stage timings, `Deployment`/`Release` events, `Deployment`↔`Incident` correlation (change-failure), rollback signals.
- **Why it fits the vision:** Deterministic pipeline data over the correlated graph; extends DORA with stage-level drill-down; serves "release risks" and "delivery bottlenecks."

### 11. Environment Waiting Analysis
- **Problem statement:** Time lost waiting for shared/test/staging environments is a real but usually unmeasured delivery tax.
- **Business value:** Quantifies environment contention as a delivery constraint, justifying environment/automation investment.
- **Deterministic data sources:** Deployment/pipeline timings by environment, environment-state signals from Kubernetes/OpenShift + Prometheus, `WorkItemTransition` waiting-for-environment states where modeled.
- **Why it fits the vision:** Deterministic timing analysis; system-level; contributes to the Friction Index (idea 4); serves "SDLC friction."

### 12. Team Collaboration Health
- **Problem statement:** Collaboration patterns (cross-review, shared ownership, hand-off latency) affect delivery resilience but are only weakly visible.
- **Business value:** Highlights collaboration strengths/gaps at team level (e.g., review concentration, siloed components) to improve resilience — without individual profiling.
- **Deterministic data sources:** `CodeReview` participation patterns (team aggregates), co-edited components, cross-team `PullRequest`/`EntityLink` activity, hand-off transitions.
- **Why it fits the vision:** Team-level, structural collaboration signals from deterministic events; strictly aggregate; complements Team Health / bus-factor.

### 13. Knowledge Distribution / Bus Factor Analytics
- **Problem statement:** Knowledge concentration (few people/teams owning critical components) is a delivery and continuity risk that is hard to see until someone leaves.
- **Business value:** Surfaces continuity risk (single-team/component concentration) so leaders can plan cross-training and ownership spread.
- **Deterministic data sources:** Commit/authorship distribution by component (team-aggregated via `MemberIdentity`→team mapping), `CodeReview` coverage per component, documentation coverage from Confluence/documents, component ownership signals.
- **Why it fits the vision:** Already named as a team-health concern in the vision (bus factor); deterministic; **team/component level only** with the existing anti-surveillance guardrail (no individual attribution in outputs).

### 14. Cross-system Correlation Opportunities
- **Problem statement:** Some high-value questions require joins the product could make but does not yet expose (e.g., requirement → deployment → incident → customer-facing release notes traceability; quality-gate outcomes vs incident rates).
- **Business value:** Unlocks end-to-end traceability and root-cause narratives that no single tool can answer, deepening the platform's core "correlation over collection" moat.
- **Deterministic data sources:** Existing `EntityLink` correlation edges + `ExternalRef` provenance across `WorkItem`↔`PullRequest`↔`Deployment`↔`Incident`↔`Release`↔quality signals; opportunities are new *reads/joins* over already-ingested data.
- **Why it fits the vision:** Pure correlation of already-deterministic canonical data — the product's defining pillar; no new ingestion, no individual data.

### 15. Additional deterministic metric families (open list)
- **Problem statement:** Other deterministic, team-level families may strengthen productivity intelligence: flow predictability/forecast confidence (deterministic historical variance), work-item aging/WIP-limit adherence, defect-density trends by component, dependency-risk scoring, incident-load vs delivery-capacity balance, sprint scope-stability, technical-debt-signal aging from ingested tools.
- **Business value:** Rounds out the "engineering productivity intelligence" surface with additional evidence-based lenses leadership commonly asks for.
- **Deterministic data sources:** The existing canonical model and connector signals (work items, sprints, PRs, deployments, incidents, quality/security findings, ops metrics) — no new sources.
- **Why it fits the vision:** All deterministic, team/system level, correlation-based, definition-complete; each would go through the normal PRD/FeatureCatalog process before ever becoming work.

---

## How this list is used

- It is an **input to Phase-2 planning only.** When Phase 2 is scoped, R-PO + R-DA + R-TPM triage these ideas against the PRD and decide which become real requirements — through the normal docs-first process (PRD → FeatureCatalog → AcceptanceCriteria → sprint tasks), never by promoting this file.
- Add new ideas here freely as they arise; keep each to the four fields (Problem statement · Business value · Deterministic data sources · Why it fits the vision). **Do not** add solutions, epics, stories, estimates, or priorities.
- This file never modifies the roadmap, architecture, PRD, or any Sprint scope. Those changes only happen through their own governed processes.

## Related documents

- [../../reviews/product-vision-alignment/ProductVisionAlignmentCheck.md](../../reviews/product-vision-alignment/ProductVisionAlignmentCheck.md) — the accepted alignment review this parking lot derives from
- [../../docs/product/PRD.md](../../docs/product/PRD.md) · [../../docs/product/FeatureCatalog.md](../../docs/product/FeatureCatalog.md) · [../../docs/product/Roadmap.md](../../docs/product/Roadmap.md) — the sources of record any future requirement must go through
- [../../docs/vision/Vision.md](../../docs/vision/Vision.md) — the vision every idea here conforms to
