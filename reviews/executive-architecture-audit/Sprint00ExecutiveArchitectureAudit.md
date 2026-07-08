# Sprint-00 Executive Architecture Audit

> Read-only CTO-level audit · Date: 2026-07-08 · Auditor: acting as CTO/Chief Architect · No files modified, nothing committed/merged/pushed.
> Evidence: README, CONTRIBUTING, SprintCatalog, ContextManifest, SPRINT-00, the Product Vision Alignment check, Vision, PRD, FeatureCatalog, ArchitectureOverview (full), BackendPlan §1, TestingStrategy §1–§3, DefinitionOfDone, QualityGatePolicy, debt-register, git log/status.

---

## 1. Executive verdict — **ACCEPTABLE WITH RISKS**

The *foundation itself* is genuinely strong: clean module boundaries, tenancy-by-construction designed in, deterministic-metrics-first, AI-as-assistive, on-prem — all coherent and internally consistent. As a *base to build on*, it is sound and does not need redoing.

The risks are not in what was built; they are in **trajectory and proportion**. After the entire Sprint-00 the repository contains roughly a thousand lines of Java (the `eip-core` value types plus their tests) and a docs-lint script — and **zero product functionality**: no persistence (no Flyway migration exists yet), no `/api/v1`, no connectors, no ingestion, no analytics, no dashboards, no AI. Everything else is documentation, configuration, and process. That is *correct* for a Phase-0 bootstrap, but it means the 6-month-to-first-license clock has consumed one sprint and the base is still an empty, well-documented shell. The process weight, the back-loaded customer value, and a connector order that may not match the first buyer are the real risks a CTO must manage — hence **ACCEPTABLE WITH RISKS**, not an unqualified green light.

Blunt: this is an *A-grade engineering foundation* attached to a *roadmap that defers all customer-visible value by ~4 sprints* and a *process built for a 20-person org being run by one contributor*.

## 2. Product vision fit

**Yes — the repo still represents Engineering Productivity & Delivery Intelligence, with no product drift toward autonomous coding.** This is architecturally enforced, not aspirational: ImplementationReadiness principle 1 ("AI is additive and never trusted; every non-generative capability works with zero LLM providers; generative failure never blocks ingestion/analytics/dashboards/RBAC"), PRD §2.2 non-goals (no source-tool replacement, no native scanning, no model training), Vision §9, and the phase order (deterministic metrics in Phases 0–2, AI only in Phases 3–4). The 18 canonical agents are all narration/analysis/validation ("never auto-remediates", "applies nothing without admin confirmation").

**One drift risk, and it is real but not product-level: perception.** The repository's current mass is a large agentic *build* apparatus (EOS, roles, gates, workflow orchestration used to construct EIP). At Phase 0 with no product code, an outsider could mistake the agentic-*development* machinery for the product being an "agent platform." CLAUDE.md disambiguates it; keep that discipline in anything customer- or investor-facing.

## 3. Foundation quality

| Element | Grade | Assessment |
|---|---|---|
| Monorepo | Strong | 9 Gradle modules + Vite frontend, convention plugins, version catalog, enforced leaf boundaries. Clean. |
| CI | Strong design, **unproven** | Four gate jobs (backend/frontend/security/docs-lint) are well-designed — but **nothing has ever run on the remote** (unpushed). "Green" is local-only. Pushing is the validation, and it hasn't happened. |
| Local dev stack | Strong | Compose (Postgres+pgvector, Kafka, Redis, MinIO, Keycloak, OTel/Prom/Grafana) boots healthy; `make dev-up` verified. |
| Onboarding | Good | CONTRIBUTING takes clone→PR; accurate to current state. |
| Shared kernel | Solid but **types-only** | `eip-core` = value types (WorkItemType, ExternalRef, 11-field EventEnvelope, UUIDv7, sealed errors) + Modulith boundary test, 100% covered. No behavior, no persistence, no Kafka. Appropriate for Phase 0 — but do not mistake it for working product code. |
| ADRs | Strong | 20 decisions materialized and indexed; ADR-015..020 resolve real contradictions the readiness review found. |
| CODEOWNERS | Adequate | Correct mappings; already drifts from ModuleOwnership §1 (DEBT-006) — a canary that the governance is elaborate enough to drift from itself. |
| docs-lint | Good, partial, unproven | Four checks, context-aware L4, green locally; coverage-partial (DEBT-007) and never run on the remote. |

