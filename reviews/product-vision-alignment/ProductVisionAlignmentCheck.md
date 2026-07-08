# Product Vision Alignment Check

> Lightweight vision-alignment review · Date: 2026-07-08 · Reviewer: R-CA (on request) · Scope: product vision vs documented spec + program, **not** a full repository review.

**Documents read (strict context, 6 total):** [Vision.md](../../docs/vision/Vision.md), [PRD.md](../../docs/product/PRD.md) (§1–§5.7, §10), [FeatureCatalog.md](../../docs/product/FeatureCatalog.md) (§1, §8–§10), [ArchitectureOverview.md](../../docs/architecture/ArchitectureOverview.md) (§7 ADR index), [ImplementationReadinessDecision.md](../architecture-readiness/ImplementationReadinessDecision.md) (full), [SprintCatalog.md](../../program/SprintCatalog.md) (full). No source code, no other docs; nothing modified.

---

## 1. Canonical product vision (5–8 bullets)

Distilled from Vision §3/§4/§9 and PRD §2/§4:

1. **On-premise, air-gap-capable Engineering Intelligence Platform** for regulated/sovereignty-constrained software organizations — data never leaves the enterprise boundary; the only optional egress is a configurable LLM provider (Vision §4.1).
2. **Integrates the SDLC toolchain an org already runs** — Jira, Confluence, GitHub, GitLab, Bitbucket, SonarQube, Artifactory, Kubernetes/OpenShift, Docker Registry, Prometheus, Grafana, OpenTelemetry, generic CI/CD/REST/SQL/file, and custom internal tools (PRD §4.1).
3. **Correlation over collection** — normalizes heterogeneous tool data into one canonical model (`WorkItem` supertype, `ExternalRef` provenance, PR→build→deploy→incident) and joins it end-to-end; correlation coverage is itself a measured KPI (Vision §4.2, §7).
4. **Deterministic, documented metrics first** — flow, DORA, quality, delivery-risk, ops, and team-health metrics, each shipping purpose/formula/inputs/grain/caveats/gaming-risks; the "single pane" is solved **without AI** in Horizon 1 (Vision §7, §8.1; PRD §2.1 goal 2).
5. **AI as an additive, grounded, assistive layer — never autonomous** — RAG-grounded, citation-bearing, Validation-gated narratives/reports/diagrams/recommendations; "AI is additive and never trusted; generative failure never blocks ingestion, analytics, dashboards, or RBAC" (Readiness §7 principle 1; FR-087/088).
6. **Humane, team-level measurement — anti-surveillance by construction** — no individual ranking, leaderboards, or commit counting, ever; release-blocking (Vision §4.4; FR-057 / NFR-071; Readiness §7 principle 8).
7. **Enterprise-operable and extensible** — multi-tenant (RLS), secure (OIDC/RBAC/envelope-encrypted secrets/audit), observable, with SPIs for connectors, vector stores, LLM providers, KMS, and MCP client/server (Vision §4.3, §4.5).
8. **Explicitly not** a source-tool replacement, a project-management tool, a BI platform, an APM, a model host, or a code-hosting/CI/security-scanning engine (Vision §9; PRD §2.2).

This matches the user-stated intent: *measure team/developer/analyst/tester/delivery/flow productivity through deterministic metrics, with AI assisting interpretation — not an autonomous coding platform.*

## 2. Evidence supporting the vision

- **Deterministic-first ordering.** Phases 0–2 deliver correlated visibility and the metric engine with **zero AI**; AI is Horizon 2 (Phases 3–4) and strictly additive (Vision §8.1–§8.2; SprintCatalog §1 — AI modules first appear in Phase 3).
- **AI bounded in the requirements.** The 18 canonical agents (FR-082) are all analysis/narrative/diagram/validation/config-assist agents. FeatureCatalog §8 is explicit: Code Quality "narrates… proposes remediation themes" (FEAT-144), Security Review "never auto-remediates" (FEAT-149), Configuration Assistant applies "nothing without explicit admin confirmation and RBAC" (FEAT-150), Team Health has "no individual attribution in outputs" (FEAT-145). **No code-generation or autonomous-development agent exists.**
- **Architecture enforces the boundary.** Readiness §7 principle 1 makes "AI additive and never trusted" a release-enforced invariant; every factual claim must trace to a tool result; validation is non-bypassable.
- **Anti-surveillance is release-blocking**, not a preference (FR-057/NFR-071; RG2 in SPRINT-03; Vision §10 refuses "just this one customer needs per-developer numbers").
- **Non-goals are normative** and cover the exact drift the user fears — no code hosting/CI/security-scanning engine, no source-tool replacement (Vision §9.2/§9.8; PRD §2.2).
- **Over-engineering is a tracked risk** with a YAGNI mitigation baked into SPRINT-00 ("everything must be consumed by a later story or cut").

## 3. Drift toward autonomous coding / agentic software development

**Product-level drift: NONE.** The specification, feature catalog, and architecture principles are unambiguous that AI is a grounded, validated, assistive interpretation/recommendation layer over deterministic analytics. There is no "AI writes the software," no autonomous remediation, no agentic-coding positioning anywhere in the product docs.

**One perception/positioning risk to name (not a product drift):** the repository's current center of gravity is a very large **build-time** apparatus — the Engineering Operating System (33 EOS docs, R-XX role taxonomy, multi-gate process, agent/workflow orchestration used to *construct* EIP). This is *how the product is built*, not *what the product is*. CLAUDE.md already disambiguates the product's 18 runtime agents from the engineering roles, but an outside reader skimming the repo today (Phase 0, no product code yet) could mistake the agentic-**development** machinery for an "agentic coding platform" product. This is a communication risk to manage, not evidence of scope drift.

## 4. Backlog / sprint items that seem over-engineered or off-vision

