# Engineering Intelligence Platform (EIP) — Product Vision

## 1. Problem Statement

Enterprise engineering organizations run their software delivery lifecycle across a dozen or more disconnected tools: Jira and Confluence for planning and knowledge, GitHub/GitLab/Bitbucket for source control and review, Jenkins/Azure DevOps/GitHub Actions/GitLab CI for build and deployment, SonarQube for quality, Artifactory for artifacts, Kubernetes/OpenShift for runtime, and Prometheus/Grafana/OpenTelemetry for operations. Each tool holds a partial, locally-consistent view of engineering reality. None of them answers the questions leadership actually asks:

- Is this release ready to ship, and what is the concrete evidence for or against?
- Which epics and projects are at risk of delay, why, and what dependency or blocker is driving it?
- Is our delivery flow improving quarter over quarter — cycle time, throughput, predictability — and where is the constraint?
- Where is technical debt, incident load, or review bottleneck actively taxing delivery?
- Did that process change (new branching model, new sprint cadence, new quality gate) actually help?

Today these questions are answered by hand: engineers export CSVs, program managers assemble slide decks, and the resulting picture is stale by the time it is presented, non-reproducible, and uncorrelated — the Jira story is not linked to its pull requests, the deployment is not linked to the incident it caused, the sprint commitment is not linked to what actually shipped. The result is a systematic lack of **correlated, trustworthy insight across SDLC tools**:

1. **Fragmentation.** Signals live in tool-specific silos with tool-specific identities; nothing joins a `WorkItem` to its commits, pull requests, builds, deployments, quality gates, and incidents end-to-end.
2. **No shared semantics.** "Cycle time" and "lead time" mean different things in every team's spreadsheet. Metrics lack stated formulas, inputs, grain, caveats, and gaming risks, so they cannot be trusted or compared.
3. **Manual, lossy reporting.** Sprint reviews, release notes, executive summaries, and risk reports are hand-assembled narratives detached from the underlying data.
4. **Data sovereignty walls.** Banks, telecoms, defense, and public-sector organizations cannot ship SDLC data — source metadata, incident details, personnel activity — to SaaS analytics vendors. Many environments are fully air-gapped. The dominant analytics products are SaaS-only and therefore simply unavailable.
5. **Surveillance backlash.** Where measurement is attempted, naive per-individual activity counting (commits, lines, tickets closed) destroys trust, invites gaming, and measures the wrong thing. Engineering leaders need humane, systemic measurement or they will get no measurement at all.

## 2. Target Market

| Segment | Profile | Why EIP |
| --- | --- | --- |
| Regulated enterprises | Banking, insurance, telecom, healthcare, energy; 200–10,000+ engineers; strict data-residency and audit obligations | On-premise deployment, air-gap support, local LLMs, envelope-encrypted secrets, full audit trail |
| Public sector & defense | Air-gapped or classified networks; no external SaaS permitted | Runs fully disconnected with local LLM providers (Ollama, vLLM); no SaaS dependencies |
| Large product/engineering organizations | Multi-business-unit companies consolidating engineering visibility across acquisitions and heterogeneous toolchains | Connector framework spanning Jira, Confluence, GitHub, GitLab, Bitbucket, SonarQube, Artifactory, Kubernetes, OpenShift, Docker Registry, Prometheus, Grafana, OpenTelemetry, generic CI/CD, REST, SQL, file/document, and custom internal tools |
| IT service providers / holding companies | Operate engineering for multiple internal or external clients | Multi-tenancy with row-level tenant isolation; per-tenant configuration, model routing, and RBAC |

Primary buyer: VP Engineering / CTO / Head of Engineering Excellence. Primary champions: engineering managers, delivery/program managers, platform teams. Primary daily users: engineering leadership, team leads, PMO, SRE/ops leads, and executives consuming generated reports.

## 3. Vision Statement

**EIP is the on-premise, AI-native system of intelligence for enterprise software delivery: it integrates with the SDLC tools an organization already runs, normalizes and correlates their data into one canonical model, computes transparent and humane engineering metrics, and uses agentic AI with RAG over the organization's own knowledge to turn that correlated data into dashboards, reports, and narrative insight — all without a single byte leaving the enterprise boundary.**