Net: **high design quality; two systemic caveats** — (a) CI/docs-lint/CODEOWNERS are all *unvalidated on the remote*; (b) it is 100% scaffolding. Neither is a defect; both are reasons the "done" status is softer than it looks.

## 4. Maintainability

- **New engineer productive in 2 hours? The dev loop, yes; the system, no.** CONTRIBUTING gets a stack booted and a PR open in well under an hour — genuinely good. But *understanding* the system means facing 30+ `/docs` spec files, 33 EOS docs, 20 ADRs, and program/sprint/review trees — easily several hundred pages. The ContextManifest's per-task reading lists are the right mitigation (and a sign the authors saw the problem), but the raw volume is a standing tax.
- **Is the governance too heavy? Yes, for this stage.** Eight merge gates (G0–G8), four release gates, 21 engineering roles, seven change classes, per-task DoR/review/merge ceremony — designed for a multi-team, multi-year org, being executed by a single contributor on an empty platform. It is not *wrong*; it is *premature*. The weight-to-value ratio right now is poor, and it is the biggest silent drag on velocity.
- **Docs fragmented/repetitive? Fragmented across five top-level trees** (`/docs`, `/engineering-operating-system`, `/program`, `/sprints`, `/reviews`, plus `/work`). DocumentationStandards §2 ("reference, never restate") and docs-lint L4 actively fight duplication/drift, which is commendable — but a reader still navigates many homes for one mental model. Repetition is controlled; *dispersion* is not.

## 5. Architecture risk

| Area | Risk | Note |
|---|---|---|
| Modulith / boundaries | **Low** | Well-designed, test-enforced, cycle = build failure. The single strongest part of the base. |
| Tenant-by-construction | **Medium (identified)** | Designed in (RLS on `app.tenant_id`, envelope `tenantId`), but *not yet implemented*. The hard part — RLS correctness under PgBouncer transaction pooling — is Sprint-01 and is genuinely the #1 technical risk. The team has flagged it as a wk-1 spike. Good, but unproven. |
| Deterministic-metrics-first | **Low** | Architecturally enforced; phase order puts metrics before AI. |
| AI assistive/non-authoritative | **Low** | Enforced (readiness principle 1; validation-gated, citation-bearing). |
| On-prem assumptions | **Medium** | All self-hostable (good). But the "1–2 platform engineer" operability claim (D6) sits on top of 8+ stateful services (Postgres, Kafka, Redis, MinIO, Keycloak, OTel, Prometheus, Grafana) — plus optional Qdrant + Ollama/vLLM + GPU. "Two backend deployables" is honest; the *infra* footprint is not small. This tension will surface in real installs. |

## 6. Sprint roadmap assessment

- **Sprint-01 (persistence & tenancy spine): realistic but overloaded.** DB baseline + RLS template + partitioning + tenant context across web/Kafka/jobs + OpenAPI baseline + observability wiring + four design-note spikes, in one sprint. RLS+pooling correctness is the crux and deserves protected focus; the OpenAPI/observability lanes could slip without harm.
- **Sprint-02 (authN/Z, audit, secrets): realistic**, correctly sequenced as the security spine.
- **Sprint-03 (console shell + Phase-0 close): realistic**; first UI + RG dry-run.
- **Ordered for customer value? No — ordered for engineering correctness.** Phase 0 (Sprints 00–03, ~8 weeks) yields "a secure, observable, *empty* platform." First data flows in Phase 1 (Sprints 04–07); first *trustworthy dashboard* — the actual value proposition — not until Phase 2. A buyer sees nothing compelling until ~Sprint 06–08. For a 6-month license goal that is dangerously back-loaded.
- **Safely deferrable foundation work:** the full observability wiring, the RBAC permission-matrix *generator*, and the admin-console skeleton can be minimized to protect a faster path to a connector→canonical→dashboard vertical slice. The governance backfill (already spent) was not the highest-ROI use of Sprint-00.

## 7. Technical debt assessment

