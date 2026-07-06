# Agent Architecture

Agentic AI backend design for the Engineering Intelligence Platform (EIP). All generative capabilities in EIP are delivered by a fleet of 18 canonical agents running inside the `eip-ai` module of the backend modular monolith, executed asynchronously on the `eip-workers` runtime. This document defines the runtime architecture, execution model, tool-calling contract, the full agent catalog, multi-agent composition, the LLM provider SPI, prompt management, evaluation, cost controls, auditability, and degradation behavior.

Related documents: `../ai/RAGArchitecture.md`, `../ai/MCPArchitecture.md`, `../architecture/SecurityModel.md`.

## 1. Design Principles

1. **On-premise first.** Agents run against local LLMs (Ollama, vLLM) by default; SaaS LLM providers are optional and explicitly configured per tenant. The platform must run fully air-gapped.
2. **AI is additive, never load-bearing.** Ingestion, analytics, dashboards, and RBAC function with zero LLM providers configured (see Section 13).
3. **Deterministic scaffolding, generative filling.** Agents fetch facts through typed tools (domain queries, metric queries, RAG retrieval); the LLM composes narrative around verifiable data. Numbers in outputs come from tools, not model recall.
4. **Everything budgeted, everything audited.** Every run has token/step/cost/time budgets; every LLM call is persisted with prompt, model, tokens, cost, and latency (prompts redacted per policy).
5. **Anti-surveillance stance.** No agent produces individual rankings or individual productivity scores. Team Health outputs are team-level only, with context, uncertainty, and limitations stated — consistent with the platform anti-goals.
6. **Java-first orchestration.** Default orchestration is Java 21 + LangChain4j inside `eip-ai`. Python is allowed only for optional AI workers, isolated behind Kafka queues/REST.

## 2. Runtime Architecture in `eip-ai`

### 2.1 Components

| Component | Responsibility |
|---|---|
| `AgentOrchestrator` | Owns the run lifecycle. Consumes run requests from `eip.ai.jobs`, drives the plan/execute loop, enforces budgets and guardrails, persists step state, publishes results to `eip.ai.results`. |
| `AgentRegistry` | Catalog of the 18 canonical agent definitions: identity, version, required tools, default model routing key, budget defaults, output JSON Schema, guardrail policy. Loaded at startup, tenant overrides applied at run time. |
| `ToolRegistry` | Typed tool catalog (Section 4). Resolves the tool set an agent may see for a given run: intersection of the agent definition, tenant configuration, and the RBAC permissions of the initiating principal. |
| `LlmProviderRegistry` | Registered LLM provider instances (Section 7) plus the per-tenant + per-agent routing table and fallback chains. |
| `PromptTemplateService` | Versioned prompt templates from PostgreSQL with per-tenant overrides (Section 8). |
| `RunStore` | PostgreSQL persistence of `agent_run`, `agent_run_step`, `llm_call` rows (tenant_id + RLS, like all EIP tables). |
| `GuardrailPipeline` | Pre-call input policies (redaction, injection screening) and post-call output policies (schema validation, citation checks, banned-content rules). |

`AgentOrchestrator` instances run in `eip-workers` deployments; the `eip-app` API app only enqueues runs and serves run status/results. This keeps long LLM calls off the request path.

### 2.2 Run lifecycle

An agent run moves through a fixed state machine. Transitions are persisted; a crashed worker resumes from the last persisted step.

```mermaid
stateDiagram-v2
    [*] --> queued : POST /api/v1/ai/runs (validated, enqueued on eip.ai.jobs)
    queued --> planning : worker claims run (Redisson lock on runId)
    planning --> executing : plan persisted (ordered step list)
    executing --> executing : step completed / tool call / LLM call (checkpoint)
    executing --> validating : final draft produced
    validating --> completed : guardrails + Validation Agent pass
    validating --> executing : validation failure, retry budget remains (revise)
    planning --> failed : plan rejected / budget exceeded
    executing --> failed : unrecoverable error / budget exceeded
    validating --> failed : validation failed, retries exhausted
    queued --> failed : expired before pickup (TTL)
    completed --> [*]
    failed --> [*]
```

State semantics:

- **queued** — request row exists, message published to `eip.ai.jobs` (key: `tenantId+runId`). Idempotency key on the enqueue endpoint prevents duplicate runs.
- **planning** — the orchestrator asks the routed model for a bounded plan (max steps from budget), or uses the agent's static plan when the agent is non-generative in its planning (most single-purpose agents ship a fixed plan template).
- **executing** — steps run sequentially (parallel fan-out only in Report Composition, Section 6). Each step's inputs/outputs are persisted before advancing, so runs are resumable.
- **validating** — output-schema check, citation check, and (for user-facing outputs) a mandatory Validation Agent pass.
- **completed / failed** — result envelope published to `eip.ai.results`; artifacts written to object storage (MinIO) and registered as `GeneratedReport` / `Artifact` domain entities where applicable.