In its target state, EIP is where an engineering organization looks first: to see delivery health, to understand risk before it becomes delay, to generate the sprint review, release notes, and executive summary that used to consume days of manual work, and to ask free-form questions of its own engineering reality — with every answer citing its sources and stating its uncertainty.

## 4. Strategic Pillars

### 4.1 On-Prem Sovereignty
EIP is on-premise first, not on-premise eventually. It deploys via Docker Compose for evaluation and Kubernetes/OpenShift for production, runs fully air-gapped, and has **no SaaS dependencies except optional, configurable LLM providers** — with local LLMs (Ollama, vLLM) as first-class citizens so even AI features work disconnected. Data residency, secrets handling (AES-256-GCM envelope encryption, pluggable KMS SPI), and audit are designed for regulated-industry review, not retrofitted.

### 4.2 Correlation Over Collection
Collecting events is commodity; the product is the join. EIP normalizes heterogeneous tool data into a canonical domain model (unified `WorkItem` supertype with `ExternalRef` source-tool identity mapping, plus Repository, PullRequest, Build, Pipeline, Deployment, Release, Incident, and the rest of the canonical vocabulary) and correlates entities across tool boundaries: story → branch → commits → pull request → build → deployment → release → incident. Metrics, risk scores, and AI insights are computed on this correlated graph, which is what makes them trustworthy and explainable rather than tool-local approximations.

### 4.3 AI-Native Insight
AI is an architectural layer, not a bolt-on chat box. A dedicated agent runtime (plan/execute agents with tool-calling, budgets, guardrails, and full audit of every LLM call) powers a canonical agent suite — Sprint Review, Release Notes, Delivery Risk, Executive Summary, Incident Analysis, Documentation, diagram generation, and more. RAG grounds every narrative in the organization's actual data and documents with permission-aware retrieval and source citations. MCP makes EIP both a client of enterprise MCP servers and a server exposing allow-listed capabilities, so EIP composes into the enterprise's broader AI estate.

### 4.4 Humane Metrics — the Anti-Surveillance Stance
EIP is explicitly **not** an individual-surveillance or stack-ranking tool. Metrics combine multiple signals, always presented with context, uncertainty, and stated limitations. There is no simplistic commit counting. Team-health analytics (load balance, review bottlenecks, knowledge concentration / bus factor) are computed at team level only and are explicitly anti-toxic-ranking. Every metric definition ships with its purpose, formula, inputs, grain, caveats/limitations, and gaming risks — because a metric whose weaknesses are hidden is a metric that will be gamed and mistrusted. This stance is a product requirement, not a documentation footnote: the platform is designed so that individual-level ranking views cannot be casually constructed.

### 4.5 Enterprise-Grade Operations
EIP is built to be operated by enterprise platform teams: secure (OIDC via Keycloak or pluggable enterprise IdP, tenant-scoped RBAC, encrypted secrets, audited access), scalable (async Kafka-based ingestion, separately deployable workers, horizontal scaling), multi-tenant (tenant_id + Postgres RLS), and observable (OpenTelemetry traces/metrics/logs, Prometheus + Grafana self-observability dashboards shipped in-box). At-least-once delivery with idempotent consumers, DLQs per consumer group, checkpointed connectors, and documented backup/restore and upgrade paths make it a system operations teams can trust with production duty.

## 5. Differentiation

### 5.1 Versus SaaS Engineering Analytics (LinearB/Jellyfish/Swarmia-class)

| Dimension | SaaS analytics tools | EIP |
| --- | --- | --- |
| Deployment | Vendor cloud only; data leaves the enterprise | On-premise first; air-gap capable; data never leaves |
| AI | Cloud LLMs, vendor-controlled | Configurable LLM provider SPI incl. local Ollama/vLLM; per-tenant, per-agent model routing |
| Data model access | Vendor-defined dashboards; limited raw access | Full canonical model in customer's PostgreSQL; open REST `/api/v1` with OpenAPI 3 |
| Coverage | Primarily Git + issue trackers | Full SDLC: planning, SCM, CI/CD, quality (SonarQube), artifacts, runtime (Kubernetes/OpenShift), observability (Prometheus/Grafana/OTel), plus generic REST/SQL/file connectors and custom internal tools |
| Measurement philosophy | Varies; individual metrics often exposed | Enforced humane-metrics stance; team-level health metrics; every metric documents caveats and gaming risks |
| Extensibility | Closed connector set | Connector SPI, VectorStore SPI, LLM provider SPI, MCP client + server |
| Multi-tenancy | Vendor-side | Customer-side: one deployment can serve multiple business units or clients with RLS isolation |
| Narrative outputs | Limited summaries | Full agentic report generation: sprint reviews, release notes, executive summaries, diagrams, presentations |