| DEBT | Matters now? | Verdict |
|---|---|---|
| DEBT-001 catalog single-source | Low | Build hygiene; a mild perfectionism symptom. Harmless. |
| DEBT-002 no dependency-verification metadata (supply chain) | **Medium** | This one is a *product* concern: air-gapped supply-chain integrity is a selling point (NFR-051). Must land before GA, not now. |
| DEBT-003 coverage ratchet scope | Low | Bites when `eip-analytics` lands (Phase 2). |
| DEBT-004 no Prettier | Trivial | Harmless. |
| DEBT-005 `.env` dev creds vs `CHANGE_ME` | Low | Matters at prod-container time; harmless in dev. |
| DEBT-006 CODEOWNERS↔ModuleOwnership drift | Low urgency | **Symptom of over-engineering** — the map and the artifact are elaborate enough to drift, and the "§7 docs-lint verifies §1 vs CODEOWNERS" rule is gold-plating. |
| DEBT-007 docs-lint coverage partial | Low | **Symptom of over-engineering** — a four-check docs-lint with anchor validation built before there is a product. |

**Matters now:** none are urgent; DEBT-002 is the one with real product stakes (pre-GA). **Harmless:** 003/004/005. **Over-engineering canaries:** 006, 007, and arguably 001.

## 8. PoC readiness

- **Meaningful local PoC (correlated data → a real metric/dashboard):** not before **Phase 2** on the current plan — realistically ~Sprint 08–09 (~4–5 months in). A *technical* PoC (data flowing into the canonical model + a data browser, on the simulation pack) arrives end of **Phase 1**, ~Sprint 07 (~14 weeks), but it does not yet show metrics — the thing a buyer cares about.
- **First real Jira/Bitbucket/Confluence/Sonar integration PoC:** the roadmap front-loads **Jira + GitHub** (Phase 1) and defers GitLab/SonarQube to Phase 2 and Bitbucket/Confluence later. So a **Jira** PoC is ~Sprint 06–07; **Bitbucket/Confluence/SonarQube** are materially later. If the first target customer is an Atlassian + SonarQube shop (a large, high-value segment), the connector order is **misaligned with the sale**.
- **Shortest credible path to a value demo:** one source (or the simulation pack) → normalized `WorkItem`/`PullRequest` → **one** flow/DORA dashboard *with drill-to-evidence*, plus one differentiated view (friction/bottleneck). That single vertical slice proves the whole thesis (correlation + humane, on-prem metrics). The current plan reaches it around Phase 2; it can and should be compressed by cutting breadth.

## 9. ROI review — if I had to sell the first enterprise license in six months

Six months ≈ 13 sprints; one is spent. To close a license you need a demo that shows a VP-Eng/CTO *their* delivery reality (or a credible simulation) as correlated, trustworthy, on-prem metrics they cannot get from a SaaS tool. Ranked by ROI (customer value + time-to-first-deployment, **not** elegance):

| # | Adjustment | ROI rationale | Class |
|---|---|---|---|
| 1 | **Protect one thin vertical slice to a dashboard.** Re-scope so a single connector (or simulation) → canonical model → one flow/DORA dashboard-with-evidence is the north star of Phases 1–2; cut anything that doesn't serve it. | This is the only thing that turns "impressive architecture" into "a demo that sells." Highest ROI by far. | **Immediate Sprint-01 adjustment** (planning intent; protect the slice) |
| 2 | **Align connector order to the first buyer.** If the pipeline is Atlassian+Sonar, front-load Jira+Bitbucket+Confluence+SonarQube over GitHub-first. | Demoing on the customer's *own* toolchain is the difference between a POC and a purchase order. | **Immediate Sprint-01 adjustment** (re-order Phase-1 connector backlog when it's planned) |
| 3 | **Lean the governance to a security/tenancy core now; re-expand as the team grows.** Keep G3/tenancy/RLS gates and change-class for CC-1/CC-2; relax the rest of the ceremony. | The process is the biggest velocity tax; trimming it buys sprints back with no product risk. | **Immediate Sprint-01 adjustment** (process, not specs) |
| 4 | **Make the simulation pack a sales asset.** A canned "here is what EIP shows" demo on synthetic enterprise data, shippable before real connectors harden. | Lets sales/demos run months before production connectors are trustworthy; the asset already exists in the strategy. | **Immediate / Phase-1** |
| 5 | **Ship one differentiated metric in the first dashboard** (a friction/bottleneck or delivery-intelligence view), not just flow/DORA. | Flow/DORA alone is commodity (LinearB/Jellyfish). The differentiation is on-prem + correlation + *humane, novel lenses*; without one, the demo looks like a cheaper clone. | **Deferred to the Phase-2 dashboard scope, but decided now** |