## 3. Execution Model

### 3.1 Async jobs on Kafka

- Request topic: `eip.ai.jobs`. Result topic: `eip.ai.results`. Consumer-group DLQs follow the platform convention `.<group>.dlq` (e.g. `eip.ai.jobs.ai-orchestrator.dlq`).
- Envelope: the standard EIP event envelope (`eventId (UUIDv7), tenantId, source, entityType=AgentRun, entityId=runId, eventType, occurredAt, ingestedAt, schemaVersion, payload, traceparent`).
- Guarantees: at-least-once delivery with idempotent consumers (dedup on `eventId`), ordered per key (`tenantId+entityId`).
- Triggers: interactive API calls, report schedules (`eip.reports.jobs` fan-in), domain-event reactions (e.g. Incident Analysis on incident-closed events from `eip.domain.ops`), and MCP server invocations (see `../ai/MCPArchitecture.md`).

### 3.2 Resumable steps

Every step is persisted in `agent_run_step` (`runId, stepIndex, stepType, input, output, status, startedAt, finishedAt, llmCallIds[]`). On worker restart, the orchestrator reloads the run, skips completed steps, and resumes from the first non-terminal step. Tool calls are designed to be idempotent or replay-safe (reads are naturally safe; the artifact writer uses content-hash keys).

### 3.3 Budgets

Each run carries a resolved budget — agent defaults, overridden per tenant, capped by platform limits:

| Budget | Default | Enforcement point |
|---|---|---|
| `maxTokensTotal` | 64k (per run, prompt + completion) | before each LLM call; exceeding transitions to `failed` with `BUDGET_EXCEEDED` |
| `maxSteps` | 12 | plan acceptance + step loop |
| `maxCost` | tenant-configured currency amount, computed from provider price table | before each LLM call |
| `maxWallClock` | 10 min interactive / 60 min scheduled | watchdog per run; timeout → `failed` |
| `maxToolCalls` | 40 | ToolRegistry interceptor |
| `maxValidationRetries` | 2 | validating → executing loop |

Budget consumption is recorded per step and surfaced in run status responses and in the tenant quota ledger (Section 11).

## 4. Tool-Calling Contract

Agents interact with the platform exclusively through **typed tools**: each tool has a name, JSON Schema for input and output, an RBAC permission requirement, and a tenant scope. LangChain4j tool specifications are generated from these definitions.

| Tool family | Examples | Backing module | Notes |
|---|---|---|---|
| Domain queries | `getWorkItems`, `getSprint`, `getPullRequests`, `getIncidents`, `getDependencies`, `getReleases` | `eip-core` read APIs | Cursor-paginated, hard row caps, canonical entities only (WorkItem, Sprint, Incident, ...). |
| Metric queries | `getMetricSeries`, `getMetricSnapshot`, `getDoraMetrics`, `getFlowMetrics`, `getReleaseReadiness` | `eip-analytics` | Returns metric values with grain, formula version, and caveats attached — agents must propagate caveats into outputs. |
| RAG retrieval | `ragSearch`, `ragFetchChunk` | `eip-ai` RAG subsystem | Permission-aware, citation-bearing (see `../ai/RAGArchitecture.md`). |
| MCP tools | admin-allow-listed external tools via `McpClientGateway` | `eip-ai` | Treated as untrusted input; see `../ai/MCPArchitecture.md`. |
| Artifact writer | `writeArtifact` (markdown/HTML/PDF/PPTX/Mermaid/CSV) | `eip-reports` + MinIO | Only mutating tool available to agents; writes are staged until the run completes. |

Contract rules:

1. **Principal propagation.** Every tool call executes with the RBAC scope of the run's *initiating principal* (user or service token) and the run's tenant. Agents never escalate: a run started by a user who cannot see Project X cannot retrieve Project X data through any tool, including RAG.
2. **Tenant scoping.** `tenantId` is injected by the ToolRegistry from the run context; it is never a model-controllable parameter. Postgres RLS is the backstop.
3. **Schema validation both ways.** Tool inputs produced by the model are validated against the input schema before execution (invalid → structured error returned to the model, counted against `maxToolCalls`); tool outputs are validated before being appended to the context.
4. **Read-mostly.** Except `writeArtifact` and explicitly allow-listed MCP tools, all tools are read-only. No agent tool mutates canonical domain data.
5. **Result size limits.** Tool results are truncated/summarized above a configurable size to protect the context window; truncation is flagged so agents can paginate instead of guessing.

