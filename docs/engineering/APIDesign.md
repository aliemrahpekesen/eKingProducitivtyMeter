# REST API Design

This document defines the REST API of the Engineering Intelligence Platform (EIP): design principles, authentication and authorization, versioning, the complete `/api/v1` endpoint catalog, worked examples, webhook intake, rate limiting, and OpenAPI conventions. Implementation details live in `BackendPlan.md`; the frontend consumer contract in `FrontendPlan.md`; entities in `../architecture/DomainModel.md`.

## 1. Principles

1. **Resource-oriented.** Nouns, plural, kebab-case paths; state changes via sub-resource actions (`POST .../sync`) only where a verb is genuinely a job, not a field update.
2. **Base path `/api/v1`.** OpenAPI 3 via springdoc at `/api/v1/openapi.json`, Swagger UI in `local`/`demo` profiles only.
3. **Tenant from auth context, never from the path.** The tenant is resolved from the OIDC token (or service token) and propagated to Postgres RLS (`eip-tenancy`). There is no `/tenants/{id}/...` prefix; `PLATFORM_ADMIN` cross-tenant operations use an explicit, audited `X-EIP-Act-As-Tenant` header.
4. **Cursor pagination with opaque cursors.** List responses are `{ "items": [...], "nextCursor": "opaque-base64", "hasMore": true }`. Cursors encode sort key + tie-breaker id, are signed, and expire; clients never construct or inspect them. Params: `cursor`, `limit` (default 50, max 200), `sort` (whitelisted fields).
5. **RFC 7807 errors.** Every non-2xx body is `application/problem+json` per the taxonomy in `BackendPlan.md` §10, always with `traceId`.
6. **Idempotency-Key header** (UUID) required on POSTs that create jobs (`/sync`, agent runs, report generation, index jobs, DLQ replay, exports). Same key + same body → same 200/202 with original resource; same key + different body → 409.
7. **ETag / If-Match for optimistic concurrency on config resources** (connectors, LLM providers, MCP servers, knowledge bases, metric definitions, dashboards, roles, report templates). GET returns `ETag` (entity `@Version`); PUT/PATCH/DELETE require `If-Match`, else 428; stale version → 412.
8. **Timestamps** are RFC 3339 UTC; durations ISO-8601; enums SCREAMING_SNAKE.
9. **Filtering** via typed query params (documented per endpoint), never a free-text query DSL on v1.

## 2. Authentication & authorization

- **OIDC bearer tokens** (Keycloak on-prem default; pluggable AD FS/Azure AD/Okta; local accounts fallback) for interactive users. `Authorization: Bearer <jwt>`.
- **Service tokens** for machine callers (CI systems, MCP clients, webhook-less pollers): long-lived, tenant-scoped, secret-store-backed, scope-limited, revocable, audited; sent as `Authorization: Bearer eip_svc_<token>`.
- Authorization is RBAC: roles carry fine-grained permissions (see `../product/Personas.md` §1); every endpoint below declares its required permission. Service-token scopes map 1:1 to permissions.

| Scope / permission | Grants |
|---|---|
| `tenant:manage` | Org/BU/tenant lifecycle (cross-tenant, `PLATFORM_ADMIN`) |
| `rbac:manage` | Roles, permissions, member-role assignment |
| `connector:manage` | Connector CRUD, test-connection, sync triggers, checkpoints |
| `connector:read` | Read connector configs (secrets masked) and health |
| `ingestion:operate` | Job control, DLQ inspect/replay |
| `workitem:read` | Work & Delivery read surface |
| `metric:read` | Metric definitions, metric queries, risk, forecasts |
| `dashboard:configure` | Saved views write |
| `agent:invoke` | Create agent runs; read own runs |
| `llm:configure` / `llm:audit` | LLM provider CRUD / LLM call audit read |
| `rag:manage` / `rag:query` | KB/source/index management / query endpoint |
| `mcp:manage` / `mcp:audit` | MCP server + capability config / MCP audit read |
| `report:generate` / `report:read` | Generation jobs / artifacts & exports read |
| `audit:read` | Audit query API |
| `system:operate` | Feature flags, system endpoints beyond public health |

