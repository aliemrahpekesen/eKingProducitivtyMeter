# Sprint-01 Planning Workshop — Executive Product Strategy

> Read-only executive workshop · Date: 2026-07-08 · Voices: CPO · Enterprise CTO · VP Engineering · Enterprise Solutions Architect. No files changed, no commits, no backlog, no roadmap/PRD/ADR edits. Architecture and quality are preserved by design; only *execution order* is in scope.

## Framing thesis (read this first)

We keep describing ourselves by our metrics and our AI. Both are commodities in 2026. **Our moat is three things, in this order: (1) we run *inside the firewall* for buyers who are legally or contractually banned from LinearB/Jellyfish/Swarmia; (2) we *correlate across tools* with provenance, which no single tool does; (3) we produce *humane, novel, trust-first* insight (where time is lost, where the org is stuck) without surveilling anyone.** The first dollar comes from a regulated enterprise — bank, telecom, defense, public sector — that *wants* engineering intelligence and *cannot legally buy the SaaS ones*. Everything below optimizes for landing that buyer, not for engineering completeness.

The uncomfortable truth from the audit: we have built an A-grade foundation and a beautiful operating system, and we are still zero-for-zero on customer value. The next 90 days must invert that.

---

## 1. The single most important customer problem to solve first

Not "measure productivity" — too broad, and it invites the surveillance objection. The sharpest wedge:

> **"Leadership cannot get a trustworthy, correlated answer to *where our delivery time actually goes and why* — and the tools that could tell them are banned by security/compliance."**

Concretely, the first slice we solve is **where engineering time is lost and where the organization is stuck** — cycle-time decomposed across the *correlated* graph (story → PR → build → deploy), with the waiting states made visible (review wait, CI wait, blocked/dependency time), on-prem, with drill-to-evidence. That is differentiated (it is the "friction/bottleneck" story, not raw DORA), it is fully deterministic, and it is exactly the Vision's promise ("where is engineering time lost").

We are **not** first solving: full DORA parity, release readiness, exec reporting, or AI narratives. Those are downstream of this one insight being trustworthy.

## 2. The first live demonstration — minute by minute (a 30-minute CTO meeting, regulated enterprise)

The demo runs on *their* stack in *their* sandbox, or on a simulation pack shaped like their stack. Nothing leaves their network — and we say so, repeatedly.

- **0:00–0:03 — The problem, named back to them.** "You can't ship your SDLC data to a US SaaS analytics vendor. So today you have spreadsheets and gut feel. Everything you're about to see runs inside your network; no byte leaves."
- **0:03–0:07 — Sovereignty & connect-your-tools.** Show it already running in their environment. Open the admin panel; add a **Jira**, a **Bitbucket** (or GitLab self-managed), and a **SonarQube** connector — validate → test connection → enable. Emphasize: read-only, credentials AES-encrypted, every action audited.
- **0:07–0:11 — The join no tool gives them.** Open one work item and watch it correlate to its Bitbucket PRs, its builds, and its Sonar quality gate — each link showing its provenance. "Jira can't see your PRs. Bitbucket can't see your tickets. Sonar can't see either. We link them, with evidence."
- **0:11–0:18 — The hero screen: Where time is lost.** The Engineering Friction / delivery-flow dashboard: cycle time broken into stages, the *waiting* stages highlighted, and the top bottleneck called out — "your #1 constraint is review wait; 40% concentrated on one cross-team dependency." Then **drill from the number to the actual 12 PRs**. The drill-down *is* the trust.
- **0:18–0:22 — Why your works council will approve this.** Open a metric definition: purpose, formula, inputs, caveats, gaming risks. Show there is **no per-person leaderboard anywhere** and that the platform structurally cannot build one. "This is team-level and humane by construction — that's why compliance and the workers' council sign off."
- **0:22–0:26 — Assistive AI, clearly secondary.** Click "explain this trend." A cited narrative appears — "review wait rose after the March reorg; here are the tickets" — running on a **local** model, every claim linked to the evidence, validation-gated. "The AI never invents. The deterministic engine is the source of truth. Turn the AI off entirely and everything still works."
- **0:26–0:30 — Trust, then the ask.** Flash the audit trail, RBAC, encrypted secrets, and the egress-deny proof. Close: "Installed in your infra in a day, connected to your tools, first insight the same day, no data out, no surveillance. Let's run a 6-week pilot on your sandbox."