## 5. Canonical Agent Catalog

The 18 canonical agents. Names are fixed vocabulary; do not invent variants.

| # | Agent | Category | Primary output | Typical trigger | User-facing (Validation Agent mandatory) |
|---|---|---|---|---|---|
| 1 | Data Ingestion Agent | Operations | Connector configuration/mapping suggestions, sync diagnostics | Admin request, sync failure event | No (admin-facing, advisory) |
| 2 | Data Quality Agent | Operations | Data quality findings report | Schedule, post-sync | No (admin-facing) |
| 3 | Engineering Metrics Agent | Analytics narrative | Metric interpretation narrative | Dashboard "explain", report section | Yes |
| 4 | Delivery Risk Agent | Analytics narrative | Risk assessment per epic/project/release | Schedule, risk threshold event | Yes |
| 5 | Sprint Review Agent | Reporting | Sprint review report | Sprint close, schedule | Yes |
| 6 | Release Notes Agent | Reporting | Release notes document | Release event, on demand | Yes |
| 7 | Documentation Agent | Reporting | Technical/process documentation drafts | On demand | Yes |
| 8 | Use Case Diagram Agent | Diagramming | Mermaid/PlantUML use case diagrams | On demand, report section | Yes |
| 9 | Architecture Diagram Agent | Diagramming | Mermaid/PlantUML architecture diagrams | On demand, report section | Yes |
| 10 | Executive Summary Agent | Reporting | Executive-level narrative summary | Schedule, report section | Yes |
| 11 | Incident Analysis Agent | Analytics narrative | Incident/postmortem analysis | Incident closed, on demand | Yes |
| 12 | Code Quality Agent | Analytics narrative | Code quality narrative and hotspot analysis | Schedule, quality gate event | Yes |
| 13 | Team Health Agent | Analytics narrative | Team-level health narrative (anti-ranking guardrails) | Schedule | Yes |
| 14 | RAG Retrieval Agent | Infrastructure | Ranked, cited context bundles | Invoked by other agents / MCP server | No (feeds other agents) |
| 15 | Report Composition Agent | Orchestration | Composed multi-section reports | Report schedule, on demand | Yes (composes validated sections; final pass) |
| 16 | Validation Agent | Quality | Validation verdict + issue list | Mandatory post-step of user-facing runs | No (it *is* the validator) |
| 17 | Security Review Agent | Analytics narrative | Security posture narrative (finding aging, gate status) | Schedule, security finding events | Yes |
| 18 | Configuration Assistant Agent | Operations | Guided platform configuration answers/suggestions | Admin interactive session | No (admin-facing, advisory, never applies changes itself) |

### 5.1 Data Ingestion Agent

- **Purpose:** Assist admins in configuring connectors and diagnosing sync problems: propose field mappings from source payload samples to the canonical model, explain checkpoint/rate-limit failures, suggest webhook vs. polling setups.
- **Inputs:** Connector type and config draft, `raw_*` staging samples, sync error logs, checkpoint state.
- **Tools:** Domain queries (connector registry, checkpoint tables), raw-sample reader (size-capped, secret-masked), RAG retrieval (connector docs).
- **Output contract:** JSON — `suggestions[] {area, currentValue, proposedValue, rationale, confidence}`, plus a markdown diagnostic summary. Never applies configuration; admin confirms in UI.
- **Guardrails:** Secrets are masked before any payload reaches the model; suggestions are advisory; raw samples truncated and PII-screened.
- **Prompt skeleton:** system role (connector expert, advisory-only) → canonical-model field reference → connector config draft → error/sample evidence blocks → task instruction → output JSON Schema.

### 5.2 Data Quality Agent

- **Purpose:** Detect and narrate data quality issues in ingested data: orphaned `ExternalRef`s, stale syncs, duplicate WorkItems, gap detection in time series, suspicious cardinality shifts.
- **Inputs:** Data quality rule results (computed deterministically in `eip-ingestion`/`eip-analytics`), sync statistics, checkpoint lag.
- **Tools:** Metric queries (freshness/completeness metrics), domain queries, artifact writer.
- **Output contract:** JSON findings list `{severity, entityType, description, evidenceRefs[], remediation}` + markdown report artifact.
- **Guardrails:** Findings must reference rule IDs and entity IDs returned by tools; the model interprets and prioritizes, it does not invent findings.
- **Prompt skeleton:** system role (data steward) → rule-result table → sync stats → prioritization instruction → schema.

