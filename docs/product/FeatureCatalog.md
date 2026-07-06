# EIP Feature Catalog

Complete, ID-stable catalog of features for the **Engineering Intelligence Platform (EIP)**. Every feature has a stable ID (`FEAT-XXX`) used across all product documents (see `./AcceptanceCriteria.md` and `./Roadmap.md`). Decisions, names, and terminology follow the shared design brief; canonical entity names follow the core domain vocabulary (see `../architecture/DomainModel.md`).

Conventions:

- **Priority**: P0 = platform cannot ship without it; P1 = required for the core value proposition; P2 = valuable, deferrable past 1.0 scope discussion.
- **Phase**: implementation phase 0-5 per the canonical roadmap (see `./Roadmap.md`).
- **Dependencies**: hard prerequisites, by FEAT ID.

## 1. Summary

| # | Capability area | Features | P0 | P1 | P2 |
|---|-----------------|----------|----|----|----|
| 2 | Platform & Tenancy | 11 | 9 | 2 | 0 |
| 3 | Admin & Configuration | 18 | 6 | 11 | 1 |
| 4 | Connectors | 20 | 4 | 4 | 12 |
| 5 | Ingestion & Normalization | 9 | 8 | 1 | 0 |
| 6 | Analytics & Metrics | 9 | 4 | 5 | 0 |
| 7 | Dashboards | 11 | 5 | 6 | 0 |
| 8 | AI Agents | 21 | 4 | 8 | 9 |
| 9 | RAG | 6 | 4 | 2 | 0 |
| 10 | MCP | 3 | 0 | 3 | 0 |
| 11 | Report Generation Center & Artifacts Library | 22 | 4 | 8 | 10 |
| 12 | Security & Audit | 6 | 5 | 1 | 0 |
| 13 | Observability | 6 | 5 | 1 | 0 |
| | **Total** | **142** | **58** | **52** | **32** |

### 1.1 ID conventions

- IDs are allocated in per-area blocks with gaps reserved for growth (e.g., Platform 001-019, Admin 020-049, Connectors 050-074). An ID, once published, is never reused or renumbered; retired features are marked deprecated in place.
- A feature's phase is the phase in which it reaches its exit criteria (see `./Roadmap.md`); earlier partial work may land behind flags.

### 1.2 Feature count by phase

| Phase | 0 – Foundations | 1 – Ingestion core | 2 – Analytics & dashboards | 3 – AI core | 4 – Agents/MCP/outputs | 5 – Hardening |
|-------|-----------------|--------------------|----------------------------|-------------|------------------------|---------------|
| Features | 20 | 19 | 28 | 25 | 34 | 16 |

## 2. Platform & Tenancy

The load-bearing foundation: multi-tenancy, identity, authorization, the API contract, deployment topology, and the runtime shape (modular monolith + workers). Everything else in the catalog depends on this area, which is why it is almost entirely P0 and Phase 0, with the deployment-hardening tail (K8s GA, HA, backup/restore) landing in Phase 5.

| ID | Name | Description | Priority | Phase | Dependencies |
|----|------|-------------|----------|-------|--------------|
| FEAT-001 | Multi-tenant core | Row-level tenant isolation on PostgreSQL 16 using `tenant_id` columns plus Postgres RLS policies on every tenant-scoped table. All queries, events, caches, vector searches, and object-storage keys carry tenant context. Cross-tenant access is denied by construction, not by convention. | P0 | 0 | — |
| FEAT-002 | Organization hierarchy | CRUD and lifecycle for Organization, BusinessUnit, Team, and Member entities with membership and reporting relationships. Provides the grouping grain for all metrics, dashboards, and RBAC scoping. | P0 | 0 | FEAT-001 |
| FEAT-003 | OIDC authentication | OIDC login with Keycloak as the on-prem default and pluggable enterprise IdP support (AD FS, Azure AD, Okta), plus a local-accounts fallback for air-gapped bootstrap. Sessions and tokens are tenant-scoped. | P0 | 0 | FEAT-001 |
| FEAT-004 | RBAC authorization | Role-based access control with roles composed of fine-grained permissions, always tenant-scoped. Enforced at the API layer and re-checked at the data layer (RLS). Includes built-in roles (platform admin, tenant admin, analyst, viewer) and custom role creation. | P0 | 0 | FEAT-001, FEAT-003 |
| FEAT-005 | REST API baseline | REST API under `/api/v1` with OpenAPI 3 documentation via springdoc, cursor pagination, RFC 7807 problem+json error responses, and idempotency keys on mutating batch endpoints. | P0 | 0 | FEAT-001 |
| FEAT-006 | Modular monolith runtime | Spring Boot 3.x modular monolith on Java 21 structured with Spring Modulith conventions (`eip-app`, `eip-core`, `eip-tenancy`, `eip-connectors`, `eip-ingestion`, `eip-analytics`, `eip-ai`, `eip-reports`), plus separately deployable async worker processes (`eip-workers`). Module boundaries are enforced so modules remain extractable to services later. | P0 | 0 | — |
| FEAT-007 | Docker Compose dev/demo stack | Single-command local deployment (`/infra/docker-compose`) of the full stack: app, workers, PostgreSQL 16 with pgvector, Redis 7, Kafka (KRaft), MinIO, Keycloak, OTel Collector, Prometheus, Grafana. Used for development and customer demos. | P0 | 0 | FEAT-006 |
| FEAT-008 | Kubernetes/OpenShift deployment | Kustomize base plus overlays under `/infra/kubernetes` with OpenShift notes: manifests for app, workers, and dependencies; secrets wiring; probes; resource limits. GA-hardened in Phase 5. | P1 | 5 | FEAT-006, FEAT-007 |
| FEAT-009 | High availability & horizontal scaling | Stateless API replicas, partitioned Kafka consumers, Redisson distributed locks for singleton jobs, and connection-pool/backpressure tuning validated under load. Documented capacity model. | P1 | 5 | FEAT-008 |
| FEAT-010 | Backup/restore & upgrade paths | Documented and scripted backup/restore for PostgreSQL, MinIO, and Kafka offsets; Flyway-based forward-only schema upgrades; tested version-to-version upgrade procedure. | P0 | 5 | FEAT-006 |
| FEAT-011 | Frontend application shell | React 18 + TypeScript + Vite single-page app with TanStack Query, shared component library, ECharts dashboard primitives, theming, and i18n-ready message catalogs. | P0 | 0 | FEAT-005 |