**Reject / avoid pulling forward:** the full 18-agent AI suite, MCP client/server, Qdrant, presentations/diagrams, the full ~20-connector catalog, and K8s/OpenShift GA — all Phase 3–5. None should move earlier for a first license; deterministic dashboards + at most one narrative report suffice. **Reject** any request to "add AI to the demo" before the metrics demo exists.

## 10. Missing / under-represented capabilities

The metric ambition on paper is developer/delivery/flow/DORA/quality/team-health — **commodity-shaped**. The capabilities that would *differentiate* and that the product owner explicitly wants — **AnalystEx, TesterEx, DevEx, an Engineering Friction Index, Delivery Intelligence, and Organizational Bottleneck / cross-team bottleneck detection** — are **not in the planned metric set**; they exist only as the parking-lot ideas captured in [Phase-2-Analytics-Enhancements](../../program/future-planning/Phase-2-Analytics-Enhancements.md). That is the single most important *product* gap: the near-term roadmap will produce a competent DORA dashboard, not the distinctive "engineering productivity intelligence" the vision promises. At least one of these differentiated lenses (a friction/bottleneck view is the highest-leverage, and is fully deterministic and anti-surveillance-compliant) should be in-scope for the first dashboard, not deferred indefinitely.

## 11. Over-engineering findings

- **Simplify now:** the governance ceremony (8 gates / 21 roles / 7 change classes / per-task review-merge choreography) for a single-contributor Phase-0. Keep tenancy/security rigor; relax the rest. The ModuleOwnership §7 "docs-lint verifies §1 vs CODEOWNERS" rule and the four-check anchor-validating docs-lint are gold-plating for a repo with no product.
- **Do not build yet (and resist scope-gravity toward):** MCP client/server, the full 18-agent suite, Qdrant, presentations/diagrams, the complete connector catalog, K8s/OpenShift GA. The roadmap defers these correctly; the risk is pulling them forward.
- **Delete/postpone:** delete nothing (docs are cheap to keep). Postpone *process depth*, not artifacts. The 100%-coverage-on-a-types-only module is a minor over-investment; harmless, leave it.
- **The meta-finding:** Sprint-00 invested in an exquisite engineering-operating-system and governance backfill *before proving one unit of product value*. For a company that must sell in six months, that is inverted sequencing. The base is excellent; the *ordering of effort* is the over-engineering.

## 12. Recommendation — **Push and close Sprint-00 now**

There is nothing in Sprint-00 a push would embarrass, and pushing is precisely what validates the CI/docs-lint/CODEOWNERS that have never run on the remote. "Stop and realign" is wrong — the foundation is sound and needs no rework. Close Sprint-00, then let the audit's ROI findings shape **Sprint-01 planning** (protect the vertical slice, align connectors to the buyer, lean the process, plan one differentiated metric). The adjustments are forward-looking, not corrections to what's done — so they must not block the close.

(One caveat on "done": the deferred remote verification — CI green on the base branch, branch protection — should be completed as part of the push so the sprint's "green" claims stop being local-only.)

## 13. Exact next recommended command

```
Push and close Sprint-00, then plan Sprint-01 for time-to-value. (1) Push integration/SPRINT-00 to the
remote and open a PR to the base branch (claude/engineering-productivity-platform-04kbet — there is no
`main`); confirm the four CI jobs (backend/frontend/security/docs-lint) run and are green on the PR head,
then flip each task MERGED → VERIFIED → DONE in work/sprints/SPRINT-00.md. (2) Before writing any Sprint-01
task, hold a one-page Sprint-01 planning decision that records, as intent only (no spec rewrite): the
protected vertical slice (one source → canonical → one flow/DORA+friction dashboard with drill-to-evidence)
as the Phase-1/2 north star; the connector order aligned to the first target customer's stack; a leaned
gate/process subset for the current team size; and one differentiated metric (friction/bottleneck) scoped
into the first dashboard. Do not expand the roadmap, add AI, or pull Phase 3–5 work forward.
```