### 5.3 Engineering Metrics Agent

- **Purpose:** Explain metric values, trends, and anomalies from the canonical metric set (flow, DORA, quality, delivery risk, ops, team health) in plain language with caveats.
- **Inputs:** Metric selection, time range, grain (team/project/sprint), audience level.
- **Tools:** Metric queries (`getMetricSeries`, `getDoraMetrics`, `getFlowMetrics`), domain queries for context (sprint boundaries, releases), RAG retrieval (metric definitions).
- **Output contract:** Markdown narrative with an embedded `dataPoints[]` appendix; every number must match a tool result; caveats/limitations section is mandatory (metric definitions ship caveats and gaming risks — the agent must include them).
- **Guardrails:** No individual-level attribution; anomaly language must state uncertainty; numbers cross-checked by the Validation Agent against the same metric queries.
- **Prompt skeleton:** system role (metrics analyst, anti-ranking policy) → metric definitions with formulas and caveats → data tables → audience instruction → structure template (Summary / Trends / Anomalies / Caveats).

### 5.4 Delivery Risk Agent

- **Purpose:** Assess delivery risk for Epics, Projects, and Releases: delay prediction inputs, dependency risk, scope churn, blocked time, release readiness score interpretation.
- **Inputs:** Target entity (Epic/Project/Release), horizon.
- **Tools:** Metric queries (epic delivery risk, project delay prediction, dependency risk, release readiness score), domain queries (Dependencies, Risks, WorkItems, Milestones), RAG retrieval (DecisionRecords, planning docs).
- **Output contract:** JSON `{riskLevel, drivers[] {factor, evidence, weight}, recommendations[], confidence, limitations}` + markdown narrative.
- **Guardrails:** Risk scores come from `eip-analytics` risk scoring, not the model; the model explains drivers and recommends mitigations. Confidence and limitations fields are required.
- **Prompt skeleton:** system role (delivery risk analyst) → risk-score breakdown from analytics → dependency graph excerpt → historical analogs → explanation + recommendation instruction → schema.

### 5.5 Sprint Review Agent

- **Purpose:** Generate sprint review reports: commitment vs. done, scope churn, velocity context, notable WorkItems, blockers, carry-over, next-sprint risks.
- **Inputs:** Sprint ID (or Board + date range for kanban cadence reviews).
- **Tools:** Domain queries (Sprint, WorkItems, WorkflowState history), metric queries (velocity, sprint predictability, scope churn, blocked time, WIP), RAG retrieval (sprint goal docs), artifact writer.
- **Output contract:** Structured markdown per the sprint-review template (versioned, tenant-overridable) with a machine-readable summary block; registered as a `GeneratedReport`.
- **Guardrails:** Team-level framing only; carried-over items listed factually without individual blame language (enforced by guardrail lexicon + Validation Agent).
- **Prompt skeleton:** system role (facilitator, neutral tone) → sprint data tables → metric snapshots with caveats → template section outline → per-section length limits.

### 5.6 Release Notes Agent

- **Purpose:** Produce release notes from Release contents: merged PullRequests, resolved WorkItems, deployment info, breaking-change candidates, known issues.
- **Inputs:** Release ID, audience (internal/customer), format profile.
- **Tools:** Domain queries (Release, Deployments, PullRequests, WorkItems, Commits), RAG retrieval (linked docs), artifact writer.
- **Output contract:** Markdown/HTML release notes grouped by change type; every entry cites its source WorkItem/PullRequest via `ExternalRef` URLs.
- **Guardrails:** Entries without a resolvable source reference are dropped, not paraphrased into existence; customer-audience profile strips internal identifiers per redaction policy.
- **Prompt skeleton:** system role (release manager) → change inventory table → audience/format profile → grouping rules → entry format spec with citation requirement.

### 5.7 Documentation Agent

- **Purpose:** Draft and update technical/process documentation: runbooks, onboarding guides, connector setup docs, metric explainers — grounded in platform data and RAG sources.
- **Inputs:** Doc request (topic, audience, outline or existing doc to revise).
- **Tools:** RAG retrieval (heavy user), domain queries, artifact writer.
- **Output contract:** Markdown document with per-section citations; revision mode returns a change summary plus the new draft.
- **Guardrails:** Claims about the environment must carry citations; uncited sections are flagged `[needs-source]` rather than asserted; drafts are never auto-published.
- **Prompt skeleton:** system role (technical writer, US spelling) → outline or existing doc → retrieved context bundles with chunk IDs → citation rules → style guide excerpt.