## 3. Admin & Configuration

Everything a platform or tenant administrator configures, in one coherent admin surface: organizational structure, users and roles, data sources and their credentials, AI settings (LLM providers, RAG, MCP, agent workflows), analytical settings (templates, metric definitions, risk rules), and operational settings (retention, notifications, scheduled jobs, queue/cache/storage/observability). Every configuration change here is audited (FEAT-197) and RBAC-guarded (FEAT-004).

| ID | Name | Description | Priority | Phase | Dependencies |
|----|------|-------------|----------|-------|--------------|
| FEAT-020 | Organization administration | Admin UI + API to create and manage Organizations (tenants): naming, lifecycle (active/suspended), tenant-level defaults, and tenant bootstrap (first admin user). | P0 | 0 | FEAT-002, FEAT-004 |
| FEAT-021 | Business unit administration | Manage BusinessUnits within an Organization, including hierarchy, ownership, and the mapping of Products/Projects to business units for roll-up reporting. | P1 | 0 | FEAT-020 |
| FEAT-022 | Team administration | Manage Teams and Member assignments, team boards, and team-to-Project/Repository mappings that drive metric attribution. Supports import of team structure from connectors where available. | P0 | 0 | FEAT-020 |
| FEAT-023 | User & role administration | Manage users (invite, deactivate, IdP-linked or local), assign roles, and define custom roles from fine-grained permissions. Includes permission preview ("what can this role see/do"). | P0 | 0 | FEAT-004 |
| FEAT-024 | Data source administration | Register and manage connector instances (data sources): select connector type, complete its JSON Schema-driven configuration form, run `validate()` and `testConnection()`, set sync schedules and scopes, enable/disable, and view health. | P0 | 1 | FEAT-050, FEAT-025 |
| FEAT-025 | Credential management | Central credential store for connector secrets, LLM API keys, and webhook signing secrets: AES-256-GCM envelope encryption, masked display, scoped usage, rotation workflow, and audited access. | P0 | 0 | FEAT-195, FEAT-197 |
| FEAT-026 | LLM provider administration | Configure LLM providers (Ollama, vLLM, OpenAI-compatible generic, Anthropic-compatible, custom enterprise endpoint), per-tenant and per-agent model routing, fallback chains, and token budgets. Includes provider health test. | P1 | 3 | FEAT-131, FEAT-025 |
| FEAT-027 | RAG settings administration | Configure chunking strategy, embedding model, vector store backend (pgvector default, Qdrant option), retrieval parameters, and re-index schedules per tenant and per corpus. | P1 | 3 | FEAT-155, FEAT-157 |
| FEAT-028 | MCP server registry | Register external enterprise MCP servers (endpoint, auth, capability discovery) for agent tool use, and configure the allow-list of internal capabilities EIP exposes as an MCP server. | P1 | 4 | FEAT-165, FEAT-166 |
| FEAT-029 | Agent workflow configuration | Enable/disable agents per tenant, configure their triggers (manual, scheduled, event-driven), tool allow-lists, budgets, guardrail policies, and default output templates. | P1 | 3 | FEAT-130 |
| FEAT-030 | Template management | Manage report and prompt templates: versioned templates with variables, tenant-level overrides of shipped defaults, and preview rendering against sample data. | P1 | 3 | FEAT-171 |
| FEAT-031 | Metric definition management | View shipped metric definitions (purpose, formula, inputs, grain, caveats/limitations, gaming risks) and manage tenant-level parameters such as WorkflowState-to-stage mapping, working calendars, and thresholds. | P1 | 2 | FEAT-090 |
| FEAT-032 | Risk rule configuration | Configure delivery-risk scoring rules and thresholds (epic delivery risk, project delay prediction, dependency risk, release readiness score), including weighting, suppression, and per-Project overrides. | P1 | 2 | FEAT-094, FEAT-097 |
| FEAT-033 | Data retention policies | Per-tenant retention configuration for raw staging data, domain events, metrics time series, audit logs, LLM call logs, and generated artifacts, with scheduled enforcement jobs and legal-hold exemption. | P1 | 2 | FEAT-035 |
| FEAT-034 | Notification channel administration | Configure notification channels (SMTP email, generic webhook, Slack/Teams-compatible webhook) and subscription rules for alerts, report delivery, job failures, and risk threshold breaches. | P1 | 4 | FEAT-023 |
| FEAT-035 | Scheduled jobs administration | Manage scheduled jobs (connector syncs, metric recomputation, RAG re-index, report generation, retention enforcement): cron-style schedules, run history, manual trigger, pause/resume, and failure visibility. | P0 | 1 | FEAT-006 |
| FEAT-036 | Queue/cache/storage settings | Operator-facing configuration and status for Kafka (topic prefix `eip.`, consumer groups, DLQs), Redis (cache TTLs, lock namespaces), and S3-compatible object storage (MinIO default; buckets, lifecycle). | P1 | 1 | FEAT-076 |
| FEAT-037 | Observability settings | Configure OTel exporter endpoints, sampling rates, log levels per module, and metric scrape exposure; toggle optional Tempo/Loki pipelines. | P2 | 2 | FEAT-205 |