The concretely planned work (SPRINT-00…07) is lean and on-vision — foundation → ingestion → connectors, no product feature ahead of its phase. Observations, in descending materiality:

- **Process weight vs product weight (mild).** The governance/EOS layer is heavy relative to a Phase-0 empty platform. It is defensible for a multi-year regulated build, and SPRINT-00 explicitly flags "over-engineering the platform" as a managed risk — but it is the largest pool of effort not *directly* producing productivity intelligence. Keep it proportionate; resist growing process faster than product.
- **MCP client/server (Phase 4, FR-104–108) is adjacent to the core value.** It is ecosystem integration, not measurement. Correctly phased late; a candidate to deprioritize/descope if Phase-3/4 focus is needed. Not drift.
- **Full 18-agent suite + presentations/diagrams (Phase 4)** is ambitious; this is where "scope gravity toward a PM/BI tool" pressure will land (Vision §10). Each agent currently maps to a real reporting need, so no cut is warranted now — but Phase 4 is the checkpoint to re-test each agent against "does this serve productivity intelligence?"
- **No over-engineered item found in SPRINT-00.** Scaffolding, CI, Compose, `eip-core` skeleton, ADR backfill, CODEOWNERS, docs-lint are all foundational and consumed by later stories.

## 5. Missing capability required for the real target product

The user's target explicitly names **analyst and tester productivity** and **DevEx / AnalystEx / TesterEx** improvement. The documented canonical metric set is "flow, DORA, quality, delivery-risk, ops, team-health" (Vision §4.2/§4.4; PRD §2.1 goal 2) — **developer- and delivery-centric**. Two gaps appear against the stated target (verify against PRD FR-050–062 and FeatureCatalog §6 before acting — those detailed sections were out of this lightweight read):

1. **Analyst and tester productivity lenses are under-represented as first-class.** The model carries the raw material (WorkItemType includes STORY/EPIC/FEATURE for requirements and BUG/INCIDENT_TICKET; SonarQube quality; review data), but there is no explicitly named **AnalystEx** (requirement cycle time, refinement/readiness flow, spec churn) or **TesterEx** (defect lead time, test/rework cycle time, escaped-defect rate — team-level) metric family.
2. **An explicit Developer-Experience / SDLC-friction framing is thin.** "Team Health" (load balance, review bottlenecks, bus factor) is DevEx-adjacent, but a named **DevEx / friction index** (review wait, CI/build wait, flow interruptions) as an experience-improvement surface is not prominent.

These are *coverage gaps vs the user's articulated scope*, not drift — and they are cleanly addressable inside the existing Phase-2 analytics architecture (they are new deterministic, team-level metric definitions, fully compatible with the anti-surveillance stance).

## 6. Recommendation

### **CONTINUE WITH ROADMAP ADJUSTMENT**

The product is fundamentally and strongly aligned with the intended vision — deterministic-metrics-first, AI-assistive-not-autonomous, anti-surveillance, on-prem. There is **no drift toward autonomous coding** and **no reason to stop or realign**. The only adjustments are *additive scope clarifications* to fully cover the user's stated analyst/tester/experience dimensions, plus light positioning hygiene — all deferrable to their natural phase with **zero disruption to the in-flight Sprint-00**.

## 7. Concrete roadmap adjustment suggestions (minimal Sprint-00 disruption)

None of these touch Sprint-00 or in-flight work; they are Phase-2 scope notes and light doc positioning, to be actioned later (not now).

1. **Do not touch Sprint-00.** Its scope (scaffold/CI/Compose/`eip-core`/ADR/CODEOWNERS/docs-lint) is foundational and correct. Continue TASK-0008 as planned.
2. **Extend the Phase-2 analytics scope (not architecture)** to name **AnalystEx** and **TesterEx** metric families and a **DevEx/friction index** alongside flow/DORA/quality — all team-level, deterministic, definition-complete (purpose/formula/caveats/gaming-risks), and anti-surveillance-compliant. Land as additions to PRD §5.3 (FR-050–062) and FeatureCatalog §6 when Phase 2 is scoped — no new module, no new architecture.
3. **One positioning line in Vision/PRD** affirming the *inclusion* side: EIP measures and improves engineering productivity **and experience across developer, analyst, tester, delivery, and flow** — complementing the existing Non-Goals that state the exclusion (autonomous coding, surveillance). Keeps the target scope explicit for future contributors.
4. **Keep AI strictly Phase-3+ and assistive** (already the case) and **re-test each Phase-4 agent** at Phase-4 kickoff against "does this serve productivity intelligence?" — the natural checkpoint for scope-gravity.
5. **Positioning hygiene:** keep the EOS / agentic-**build** apparatus clearly labelled as "how we build EIP," distinct from the product, so the repo is never mistaken for an agentic-coding product (CLAUDE.md already does this; preserve it in any README/marketing-facing summary).

**Do not** rewrite documents, create backlog items, or start implementation from this check (per instruction) — these are recommendations for the next planning cycle.

---

## Report summary

- **Verdict:** **CONTINUE WITH ROADMAP ADJUSTMENT** — strongly aligned; no autonomous-coding drift; additive scope clarifications only.
- **Drift risks:** (1) *Product* drift toward autonomous coding — **none detected**; architecture makes "AI additive, never trusted" release-enforced. (2) *Perception* risk — the large agentic-**build** EOS could be mistaken for the product; manage via positioning. (3) *Scope-gravity* risk at Phase 4 (18 agents + presentations, MCP) toward PM/BI territory — governed by the normative Non-Goals; re-test at Phase-4 kickoff. (4) *Coverage gap* — AnalystEx/TesterEx/explicit DevEx under-represented vs the stated target; addressable in Phase-2 analytics.
- **Recommended next command:** see below.