If we can deliver **0:07–0:18** convincingly on a customer's own data, we have a category-defining product. Everything else in the roadmap serves that eleven-minute stretch.

## 3. The minimum *lovable* product (not MVP)

An MVP would be "DORA charts on-prem." Nobody loves that. The MLP is the smallest thing that makes a VP-Eng say *"I can't get this anywhere else"*:

1. **On-prem install that survives their security review** (Compose or single-namespace K8s; RLS tenancy, OIDC, RBAC, encrypted secrets, audit, air-gap).
2. **Connect ≥3 of *their* tools** from an admin panel: Jira + one SCM (their SCM) + SonarQube, syncing real data.
3. **Correlation with provenance** across those tools.
4. **One differentiated, evidence-backed dashboard**: *where time is lost* + top bottleneck + review efficiency, team-level, drill-to-evidence, metric definitions visible.
5. **One assistive, cited, local-model narrative** — the modern cherry that proves we're not a 2015 BI tool.

The *lovable* moment is item 3→4: seeing their own cross-tool bottleneck, with evidence, on-prem, that they literally cannot buy elsewhere. Take away the AI (5) and it's still lovable. Take away the differentiated insight (4) and it's just another dashboard.

## 4. What absolutely MUST exist before talking to the first enterprise customer

To *talk*: a simulation-based demo of §2 and a one-page security/architecture brief. That can exist in weeks.

To *pilot* (their data, their infra):
- **Security & sovereignty that passes their review**: on-prem deploy, air-gap/egress-deny, RLS tenant isolation, OIDC/RBAC, AES-encrypted secrets, append-only audit, and a written data-handling/security packet.
- **Real connectors for their stack**: Jira + their SCM + SonarQube, with working incremental sync + checkpointing.
- **Correlation + the one friction/bottleneck dashboard with drill-to-evidence.**
- **Demonstrable humane-metrics enforcement** (no individual ranking) — this is a *sales unlock*, not an ethics footnote, in works-council and regulated environments.
- **A credible upgrade + backup/restore story** (even minimal) — the on-prem buyer's #2 question after security.

## 5. What should NOT exist yet (even if engineers would enjoy building it)

- The full **18-agent AI suite**, **MCP client/server**, generated **presentations/diagrams**, executive-summary automation, report scheduling/notification center.
- **Qdrant**, GPU-scale AI, multi-model routing, per-agent budgets — pgvector + one local model is plenty.
- The full **20-connector catalog** (Artifactory, Docker Registry, K8s/OpenShift ingestion, Grafana, generic SQL/file, Confluence-for-RAG). Breadth is a Phase-2+ land-and-expand motion, not a first-sale requirement.
- **K8s/OpenShift GA + HA/DR + multi-AZ** — a pilot runs on Compose or one namespace.
- **50-tenant multi-tenancy at scale / per-tenant sharding** — design RLS in (already done), but do not *build* the sharding; a pilot is one tenant.
- The **RBAC permission-matrix generator, break-glass sophistication, full Grafana starter dashboards** — gold-plating before there are endpoints or users.
- **Schema-driven form framework / frontend polish** beyond the five demo screens.

## 6. Integrations ranked by commercial value (and why)

The current roadmap front-loads **Jira + GitHub**. For our actual buyer that is a **mistake on the SCM choice**. Regulated on-prem enterprises overwhelmingly run **Bitbucket Data Center or GitLab self-managed**, not github.com. Ranking:

1. **Jira (work tracker).** Non-negotiable spine; every flow/friction/bottleneck metric needs work items, transitions, sprints, dependencies. *(Also plan for Azure DevOps Boards / Jira Data Center variants — many targets are DC, not Cloud.)*
2. **The buyer's SCM — Bitbucket DC or GitLab self-managed first, GitHub Enterprise second.** PR + review + commit data; correlation and review-efficiency depend on it. **Re-order from the current GitHub-first plan to match the pilot customer.**
3. **SonarQube.** Quality gates/findings; self-hosted and ubiquitous; unlocks the *quality-correlated-to-flow* story that BI tools can't tell. High differentiation for modest effort.
4. **CI/CD — Jenkins / Azure DevOps / GitLab CI.** Pipeline + deployment data for DORA + release/deploy intelligence and environment-wait analysis.
5. **Confluence.** Lower for the first *flow* value; higher once RAG/AI narratives matter (Phase 3). Knowledge context, not core measurement.
6. **Prometheus / Grafana / Kubernetes / OpenShift.** Ops/incident correlation and DORA change-failure/MTTR; valuable but a later expansion.

Rule that overrides the ranking: **build the exact variants the first design-partner runs.** One real Bitbucket-DC + Jira-DC + SonarQube pilot beats five half-built SaaS connectors.

## 7. Metrics — commodity vs unique