Note: FEAT-020 through FEAT-037 deliberately mirror the admin surface: orgs, business units, teams, users/roles, data sources, credentials, LLM providers, RAG settings, MCP servers, agent workflows, templates, metric definitions, risk rules, retention, notifications, scheduled jobs, and queue/cache/storage/observability settings.

## 4. Connectors

The canonical connector list, built on a single pluggable SPI. The SPI contract for every connector: JSON Schema configuration, `validate()`, `testConnection()`, `healthCheck()`, `fullSync()`, `incrementalSync(checkpoint)`, webhook intake where supported, rate limiting, retry with exponential backoff + jitter, idempotent upserts, checkpointing, dedup, and simulation/mock mode. Phase placement follows the canonical roadmap: Jira + GitHub + simulation in Phase 1, the remaining P1 connectors (GitLab, SonarQube, Generic CI/CD, Prometheus) in Phase 2, and all remaining connectors in Phase 5.

| ID | Name | Description | Priority | Phase | Dependencies |
|----|------|-------------|----------|-------|--------------|
| FEAT-050 | Connector SPI | The pluggable connector framework implementing the full contract above, hosted in `eip-connectors`. All built-in and custom connectors implement this SPI; the sync engine and admin UI are written against it only. | P0 | 1 | FEAT-006, FEAT-076 |
| FEAT-051 | Simulation connector mode | Every connector runs in simulation/mock mode against simulated enterprise data packs (`/simulation`), producing the same canonical entities and events as live mode. Enables air-gapped demos, testing, and load rehearsal without external systems. | P0 | 1 | FEAT-050 |
| FEAT-052 | Jira connector | Syncs projects, boards, sprints, and issues into WorkItem (EPIC/FEATURE/STORY/TASK/BUG), Sprint, Board, and WorkflowState entities, including changelogs for state-transition timing, links for Dependency, and webhook intake. | P0 | 1 | FEAT-050 |
| FEAT-053 | GitHub connector | Syncs Repository, Branch, Commit, PullRequest, CodeReview, and GitHub Actions Build/Pipeline/Deployment data, with webhook intake and incremental sync from checkpoints. | P0 | 1 | FEAT-050 |
| FEAT-054 | GitLab connector | Syncs repositories, merge requests, reviews, and GitLab CI pipelines into the same canonical SCM/CI entities as GitHub. | P1 | 2 | FEAT-050 |
| FEAT-055 | Bitbucket connector | Syncs Bitbucket repositories, pull requests, and reviews into canonical SCM entities. | P2 | 5 | FEAT-050 |
| FEAT-056 | SonarQube connector | Syncs coverage, code smells, duplication, QualityGate status, and SecurityFinding data per project/branch for quality metrics. | P1 | 2 | FEAT-050 |
| FEAT-057 | Confluence connector | Syncs spaces and pages into Document entities for RAG indexing and documentation-driven reports (with permission metadata preserved). | P2 | 5 | FEAT-050 |
| FEAT-058 | Generic CI/CD connector | Unified connector for Jenkins, Azure DevOps, GitHub Actions, and GitLab CI: Build, Pipeline, Deployment, and Release events feeding DORA metrics. | P1 | 2 | FEAT-050 |
| FEAT-059 | Prometheus connector | Pulls selected metric series and Alert state for Ops metrics (SLO health, alert noise) and Service correlation. | P1 | 2 | FEAT-050 |
| FEAT-060 | Artifactory connector | Syncs Artifact metadata (versions, promotions, checksums) to correlate builds to releases and support release readiness. | P2 | 5 | FEAT-050 |
| FEAT-061 | Kubernetes connector | Reads Deployment/rollout events, workload health, and Environment topology from Kubernetes clusters for deployment tracking and system correlation. | P2 | 5 | FEAT-050 |
| FEAT-062 | OpenShift connector | OpenShift-specific extension of the Kubernetes connector (routes, projects, image streams, deployment configs). | P2 | 5 | FEAT-061 |
| FEAT-063 | Docker Registry connector | Syncs image tags, digests, and push events from Docker Registry v2-compatible registries to correlate Artifacts with Deployments. | P2 | 5 | FEAT-050 |
| FEAT-064 | Grafana connector | Imports dashboard/alert-rule inventory and alert state to enrich operational-health context and alert-noise analysis. | P2 | 5 | FEAT-050 |
| FEAT-065 | OpenTelemetry (OTLP intake) connector | Accepts OTLP traces/metrics/logs from customer systems as an intake endpoint, producing TraceReference/LogReference/Metric entities for incident and Service analysis. | P2 | 5 | FEAT-050 |
| FEAT-066 | Generic REST connector | Configuration-driven connector for arbitrary REST APIs: auth, pagination, JSONPath field mapping to canonical entities, and cursor-based incremental sync. | P2 | 5 | FEAT-050 |
| FEAT-067 | Generic SQL connector | Reads from customer databases via configurable queries mapped to canonical entities, with watermark-column incremental sync. | P2 | 5 | FEAT-050 |
| FEAT-068 | Generic File/Document connector | Ingests files (Markdown, PDF, Office, CSV) from object storage or file shares into Document entities and raw blobs for RAG and reports. | P2 | 5 | FEAT-050 |
| FEAT-069 | Custom internal demand/project tool connector | Reference connector for a customer's internal demand/project management tool, demonstrating the SPI for bespoke enterprise systems and mapping to WorkItem/Project/Initiative. | P2 | 5 | FEAT-050 |