## 3. Versioning & deprecation policy

- Path-versioned major (`/api/v1`); additive changes (new fields, endpoints, enum values clients must tolerate) are non-breaking within v1.
- Breaking changes require v2 side-by-side; v1 then enters deprecation: responses carry `Deprecation: true` and `Sunset: <RFC 3339>` headers plus a `Link: <migration doc>; rel="deprecation"`.
- Minimum deprecation window: 2 minor product releases or 6 months, whichever is longer (on-premise upgrade cadence).
- OpenAPI diff (CI stage 5, `BackendPlan.md` §16) blocks unannounced breaking changes.

## 4. Endpoint catalog

Response shape names refer to OpenAPI component schemas. All list endpoints support `cursor`/`limit`/`sort` (§1.4); those columns list only endpoint-specific params. `{id}`s are UUIDv7.

### 4.1 Tenancy & Admin

| Method | Path | Purpose | Key params | Response | Permission |
|---|---|---|---|---|---|
| GET | `/api/v1/organizations` | List organizations | `name` | `Page<OrganizationSummary>` | `tenant:manage` |
| POST | `/api/v1/organizations` | Create organization (tenant root) | body: `OrganizationCreate` | `Organization` | `tenant:manage` |
| GET/PUT/DELETE | `/api/v1/organizations/{id}` | Read / update / retire org | If-Match on PUT/DELETE | `Organization` | `tenant:manage` |
| GET/POST | `/api/v1/business-units` | List / create BusinessUnits | `organizationId` | `Page<BusinessUnit>` / `BusinessUnit` | `tenant:manage` (POST), `metric:read` (GET) |
| GET/PUT/DELETE | `/api/v1/business-units/{id}` | Manage BusinessUnit | If-Match | `BusinessUnit` | `tenant:manage` |
| GET/POST | `/api/v1/teams` | List / create Teams | `businessUnitId`, `name` | `Page<Team>` / `Team` | `rbac:manage` (POST), `metric:read` (GET) |
| GET/PUT/DELETE | `/api/v1/teams/{id}` | Manage Team | If-Match | `Team` | `rbac:manage` |
| GET/POST/DELETE | `/api/v1/teams/{id}/members` | List / add / remove Members | `memberId` (DELETE) | `Page<Member>` | `rbac:manage` |
| GET/POST | `/api/v1/members` | List / create Members (person ↔ identity mapping incl. `ExternalRef` aliases) | `teamId`, `identity` | `Page<Member>` / `Member` | `rbac:manage` |
| GET/PUT | `/api/v1/members/{id}` | Read / update Member (aliases, active state) | If-Match | `Member` | `rbac:manage` |
| GET/POST | `/api/v1/roles` | List / create Roles | — | `Page<Role>` / `Role` | `rbac:manage` |
| GET/PUT/DELETE | `/api/v1/roles/{id}` | Manage Role and its permission set | If-Match | `Role` | `rbac:manage` |
| GET | `/api/v1/permissions` | Catalog of fine-grained permissions | — | `PermissionCatalog` | `rbac:manage` |
| GET/POST | `/api/v1/users` | List / provision users (local accounts fallback) | `email`, `status` | `Page<User>` / `User` | `rbac:manage` |
| GET/PUT | `/api/v1/users/{id}` | Read / update user (roles, status) | If-Match | `User` | `rbac:manage` |
| POST | `/api/v1/service-tokens` | Issue service token (returned once) | body: `ServiceTokenCreate` (scopes, expiry) | `ServiceTokenIssued` | `rbac:manage` |
| DELETE | `/api/v1/service-tokens/{id}` | Revoke service token | — | 204 | `rbac:manage` |

### 4.2 Connectors & Data Sources