The structural difference: SaaS tools cannot serve air-gapped and data-sovereign customers at all, and none combines full-SDLC correlation with an on-prem agentic AI layer grounded in the customer's own knowledge base.

### 5.2 Versus Raw BI (data warehouse + Tableau/Power BI/Metabase)

Raw BI on top of tool exports gives an organization charts, but the organization must itself build and forever maintain: connectors with rate limiting, retry, and checkpointing; identity resolution across tools; a canonical schema and its evolution; metric definitions with agreed semantics; incremental sync and webhook intake; permission-aware access; and any AI layer. EIP ships all of that as product:

- **Opinionated canonical model** — the cross-tool join (WorkItem ↔ PullRequest ↔ Deployment ↔ Incident) is built in, not a bespoke ETL project.
- **Metric semantics as product** — flow, DORA, quality, delivery-risk, ops, and team-health metrics with canonical, documented formulas rather than every analyst reinventing cycle time.
- **Operational ingestion** — connector SPI with validate/testConnection/healthCheck, full and incremental sync, webhooks, exponential backoff with jitter, idempotent upserts, dedup, and simulation mode.
- **AI on top of the model** — agents, RAG with citations, and MCP integration that BI stacks do not provide.
- **Escape hatch preserved** — because the canonical model lives in the customer's PostgreSQL, existing BI tools can still query it; EIP complements rather than blocks BI investment.

## 6. Guiding Principles

1. **Trust before insight.** Every number is explainable: formula, inputs, grain, caveats, gaming risks. Every AI narrative cites sources. Uncertainty is stated, never hidden.
2. **Teams, not individuals.** Measurement targets systems and flow. No stack ranking, no individual leaderboards, no commit counting.
3. **Sovereign by default.** Assume the air-gapped deployment; anything requiring egress must be optional and configurable, and the only permitted category is LLM providers.
4. **Correlate, then compute.** Metrics and AI operate on the correlated canonical model, never on raw single-tool feeds.
5. **Async and idempotent.** Ingestion and AI work flows through Kafka with at-least-once delivery, idempotent consumers, and checkpointing; the platform degrades gracefully, never corrupts.
6. **Boring, durable technology.** Java 21 + Spring Boot 3.x modular monolith (Spring Modulith conventions), PostgreSQL 16, Redis 7, Kafka — chosen for enterprise operability and a clean extraction path to services when scale demands.
7. **Every capability has an SPI.** Connectors, vector stores (pgvector default, Qdrant option), LLM providers, KMS — pluggable, so no enterprise environment is a dead end.
8. **Secure and audited by construction.** Tenant isolation via RLS, RBAC on every capability including MCP-exposed ones, secrets encrypted and masked, access audited.
9. **Simulation-first development.** Simulated enterprise data packs and connector mock modes make the platform demonstrable and testable without customer data.
10. **Documentation is product.** The /docs tree is the source of record; behavior-defining docs carry explicit acceptance criteria.

## 7. Success Measures for the Product

| Measure | Target | Rationale |
| --- | --- | --- |
| Time-to-first-insight | < 1 day from install to first correlated dashboard (Jira + GitHub via guided setup or simulation pack) | Adoption lives or dies on setup friction |
| Correlation coverage | ≥ 80% of pull requests in connected repos automatically linked to a WorkItem; ≥ 90% of deployments linked to a release/pipeline | Correlation is the core value; must be measurable |
| Report displacement | ≥ 70% of pilot teams replace a previously manual recurring report (sprint review, release notes, or exec summary) with a generated one within one quarter | Proves the AI layer saves real work |
| Metric trust | 100% of shipped metrics carry complete definitions (purpose, formula, inputs, grain, caveats, gaming risks); user-reported "I don't trust this number" issues trending to zero | Trust is the differentiator |
| Air-gap viability | Full platform including RAG + agents operable with zero external network access using local LLMs | Non-negotiable market requirement |
| Operational health | Reference deployment meets published NFR targets (see ../product/PRD.md §7) for ingest throughput, dashboard latency, and availability | Enterprise-grade is a testable claim |
| Anti-surveillance integrity | Zero individual-ranking features shipped; team-health metrics reviewed against anti-toxic-ranking criteria before release | The stance must survive roadmap pressure |
| Extensibility proof | At least one connector, one LLM provider, and one MCP integration built outside the core team against published SPIs | SPIs are real only if third parties succeed with them |