### 5.8 Use Case Diagram Agent

- **Purpose:** Generate use case diagrams (Mermaid, optionally PlantUML) from Features/Stories, roles, and documented flows.
- **Inputs:** Scope (Product/Project/Epic), actor hints, notation choice.
- **Tools:** Domain queries (Features, Stories, Roles), RAG retrieval (requirement docs), artifact writer.
- **Output contract:** Diagram source block + legend + assumption list; diagram source must parse (syntax-checked deterministically before validation).
- **Guardrails:** Parse-check is a hard gate with bounded auto-repair retries; assumptions about actors not present in data are listed explicitly.
- **Prompt skeleton:** system role (analyst) → entity inventory → notation grammar reminder → diagram size limits → output fencing rules.

### 5.9 Architecture Diagram Agent

- **Purpose:** Generate architecture/deployment/data-flow diagrams from Services, ApiEndpoints, Repositories, Deployments, Environments, and documented architecture decisions.
- **Inputs:** Scope (Product/Service set/Environment), view type (context, container, deployment, data flow), notation.
- **Tools:** Domain queries (Services, ApiEndpoints, Deployments, Environments, Dependencies), RAG retrieval (DecisionRecords, architecture docs), artifact writer.
- **Output contract:** Diagram source + component-to-source mapping table + confidence notes for inferred edges.
- **Guardrails:** Edges not backed by domain data or cited documents are rendered dashed and labeled "inferred"; parse-check hard gate as in 5.8.
- **Prompt skeleton:** system role (architect) → component inventory with relationships → view-type conventions → inference labeling rules → notation grammar.

### 5.10 Executive Summary Agent

- **Purpose:** Produce executive-level narrative summaries across portfolios: delivery health, risk posture, quality trend, incident posture — concise, caveated, decision-oriented.
- **Inputs:** Scope (Organization/BusinessUnit/Product portfolio), period, prior summary (for continuity).
- **Tools:** Metric queries (aggregated grains only), Delivery Risk Agent outputs (via composition), RAG retrieval (prior GeneratedReports), artifact writer.
- **Output contract:** Fixed-structure markdown (Headline / Highlights / Risks / Asks / Data notes) with strict length budget; machine-readable KPI block.
- **Guardrails:** Aggregate grains only — no team-member data; every KPI traces to a metric query; trend claims require at least two periods of data or are marked insufficient-data.
- **Prompt skeleton:** system role (chief-of-staff voice) → KPI table with period deltas → risk register excerpt → prior summary → length and structure constraints.

### 5.11 Incident Analysis Agent

- **Purpose:** Analyze Incidents: timeline reconstruction, contributing factors, MTTR context, related Alerts/Deployments/Changes, draft postmortem sections.
- **Inputs:** Incident ID (or incident set for trend analysis).
- **Tools:** Domain queries (Incident, Alerts, Deployments, LogReference/TraceReference metadata), metric queries (incident frequency/impact, MTTR, SLO health), RAG retrieval (runbooks, prior postmortems), artifact writer.
- **Output contract:** Postmortem draft per template: Timeline (tool-sourced timestamps only) / Impact / Contributing factors (each cited) / Follow-ups; blameless-language enforced.
- **Guardrails:** Blameless policy lexicon; timeline entries must carry source event IDs; causal language limited to "contributing factor" phrasing unless a documented root cause exists.
- **Prompt skeleton:** system role (blameless postmortem facilitator) → event timeline table → related-change list → template sections → language policy.

### 5.12 Code Quality Agent

- **Purpose:** Narrate code quality state and trends: coverage, code smells, duplication, quality gate status, escaped defects, bug aging, technical debt ratio; identify hotspots and debt-paydown candidates.
- **Inputs:** Scope (Repository/Project/Product), period.
- **Tools:** Metric queries (quality metric family), domain queries (Repositories, QualityGates, TechnicalDebtItems, Bugs), RAG retrieval (coding standards docs), artifact writer.
- **Output contract:** Markdown narrative + hotspot table `{repo, path/module, signal, trend, suggestedAction}`; all signals tool-sourced.
- **Guardrails:** Repository/module granularity — never author granularity; suggested actions framed as options with effort/impact caveats.
- **Prompt skeleton:** system role (quality coach) → metric snapshot/trend tables → gate status list → hotspot detection instruction → anti-attribution rule.

### 5.13 Team Health Agent