| Method | Path | Purpose | Key params | Response | Permission |
|---|---|---|---|---|---|
| GET | `/api/v1/connector-types` | Catalog of installed connector types + their JSON Schemas | — | `ConnectorTypeCatalog` | `connector:read` |
| GET/POST | `/api/v1/connectors` | List / create connector instances | `type`, `status` | `Page<ConnectorSummary>` / `Connector` | `connector:read` / `connector:manage` |
| GET/PUT/DELETE | `/api/v1/connectors/{id}` | Read (secrets masked) / update / delete | If-Match | `Connector` | `connector:read` / `connector:manage` |
| POST | `/api/v1/connectors/{id}/test-connection` | Run SPI `testConnection()` synchronously | — | `ConnectionTestResult` | `connector:manage` |
| POST | `/api/v1/connectors/{id}/sync` | Trigger sync job | Idempotency-Key; body: `{mode: FULL\|INCREMENTAL, streams?[]}` | 202 `JobRef` | `connector:manage` |
| GET | `/api/v1/connectors/{id}/checkpoints` | List per-stream checkpoints | `stream` | `Page<Checkpoint>` | `connector:read` |
| PUT | `/api/v1/connectors/{id}/checkpoints/{stream}` | Reset/set checkpoint (re-ingest window) | If-Match | `Checkpoint` | `connector:manage` |
| GET | `/api/v1/connectors/{id}/health` | SPI `healthCheck()` + circuit-breaker state + last sync | — | `ConnectorHealth` | `connector:read` |
| GET | `/api/v1/connectors/health` | Fleet health rollup | `type` | `ConnectorHealthSummary` | `connector:read` |

### 4.3 Ingestion & Jobs

| Method | Path | Purpose | Key params | Response | Permission |
|---|---|---|---|---|---|
| GET | `/api/v1/jobs` | List jobs (sync, index, report, agent, outbox, retention) | `kind`, `status`, `connectorId`, `from`,`to` | `Page<JobSummary>` | `ingestion:operate` |
| GET | `/api/v1/jobs/{id}` | Job detail incl. schedule | — | `Job` | `ingestion:operate` |
| POST | `/api/v1/jobs/{id}/cancel` | Request cooperative cancel | — | 202 `Job` | `ingestion:operate` |
| GET | `/api/v1/jobs/{id}/runs` | Run history | `status` | `Page<JobRun>` | `ingestion:operate` |
| GET | `/api/v1/job-runs/{runId}` | Run detail (timings, counts, errors, traceId) | — | `JobRun` | `ingestion:operate` |
| GET | `/api/v1/dlq/groups` | DLQ depth per consumer group | — | `DlqGroupList` | `ingestion:operate` |
| GET | `/api/v1/dlq/groups/{group}/messages` | Inspect DLQ messages (envelope + failure cause) | `from`,`to` | `Page<DlqMessage>` | `ingestion:operate` |
| POST | `/api/v1/dlq/groups/{group}/replay` | Replay selected/all messages | Idempotency-Key; body: `{messageIds?[], all?}` | 202 `JobRef` | `ingestion:operate` |
| DELETE | `/api/v1/dlq/groups/{group}/messages/{msgId}` | Discard poison message (audited) | — | 204 | `ingestion:operate` |

### 4.4 Work & Delivery

All read-only (canonical data is connector-owned). Common filters: `teamId`, `businessUnitId`, `projectId`, `from`, `to`, `updatedSince`, `externalSource`.

