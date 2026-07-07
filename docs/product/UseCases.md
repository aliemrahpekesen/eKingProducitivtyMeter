# Use Cases

Formal use cases for the Engineering Intelligence Platform (EIP). Actors are the personas defined in `./Personas.md` (with their RBAC roles); narrative context is in `./UserJourneys.md`. Component names, connectors, agents, Kafka topics, and vocabulary follow the shared design brief and `../architecture/DomainModel.md`.

**Conventions.** Use case IDs `UC-NNN` are stable and never reused. Requirement references `FR-NNN` / `NFR-NNN` cite `./PRD.md` section 5/6, which organizes functional requirements by capability area: connectors FR-001–FR-019, ingestion FR-030–FR-041, analytics FR-050–FR-062, dashboards FR-065–FR-074, AI agents FR-080–FR-092, RAG FR-095–FR-101, MCP FR-104–FR-108, reports FR-110–FR-118, admin/security/tenancy FR-113 and FR-120–FR-141. All flows are tenant-scoped; Postgres RLS enforcement is an implicit precondition everywhere and is not repeated per use case. All mutating batch endpoints accept idempotency keys; all errors are RFC 7807 problem+json.

## 1. Tenancy, security, and administration

### UC-001 — Onboard a new tenant

- **Actor:** Platform Administrator (`PLATFORM_ADMIN`).
- **Goal:** Create an isolated tenant with a delegated administrator, ready for connector configuration.
- **Preconditions:** EIP installed; actor authenticated via OIDC (Keycloak or configured enterprise IdP); actor holds `tenant:manage`.
- **Main flow:**
  1. Actor opens Admin Console → Tenants → New tenant.
  2. Actor enters tenant name, Organization mapping, IdP realm/group mapping, and default locale.
  3. System creates the tenant: tenant_id issued, RLS-scoped rows initialized, built-in base roles available (`TENANT_ADMIN`, `ANALYST`, `VIEWER`; `PLATFORM_ADMIN` is installation-scoped), and the six seeded persona role templates provisioned by default on the `ANALYST`/`VIEWER` bases (`ENGINEERING_MANAGER`, `TEAM_LEAD`, `MEMBER`, `RELEASE_MANAGER`, `EXECUTIVE_VIEWER`, `SECURITY_AUDITOR`), editable per tenant.
  4. Actor assigns at least one Member as `TENANT_ADMIN`.
  5. System writes audit entries for tenant creation and role assignment and emits a notification to the new admin.
- **Alternate/exception flows:**
  - 3a. Tenant name collides → validation error; no partial creation.
  - 4a. IdP group mapping unresolvable → tenant created in `PENDING_ACCESS` state; local-account fallback offered.
- **Postconditions:** Tenant active (or pending access); zero data visible to other tenants; audit trail present.
- **Related requirements:** FR-120, FR-121, FR-122, FR-123, FR-128, FR-129.
- **Related agents/connectors:** none (platform core).

### UC-002 — Manage roles and permissions

- **Actor:** Tenant Administrator (`TENANT_ADMIN`); Platform Administrator for cross-tenant roles.
- **Goal:** Grant, modify, or revoke tenant-scoped roles and fine-grained permissions for a Member.
- **Preconditions:** Tenant active; actor holds `rbac:manage` for the tenant.
- **Main flow:**
  1. Actor opens Admin Console → Access → selects a Member (synced from IdP or local account).
  2. Actor assigns roles (additive) and, where needed, fine-grained overrides (e.g., grant `agent:invoke` for Incident Analysis to an ops team member; restrict `dashboard:configure`).
  3. System validates separation-of-duties constraints (e.g., `SECURITY_AUDITOR` cannot also hold `secret:rotate`).
  4. System applies changes effective immediately (authorization cache in Redis invalidated) and audits the change with before/after state.
- **Alternate/exception flows:**
  - 3a. SoD violation → change rejected with the violated constraint named.
  - 4a. Actor attempts to remove the tenant's last `TENANT_ADMIN` → rejected.