- **Purpose:** Team-level health narrative: load balance, review bottlenecks, knowledge concentration (bus factor), WIP pressure — explicitly anti-toxic-ranking, framed as systemic signals with limitations.
- **Inputs:** Team ID(s), period.
- **Tools:** Metric queries (team health family — team-level aggregates only), domain queries (Team, Board, WorkflowState distribution), artifact writer.
- **Output contract:** Markdown with mandatory sections: Signals / What this does and does not mean / Limitations / Suggested conversations. No individual names in analytical statements.
- **Guardrails:** Strictest guardrail profile: individual-identifier suppression in outputs, ranking-language lexicon block, mandatory limitations section; Validation Agent runs with the anti-ranking checklist enabled.
- **Prompt skeleton:** system role (org-health facilitator, explicit anti-surveillance charter) → team-level aggregates → interpretation guardrail text → required section structure.

### 5.14 RAG Retrieval Agent

- **Purpose:** Infrastructure agent providing high-quality, permission-aware, cited context bundles to other agents and to the MCP server's retrieval capability. Performs query decomposition, hybrid search invocation, re-rank, deduplication, and citation assembly.
- **Inputs:** Retrieval request `{query, scopeFilters, k, diversity, callerPrincipal}` from a calling agent run.
- **Tools:** RAG retrieval primitives (`ragSearch`, `ragFetchChunk`) — no domain mutation, no artifact writer.
- **Output contract:** `contextBundle {chunks[] {chunkId, text, source, url, score}, coverageNotes, unresolvedAspects[]}` — exactly the citation contract in `../ai/RAGArchitecture.md`.
- **Guardrails:** Inherits the calling run's principal — cannot widen scope; returns empty-with-explanation rather than lowering the relevance floor.
- **Prompt skeleton:** system role (retrieval planner) → query decomposition instruction → filter vocabulary → bundle assembly rules. (Query decomposition is the only LLM step; search itself is deterministic.)

### 5.15 Report Composition Agent

- **Purpose:** Orchestrate multi-section reports by delegating to section agents (Sprint Review, Executive Summary, Delivery Risk, Code Quality, etc.), then unifying tone, deduplicating content, and assembling final artifacts. Detailed in Section 6.
- **Inputs:** Report template ID, scope parameters, output formats.
- **Tools:** Agent-invocation tool (spawn child runs — the only agent with this tool), artifact writer, RAG retrieval (prior reports for continuity).
- **Output contract:** Composed `GeneratedReport` (markdown + optional HTML/PDF/PPTX via `eip-reports`) with per-section provenance (child runId, agent, version).
- **Guardrails:** Child runs inherit principal, tenant, and a partitioned share of the parent budget; composition may reorder/trim but not alter section facts; final Validation Agent pass over the composed document.
- **Prompt skeleton:** system role (editor-in-chief) → template outline with section contracts → child section outputs → unification instruction (tone, dedup, cross-references) → no-new-facts rule.

### 5.16 Validation Agent