| Method | Path | Purpose | Key params | Response | Permission |
|---|---|---|---|---|---|
| GET | `/api/v1/work-items` | Query normalized `WorkItem`s | `+type (EPIC\|FEATURE\|STORY\|TASK\|BUG\|INCIDENT_TICKET)`, `state`, `sprintId`, `epicId`, `assigneeMemberId`, `blocked`, `labels` | `Page<WorkItem>` | `workitem:read` |
| GET | `/api/v1/work-items/{id}` | WorkItem detail incl. `ExternalRef`s, state history, links | — | `WorkItemDetail` | `workitem:read` |
| GET | `/api/v1/sprints` | List Sprints | `teamId`, `state`, `boardId` | `Page<Sprint>` | `workitem:read` |
| GET | `/api/v1/sprints/{id}` | Sprint detail (commitment vs done, scope churn events) | — | `SprintDetail` | `workitem:read` |
| GET | `/api/v1/boards` | List Boards | `teamId`, `kind (SCRUM\|KANBAN)` | `Page<Board>` | `board:read` |
| GET | `/api/v1/boards/{id}` | Board with WorkflowStates (WIP states) and current WIP | — | `BoardDetail` | `board:read` |
| GET | `/api/v1/releases` | List Releases | `productId`, `state`, `windowFrom`,`windowTo` | `Page<Release>` | `release:read` |
| GET | `/api/v1/releases/{id}` | Release detail (scope, readiness inputs, linked deployments) | — | `ReleaseDetail` | `release:read` |
| GET | `/api/v1/deployments` | List Deployments | `serviceId`, `environment`, `status`, `releaseId` | `Page<Deployment>` | `workitem:read` |
| GET | `/api/v1/incidents` | List Incidents | `severity`, `serviceId`, `state`, `slaBreached` | `Page<Incident>` | `workitem:read` |
| GET | `/api/v1/incidents/{id}` | Incident detail (timeline, linked deployments/alerts) | — | `IncidentDetail` | `workitem:read` |

### 4.5 Analytics

| Method | Path | Purpose | Key params | Response | Permission |
|---|---|---|---|---|---|
| GET | `/api/v1/metric-definitions` | Catalog: purpose, formula, inputs, grain, caveats/limitations, gaming risks | `category (FLOW\|DORA\|QUALITY\|DELIVERY_RISK\|OPS\|TEAM_HEALTH)` | `Page<MetricDefinition>` | `metric:read` |
| GET | `/api/v1/metric-definitions/{key}` | Single definition (e.g. `flow.cycle_time`, `dora.deployment_frequency`) | — | `MetricDefinition` | `metric:read` |
| POST | `/api/v1/metrics/query` | Metric query (grain / time-range / group-by contract, §5) | body: `MetricQueryRequest` | `MetricQueryResponse` | `metric:read` |
| GET | `/api/v1/risk-scores` | Delivery risk scores (epic delivery risk, dependency risk, release readiness score) | `subjectType (EPIC\|PROJECT\|RELEASE\|DEPENDENCY)`, `subjectId`, `minScore` | `Page<RiskScore>` | `risk:read` |
| GET | `/api/v1/risk-scores/{id}/explanation` | Score breakdown: signals, weights, uncertainty, limitations | — | `RiskExplanation` | `risk:read` |
| GET | `/api/v1/forecasts` | Project delay prediction / sprint completion forecasts | `subjectType`, `subjectId` | `Page<Forecast>` | `risk:read` |

### 4.6 Dashboards

| Method | Path | Purpose | Key params | Response | Permission |
|---|---|---|---|---|---|
| GET/POST | `/api/v1/dashboards` | List / create saved views (widget grid + global filters) | `persona`, `shared` | `Page<DashboardSummary>` / `Dashboard` | `metric:read` / `dashboard:configure` |
| GET/PUT/DELETE | `/api/v1/dashboards/{id}` | Read / update / delete saved view | If-Match | `Dashboard` | `metric:read` / `dashboard:configure` |
| POST | `/api/v1/dashboards/{id}/share` | Share with teams/roles | body: `ShareRequest` | `Dashboard` | `dashboard:configure` |

### 4.7 AI