## 5. Ingestion & Normalization

The data spine: collectors land source payloads in raw staging, normalizers produce the canonical model, and domain events flow over Kafka to analytics, RAG, and report consumers. Delivery guarantees are at-least-once with idempotent consumers, ordered per key (tenantId+entityId), with checkpoints per connector+stream — so re-ingestion, replays, and crashes never corrupt the canonical model.

| ID | Name | Description | Priority | Phase | Dependencies |
|----|------|-------------|----------|-------|--------------|
| FEAT-075 | Raw staging layer | Collectors land source payloads unmodified into `raw_*` JSONB tables (blobs to object storage) with tenant, source, and checkpoint metadata, decoupling collection from normalization and enabling replay. | P0 | 1 | FEAT-001, FEAT-006 |
| FEAT-076 | Kafka event pipeline | Domain events on Kafka (KRaft) with the canonical envelope (`eventId` UUIDv7, `tenantId`, `source`, `entityType`, `entityId`, `eventType`, `occurredAt`, `ingestedAt`, `schemaVersion`, `payload`, `traceparent`) over topics `eip.raw.<connector>`, `eip.domain.workitem`, `eip.domain.scm`, `eip.domain.cicd`, `eip.domain.quality`, `eip.domain.ops`, `eip.analytics.metrics`, `eip.ai.jobs`, `eip.ai.results`, `eip.reports.jobs`. | P0 | 1 | FEAT-006 |
| FEAT-077 | Normalization to canonical model | Normalizers transform raw payloads into the canonical domain model, including the unified `WorkItem` supertype (EPIC/FEATURE/STORY/TASK/BUG/INCIDENT_TICKET) with `ExternalRef` (sourceSystem, externalId, url) identity mapping. | P0 | 1 | FEAT-075, FEAT-076 |
| FEAT-078 | Sync engine with checkpointing | Orchestrates `fullSync()` and `incrementalSync(checkpoint)` per connector+stream with a checkpoint table, so interrupted syncs resume from the last committed checkpoint without data loss or duplication. | P0 | 1 | FEAT-050, FEAT-075 |
| FEAT-079 | Webhook intake | Authenticated webhook endpoints per connector instance (signature verification, replay protection) that enqueue events onto `eip.raw.<connector>` for near-real-time freshness between scheduled syncs. | P1 | 1 | FEAT-050, FEAT-076 |
| FEAT-080 | Idempotent processing & dedup | At-least-once delivery with idempotent consumers: dedup on `eventId`, idempotent upserts keyed by `ExternalRef`, and per-key ordering (tenantId+entityId), guaranteeing re-ingestion never creates duplicates. | P0 | 1 | FEAT-076, FEAT-077 |
| FEAT-081 | Dead-letter queues & replay | Per-consumer-group DLQs (`.<group>.dlq`) with an operator UI to inspect, fix-forward, and replay poisoned messages; alerting on DLQ growth. | P0 | 1 | FEAT-076, FEAT-120 |
| FEAT-082 | Rate limiting & retry | Outbound rate limiting per connector instance (Redis-backed) and retry with exponential backoff + jitter that respects source-system rate-limit headers. | P0 | 1 | FEAT-050 |
| FEAT-083 | Cross-tool entity correlation | Correlates entities across tools (e.g., Commit/PullRequest to WorkItem via branch names and message keys; Deployment to Release; Incident to Service) to enable end-to-end lead time and DORA metrics. | P0 | 1 | FEAT-077 |

## 6. Analytics & Metrics

The canonical metric set — flow, DORA, quality, delivery risk, ops, and team health — computed by a single metric engine over domain events. Every metric definition includes purpose, formula, inputs, grain, caveats/limitations, and gaming risks; this is a product requirement, not documentation polish. Per the platform anti-goals, nothing in this area supports individual surveillance or stack ranking.

| ID | Name | Description | Priority | Phase | Dependencies |
|----|------|-------------|----------|-------|--------------|
| FEAT-090 | Metric engine & definitions | Computes metrics from domain events into queryable time series. Every metric definition ships with purpose, formula, inputs, grain, caveats/limitations, and gaming risks, surfaced in UI tooltips and the metric catalog. | P0 | 2 | FEAT-076, FEAT-077 |
| FEAT-091 | Flow metrics | Velocity, throughput, cycle time, lead time, WIP, flow efficiency, blocked time, sprint predictability (commitment vs done), and scope churn, computed per Team/Board/Sprint from WorkflowState transitions. | P0 | 2 | FEAT-090 |
| FEAT-092 | DORA metrics | Deployment frequency, lead time for changes, change failure rate, and MTTR, computed from correlated SCM, CI/CD, and Incident data at Service/Team/Organization grain. | P0 | 2 | FEAT-090, FEAT-083 |
| FEAT-093 | Quality metrics | Coverage, code smells, duplication, quality gate status, escaped defects, bug aging, technical debt ratio, and security finding aging, primarily from SonarQube and WorkItem data. | P1 | 2 | FEAT-090, FEAT-056 |
| FEAT-094 | Delivery risk metrics | Epic delivery risk, project delay prediction, dependency risk, and release readiness score, combining flow trends, scope churn, Dependency graphs, and QualityGate signals with stated uncertainty. | P1 | 2 | FEAT-090, FEAT-091 |
| FEAT-095 | Ops metrics | Incident frequency/impact, SLO health, and alert noise from Incident, SlaSlo, Prometheus, and Alert data. | P1 | 2 | FEAT-090, FEAT-059 |
| FEAT-096 | Team health metrics | Load balance, review bottlenecks, and knowledge concentration (bus factor) — computed and displayed at team level only, with explicit anti-toxic-ranking design: no individual leaderboards, no cross-person comparison views, context and limitations always shown. | P1 | 2 | FEAT-090 |
| FEAT-097 | Risk scoring engine | Rule- and threshold-based scoring runtime that evaluates configurable risk rules (FEAT-032) over metrics and entities, producing Risk records and alerts with explanations. | P1 | 2 | FEAT-090, FEAT-094 |
| FEAT-098 | Metrics API & time series store | `/api/v1` metric query endpoints (dimensions, time ranges, roll-ups by Team/BusinessUnit/Organization) over PostgreSQL time-series tables, publishing computed values on `eip.analytics.metrics`. | P0 | 2 | FEAT-090, FEAT-005 |