- **Postconditions:** Member's effective permissions updated everywhere (API and UI) within one request cycle.
- **Related requirements:** FR-120, FR-122, FR-123 (see also FEAT-004/FEAT-023 in `./FeatureCatalog.md` for custom-role composition and permission preview).
- **Related agents/connectors:** none.

### UC-003 — Rotate a connector secret

- **Actor:** Platform Administrator (`PLATFORM_ADMIN`, `secret:rotate`).
- **Goal:** Replace a stored credential (e.g., GitHub PAT) without sync interruption or plaintext exposure.
- **Preconditions:** Connector configured; new credential issued in the source system; KMS SPI master key available (env/file/Vault).
- **Main flow:**
  1. Actor opens Connector → Credentials → Rotate.
  2. Actor submits the new secret; system envelope-encrypts it (AES-256-GCM) as a new secret version.
  3. System runs `testConnection()` using the new version.
  4. On success, system atomically promotes the new version; in-flight sync jobs finish on the old version, new jobs use the new one.
  5. System schedules the old version for destruction after a configurable grace period and audits rotation (actor, connector, versions — never values).
- **Alternate/exception flows:**
  - 3a. Test fails → rotation aborted; old version stays active; failure audited.
  - 5a. Emergency ("compromised") rotation → grace period skipped; running jobs using the old version are cancelled and rescheduled.
- **Postconditions:** New secret active; old secret destroyed or pending destruction; UI shows only masked values throughout; rotation visible to `SECURITY_AUDITOR`.
- **Related requirements:** FR-113, FR-009, FR-123, NFR-040, NFR-042.
- **Related agents/connectors:** any connector; no agents.

### UC-004 — Review the audit log