- **Purpose:** Mandatory post-step for all user-facing outputs. Performs three checks: **fact-check** (numbers/claims re-verified against source data via the same tools), **citation check** (every citation resolves; uncited factual claims flagged), **schema check** (output conforms to the agent's output contract — deterministic validation, LLM used for claim extraction only).
- **Inputs:** Candidate output, producing agent's output contract, list of tool calls/results from the producing run.
- **Tools:** Same read tools as the producing run (same principal — validation must not see more than the producer), citation resolver.
- **Output contract:** `verdict {pass|fail}, issues[] {type: FACT|CITATION|SCHEMA|POLICY, location, detail, severity}`; on fail, issues feed the producing run's revision loop (bounded by `maxValidationRetries`).
- **Guardrails:** Runs with a different model than the producer where the routing table allows (cross-model checking); its own budget is charged to the parent run.
- **Prompt skeleton:** system role (adversarial fact-checker) → claim-extraction instruction → per-claim verification protocol (query tool, compare, verdict) → issue schema.

### 5.17 Security Review Agent

- **Purpose:** Narrate security posture from ingested signals: SecurityFinding aging, severity distribution, quality gate security conditions, dependency risk signals, incident overlap; draft security review summaries for release readiness.
- **Inputs:** Scope (Project/Release/Organization), period.
- **Tools:** Domain queries (SecurityFindings, QualityGates, Releases), metric queries (security finding aging, release readiness score), RAG retrieval (security policies), artifact writer.
- **Output contract:** Markdown security review with severity-ordered findings table and remediation-status summary; all findings tool-sourced with IDs.
- **Guardrails:** Never generates new vulnerability claims — it reports and contextualizes ingested findings only; exploit details are excluded from outputs by policy; report access restricted by dedicated RBAC permission.
- **Prompt skeleton:** system role (security analyst, report-not-scan charter) → finding inventory → gate/policy context → summary structure → exclusion rules.

### 5.18 Configuration Assistant Agent

- **Purpose:** Interactive admin assistant for platform configuration: explain settings, propose connector/metric/RBAC/report configurations from natural-language intent, generate JSON config drafts against the relevant JSON Schemas.
- **Inputs:** Admin chat turns, current configuration context (secret-masked).
- **Tools:** Domain queries (configuration registries, JSON Schemas), RAG retrieval (this /docs tree, indexed), MCP tools where allow-listed for admin context.
- **Output contract:** Chat responses plus optional `configDraft {schemaRef, json, diff}` blocks that the admin explicitly applies through the normal validated API — the agent has no write access to configuration.
- **Guardrails:** Strictly advisory (no mutation tool); secrets never enter context; drafts are schema-validated before display; admin RBAC required to start a session.
- **Prompt skeleton:** system role (platform SME, advisory-only) → relevant JSON Schemas → current masked config → conversation history → draft-formatting rules.

## 6. Multi-Agent Composition

### 6.1 Report Composition Agent as orchestrator

The Report Composition Agent is the only agent permitted to spawn child runs. Composition flow:

1. Resolve the report template (versioned; per-tenant overrides) into an ordered section list, each bound to a section agent and parameter mapping.
2. Spawn child runs on `eip.ai.jobs` (fan-out; independent sections run in parallel across workers). Each child inherits `tenantId`, initiating principal, `traceparent`, and a budget partition.
3. Await child results on `eip.ai.results` (correlation by parent runId); a failed non-critical section degrades to an "unavailable" placeholder with the failure reason, a failed critical section fails the composition.
4. Compose: unify tone, dedupe overlapping facts, build cross-references and table of contents. Composition may not introduce facts absent from section outputs.
5. Final Validation Agent pass over the composed document (in addition to per-section validation).
6. Render output formats via `eip-reports` and register the `GeneratedReport`.

### 6.2 Validation Agent as mandatory post-step

Every agent marked user-facing in the catalog table has `validating` wired to a Validation Agent pass. The three checks (fact, citation, schema) are non-negotiable platform policy — tenants may add checks (e.g., custom lexicons) but cannot disable the baseline. Validation failures trigger a revision loop: the producing agent receives the issue list and re-executes its final drafting step, up to `maxValidationRetries`; exhaustion fails the run rather than shipping unvalidated output.

## 7. LLM Provider SPI

### 7.1 Interface sketch

```java
public interface LlmProvider {
    String id();                          // e.g. "ollama-local", "vllm-cluster-a"
    Set<LlmCapability> capabilities();    // CHAT, TOOL_CALLING, STRUCTURED_OUTPUT, STREAMING, EMBEDDINGS
    List<ModelDescriptor> models();       // id, contextWindow, pricePer1kTokens (0 for local), maxOutputTokens
    ChatResponse chat(ChatRequest req);   // messages, tools, responseSchema?, budgetGuard
    Flux<ChatChunk> chatStream(ChatRequest req);
    HealthStatus health();                // used by routing/fallback and admin UI
}
```

Implementations (all configurable per tenant, secrets via the platform secret vault):

| Provider | Transport | Notes |
|---|---|---|
| Ollama | HTTP (local) | Air-gapped default for chat + embeddings. |
| vLLM (OpenAI-compatible) | HTTP | On-prem GPU serving; OpenAI wire format. |
| OpenAI-compatible generic | HTTP | Any endpoint speaking the OpenAI API (LiteLLM, gateways). |
| Anthropic-compatible | HTTP | Anthropic Messages API wire format. |
| Custom enterprise endpoint | HTTP (adapter) | Thin adapter SPI for bespoke internal gateways. |

### 7.2 Routing, fallbacks, streaming, structured output

- **Routing table** (`llm_route`, DB-managed, admin UI): key = `(tenantId, agentName, purpose)` where purpose ∈ {PLANNING, DRAFTING, VALIDATION, EMBEDDING}; value = ordered provider+model list. Resolution: exact match → tenant default → platform default.
- **Fallback chains:** on provider error/timeout/health failure, the orchestrator advances down the route list; each hop is audited; capability mismatches (e.g., no tool calling) are excluded at resolution time, not discovered at call time.
- **Streaming:** interactive surfaces (Configuration Assistant, dashboard "explain") stream via SSE from `eip-app`; batch runs consume non-streaming. Streamed responses are buffered server-side so audit records and guardrail post-checks always see the complete output.
- **Structured output enforcement:** where the provider supports native JSON Schema output, it is used; otherwise the orchestrator applies constrained-prompt + parse-and-repair (bounded retries) and the schema check in `validating` remains the final gate.

## 8. Prompt & Template Management

- Prompt templates are **versioned rows in PostgreSQL** (`prompt_template`: templateKey, version, agentName, purpose, locale, body, variablesSchema, createdBy, createdAt, status ∈ DRAFT|ACTIVE|RETIRED). Rendering uses a strict variable whitelist; unknown variables fail the render.
- **Per-tenant overrides** shadow platform templates by templateKey; override diffs are visible in the admin UI and audited. Platform upgrades never silently replace an overridden template — admins see an upgrade-available flag.
- Every run records the exact `(templateKey, version)` pairs used, so any historical output can be reproduced and template regressions can be bisected.
- Template changes route through the evaluation harness (Section 9) before promotion to ACTIVE.

## 9. Evaluation & Quality

- **Golden datasets from simulation mode.** The `/simulation` data packs provide deterministic tenants with known ground truth (planted risks, known sprint outcomes, seeded incidents). Golden cases pair inputs with expected structured outputs/claims.
- **Regression evals per agent:** run in CI against a pinned local model and on demand against tenant-routed models; scored on schema validity, fact accuracy vs. ground truth, citation resolution rate, guardrail compliance (e.g., anti-ranking lexicon for Team Health), and budget adherence. Promotion of a prompt template or agent version requires non-regression on its eval suite.
- **Human feedback loop:** every user-facing output carries accept/edit/reject feedback affordances; feedback rows link runId + templateKey/version + verdict + optional comment, feeding eval-case candidates and template tuning. Feedback is about outputs, never about people, consistent with platform anti-goals.

## 10. Cost & Quota Management

- Provider price tables (per model, per 1k prompt/completion tokens; zero for local) are admin-maintained; every `llm_call` row stores computed cost.
- Quota ledger per tenant: daily/monthly token and cost ceilings, per-agent sub-quotas optional. Enforcement at enqueue (reject with RFC 7807 `quota-exceeded`) and pre-call (fail run with `BUDGET_EXCEEDED`).
- Dashboards (Grafana + in-product admin) show spend by tenant/agent/model, cache-hit and fallback rates, and eval scores over time via Micrometer/OTel metrics.

## 11. Auditability

Every LLM call is audited without exception: `llm_call (id, runId, stepIndex, tenantId, principal, provider, model, templateKey, templateVersion, promptRedacted, completionRedacted, promptTokens, completionTokens, cost, latencyMs, finishReason, error?)`. Redaction policy (per tenant) controls whether full prompts, hashed prompts, or metadata-only are retained. Tool calls, MCP calls, and RAG retrievals are audited in their own trails (see sibling docs). All audit rows are tenant-scoped (RLS), immutable, and exportable; `traceparent` links audit rows to OTel traces.

## 12. Acceptance Criteria

- [ ] Given a run request from a principal lacking permission to an entity, when any tool call touches that entity, then the tool returns an authorization error and no data reaches the model.
- [ ] Given a worker crash mid-run, when another worker claims the run, then it resumes from the last persisted step with no duplicated `writeArtifact` effects.
- [ ] Given a user-facing agent output, when validation runs, then fact, citation, and schema checks all execute and a failure with exhausted retries yields `failed`, never an unvalidated `completed`.
- [ ] Given any completed or failed run, when an auditor queries it, then every LLM call, tool call, template version, token count, and cost is retrievable.
- [ ] Given a provider outage, when a routed call fails, then the fallback chain is attempted in order and each hop is audited.

## 13. Degradation Behavior Without an LLM

When no LLM provider is configured (or all are unhealthy) the platform remains **fully functional minus generative features**:

| Capability | Without LLM |
|---|---|
| Ingestion, normalization, connectors | Unaffected. |
| Metrics, dashboards, alerting | Unaffected (metric engines are deterministic, in `eip-analytics`). |
| Deterministic report exports (tables, charts) | Unaffected via `eip-reports`. |
| Agent runs | Enqueue rejected with RFC 7807 `llm-unavailable`; scheduled generative reports skipped with an audited notice. |
| RAG indexing | Continues if the embedding route uses a local model; otherwise paused with connector-style health status. |
| MCP server capabilities | Non-generative capabilities (query metrics, query work items) unaffected; generative ones report unavailable. |
| UI | Generative panels render an explicit "AI not configured" state with admin guidance — never blank or broken. |