## 7. Dashboards

User-facing and operator-facing visualization built on one shared framework (ECharts + React). User dashboards (productivity, delivery risk, sprint, kanban, release, quality, operational health) render metric-engine values only — no client-side recomputation — and always expose metric caveats and data freshness. Operator dashboards (admin, system health, job/queue/cache/connector monitors) make the platform itself observable to its administrators.

| ID | Name | Description | Priority | Phase | Dependencies |
|----|------|-------------|----------|-------|--------------|
| FEAT-110 | Dashboard framework | Shared dashboard infrastructure: ECharts chart library components, filter bar (Organization/BusinessUnit/Team/Project/Sprint/time range), drill-down navigation, metric caveat tooltips, and export to image. | P0 | 2 | FEAT-011, FEAT-098 |
| FEAT-111 | Admin dashboard | Tenant administration overview: users, teams, data sources and their health, sync status, scheduled jobs, storage/queue usage, and recent audit highlights. | P0 | 1 | FEAT-011, FEAT-024 |
| FEAT-112 | Productivity dashboard | Team-level flow overview: velocity, throughput, cycle/lead time trends, WIP, flow efficiency, and blocked time, always with context and limitations — explicitly not an individual-ranking view. | P0 | 2 | FEAT-110, FEAT-091 |
| FEAT-113 | Delivery risk dashboard | Portfolio view of epic delivery risk, project delay predictions, dependency risk, and release readiness scores with drill-down to contributing signals and risk-rule explanations. | P1 | 2 | FEAT-110, FEAT-094, FEAT-097 |
| FEAT-114 | Sprint dashboard | Live sprint view: burndown/burnup, commitment vs done, scope churn, blocked items, and sprint predictability, refreshed from incremental sync and webhooks. | P0 | 2 | FEAT-110, FEAT-091 |
| FEAT-115 | Kanban dashboard | Flow-based view: cumulative flow diagram, WIP by WorkflowState, aging work-in-progress, cycle time scatterplot and percentiles. | P1 | 2 | FEAT-110, FEAT-091 |
| FEAT-116 | Release dashboard | Release readiness: scope completion, QualityGate status, open Risks and Dependencies, deployment pipeline status, and readiness score per Release. | P1 | 2 | FEAT-110, FEAT-094 |
| FEAT-117 | Quality dashboard | Coverage, code smells, duplication, quality gates, escaped defects, bug aging, technical debt ratio, and security finding aging trends per Repository/Project. | P1 | 2 | FEAT-110, FEAT-093 |
| FEAT-118 | Operational health dashboard | Incident frequency/impact, MTTR, SLO health, and alert noise per Service/Environment. | P1 | 2 | FEAT-110, FEAT-095 |
| FEAT-119 | System health dashboard | EIP self-health for operators: API latency/error rates, worker throughput, Kafka lag, Redis and PostgreSQL health, LLM provider availability, sourced from platform observability. | P0 | 1 | FEAT-011, FEAT-205 |
| FEAT-120 | Job/queue/cache/connector monitors | Operational monitors: scheduled job runs and failures, Kafka topic/DLQ depth and consumer lag, Redis cache hit rates, and per-connector sync status, checkpoint age, and error history. | P1 | 1 | FEAT-119, FEAT-035 |

## 8. AI Agents

Agent infrastructure plus the 18 canonical agents: Data Ingestion, Data Quality, Engineering Metrics, Delivery Risk, Sprint Review, Release Notes, Documentation, Use Case Diagram, Architecture Diagram, Executive Summary, Incident Analysis, Code Quality, Team Health, RAG Retrieval, Report Composition, Validation, Security Review, and Configuration Assistant. All agents run in the `eip-ai` plan/execute runtime with tool-calling, budgets, guardrails, and full LLM-call audit; all function air-gapped against local LLMs via the provider SPI.