## 8. Three-Horizon Outlook

Horizons map onto the canonical implementation phases (see ../product/PRD.md §10 for release criteria per phase).

### Horizon 1 — Correlated Visibility (Phases 0–2)
Foundations, ingestion core, and analytics. The platform scaffolding (tenancy, RBAC, audit, secrets, OpenAPI, observability, Docker Compose dev stack) is laid in Phase 0; the connector SPI, Kafka pipeline, Jira + GitHub + simulation connectors, and normalized model v1 arrive in Phase 1; the metric engine with flow, DORA, and quality metrics, the productivity/sprint/kanban/quality dashboards, and the remaining P1 connectors (GitLab, SonarQube, CI/CD, Prometheus) land in Phase 2. **Outcome:** an engineering org sees trustworthy, correlated delivery metrics across its core toolchain — the "single pane" problem solved without AI.

### Horizon 2 — AI-Native Insight (Phases 3–4)
The AI core: LLM provider SPI, RAG pipeline, and the first agents (Sprint Review, Release Notes, Delivery Risk) plus the report engine and artifact library in Phase 3; the full agent suite, MCP client/server, generated outputs (presentations, diagrams, exec reports), scheduling, and notification channels in Phase 4. **Outcome:** the platform moves from showing data to producing work products — recurring reports generate themselves, risk is narrated with evidence and citations, and EIP participates in the enterprise AI ecosystem via MCP.

### Horizon 3 — Enterprise Standard (Phase 5 and beyond)
Enterprise hardening: Kubernetes/OpenShift GA, HA, performance at published scale targets, security certification checklist, backup/restore, upgrade paths, and the remaining connector catalog. Beyond Phase 5: deeper predictive analytics on the correlated graph, richer simulation packs, and an ecosystem of third-party connectors and agents built on the SPIs. **Outcome:** EIP is the default engineering-intelligence layer in sovereignty-constrained enterprises — as standard in the stack as the observability platform.

## 9. Non-Goals

EIP deliberately does **not** aim to be:

1. **An individual-surveillance or stack-ranking tool.** No per-person productivity scores, leaderboards, or commit counting. This is the product's foundational anti-goal.
2. **A replacement for source SDLC tools.** EIP does not replace Jira, GitHub, Jenkins, SonarQube, or Grafana; it correlates and analyzes their data. Write-back to source tools is out of scope except where a future phase explicitly defines it.
3. **A project management tool.** No backlog editing, sprint planning boards, or work assignment. EIP reads Sprints, Boards, and WorkflowStates; it does not manage them.
4. **A general-purpose BI platform.** EIP ships opinionated engineering analytics; arbitrary self-service data modeling remains the job of the customer's BI stack, which can query EIP's canonical model directly.
5. **A SaaS product (in this roadmap).** All phases target on-premise deployment. A managed offering is not on the canonical roadmap.
6. **An APM/observability platform.** EIP consumes Prometheus/Grafana/OTel signals for engineering intelligence (incident frequency/impact, SLO health, alert noise); it does not replace runtime monitoring or alerting.
7. **An LLM vendor or model host.** EIP orchestrates models through the LLM provider SPI (Ollama, vLLM, OpenAI-compatible, Anthropic-compatible, custom enterprise endpoints); it does not train or ship its own models.
8. **A code hosting, CI, or security scanning engine.** SecurityFindings and QualityGates are ingested from tools like SonarQube, not produced by EIP's own scanners.

---
*Sibling documents: ../product/PRD.md (requirements), ../product/Personas.md (users). Source of record: the /docs tree.*