- **Actor:** CISO / Security Officer (`SECURITY_AUDITOR`); Tenant Administrator for tenant-scoped review.
- **Goal:** Inspect and export audit evidence for a time window: authentication, RBAC changes, connector/secret operations, LLM calls, RAG retrievals, MCP invocations, report generations.
- **Preconditions:** Actor holds `audit:read` (plus `llm:audit`, `secret:audit`, `mcp:audit` for those categories).
- **Main flow:**
  1. Actor opens Audit and filters by category, actor, entity, tenant, and time range (cursor-paginated).
  2. System returns matching entries; sensitive payloads (prompts, secret values) rendered per redaction policy — secret values never stored or shown.
  3. Actor drills into an entry to see full context (e.g., an LLM call's agent, model, tokens, cost, latency, traceparent link to Tempo).
  4. Actor requests an export; system generates an evidence pack asynchronously (`eip.reports.jobs`) into the artifact library.
- **Alternate/exception flows:**
  - 2a. Redacted prompt needed for an investigation → break-glass reveal requires a second approver; the reveal is itself audited.
  - 4a. Very large window → export chunked; UI reports progress.
- **Postconditions:** No state changed except the audited fact of the review/export itself.
- **Related requirements:** FR-123, FR-081, FR-099, FR-107, NFR-042.
- **Related agents/connectors:** Security Review agent (optional evidence-pack compilation).

## 2. Connectors and ingestion

### UC-005 — Onboard a connector

- **Actor:** Platform Administrator or Tenant Administrator (`connector:manage`).
- **Goal:** Configure, validate, and activate a connector (any of: Jira, Confluence, GitHub, GitLab, Bitbucket, SonarQube, Artifactory, Kubernetes, OpenShift, Docker Registry, Prometheus, Grafana, OpenTelemetry OTLP intake, Generic CI/CD, Generic REST, Generic SQL, Generic File/Document, Custom internal demand/project tool).
- **Preconditions:** Network route to the source system; credentials available; tenant active.
- **Main flow:**
  1. Actor selects the connector type; UI renders its JSON Schema configuration form.
  2. Actor enters endpoint, scope filters (projects/repos/spaces/namespaces), and credentials; secrets are envelope-encrypted on submit.
  3. System runs `validate()` (schema + semantic checks), then `testConnection()`; results shown with discovered scope (e.g., reachable projects, rate limits).
  4. Actor optionally registers webhook intake where the source supports it; system issues the webhook URL and secret.
  5. Actor activates the connector; system schedules `fullSync()` (immediate or windowed) and registers `healthCheck()` on the standard cadence.
  6. System audits creation; Connector Health dashboard begins tracking the instance.
- **Alternate/exception flows:**
  - 3a. Validation/test failure → precise problem+json cause (auth, TLS, permission, unreachable); connector stays inactive.
  - 5a. Actor defers full sync → connector active in webhook-only mode with a visible "no backfill" warning.
- **Postconditions:** Connector active with health status; secrets stored encrypted; sync scheduled.
- **Related requirements:** FR-001, FR-008, FR-009, FR-011, FR-012, FR-017.
- **Related agents/connectors:** all connectors; Configuration Assistant agent may guide form completion.

### UC-006 — Run initial full sync

- **Actor:** Platform Administrator (initiates); system (`eip-workers`) executes.
- **Goal:** Backfill the canonical model from a newly activated connector.
- **Preconditions:** UC-005 completed; Kafka, PostgreSQL, MinIO healthy.
- **Main flow:**
  1. Sync engine (`eip-ingestion`) leases the job (Redisson distributed lock — one runner per connector+stream).
  2. Collector pages through the source API under rate limiting; raw payloads land in `raw_*` JSONB staging, large blobs in MinIO; raw events publish to `eip.raw.<connector>`.
  3. Normalizers consume, map to canonical entities (WorkItem with `ExternalRef`, Sprint, Board, Repository, Commit, PullRequest, Build, Deployment, …), and upsert idempotently (dedup on `eventId`, ordered per tenantId+entityId).
  4. Domain events publish to the matching `eip.domain.*` topic; checkpoints advance per connector+stream.
  5. On completion, connector status becomes `SYNCED`; analytics consumers materialize first metrics to `eip.analytics.metrics`.
- **Alternate/exception flows:**
  - 2a. Source rate limit hit → exponential backoff + jitter; checkpoint preserved.
  - 3a. Unmappable record → parked in staging with a data-quality flag; Data Quality agent aggregates such records for review; sync continues.
  - Any-step worker crash → job re-leased; at-least-once redelivery is safe due to idempotent upserts.
- **Postconditions:** Canonical model backfilled; checkpoints established for incremental sync; dashboards populated.
- **Related requirements:** FR-010, FR-016, FR-030, FR-031, FR-032, FR-033, FR-034, FR-035, FR-037.
- **Related agents/connectors:** Data Ingestion agent (orchestration assistance), Data Quality agent.

### UC-007 — Incremental sync

- **Actor:** System (scheduler / webhook intake); Administrators observe.
- **Goal:** Keep the canonical model current with low latency and bounded source load.
- **Preconditions:** Checkpoint exists (UC-006); connector active.
- **Main flow:**
  1. Scheduler triggers `incrementalSync(checkpoint)` per connector cadence, or a webhook event arrives at the intake endpoint (signature-verified).
  2. Collector fetches only changes since the checkpoint; dedup discards already-seen events.
  3. Normalizer upserts changed entities and emits `eip.domain.*` events with `schemaVersion` and `traceparent` for end-to-end tracing.
  4. Checkpoint advances only after successful publish (at-least-once).
  5. Downstream consumers (analytics, RAG incremental re-index, report freshness) react to the domain events.
- **Alternate/exception flows:**
  - 1a. Webhook missed (source outage) → next scheduled incremental sync heals the gap from the checkpoint; webhooks are an optimization, never the source of truth.
  - 2a. Checkpoint invalidated by the source (e.g., project migration) → connector enters `RESYNC_REQUIRED`; admin approves a scoped re-backfill.
- **Postconditions:** Freshness watermark per stream updated; dashboards reflect changes within the cadence SLO.
- **Related requirements:** FR-010, FR-012, FR-014, FR-015, FR-040, NFR-012.
- **Related agents/connectors:** all connectors; Data Ingestion agent.

### UC-008 — Recover from sync failure

- **Actor:** Platform Administrator; system performs automatic recovery first.
- **Goal:** Restore a failing sync stream to health without data loss or duplication.
- **Preconditions:** Connector in `DEGRADED` or `FAILED` state, or messages accumulating in a consumer group DLQ (`<group>.dlq`).
- **Main flow:**
  1. `healthCheck()` or consumer lag alerting (Micrometer → Prometheus) flags the stream; Connector Health dashboard shows state, last error (problem+json), retry history, and checkpoint position.
  2. System has already retried with exponential backoff + jitter; actor inspects the terminal cause.
  3. For credential causes → actor performs UC-003 (rotation) and re-runs `testConnection()`.
  4. For poison messages → actor opens the DLQ inspector, views the raw payload from staging, and chooses per message: redrive (after a fix/patch), park, or discard-with-reason.
  5. Actor resumes the stream; sync continues from the checkpoint; idempotent upserts make redriven messages safe.
  6. System audits every manual intervention (redrive/discard decisions with reasons).
- **Alternate/exception flows:**
  - 4a. Systemic normalizer bug → actor pauses the stream; after the fix deploys, bulk redrive from DLQ; no re-fetch from source needed because raw staging retains payloads.
  - 5a. Checkpoint corrupted → admin resets to a prior checkpoint; overlap is absorbed by dedup on `eventId`.
- **Postconditions:** Stream healthy; DLQ drained or consciously parked; no duplicate canonical entities; interventions audited.
- **Related requirements:** FR-011, FR-013, FR-038, FR-039, FR-123.
- **Related agents/connectors:** Data Quality agent (failure pattern summarization).

## 3. Analytics and risk

### UC-009 — Explore metrics on a dashboard

- **Actor:** Engineering Manager, Team Lead, Developer, Product Manager, SRE, Executive, Agile Coach (`metric:read` at their scope).
- **Goal:** View and drill into flow, DORA, quality, delivery-risk, ops, and team-health metrics with full definitional context.
- **Preconditions:** Metrics materialized by `eip-analytics`; actor scoped to at least one Team/BusinessUnit.
- **Main flow:**
  1. Actor opens a dashboard (productivity, sprint, kanban, quality, ops, executive); frontend queries `/api/v1/metrics` with scope, grain, and time-range parameters (cursor pagination).
  2. Charts render (ECharts) with freshness watermarks per underlying stream.
  3. Actor opens any metric's definition drawer: purpose, formula, inputs, grain, caveats/limitations, gaming risks — always available, never hidden.
  4. Actor drills from an aggregate to its evidence set (e.g., blocked time → blocked WorkItems with WorkflowState history and `ExternalRef` links to the source tool).
  5. Actor pins a configured view or exports a snapshot to a report draft.
- **Alternate/exception flows:**
  - 4a. Drill target outside actor's scope → aggregate shown, row-level evidence withheld (RLS + permission check), with an explicit "restricted" marker.
  - 2a. Underlying stream degraded → charts render with staleness banner linking to Connector Health (read-only).
  - Invariant: no view ranks named individuals; team-health metrics (load balance, review bottlenecks, knowledge concentration) render at team grain only.
- **Postconditions:** Read-only; pinned views/snapshots saved to the actor's workspace.
- **Related requirements:** FR-050, FR-051, FR-052, FR-054, FR-055, FR-056, FR-057, FR-060, FR-065, FR-067, FR-068, FR-069, FR-074.
- **Related agents/connectors:** Engineering Metrics agent (ad-hoc metric Q&A), Team Health agent.

### UC-010 — Review detected delivery risks

- **Actor:** Engineering Manager or Product Manager (`risk:read`).
- **Goal:** Triage risks raised by the Delivery Risk agent (epic delivery risk, project delay prediction, dependency risk, release readiness score) and record dispositions.
- **Preconditions:** Delivery Risk agent enabled and scheduled; sufficient historical data for the model's stated confidence floor.
- **Main flow:**
  1. Actor opens the Risk view; risks listed with score, confidence, trend, and a natural-language rationale citing the contributing signals (scope churn, blocked time, dependency chains, historical predictability).
  2. Actor opens a risk; evidence panel links each cited WorkItem, Dependency, and Metric.
  3. Actor records a disposition: accept (with owner + action), mute (with reason + expiry), or mark false-positive.
  4. False-positive dispositions feed the agent's evaluation set; mutes expire and resurface.
  5. Dispositions are audited and appear in downstream reports (e.g., Executive Summary "top risks" respects dispositions).
- **Alternate/exception flows:**
  - 1a. Confidence below floor → risk shown in a separate "low confidence" section, never mixed silently with confident findings.
  - 3a. Risk on an Epic spanning teams outside actor's scope → visible in aggregate; disposition requires an actor scoped to all affected teams or the `TENANT_ADMIN`.
- **Postconditions:** Dispositions persisted, audited, and reflected in reports; agent evaluation data enriched.
- **Related requirements:** FR-053, FR-066, FR-087, FR-123.
- **Related agents/connectors:** Delivery Risk agent, Validation agent.

## 4. AI, RAG, and MCP

### UC-011 — Generate a report on demand

- **Actor:** Any persona with `report:generate` at the relevant scope (e.g., Team Lead → Sprint Review; Release Manager → Release Notes; Executive → Executive Summary).
- **Goal:** Produce a cited GeneratedReport (document, presentation, or diagram) from live platform data.
- **Preconditions:** LLM provider configured and routed for the tenant+agent; agent enabled with a token budget; input scope (Sprint/Release/quarter) exists.
- **Main flow:**
  1. Actor selects scope, template, and output format; frontend calls `POST /api/v1/reports/generate` (idempotency key attached).
  2. `eip-app` enqueues on `eip.ai.jobs`; the `eip-ai` runtime in `eip-workers` leases the job.
  3. The responsible agent (Sprint Review / Release Notes / Executive Summary / Incident Analysis / Documentation / Use Case Diagram / Architecture Diagram) plans and executes tool calls against `eip-analytics`, the canonical model, and the RAG Retrieval agent — all permission-checked as the requesting actor.
  4. Validation agent checks the draft (citation coverage, metric fidelity, simulated-data markers); Report Composition agent renders the final artifact.
  5. Artifact stored in MinIO; GeneratedReport row persisted with provenance (inputs, agent versions, model, citations); result on `eip.ai.results`; actor notified.
  6. Every LLM call in the run is audited with tokens, cost, and latency, counted against the budget.
- **Alternate/exception flows:**
  - 3a. Primary model unavailable → fallback per routing config; if none, job parks `AWAITING_PROVIDER` with a visible status.
  - 4a. Validation failure → one bounded regeneration; persistent failure → artifact delivered as draft with flagged sections.
  - 6a. Budget exhausted mid-run → graceful truncation to a shorter template, flagged in the artifact.
- **Postconditions:** Versioned artifact in the library; provenance and audit complete; duplicate submissions coalesced by idempotency key.
- **Related requirements:** FR-080, FR-081, FR-086, FR-087, FR-088, FR-090, FR-110, FR-114, FR-115.
- **Related agents/connectors:** the named generator agents plus RAG Retrieval, Validation, Report Composition.

### UC-012 — Schedule recurring report generation

- **Actor:** Tenant Administrator or Engineering Manager (`report:schedule`).
- **Goal:** Configure a report to generate on a schedule (e.g., weekly Delivery Health, quarterly Engineering Health) and deliver to notification channels.
- **Preconditions:** UC-011 works on demand for the same template/scope; scheduler enabled.
- **Main flow:**
  1. Actor defines schedule (cron-like), scope resolution rule (e.g., "most recently closed Sprint"), template, recipients/channels, and the run-as authorization scope.
  2. System validates the run-as scope covers the template's data needs and stores the schedule.
  3. At fire time, scheduler enqueues on `eip.reports.jobs`; generation proceeds as UC-011 steps 3–6.
  4. Artifact delivered to channels and the artifact library; each run recorded with status and cost.
- **Alternate/exception flows:**
  - 3a. Run fails → retry with backoff; terminal failure notifies the schedule owner with the problem+json error.
  - 2a. Scope grant later revoked → schedule suspends with a clear reason instead of running with elevated rights.
- **Postconditions:** Recurring artifacts accumulate with version history; costs attributable per schedule.
- **Related requirements:** FR-090, FR-112, FR-115, FR-116, FR-118, FR-122.
- **Related agents/connectors:** Report Composition, Executive Summary, and any scheduled generator agent.

### UC-013 — Ingest documents into the RAG knowledge base

- **Actor:** Platform/Tenant Administrator (`rag:manage`); system executes.
- **Goal:** Make a document corpus (Confluence spaces, Generic File/Document uploads) retrievable by agents with permission-aware, cited retrieval.
- **Preconditions:** Embedding model configured via LLM provider SPI; vector store available (pgvector default, Qdrant via VectorStore SPI); source connector active (for connector-fed corpora).
- **Main flow:**
  1. Actor enables a source as a RAG corpus and sets metadata mapping (space/label → filters) and permission mapping (source ACLs → EIP permissions).
  2. Pipeline runs per Document: fetch (staging/MinIO) → chunking → embedding → vector upsert with tenant_id, permission metadata, and source citation anchors.
  3. Incremental re-indexing binds to the source connector's `incrementalSync(checkpoint)`; actor may also set a scheduled full re-index.
  4. Actor validates via the RAG console: a test query returns chunks with citations; a restricted test identity retrieves nothing from restricted sources.
  5. Indexing runs and retrieval queries are audit-logged.
- **Alternate/exception flows:**
  - 2a. Non-text or oversized content → blob stored, chunking skipped with a logged notice; corpus report lists skipped items.
  - 3a. Embedding model changed → mixed-model index blocked; system requires and offers a background re-embed job.
  - 4a. Source permission model drifts → refreshed on next incremental sync; the bounded staleness window is documented per corpus.
- **Postconditions:** Corpus retrievable, cited, permission-aware, tenant-isolated; re-index pipeline active.
- **Related requirements:** FR-095, FR-096, FR-097, FR-098, FR-099, FR-100, FR-101.
- **Related agents/connectors:** Confluence, Generic File/Document connectors; RAG Retrieval agent; Data Quality agent.

### UC-014 — Agent invokes an MCP tool

- **Actor:** System (an agent in `eip-ai`, acting for a requesting user); Platform Administrator configures; Security Officer audits.
- **Goal:** Let an agent call a capability on a registered enterprise MCP server (EIP as MCP client) — e.g., the Incident Analysis agent querying an internal CMDB MCP server.
- **Preconditions:** MCP server registered in the MCP registry with endpoint + credentials (encrypted per UC-003 rules); the specific capability allow-listed; per-capability RBAC mapping defined; the requesting user's role permits the capability.
- **Main flow:**
  1. During plan/execute, the agent selects the MCP tool from its allow-listed toolset.
  2. The runtime checks per-capability RBAC against the requesting user's effective permissions and the agent's own grant.
  3. The runtime invokes the MCP capability with tenant-scoped parameters and a `traceparent`; response is validated against the capability's declared schema.
  4. The invocation is audited (agent, user, capability, server, latency, outcome; payloads per redaction policy) and counted against the agent's budget.
  5. The agent incorporates the result with a tool-source citation in its output.
- **Alternate/exception flows:**
  - 2a. Capability not allow-listed or RBAC denies → invocation refused; agent proceeds without the tool and states the limitation in its output.
  - 3a. MCP server timeout/error → bounded retries; then degrade as 2a.
  - Mirror case: an external MCP client calls EIP as MCP **server** → only explicitly allow-listed internal capabilities are exposed, deny-by-default; same tenant isolation, RBAC, and audit apply (FR-105, FR-106, FR-108).
- **Postconditions:** Tool result used with citation; complete audit trail; no un-allow-listed capability ever reachable.
- **Related requirements:** FR-104, FR-105, FR-106, FR-107, FR-108, FR-088.
- **Related agents/connectors:** any agent with MCP tools; commonly Incident Analysis, Delivery Risk, Configuration Assistant.

### UC-015 — Configure an LLM provider and model routing

- **Actor:** Platform Administrator (`llm:configure`).
- **Goal:** Register a provider (Ollama, vLLM, OpenAI-compatible generic, Anthropic-compatible, custom enterprise endpoint) and route models per tenant and per agent with fallbacks and budgets.
- **Preconditions:** Provider endpoint reachable from the deployment network; for air-gapped installs, provider must be internal.
- **Main flow:**
  1. Actor registers the provider via the LLM provider SPI form (endpoint, auth, model list, timeouts, rate limits) and runs the provider test (health + short completion).
  2. Actor sets routing: tenant default model, per-agent overrides, fallback chain, per-agent token budgets.
  3. Actor selects the embedding model for RAG (triggers UC-013 re-embed rules if changed).
  4. System persists configuration, audits it, and applies routing to subsequent `eip.ai.jobs` without restart.
- **Alternate/exception flows:**
  - 1a. Provider test fails → registration saved as `INACTIVE`; nothing routes to it.
  - 2a. Fallback chain empty for an enabled agent → warning; agent jobs park `AWAITING_PROVIDER` when the primary is down.
- **Postconditions:** Agents run against routed models within budgets; every subsequent call audited with model + cost.
- **Related requirements:** FR-084, FR-085, FR-101, FR-113, FR-130, NFR-051.
- **Related agents/connectors:** all agents; Configuration Assistant.

## 5. Evaluation

### UC-016 — Run a tenant in simulation mode

- **Actor:** Platform Administrator; evaluators (Engineering Manager, Team Lead) consume.
- **Goal:** Exercise the full pipeline — ingestion through agents and reports — on simulated enterprise data with no external systems or credentials.
- **Preconditions:** Simulation data pack from `/simulation` compatible with the platform's schema version; dedicated tenant available.
- **Main flow:**
  1. Actor creates a tenant with the simulation-mode flag; real connectors are not configurable on it.
  2. Actor loads a data pack; simulation-mode connectors (Connector SPI simulation/mock mode) replay it through `eip.raw.<connector>` and the real normalizer → canonical → `eip.domain.*` → analytics/RAG path.
  3. Evaluators use dashboards, risk views, and agents normally; all screens and GeneratedReports carry a SIMULATED DATA marker (metadata + rendered output).
  4. Actor resets (deterministic replay) or deletes the tenant; deletion cascades tenant-scoped data and is audited.
- **Alternate/exception flows:**
  - 1a. Attempt to mix real and simulated connectors on one tenant → blocked at the tenant flag level.
  - 2a. Pack schema version mismatch → loader refuses with version details.
  - 3a. No LLM configured → agents degrade to template-only outputs with notice; metric evaluation unaffected.
- **Postconditions:** Faithful evaluation environment; zero production contact; clean teardown.
- **Related requirements:** FR-004, FR-019, FR-129.
- **Related agents/connectors:** simulation mode of Jira/GitHub/SonarQube/CI connectors; all agents.

### UC-017 — Investigate data quality findings

- **Actor:** Platform/Tenant Administrator; Data Quality agent detects.
- **Goal:** Review and resolve normalization gaps (unmapped WorkflowStates, orphaned `ExternalRef`s, duplicate suspects) that would distort metrics.
- **Preconditions:** Sync running (UC-006/UC-007); Data Quality agent enabled.
- **Main flow:**
  1. Data Quality agent aggregates flagged staging records and canonical anomalies into findings with affected-metric impact estimates.
  2. Actor reviews a finding (e.g., a Jira status not mapped to a WorkflowState, silently excluded from cycle time).
  3. Actor fixes the mapping in connector configuration; system re-normalizes affected records from raw staging (no source re-fetch).
  4. Affected metrics rematerialize; finding auto-closes with an audit trail.
- **Alternate/exception flows:**
  - 3a. Fix requires source-side change → finding parked with owner and revisit date.
- **Postconditions:** Metric integrity restored; distortion window documented on affected metric freshness metadata.
- **Related requirements:** FR-039, FR-041, FR-060, FR-061.
- **Related agents/connectors:** Data Quality agent; any connector.

### UC-018 — Audit AI usage for a period (security review)

- **Actor:** CISO / Security Officer (`SECURITY_AUDITOR`).
- **Goal:** Produce a compliance-grade answer to "what did the AI see, say, and cost this quarter, and did any data leave the network?"
- **Preconditions:** UC-004 permissions; LLM audit, RAG audit, and MCP audit populated since the period start.
- **Main flow:**
  1. Actor filters LLM Usage audit by period and tenant: calls by agent, model, provider, tokens, cost, latency; prompts per redaction policy.
  2. Actor verifies the provider list contains only approved endpoints (air-gap check) and cross-references egress via observability dashboards.
  3. Actor samples RAG retrieval logs for permission-aware behavior and reviews all MCP client/server invocations against the allow-list.
  4. Actor invokes the Security Review agent to compile the evidence pack, reviews its anomaly section, and exports the pack.
- **Alternate/exception flows:**
  - 2a. Unapproved provider found → finding raised; `PLATFORM_ADMIN` deactivates it (UC-015 1a state) and the deactivation is audited.
  - 4a. Agent unavailable → manual export path from UC-004 remains sufficient; agent is assistive, never the only path.
- **Postconditions:** Evidence pack archived; anomalies tracked to closure.
- **Related requirements:** FR-081, FR-099, FR-107, NFR-042, NFR-051.
- **Related agents/connectors:** Security Review agent.

## 6. Use-case-to-persona traceability

P = primary actor, S = secondary/consumer, C = configures, A = audits.

| Use case | Platform Admin | Tenant Admin | Eng. Manager | Team Lead/SM | Developer | Product Manager | Release Manager | SRE/Ops | CISO/Security | VP Eng/CTO | Agile Coach |
|---|---|---|---|---|---|---|---|---|---|---|---|
| UC-001 Tenant onboarding | P | S | | | | | | | A | | |
| UC-002 Role management | S | P | | | | | | | A | | |
| UC-003 Secret rotation | P | | | | | | | | A | | |
| UC-004 Audit log review | S | S | | | | | | | P | | |
| UC-005 Connector onboarding | P | P | S | | | | | S | A | | |
| UC-006 Initial full sync | P | S | S | | | | | | | | |
| UC-007 Incremental sync | C | C | S | S | S | S | S | S | | S | S |
| UC-008 Sync failure recovery | P | S | | | | | | | A | | |
| UC-009 Metric exploration | | | P | P | S | P | S | P | | S | P |
| UC-010 Risk detection review | | | P | S | | P | S | | | S | S |
| UC-011 Report generation (on demand) | | | P | P | S | P | P | P | S | P | P |
| UC-012 Scheduled reports | | P | P | S | | S | S | | | S | |
| UC-013 RAG document ingestion | P | P | | | S | | | | A | | |
| UC-014 MCP tool invocation | C | | S | S | | | | S | A | | |
| UC-015 LLM provider & routing | P | | | | | | | | A | | |
| UC-016 Simulation mode | P | S | S | S | | S | | | | S | S |
| UC-017 Data quality findings | P | P | S | | | | | | | | |
| UC-018 AI usage audit | S | | | | | | | | P | | |

## 7. Acceptance criteria conventions

Each use case above is testable; PRD requirements referenced per use case carry the Given/When/Then detail. Two platform-wide invariants apply to every use case and are asserted in integration tests:

- **Given** any actor without `PLATFORM_ADMIN`, **when** any flow executes, **then** no data from another tenant is readable or writable (enforced by tenant_id + Postgres RLS, verified at the SQL layer, not the UI).
- **Given** any mutating or AI/secret-touching operation, **when** it completes or fails, **then** a corresponding audit entry exists with actor, action, entity, tenant, and outcome.