| ID | Name | Description | Priority | Phase | Dependencies |
|----|------|-------------|----------|-------|--------------|
| FEAT-130 | Agent runtime | Plan/execute agent orchestration in `eip-ai` (Java + LangChain4j): tool-calling, token/cost budgets, guardrails (allowed tools, output validation, PII redaction policy), retries, and audit of every LLM call (prompt, model, tokens, cost, latency; prompts redacted per policy). Optional Python AI workers isolated behind Kafka/REST. | P0 | 3 | FEAT-006, FEAT-076, FEAT-198 |
| FEAT-131 | LLM provider SPI | Pluggable providers: Ollama, vLLM (OpenAI-compatible), OpenAI-compatible generic, Anthropic-compatible, and custom enterprise endpoint. Works fully air-gapped with local LLMs. | P0 | 3 | FEAT-130 |
| FEAT-132 | Model routing & failover | Per-tenant and per-agent model routing with ordered fallback chains, token budgets, health-based circuit breaking, and transparent failover when a provider is down or over budget. | P0 | 3 | FEAT-131, FEAT-026 |
| FEAT-133 | Data Ingestion Agent | Assists ingestion operations: diagnoses failing syncs, proposes field mappings for generic connectors, and drafts connector configurations from source-system samples. | P2 | 4 | FEAT-130, FEAT-050 |
| FEAT-134 | Data Quality Agent | Scans canonical data for gaps, staleness, mapping anomalies, and broken correlations; produces data-quality findings that annotate affected metrics and dashboards. | P1 | 4 | FEAT-130, FEAT-077 |
| FEAT-135 | Engineering Metrics Agent | Explains metric movements in natural language with cited underlying data, answers metric questions, and flags trend changes worth attention — always including caveats and uncertainty. | P1 | 4 | FEAT-130, FEAT-098 |
| FEAT-136 | Delivery Risk Agent | Analyzes epics, dependencies, and flow trends to narrate delivery risks, delay predictions, and mitigation options; feeds the risk report and delivery risk dashboard annotations. | P1 | 3 | FEAT-130, FEAT-094 |
| FEAT-137 | Sprint Review Agent | Produces sprint review narratives and presentation content from Sprint, WorkItem, and metric data: accomplishments, misses, scope churn, blockers, and carry-over, with source citations. | P1 | 3 | FEAT-130, FEAT-091, FEAT-158 |
| FEAT-138 | Release Notes Agent | Generates release notes from merged PullRequests, resolved WorkItems, and Deployment/Release data, grouped by audience (technical/customer-facing) with traceable citations. | P1 | 3 | FEAT-130, FEAT-083 |
| FEAT-139 | Documentation Agent | Drafts and updates user manuals and technical documentation from Repository content, Documents, and ApiEndpoint inventories, via RAG over ingested sources. | P2 | 4 | FEAT-130, FEAT-158 |
| FEAT-140 | Use Case Diagram Agent | Generates use case diagrams (Mermaid/PlantUML sources rendered to artifacts) from requirements-bearing WorkItems and Documents. | P2 | 4 | FEAT-130, FEAT-158 |
| FEAT-141 | Architecture Diagram Agent | Generates architecture and dependency diagrams from Repository, Service, ApiEndpoint, and Deployment topology data, with human-editable diagram sources. | P2 | 4 | FEAT-130, FEAT-158 |
| FEAT-142 | Executive Summary Agent | Composes executive-level summaries across portfolios: delivery health, top risks, quality and ops posture, in configurable tone and length with citations. | P1 | 4 | FEAT-130, FEAT-098, FEAT-094 |
| FEAT-143 | Incident Analysis Agent | Summarizes Incidents (timeline, impact, contributing factors, follow-ups) from Incident records, Alerts, and Log/TraceReferences; drafts incident summaries for review. | P2 | 4 | FEAT-130, FEAT-095 |
| FEAT-144 | Code Quality Agent | Narrates code-quality posture and trends (coverage, smells, debt ratio, security finding aging) per Repository/Project and proposes prioritized remediation themes. | P2 | 4 | FEAT-130, FEAT-093 |
| FEAT-145 | Team Health Agent | Produces team-level health narratives (load balance, review bottlenecks, bus factor) with explicit anti-surveillance guardrails: no individual attribution in outputs. | P2 | 4 | FEAT-130, FEAT-096 |
| FEAT-146 | RAG Retrieval Agent | The retrieval tool used by other agents: permission-aware, tenant-isolated vector search with metadata filters, returning chunks with source citations. | P0 | 3 | FEAT-130, FEAT-158 |
| FEAT-147 | Report Composition Agent | Assembles multi-section reports from other agents' outputs and templates, enforcing structure, citation completeness, and length budgets before hand-off to the report engine. | P1 | 3 | FEAT-130, FEAT-170 |
| FEAT-148 | Validation Agent | Reviews generated outputs against source data before publication: verifies claims trace to citations, flags hallucination risk, and blocks or annotates outputs that fail validation policy. | P1 | 4 | FEAT-130, FEAT-147 |
| FEAT-149 | Security Review Agent | Aggregates SecurityFindings and configuration posture into security review narratives and drafts the security findings report; never auto-remediates. | P2 | 4 | FEAT-130, FEAT-093 |
| FEAT-150 | Configuration Assistant Agent | Conversational assistant for administrators: explains settings, drafts connector/metric/risk-rule configurations, and validates proposed changes — applying nothing without explicit admin confirmation and RBAC checks. | P2 | 4 | FEAT-130, FEAT-024 |

## 9. RAG

Retrieval-augmented generation grounded in the tenant's own engineering data: ingestion → chunking → embedding (configurable model) → vector store (pgvector default, Qdrant via the VectorStore SPI) → permission-aware retrieval. Isolation and citations are non-negotiable: retrieval always enforces tenant and RBAC scope, and every retrieved chunk cites its source.