**Commodity (table stakes; do them, don't lead with them):** DORA four keys, sprint velocity, cycle/lead time, throughput, PR counts, burndown, basic quality-gate pass rate. LinearB/Jellyfish/Swarmia have all of these. If our demo leads here, we look like a cheaper clone.

**Unique (the moat — computed on the *correlated* graph, on-prem, humane):**
- **Engineering Friction / where-time-is-lost** (wait-time decomposition across correlated stages).
- **Organizational & cross-team bottleneck detection** (dependency-attributed delay).
- **Review efficiency** (queue dynamics, wait, rework — team-level).
- **Delivery Intelligence** (evidence-linked "why is this slice at risk").
- **Requirement quality / flow** and **test effectiveness** (upstream + downstream friction correlated to delivery).
- **DevEx / AnalystEx / TesterEx** as *experience-of-the-work-system* indices (team-level, deterministic, never per-person).

The differentiation is not any single metric — it is that these run **on correlated cross-tool data, inside the firewall, without surveillance.** That combination is uncopyable by the SaaS incumbents (they can't go on-prem/air-gap) and by BI tools (they can't correlate or ship the pipeline).

## 8. Candidate differentiators — what ships in v1 vs waits

| Candidate | Verdict | Why |
|---|---|---|
| **Engineering Friction Index** | **v1 — the hero metric** | Fully deterministic from flow data; most compelling ("where time is lost"); the demo centerpiece. |
| **Organizational Bottleneck Detection** | **v1 (lite)** — "top 3 bottlenecks" | Extremely compelling; needs dependency + team data (Jira). Ship a ranked shortlist; full graph later. |
| **Cross-team dependency analysis** | **v1 (lite)** | Powers bottleneck detection; ship the dependency-delay attribution, defer the full network view. |
| **Review efficiency** | **v1** | Cheap (SCM data), compelling, a friction component. |
| **Delivery Intelligence** | **v1 (as the framing dashboard)** | Ties friction + bottleneck + risk together; the deterministic version ships now, agent-narrated version later. |
| **Developer Experience Index** | **v1 (composite)** | Ship as a DevEx-flavored composite of friction/review/CI-wait. Do **not** build survey-based DevEx yet. |
| **Test effectiveness** | **v1.5 / early Phase-2** | Needs Sonar + defect data; include if Sonar is in the first pilot. |
| **Requirement quality** | **wait (Phase-2)** | Needs deeper Jira workflow/refinement modeling. |
| **Analyst Experience Index** | **wait** | Requires requirement-flow maturity. |
| **Tester Experience Index** | **wait (Phase-2, pair with test effectiveness)** | Requires QA-flow modeling. |

**Principle:** v1 ships **one hero (Friction) + bottleneck-lite + review efficiency + a DevEx composite**, framed as "Delivery Intelligence." The full experience-index family (Analyst/Tester/Requirement) is the *land-and-expand* story for the second and third releases — and, critically, the reason a customer renews and expands. Do not dump all ten into v1; do not defer all ten to "future planning" either (the current sin).

## 9. Five screens for a 30-minute CTO meeting

1. **Connect-your-tools admin panel** (Jira / Bitbucket / SonarQube) — proves sovereignty + ease-of-adoption.
2. **Correlation view** — one story → PRs → builds → Sonar gate, with provenance. The join.
3. **Where-time-is-lost / Friction dashboard** — the hero: stage breakdown + wait times + top bottleneck, with **drill-to-evidence**.
4. **Metric definition + humane posture** — formula/caveats/gaming-risks, team-level, provably no leaderboard. The trust + compliance unlock.
5. **Assistive AI narrative (local model, cited)** — "explain this," evidence-linked, explicitly optional/secondary.

Audit trail and security are woven through screens 1 and 4 — not their own hero screen, but never skipped for this buyer.

## 10. Shortest path from today's repo to the first enterprise pilot

Today: excellent empty foundation. The path (ruthless scope, architecture unchanged):

1. **Persistence + tenancy/RLS spine** (Sprint-01, leaned) — de-risk RLS+pooling in week 1.
2. **Security spine** (OIDC/RBAC/audit/secrets, Sprint-02, leaned) — enough for the security packet.
3. **Compress Phase-1:** build the connector SPI + **Jira + the buyer's SCM + SonarQube** + the sync engine — **on the simulation pack first**, then real credentials. Do not build the 20-connector catalog or the full K1–K7 kit before one connector earns its keep.
4. **The one vertical slice:** ingestion → canonical → correlation → **Friction/bottleneck dashboard** with drill-to-evidence + visible metric definitions.
5. **A thin `eip-ai` slice:** one cited narrative on a local model — not the agent runtime, not MCP.
6. **Package** the on-prem deploy + security/data-handling brief.

Everything not on this path is deferred. The current roadmap reaches the pilot later because it builds *breadth and governance before the slice*.

## 11. Sprint-01 review — reorder without touching architecture

Sprint-01 (DB baseline + RLS + tenancy context + OpenAPI baseline + observability + 4 spikes) is **the right sprint**, slightly overloaded. Optimizations:
- **Keep the RLS + PgBouncer transaction-pooling spike in week 1** (already planned — good; it is the crux of the whole product's trust and scale story).
- **Treat the persistence + RLS spine as the protected core.** The OpenAPI baseline can be minimal, and the *full* observability wiring (Grafana starter dashboards) is deferrable — basic OTel HTTP→DB tracing is enough for now.
- **Do not over-build partitioning** before there is data volume — implement the template, defer the heavy partitioning work.
- **Highest-value addition:** when modeling `WorkItem` persistence, make `WorkItemTransition` / blocked-time and PR-review timings *first-class from day one*, because they are the exact inputs the Friction metric needs. Getting this shape right in Sprint-01 prevents a re-migration when the hero dashboard is built.

## 12. Sprint-02 review

Sprint-02 (RBAC + OIDC + audit + secrets) is **essential and correctly placed** — it is the security-review enabler for a regulated pilot; do not cut it. Optimize:
- Ship **OIDC/Keycloak + tenant/role mapping first** (unblocks everything), then **audit**, then **secret vault**.
- **Defer** the permission-matrix generator over *all* endpoints (there are barely any endpoints), advanced break-glass, and any RBAC sophistication beyond what the pilot's roles need. Build the vault + rotation (needed for the security packet); skip the ceremony.

## 13. Sprint-03 review

Sprint-03 (frontend shell + admin console + Phase-0 close + RG dry-run) — mostly right, one ordering flaw:
- **Keep** the shell (OIDC login, tenant switch, RBAC routing, i18n) — the demo needs a UI.
- **Ordering flaw:** the *connector-configuration* admin UI — literally demo screen #1 ("connect your tools") — is currently scheduled in **Sprint-07**, while Sprint-03's admin console is only tenants/users/roles/audit. That pushes the single most important demo moment months out. **Pull a minimal connector-config admin UI forward to land *with the first connector*** so screen #1 exists as early as possible.
- The **RG1–RG4 dry-run is internal ceremony** — valuable, but do not let it gate customer-facing progress. Timebox it.

## 14. Where we are optimizing for engineering elegance over customer value

Direct list — these cost velocity and buy no customer value *right now*:
- The **8-gate / 21-role / 7-change-class** governance executed by a single contributor on an empty platform.
- **100% coverage on a types-only module**; the **anchor-validating docs-lint**; the **CODEOWNERS↔ModuleOwnership drift check** (DEBT-006/007) — governance policing governance.
- Building the **full connector SPI breadth + K1–K7 contract kit** before one connector proves value.
- The **permission-matrix generator, break-glass, full Grafana starter dashboards** in Phase 0/2.
- **Five documentation trees + docs-lint** stood up before a single unit of product.
- Perfecting the **outbox/Modulith/extraction-seam** before there is any traffic to justify it. (The *design* is right; the urge to *finish* it early is elegance.)

None of this should be deleted — but its *depth* should be dialed to team-size and stage, and re-expanded as we grow.

## 15. Where we are UNDER-investing in long-term architecture

To be fair and balanced — the risks are not all over-build:
- **Connector resilience against real tools.** The SPI is designed, but the parts that actually break enterprise installs — rate-limit/backoff correctness against real Jira/Bitbucket DC, incremental-sync/checkpoint correctness, deletion detection, multi-day large-instance initial sync — are under-invested relative to the metrics/governance investment. This is where pilots fail.
- **Normalization flexibility.** Real customers have messy, bespoke workflows, custom fields, and non-standard statuses. Correlation *quality* lives in the normalizer's ability to map that reality. It is under-specified relative to its make-or-break importance.
- **The metric-correctness / trust harness.** Our entire value proposition is "numbers you can trust." The golden-dataset approach exists on paper, but the deterministic metric engine and its correctness harness — the product's soul — have had *zero* investment while governance had heavy investment. This is backwards.
- **On-prem operability (upgrade / backup-restore / air-gap bundle).** The buyer's #2 concern after security is "can my two-person platform team run and upgrade this." It is parked in Phase 5. A pilot needs at least a credible, tested upgrade + restore path far earlier.
- **The differentiating metrics themselves.** The friction/bottleneck/experience lenses — the actual reason we win — are parked in a "future planning" doc. Under-investing in the moat while over-investing in the scaffolding is the central strategic error to correct.

## 16. Estimates (small, highly capable team; ruthless scope discipline)

| Milestone | Estimate | Gated by |
|---|---|---|
| First usable PoC (simulation → one dashboard, local) | **~6–8 weeks** | Persistence + minimal ingestion on simulation + one dashboard — *if* the slice is protected |
| First customer *demo* (simulation-based, tells the value story) | **~8–10 weeks** | The five screens on simulation data + a security brief |
| First enterprise *pilot* (their tools, their infra, security-reviewed) | **~4–5 months** | Jira + their SCM + Sonar connectors + security spine + deploy/security packet |
| First *production* deployment (paying install) | **~6–8 months** | Connector hardening + the customer's security/procurement cycle (often the longer pole) |

On the *current* breadth-and-governance-first sequencing, add 1–3 months to each; the delta is entirely scope discipline, not capability.

## 17. If I became CTO tomorrow — the exact 90-day execution strategy

Priority order is **business impact, not engineering beauty**: (1) security + on-prem credibility, (2) connect the buyer's tools, (3) one differentiated evidence-backed insight, (4) assistive AI as a cherry, (5) everything else deferred.

**Weeks 1–2 (decide + de-risk).**
- Pick **one design-partner profile**: a regulated enterprise on **Jira DC + Bitbucket DC (or GitLab self-managed) + SonarQube**. Align the connector plan to *their exact stack* — kill GitHub-first.
- Start the **RLS + connection-pooling spike immediately**; it is the trust-and-scale crux.
- **Lean the process now**: keep G3/security + tenancy/RLS gates and CC-1/CC-2 review; suspend the rest of the ceremony until we're 5+ engineers. Re-expand later.

**Weeks 3–6 (spine + pipeline on simulation).**
- Finish persistence + tenancy/RLS + minimal API (Sprint-01, protected core).
- Begin the security spine (OIDC/RBAC/audit/secrets) in parallel — enough for a security packet.
- Build connector SPI + the sync engine and run the **full pipeline on the simulation pack**: ingestion → canonical → correlation. This de-risks the pipeline *without waiting for customer credentials*.
- Model `WorkItemTransition`/wait-time and PR-review timings as first-class (Friction inputs).

**Weeks 7–10 (the hero slice + demo).**
- Build the **Where-time-is-lost / Friction dashboard + bottleneck-lite + review efficiency + DevEx composite**, framed as **Delivery Intelligence**, with **drill-to-evidence** and visible metric definitions.
- Add a **minimal connect-your-tools admin UI** (pull forward from Sprint-07) and the frontend shell.
- Add **one cited, local-model narrative** (thin `eip-ai` slice — no agent runtime, no MCP).
- Assemble the **simulation-based sales demo (the five screens)** + the **security/deploy packet**. Start conversations with 2–3 design partners.

**Weeks 11–13 (real data + pilot motion).**
- Connect the design partner's **real Jira + SCM + SonarQube** on their sandbox; harden incremental sync/checkpointing against their real instances (expect this to surface the hardest bugs — budget for it).
- Run the demo on **their** data. Convert to a **6-week paid pilot**.
- Ship a **credible upgrade + backup/restore** path (even minimal) for their platform team.

**Throughout — non-negotiables:**
- Protect the vertical slice; **defer** the agent suite, MCP, extra connectors, Qdrant, K8s GA, HA/DR, report center.
- The **deterministic engine is always the source of truth**; AI is optional, cited, validation-gated, off-by-default-safe.
- **Never ship an individual metric** — it is our GTM unlock, not just our ethics.
- Invest in **connector resilience, normalization flexibility, and the metric-trust harness** *before* re-investing in governance depth.

---

## Where I disagree with the current direction — and where it is genuinely excellent

**Disagree (change these):**
1. **GitHub-first connectors** — wrong SCM for our sovereignty-constrained buyer; lead with Bitbucket DC / GitLab self-managed.
2. **Breadth-before-proof** — building the full connector SPI + K1–K7 kit + 20-connector catalog before one connector delivers a dashboard.
3. **Governance weight at this stage** — an org-scale operating system run by one contributor; dial depth to team size.
4. **Differentiators parked in "future planning"** — the friction/bottleneck/experience lenses are the *moat*; at least the Friction hero + bottleneck-lite must be first-class near-term targets, not deferred indefinitely.
5. **Connector admin UI in Sprint-07** — that's demo screen #1; pull it forward.

**Excellent (protect these — earned, not flattered):**
1. **On-prem / air-gap wedge** — the single smartest strategic choice; it defines a market the SaaS incumbents structurally cannot enter.
2. **Humane, team-level, anti-surveillance stance** — correctly treated as a market position and a works-council sales unlock, not a footnote.
3. **Deterministic-first, AI-assistive discipline** — architecturally enforced; it is what makes the numbers trustworthy and keeps us out of the "AI slop" category.
4. **Modular monolith + extraction seams + tenancy-by-construction** — genuinely excellent engineering that will not need to be redone; it lets us scale *when* we need to without a rewrite.
5. **Simulation-first testing** — an under-appreciated *GTM* asset: it lets us demo and pilot months before real credentials, and it de-risks the pipeline. Lean on it hard.

**Bottom line:** the architecture is category-defining; the *sequencing* is not yet. Do not touch the architecture. Re-order the next 90 days around the one eleven-minute demo stretch that sells the company, and treat the differentiating metrics — not the governance — as the thing worth being excellent at first.