| Method | Path | Purpose | Key params | Response | Permission |
|---|---|---|---|---|---|
| GET/POST | `/api/v1/llm-providers` | List / register providers (Ollama, vLLM, OpenAI-compatible generic, Anthropic-compatible, custom enterprise endpoint) | `kind`, `enabled` | `Page<LlmProvider>` / `LlmProvider` | `llm:configure` |
| GET/PUT/DELETE | `/api/v1/llm-providers/{id}` | Manage provider (config JSON-Schema validated; secrets masked) | If-Match | `LlmProvider` | `llm:configure` |
| POST | `/api/v1/llm-providers/{id}/test` | Probe endpoint + model list | — | `LlmProviderTestResult` | `llm:configure` |
| GET | `/api/v1/model-routes` | Per-tenant + per-agent model routing incl. fallbacks, token budgets | `agentKey` | `Page<ModelRoute>` | `llm:configure` |
| PUT | `/api/v1/model-routes/{id}` | Update route | If-Match | `ModelRoute` | `llm:configure` |
| GET | `/api/v1/agents` | Canonical agent catalog (Data Ingestion … Configuration Assistant) with enablement + guardrail config | `enabled` | `Page<AgentDefinition>` | `agent:invoke` |
| GET/PUT | `/api/v1/agents/{key}` | Read / configure agent (budgets, guardrails, tools) | If-Match | `AgentDefinition` | `agent:invoke` / `llm:configure` |
| POST | `/api/v1/agent-runs` | Create agent run (async job) | Idempotency-Key; body: `AgentRunCreate` (§6) | 202 `AgentRun` | `agent:invoke` |
| GET | `/api/v1/agent-runs` | List runs | `agentKey`, `status`, `from`,`to` | `Page<AgentRunSummary>` | `agent:invoke` |
| GET | `/api/v1/agent-runs/{id}` | Run detail: plan, steps, tool calls, tokens, cost, citations | — | `AgentRunDetail` | `agent:invoke` |
| GET | `/api/v1/agent-runs/{id}/events` | **SSE stream** of run progress (`text/event-stream`; events: `plan`, `step`, `tool_call`, `token_usage`, `guardrail`, `completed`, `failed`; `Last-Event-ID` resume) | — | SSE `AgentRunEvent` | `agent:invoke` |
| POST | `/api/v1/agent-runs/{id}/cancel` | Cancel run | — | 202 `AgentRun` | `agent:invoke` |
| GET/PUT | `/api/v1/prompts` , `/api/v1/prompts/{key}` | Versioned prompt templates per agent (list/read/update; history retained) | `agentKey`; If-Match | `Page<PromptTemplate>` / `PromptTemplate` | `llm:configure` |
| GET | `/api/v1/llm-calls` | LLM call audit (model, tokens, cost, latency; prompts redacted per policy) | `agentRunId`, `providerId`, `from`,`to` | `Page<LlmCallAudit>` | `llm:audit` |

### 4.8 RAG

| Method | Path | Purpose | Key params | Response | Permission |
|---|---|---|---|---|---|
| GET/POST | `/api/v1/knowledge-bases` | List / create KBs (embedding model, vector store: pgvector default / Qdrant, permission scope) | — | `Page<KnowledgeBase>` / `KnowledgeBase` | `rag:manage` |
| GET/PUT/DELETE | `/api/v1/knowledge-bases/{id}` | Manage KB | If-Match | `KnowledgeBase` | `rag:manage` |
| GET/POST | `/api/v1/knowledge-bases/{id}/sources` | List / attach sources (Confluence spaces, repos, file uploads, Generic File/Document connector) | `type` | `Page<RagSource>` / `RagSource` | `rag:manage` |
| DELETE | `/api/v1/knowledge-bases/{id}/sources/{sourceId}` | Detach source (purges chunks) | — | 204 | `rag:manage` |
| GET | `/api/v1/knowledge-bases/{id}/documents` | Ingested documents + chunk/index status | `sourceId`, `status` | `Page<RagDocument>` | `rag:manage` |
| POST | `/api/v1/knowledge-bases/{id}/index-jobs` | Trigger full or incremental re-index | Idempotency-Key; body: `{mode: FULL\|INCREMENTAL}` | 202 `JobRef` | `rag:manage` |
| GET | `/api/v1/knowledge-bases/{id}/index-jobs` | Index job history | `status` | `Page<JobSummary>` | `rag:manage` |
| POST | `/api/v1/knowledge-bases/{id}/query` | Permission-aware retrieval: tenant-isolated, metadata filters, source citations (audited) | body: `RagQueryRequest` (query, topK, filters) | `RagQueryResponse` (chunks + citations + scores) | `rag:query` |