| ID | Name | Description | Priority | Phase | Dependencies |
|----|------|-------------|----------|-------|--------------|
| FEAT-155 | RAG ingestion & chunking | Pipeline from canonical entities and Documents to retrieval corpus: content extraction, configurable chunking, and metadata enrichment (tenant, source, entity type, permissions, timestamps). | P0 | 3 | FEAT-077, FEAT-076 |
| FEAT-156 | Embedding pipeline | Configurable embedding model (local by default for air-gapped operation) invoked through the LLM provider SPI; batch and streaming embedding with cost/token accounting. | P0 | 3 | FEAT-155, FEAT-131 |
| FEAT-157 | Vector store SPI | VectorStore abstraction with pgvector as default backend and Qdrant as a pluggable option; tenant-partitioned collections/namespaces and filtered similarity search. | P0 | 3 | FEAT-156 |
| FEAT-158 | Permission-aware retrieval | Retrieval enforcing tenant isolation and the caller's RBAC scope, with metadata filters (source, entity type, time) and mandatory source citations on every returned chunk. | P0 | 3 | FEAT-157, FEAT-004 |
| FEAT-159 | Incremental & scheduled re-indexing | Change-driven incremental re-indexing from domain events plus scheduled full re-index jobs; index versioning to allow re-embedding on model change without downtime. | P1 | 3 | FEAT-155, FEAT-035 |
| FEAT-160 | RAG audit logging | Audit of retrieval operations: who/which agent retrieved what corpus scope, filters applied, and which documents were cited into generated outputs. | P1 | 3 | FEAT-158, FEAT-197 |

## 10. MCP

EIP participates in the Model Context Protocol in both directions: as an MCP client, enterprise MCP servers become tools available to agents; as an MCP server, selected internal capabilities are exposed to enterprise AI clients. Both directions are default-deny (allow-listed), per-capability RBAC-guarded, and fully audited.

| ID | Name | Description | Priority | Phase | Dependencies |
|----|------|-------------|----------|-------|--------------|
| FEAT-165 | MCP client | EIP connects to registered enterprise MCP servers and exposes their tools to agents, subject to per-agent tool allow-lists and budgets. | P1 | 4 | FEAT-130, FEAT-028 |
| FEAT-166 | MCP server | EIP exposes selected, allow-listed internal capabilities (metric queries, report triggers, entity lookups) as an MCP server for enterprise AI clients. | P1 | 4 | FEAT-005, FEAT-028 |
| FEAT-167 | MCP RBAC & audit | Per-capability RBAC on both MCP directions plus full audit of MCP tool invocations (caller, capability, parameters per redaction policy, outcome). | P1 | 4 | FEAT-165, FEAT-166, FEAT-197 |

## 11. Report Generation Center & Artifacts Library

The generation engine (FEAT-170 to FEAT-173) plus the full catalog of output types (FEAT-174 to FEAT-191). Every GeneratedReport is produced asynchronously, rendered through versioned templates, validated for citation completeness, and stored as an immutable version in the Artifacts Library with RBAC-scoped access. Drafted communications are never auto-sent; humans stay in the loop for anything leaving the platform.

| ID | Name | Description | Priority | Phase | Dependencies |
|----|------|-------------|----------|-------|--------------|
| FEAT-170 | Report Generation Center | Central UI + API to request, configure, schedule, and track report generation jobs (`eip.reports.jobs`), with per-report-type parameter forms, run history, and failure diagnostics. | P0 | 3 | FEAT-011, FEAT-076, FEAT-130 |
| FEAT-171 | Template engine | Renders GeneratedReport content through versioned templates into target formats (Markdown, HTML, PDF, PPTX for presentations, PNG/SVG for diagrams), with tenant template overrides. | P0 | 3 | FEAT-030 |
| FEAT-172 | Artifacts Library | Versioned library of all GeneratedReports and diagram artifacts in object storage (MinIO default): metadata, full version history, source citation records, RBAC-scoped sharing, and download. | P0 | 3 | FEAT-171, FEAT-004 |
| FEAT-173 | Report scheduling & distribution | Scheduled report generation with delivery through notification channels (email, webhook) and links back to the Artifacts Library. | P1 | 4 | FEAT-170, FEAT-034, FEAT-035 |
| FEAT-174 | Sprint review presentation | Generated sprint review deck (PPTX/HTML) per Sprint: goals vs outcomes, metrics, demos list, blockers, and carry-over, with citations. | P0 | 3 | FEAT-137, FEAT-171 |
| FEAT-175 | Release notes | Generated release notes per Release, technical and customer-facing variants, traceable to PullRequests and WorkItems. | P1 | 3 | FEAT-138, FEAT-171 |
| FEAT-176 | Executive report | Portfolio-level executive report: delivery health, risks, quality, and ops posture with trend commentary and citations. | P1 | 4 | FEAT-142, FEAT-171 |
| FEAT-177 | Risk report | Delivery risk deep-dive per Project/Epic: risk scores, contributing signals, dependency exposure, and mitigation options. | P1 | 3 | FEAT-136, FEAT-171 |
| FEAT-178 | Status report | Periodic Project/Team status report: progress, upcoming milestones, blockers, and asks, in configurable cadence and audience tone. | P1 | 4 | FEAT-147, FEAT-171 |
| FEAT-179 | Blocker analysis | Analysis of blocked WorkItems: blocked-time distribution, systemic blocker categories, and longest-standing blockers with owners at team grain. | P1 | 4 | FEAT-091, FEAT-147 |
| FEAT-180 | Incident summary | Post-incident summary document: timeline, impact, contributing factors, and follow-up actions, drafted for human review. | P2 | 4 | FEAT-143, FEAT-171 |
| FEAT-181 | Tech debt report | TechnicalDebtItem and debt-ratio report per Repository/Project with prioritized remediation themes. | P2 | 4 | FEAT-144, FEAT-171 |
| FEAT-182 | Security findings report | SecurityFinding aging and posture report with severity roll-ups and remediation SLAs. | P2 | 4 | FEAT-149, FEAT-171 |
| FEAT-183 | Code quality report | Code-quality trend report (coverage, smells, duplication, gates) per Repository/Project. | P2 | 4 | FEAT-144, FEAT-171 |
| FEAT-184 | User manual generation | Generated user manual drafts from ingested Documents, Repository docs, and ApiEndpoint inventories. | P2 | 4 | FEAT-139, FEAT-171 |
| FEAT-185 | Use case diagram artifact | Generated use case diagrams with editable sources, versioned in the Artifacts Library. | P2 | 4 | FEAT-140, FEAT-172 |
| FEAT-186 | Architecture diagram artifact | Generated architecture diagrams with editable sources, versioned in the Artifacts Library. | P2 | 4 | FEAT-141, FEAT-172 |
| FEAT-187 | Dependency map | Generated dependency map across Epics/Projects/Services showing Dependency records, criticality, and risk overlay. | P1 | 4 | FEAT-094, FEAT-141 |
| FEAT-188 | API/service inventory | Generated inventory of Services and ApiEndpoints with ownership, environments, and change recency. | P2 | 4 | FEAT-141, FEAT-171 |
| FEAT-189 | Readiness reports | Release/Environment readiness reports combining release readiness score, QualityGate status, open Risks, and pending Dependencies. | P1 | 4 | FEAT-094, FEAT-147 |
| FEAT-190 | Change impact report | Analysis of a proposed or shipped change set: affected Services/ApiEndpoints, dependent teams, and risk assessment. | P2 | 4 | FEAT-141, FEAT-147 |
| FEAT-191 | Stakeholder comms draft | Drafted stakeholder communications (announcements, delay notices, incident comms) for human editing — never auto-sent. | P2 | 4 | FEAT-147, FEAT-171 |