### 4.9 MCP

| Method | Path | Purpose | Key params | Response | Permission |
|---|---|---|---|---|---|
| GET/POST | `/api/v1/mcp/servers` | List / register upstream MCP servers (EIP as MCP client; agent tools) | `enabled` | `Page<McpServer>` / `McpServer` | `mcp:manage` |
| GET/PUT/DELETE | `/api/v1/mcp/servers/{id}` | Manage server (transport, auth, tool allow-list) | If-Match | `McpServer` | `mcp:manage` |
| POST | `/api/v1/mcp/servers/{id}/test` | Handshake + list tools | — | `McpServerTestResult` | `mcp:manage` |
| GET | `/api/v1/mcp/exposed-capabilities` | Capabilities EIP exposes as MCP server (allow-listed internal capabilities) | `enabled` | `Page<ExposedCapability>` | `mcp:manage` |
| PUT | `/api/v1/mcp/exposed-capabilities/{key}` | Enable/disable + per-capability RBAC binding | If-Match | `ExposedCapability` | `mcp:manage` |
| GET | `/api/v1/mcp/invocations` | MCP invocation audit (both directions) | `serverId`, `capability`, `from`,`to` | `Page<McpInvocationAudit>` | `mcp:audit` |

### 4.10 Reports

| Method | Path | Purpose | Key params | Response | Permission |
|---|---|---|---|---|---|
| GET/POST | `/api/v1/report-templates` | List / create templates (sprint review, release notes, executive summary, incident analysis, …) | `category` | `Page<ReportTemplate>` / `ReportTemplate` | `report:read` / `report:generate` |
| GET/PUT/DELETE | `/api/v1/report-templates/{id}` | Manage template | If-Match | `ReportTemplate` | `report:generate` |
| POST | `/api/v1/report-jobs` | Create generation job (template + scope + schedule?) | Idempotency-Key; body: `ReportJobCreate` | 202 `JobRef` | `report:generate` |
| GET | `/api/v1/report-jobs` , `/api/v1/report-jobs/{id}` | List / read generation jobs | `templateId`, `status` | `Page<JobSummary>` / `Job` | `report:generate` |
| GET | `/api/v1/artifacts` | Artifact library (GeneratedReport instances) | `templateId`, `scopeType`, `from`,`to` | `Page<ArtifactSummary>` | `report:read` |
| GET | `/api/v1/artifacts/{id}` | Artifact metadata + citation map | — | `Artifact` | `report:read` |
| GET | `/api/v1/artifacts/{id}/versions` | Version history | — | `Page<ArtifactVersion>` | `report:read` |
| GET | `/api/v1/artifacts/{id}/content` | Download rendered content | `format (md\|pdf\|html\|json\|csv\|pptx)`, `version` | binary/stream | `report:read` |
| POST | `/api/v1/artifacts/{id}/exports` | Async export/conversion to another format | Idempotency-Key; body: `{format}` | 202 `JobRef` | `report:read` |

### 4.11 Audit

| Method | Path | Purpose | Key params | Response | Permission |
|---|---|---|---|---|---|
| GET | `/api/v1/audit-events` | Query audit trail (RBAC changes, secret access, config changes, LLM/MCP/RAG activity, DLQ discards, act-as-tenant) | `actorId`, `action`, `resourceType`, `resourceId`, `from`,`to`, `outcome` | `Page<AuditEvent>` | `audit:read` |
| GET | `/api/v1/audit-events/{id}` | Single event with before/after diff (secrets masked) | — | `AuditEventDetail` | `audit:read` |
| POST | `/api/v1/audit-events/exports` | Async export (json/csv) for compliance | Idempotency-Key; body: `{format, filter}` | 202 `JobRef` | `audit:read` |

### 4.12 System

| Method | Path | Purpose | Key params | Response | Permission |
|---|---|---|---|---|---|
| GET | `/api/v1/system/health` | Aggregate health: DB, Kafka, Redis, MinIO, vector store, worker heartbeats, connector fleet | — | `SystemHealth` | authenticated |
| GET | `/api/v1/system/info` | Version, build SHA, enabled modules, license/edition | — | `SystemInfo` | authenticated |
| GET | `/api/v1/system/feature-flags` | Effective feature flags for tenant | — | `FeatureFlagList` | authenticated |
| PUT | `/api/v1/system/feature-flags/{key}` | Toggle flag | If-Match | `FeatureFlag` | `system:operate` |
| GET | `/api/v1/system/queues` | Kafka consumer lag per group, DLQ depths | — | `QueueStats` | `system:operate` |
| GET | `/api/v1/system/cache` | Redis stats, rate-limiter budgets, lock table | — | `CacheStats` | `system:operate` |

## 5. Worked example 1 — metric query

`POST /api/v1/metrics/query` — the single analytics contract: metric keys + time range + grain + group-by + filters.

```json
{
  "metrics": ["flow.cycle_time", "dora.deployment_frequency"],
  "timeRange": { "from": "2026-05-01T00:00:00Z", "to": "2026-06-30T23:59:59Z" },
  "grain": "WEEK",
  "groupBy": ["teamId"],
  "filters": { "businessUnitId": "018f3c2e-9a41-7cc2-b8f1-2f6f0d6a9e10" },
  "options": { "includeDefinitions": true, "percentiles": [50, 85] }
}
```

```json
{
  "series": [
    {
      "metric": "flow.cycle_time",
      "unit": "hours",
      "group": { "teamId": "018f3c2e-1111-7abc-9d00-aa00bb11cc22" },
      "points": [
        { "bucketStart": "2026-05-04T00:00:00Z", "p50": 41.5, "p85": 96.0, "sampleSize": 23 },
        { "bucketStart": "2026-05-11T00:00:00Z", "p50": 38.2, "p85": 88.4, "sampleSize": 27 }
      ]
    }
  ],
  "definitions": [
    {
      "key": "flow.cycle_time",
      "purpose": "Time from work start to done for completed WorkItems.",
      "grain": "WorkItem, aggregated per team",
      "caveats": "Sensitive to board WorkflowState mapping; excludes items lacking start transitions.",
      "gamingRisks": "Splitting items artificially lowers cycle time; pair with throughput and scope churn."
    }
  ],
  "warnings": ["Team 'Atlas' has < 10 samples in 2 buckets; percentiles have high uncertainty."]
}
```

## 6. Worked example 2 — agent-run creation

`POST /api/v1/agent-runs` with `Idempotency-Key: 7f9d1a4e-...`:

```json
{
  "agentKey": "SPRINT_REVIEW",
  "input": { "sprintId": "018f3c2e-2222-7def-8a11-334455667788", "audience": "TEAM" },
  "budget": { "maxTokens": 120000, "maxToolCalls": 40, "maxDurationSeconds": 600 },
  "modelRouteOverride": null,
  "knowledgeBaseIds": ["018f3c2e-3333-70aa-bb22-99aabbccddee"]
}
```

Response `202 Accepted`, `Location: /api/v1/agent-runs/018f3c2e-4444-7bbb-cc33-001122334455`:

```json
{
  "id": "018f3c2e-4444-7bbb-cc33-001122334455",
  "agentKey": "SPRINT_REVIEW",
  "status": "QUEUED",
  "eventsUrl": "/api/v1/agent-runs/018f3c2e-4444-7bbb-cc33-001122334455/events",
  "createdAt": "2026-07-06T09:14:03Z"
}
```