## 12. Security & Audit

Security features that are product surface (secrets handling, audit, governance) as opposed to engineering practice (which the Definition of Done in `./AcceptanceCriteria.md` covers). Secrets are never in plaintext, always masked in UI, and every access is audited; the audit trail itself is append-only and tenant-scoped.

| ID | Name | Description | Priority | Phase | Dependencies |
|----|------|-------------|----------|-------|--------------|
| FEAT-195 | Secrets encryption | AES-256-GCM envelope encryption for all stored secrets with master key from env/file/Vault via a pluggable KMS SPI. Secrets are never persisted or logged in plaintext and are always masked in UI and API responses. | P0 | 0 | FEAT-006 |
| FEAT-196 | Secret rotation | Rotation workflows for data keys and individual secrets (re-wrap on master key rotation, connector credential rotation with health re-verification) without downtime. | P1 | 2 | FEAT-195 |
| FEAT-197 | Audit log | Append-only, tenant-scoped audit trail of authentication events, RBAC changes, configuration changes, secret access, data exports, report generation, and MCP/RAG access, with actor, timestamp, action, target, and outcome; queryable and exportable. | P0 | 0 | FEAT-001, FEAT-004 |
| FEAT-198 | LLM call audit | Dedicated audit of every LLM invocation: prompt (redacted per policy), model, provider, tokens, cost, latency, agent, and triggering user/job. | P0 | 3 | FEAT-197, FEAT-130 |
| FEAT-199 | Security hardening & certification checklist | Enterprise hardening: dependency and container scanning gates, TLS everywhere, CSP headers, penetration-test remediation, and a documented security certification checklist for customer security reviews. | P0 | 5 | FEAT-195, FEAT-197 |
| FEAT-200 | Metric governance guardrails | Enforced anti-goals: no individual-surveillance or stack-ranking views; individual-grain queries restricted; every metric surface shows context, uncertainty, and limitations; governance settings audited. | P0 | 2 | FEAT-090, FEAT-197 |

## 13. Observability

Self-observability of the platform, distinct from the observability data EIP ingests from customer systems (see the Prometheus/Grafana/OTLP connectors in section 4). The stack is OpenTelemetry SDK → OTel Collector → Prometheus + Grafana with optional Tempo/Loki, plus Micrometer metrics and structured JSON logging; every API request is traceable end-to-end into Kafka consumers via `traceparent`.

| ID | Name | Description | Priority | Phase | Dependencies |
|----|------|-------------|----------|-------|--------------|
| FEAT-205 | OpenTelemetry instrumentation | OTel SDK traces, metrics, and logs across API, workers, Kafka consumers, and connector calls, exported through the OTel Collector; `traceparent` propagated end-to-end including the event envelope. | P0 | 0 | FEAT-006 |
| FEAT-206 | Structured JSON logging | Structured JSON logs with tenant, trace, and module context; correlation with traces via trace/span IDs; optional Loki pipeline. | P0 | 0 | FEAT-205 |
| FEAT-207 | Platform metrics exposure | Micrometer metrics for JVM, HTTP, Kafka lag, job durations, connector sync outcomes, LLM token/cost counters, and cache hit rates, scraped by Prometheus. | P0 | 0 | FEAT-205 |
| FEAT-208 | Self-observability dashboards | Shipped Grafana dashboards (`/infra/grafana`) for platform health: API, workers, Kafka, PostgreSQL, Redis, MinIO, LLM providers, plus optional Tempo/Loki drill-through. | P0 | 0 | FEAT-207 |
| FEAT-209 | Health & readiness endpoints | Liveness/readiness/startup probes for app and workers, including dependency checks (DB, Kafka, Redis, object storage) with degraded-state reporting. | P0 | 0 | FEAT-006 |
| FEAT-210 | Platform SLO alerting | Alert rules on platform SLOs (API latency/error budget, ingestion lag, job failure rate, DLQ depth) wired to notification channels. | P1 | 2 | FEAT-207, FEAT-034 |

## 14. Cross-references

- Acceptance criteria for P0/P1 features: `./AcceptanceCriteria.md`.
- Phase mapping, deliverables, and exit criteria: `./Roadmap.md`.
- Domain model and entity definitions: `../architecture/DomainModel.md`.