The client then opens the SSE stream at `eventsUrl` (§4.7) or polls the run resource (`FrontendPlan.md` §5).

## 7. Worked example 3 — problem+json error

`PUT /api/v1/connectors/{id}` with a stale `If-Match`:

```json
{
  "type": "https://docs.eip.local/problems/precondition-failed",
  "title": "Precondition Failed",
  "status": 412,
  "detail": "Connector was modified by another request; refresh and retry with the current ETag.",
  "instance": "/api/v1/connectors/018f3c2e-5555-7ccc-dd44-556677889900",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "currentEtag": "\"17\""
}
```

Validation errors additionally carry `errors: [{ "field": "config.baseUrl", "message": "must be a valid https URL" }]`.

## 8. Webhook intake endpoints

Push-capable connectors receive events at an unauthenticated-path/verified-payload intake, separate from `/api/v1` (no bearer token — verification is per-source):

| Path | Source | Verification |
|---|---|---|
| `/webhooks/v1/{connectorId}/jira` | Jira | HMAC secret per connector instance |
| `/webhooks/v1/{connectorId}/github` | GitHub | `X-Hub-Signature-256` HMAC |
| `/webhooks/v1/{connectorId}/gitlab` | GitLab | `X-Gitlab-Token` shared secret |
| `/webhooks/v1/{connectorId}/bitbucket` | Bitbucket | HMAC secret |
| `/webhooks/v1/{connectorId}/sonarqube` | SonarQube | `X-Sonar-Webhook-HMAC-SHA256` |
| `/webhooks/v1/{connectorId}/generic` | Generic CI/CD & Generic REST | HMAC or mTLS per config |
| `/otlp/v1/{traces,metrics,logs}` | OpenTelemetry OTLP intake connector | mTLS / bearer per config |

Intake handlers do no processing: verify, wrap in the event envelope, write to `eip.raw.<connector>` via the outbox path, return 202 within 500ms. Replays are safe (dedup on eventId).

## 9. Rate limiting

Per-token and per-tenant limits enforced at the API layer (Redis-backed budgets shared with workers). Every response carries:

| Header | Meaning |
|---|---|
| `X-RateLimit-Limit` | Requests allowed in the current window for this token |
| `X-RateLimit-Remaining` | Remaining in window |
| `X-RateLimit-Reset` | Unix epoch seconds when the window resets |
| `Retry-After` | On 429 only; seconds to back off |

Expensive endpoints (`/metrics/query`, `/knowledge-bases/{id}/query`, exports) have separate, lower buckets; limits are configurable per tenant.

## 10. OpenAPI / springdoc conventions

- One OpenAPI document for the whole API, generated at build (CI stage 5) and committed diff-checked; tags = catalog areas above.
- **operationId naming:** `<area><Verb><Resource>` in lowerCamelCase — `connectorsTestConnection`, `analyticsQueryMetrics`, `agentRunsStreamEvents`, `reportsDownloadArtifactContent`. operationIds are API-stable: renaming one is a breaking change (they seed generated client method names).
- Component schema names match the Response column of the catalog; shared primitives (`Page*`, `Problem`, `JobRef`) live under `components.schemas` once.
- Every endpoint documents its required permission via a custom `x-eip-permission` extension (drives the frontend permission gates, `FrontendPlan.md` §2) and idempotency/ETag requirements via `x-eip-idempotent` / `x-eip-etag`.
- **Generated TypeScript client:** `openapi-typescript` + a thin typed fetch wrapper generated into `/frontend/src/api/generated` in CI; frontend code never hand-writes request/response types (`FrontendPlan.md` §1). SSE endpoints are declared with `text/event-stream` content and consumed via a hand-written typed SSE helper (codegen limitation), with event payload schemas still sourced from OpenAPI components.
