# Component Model

This document is the C4 level 3 view of the EIP backend: the components inside each Gradle module, their responsibilities, the public interfaces modules export to each other, the Kafka events each module publishes and consumes, and the data each module owns. It refines [ArchitectureOverview.md](./ArchitectureOverview.md) (§5 module map) and is consistent with the event contracts in [../engineering/EventModel.md](../engineering/EventModel.md), the entities in [DomainModel.md](./DomainModel.md), and the connector SPI in [../engineering/ConnectorFramework.md](../engineering/ConnectorFramework.md).

Conventions: each module exposes a public `api` package (Java interfaces + DTOs); everything else is module-private and enforced by Spring Modulith verification tests. Interface sketches below are illustrative design signatures, not final implementation.

## 1. eip-core — Domain Model and Shared Kernel

**Responsibilities:** canonical domain types, shared identifiers and envelopes, and SPIs that must be visible platform-wide. No business logic, no persistence, no Spring beans beyond configuration properties. Depends on nothing.

**Key components:**
- **CanonicalModel** — entity types from [DomainModel.md](./DomainModel.md): `WorkItem` supertype (type: `EPIC|FEATURE|STORY|TASK|BUG|INCIDENT_TICKET`), `ExternalRef` (sourceSystem, externalId, url), Repository, Commit, PullRequest, Build, Pipeline, Deployment, Release, Incident, Sprint, Board, WorkflowState, etc.
- **EventEnvelope** — `eventId (UUIDv7), tenantId, source, entityType, entityId, eventType, occurredAt, ingestedAt, schemaVersion, payload, traceparent`.
- **IdGenerator** — UUIDv7 generation (time-ordered, index-friendly).
- **VectorStore SPI** — `VectorStore` interface implemented by pgvector (default) and Qdrant adapters.
- **KmsProvider SPI** — master-key acquisition (env/file/Vault) for envelope encryption.
- **ProblemTypes / Result** — RFC 7807 problem+json error taxonomy, shared result types.

```java
public interface VectorStore {
    void upsert(TenantId tenant, String collection, List<EmbeddedChunk> chunks);
    List<ScoredChunk> search(TenantId tenant, String collection, float[] query,
                             MetadataFilter filter, int topK);
    void deleteBySource(TenantId tenant, String collection, DocumentSourceId source);
}
```

**Events:** none (defines the envelope; publishes nothing). **Data owned:** none (types only).

## 2. eip-tenancy — Tenants, RBAC, Audit, Secrets

**Responsibilities:** organizations and tenants, role-based access control (roles + fine-grained permissions, tenant-scoped), the append-only audit trail, and the secret vault.

**Key components:**
- **TenantDirectory** — CRUD for Organization/BusinessUnit/Team/Member; tenant lifecycle (provision, suspend); establishes the RLS `app.tenant_id` binding contract.
- **RbacService / PermissionEvaluator** — role and permission resolution; method-security integration; permission-set caching (Redis, 5 min TTL).
- **AuditTrail** — append-only audit writer + query API; consumes audit events from all modules; entries: actor, tenant, action, resource, outcome, timestamp, traceparent.
- **SecretVault** — AES-256-GCM envelope encryption over `KmsProvider`; versioned secrets, rotation, masked reads, audited access. Used by connectors (credentials) and `eip-ai` (provider API keys).
- **OidcIdentityBridge** — maps OIDC claims (Keycloak or brokered enterprise IdP) and local-account principals to Members and roles.

```java
public interface SecretVault {
    SecretRef store(TenantId tenant, String name, SecretValue value);
    SecretValue reveal(TenantId tenant, SecretRef ref, AccessContext ctx); // audited
    void rotate(TenantId tenant, SecretRef ref, SecretValue newValue);
}

public interface AuditTrail {
    void record(AuditEvent event);                       // append-only
    Page<AuditEntry> query(TenantId tenant, AuditFilter filter, Cursor cursor);
}
```

**Events:** publishes internal `audit.recorded` application events (persisted transactionally; see [DataFlow.md](./DataFlow.md) flow h); consumes none from Kafka. **Data owned:** `tenants`, `orgs`, `business_units`, `teams`, `members`, `roles`, `permissions`, `role_assignments`, `audit_log` (append-only, time-partitioned), `secrets` (ciphertext + key metadata).

## 3. eip-connectors — Connector SPI and Built-in Connectors

**Responsibilities:** the connector SPI (see [../engineering/ConnectorFramework.md](../engineering/ConnectorFramework.md)), JSON Schema-driven configuration, and the 18 built-in connectors (Jira, Confluence, GitHub, GitLab, Bitbucket, SonarQube, Artifactory, Kubernetes, OpenShift, Docker Registry, Prometheus, Grafana, OTLP intake, Generic CI/CD, Generic REST, Generic SQL, Generic File/Document, custom internal demand/project tool).

**Key components:**
- **ConnectorRegistry** — discovers connector plugins, exposes their JSON Schema config, instantiates configured connector instances per tenant.
- **ConnectorConfigService** — validates configs against JSON Schema (`validate()`), stores them with credentials in `SecretVault`, runs `testConnection()`.
- **ConnectorHealthMonitor** — periodic `healthCheck()` per instance; feeds admin UI status and circuit-breaker state.
- **RateLimiterFactory** — per-instance token-bucket limiters (Redis-backed) honoring source-tool limits.
- **WebhookVerifier** — signature verification for inbound webhooks per connector type.
- **SimulationConnector support** — every connector offers a simulation/mock mode for demo/data packs.

```java
public interface Connector {
    ConnectorDescriptor descriptor();                    // id, JSON Schema, capabilities
    ValidationResult validate(JsonNode config);
    TestResult testConnection(ConnectorContext ctx);
    HealthStatus healthCheck(ConnectorContext ctx);
    SyncResult fullSync(ConnectorContext ctx, RawEmitter emitter);
    SyncResult incrementalSync(ConnectorContext ctx, Checkpoint checkpoint, RawEmitter emitter);
    Optional<WebhookHandler> webhookHandler();
}
```

**Events:** publishes to `eip.raw.<connector>` (raw records, via `RawEmitter` handed in by `eip-ingestion`); consumes none. **Data owned:** `connector_instances`, `connector_configs` (JSON, credentials as `SecretRef`), `connector_health`.

## 4. eip-ingestion — Sync Engine, Staging, Normalization

**Responsibilities:** orchestrates connector syncs, stages raw data, normalizes into the canonical model, and publishes domain events. Implements the pattern: collectors → raw staging (`raw_*` JSONB + object storage for blobs) → normalizers → canonical model → domain events on Kafka.

**Key components:**
- **SyncScheduler** — cron/interval scheduling of full and incremental syncs per connector instance; Redisson leases prevent concurrent syncs of the same instance; enqueues `SyncJob`s.
- **SyncJobExecutor** (workers) — runs `fullSync`/`incrementalSync` through the connector's rate limiter and circuit breaker; retries with exponential backoff + jitter.
- **CheckpointStore** — checkpoint table per connector+stream (cursor, watermark, updatedAt); committed transactionally with staged raw batches.
- **RawStagingWriter** — persists raw records to `raw_<connector>` JSONB tables, large payloads/blobs to MinIO; produces to `eip.raw.<connector>` with dedup keys.
- **NormalizationPipeline** — per-connector mappers raw → canonical (`WorkItem`, `Commit`, `PullRequest`, `Build`, `Deployment`, `Incident`, …); resolves `ExternalRef` identity, dedups, performs idempotent canonical upserts, and emits domain events via the transactional outbox.
- **WebhookIntakeService** (app) — receives verified webhooks, wraps them as raw records onto `eip.raw.<connector>` for the same normalization path.
- **DlqReplayService** — operator-facing inspection and replay of `.<group>.dlq` messages.

```java
public interface CheckpointStore {
    Optional<Checkpoint> load(ConnectorInstanceId id, String stream);
    void commit(ConnectorInstanceId id, String stream, Checkpoint next); // tx with staged batch
}

public interface NormalizationPipeline {
    NormalizationResult normalize(RawRecordBatch batch); // upserts + outbox events
}
```

**Events:** produces `eip.raw.<connector>`; consumes `eip.raw.<connector>`; produces `eip.domain.workitem`, `eip.domain.scm`, `eip.domain.cicd`, `eip.domain.quality`, `eip.domain.ops`; parks failures on `eip.raw.<connector>.<group>.dlq`. **Data owned:** `raw_*` staging tables (JSONB, time-partitioned), `checkpoints`, `sync_jobs`, `external_refs`, canonical entity tables (write ownership; other modules read via domain events, never via SQL joins), `outbox_events`.

```mermaid
flowchart LR
    subgraph eip-app
        WS[WebhookIntakeService]
        SS[SyncScheduler]
    end
    subgraph eip-connectors
        CR[ConnectorRegistry]
        RL[RateLimiterFactory]
    end
    subgraph eip-workers runtime
        SJE[SyncJobExecutor]
        RSW[RawStagingWriter]
        NP[NormalizationPipeline]
        CS[CheckpointStore]
    end
    K1[(eip.raw.* topics)]
    K2[(eip.domain.* topics)]
    PG[(PostgreSQL: raw_*, checkpoints, canonical, outbox)]
    S3[(MinIO blobs)]

    SS -->|SyncJob| SJE
    SJE --> CR
    CR --> RL
    SJE --> RSW
    RSW --> PG
    RSW --> S3
    RSW --> K1
    WS --> K1
    K1 --> NP
    NP --> PG
    NP -->|outbox relay| K2
    SJE <--> CS
    CS --> PG
```

## 5. eip-analytics — Metric Engines, Risk Scoring, Read Models

**Responsibilities:** compute the canonical metric set (flow, DORA, quality, delivery risk, ops, team health — team-level only, anti-toxic-ranking by design) from domain events, maintain materialized read models for dashboards, and score delivery risks.

**Key components:**
- **MetricEngine** — consumes `eip.domain.*`; incrementally updates metric facts per definition (velocity, throughput, cycle time, lead time, WIP, flow efficiency, blocked time, sprint predictability, scope churn; deployment frequency, lead time for changes, change failure rate, MTTR; coverage, code smells, duplication, quality gate status, escaped defects, bug aging, technical debt ratio, security finding aging; incident frequency/impact, SLO health, alert noise; load balance, review bottlenecks, knowledge concentration).
- **MetricDefinitionCatalog** — declarative registry: purpose, formula, inputs, grain, caveats/limitations, gaming risks for every metric; served to the UI so caveats render beside every chart.
- **ReadModelProjector** — maintains `rm_*` aggregates (team/sprint/day grain) consumed by dashboard APIs; rebuildable by replaying `eip.domain.*`.
- **RiskScoringService** — epic delivery risk, project delay prediction, dependency risk, release readiness score; combines multiple signals with confidence intervals; emits `Risk` entities and alerts.
- **MetricQueryService** — the module's exported read interface (used by `eip-app` dashboards, `eip-ai` agents, `eip-reports`).

```java
public interface MetricQueryService {
    MetricSeries series(TenantId t, MetricId metric, Grain grain, Scope scope, TimeRange range);
    SprintSummary sprintSummary(TenantId t, SprintId sprint);
    List<RiskAssessment> risks(TenantId t, RiskScope scope);
    MetricDefinition definition(MetricId metric);        // formula, caveats, gaming risks
}
```

**Events:** consumes `eip.domain.workitem`, `eip.domain.scm`, `eip.domain.cicd`, `eip.domain.quality`, `eip.domain.ops`; produces `eip.analytics.metrics` (metric-updated / risk-detected events); DLQ `eip.domain.<topic>.analytics.dlq` per consumed topic. **Data owned:** `metric_facts` (time-partitioned), `rm_*` read-model tables, `metric_definitions`, `risk_assessments`, `analytics_watermarks` (per-partition processed offsets for rebuild safety).

## 6. eip-ai — Agents, LLM Providers, RAG, MCP

**Responsibilities:** agent runtime (plan/execute with tool-calling, budgets, guardrails), LLM provider SPI with per-tenant + per-agent routing, the RAG pipeline, and MCP client/server integration. Default orchestration is Java + LangChain4j (ADR-006); optional Python workers hang off `eip.ai.jobs`/`eip.ai.results`.

**Key components:**
- **AgentOrchestrator** — plans and executes agent runs (LangChain4j); enforces step limits, token budgets, and guardrails; supports the canonical agents: Data Ingestion, Data Quality, Engineering Metrics, Delivery Risk, Sprint Review, Release Notes, Documentation, Use Case Diagram, Architecture Diagram, Executive Summary, Incident Analysis, Code Quality, Team Health, RAG Retrieval, Report Composition, Validation, Security Review, Configuration Assistant.
- **AgentToolbox** — typed tools agents may call: `MetricQueryService`, `RetrievalService`, `McpClientGateway`, report drafting utilities; every tool call is RBAC-checked in the caller's tenant context.
- **LlmProviderRegistry** — LLM provider SPI (Ollama, vLLM OpenAI-compatible, OpenAI-compatible generic, Anthropic-compatible, custom enterprise endpoint); per-tenant + per-agent model routing, fallback chains, token budgets, circuit breakers.
- **LlmCallAuditor** — audits every LLM call (prompt, model, tokens, cost, latency; prompts redacted per policy) into `AuditTrail` + `llm_calls`.
- **RagIndexer** — document ingestion → chunking → embedding (configurable model) → `VectorStore`; incremental re-indexing on domain/document events; scheduled full re-index.
- **RetrievalService** — permission-aware, tenant-isolated retrieval with metadata filters and source citations; audit-logged.
- **McpClientGateway** — connects to enterprise MCP servers and exposes their tools to agents (allow-listed, audited).
- **McpServerEndpoint** (hosted in `eip-app`) — exposes selected, allow-listed internal capabilities as an MCP server with per-capability RBAC + audit.

```java
public interface AgentOrchestrator {
    AgentRunId submit(TenantId t, AgentType agent, AgentInput input, Budget budget);
    AgentRunStatus status(AgentRunId id);
}

public interface RetrievalService {
    RetrievalResult retrieve(TenantId t, Principal caller, Query q, MetadataFilter f, int topK);
    // result chunks carry source citations; access filtered by caller permissions
}
```

**Events:** consumes `eip.ai.jobs` (agent/RAG/embedding jobs), `eip.domain.workitem`, `eip.domain.scm`, `eip.domain.quality` (incremental re-index triggers), `eip.analytics.metrics` (risk-narrative triggers); produces `eip.ai.jobs` (fan-out sub-jobs), `eip.ai.results`; DLQ `eip.ai.jobs.ai.dlq`. **Data owned:** `agent_runs`, `agent_steps`, `llm_calls`, `llm_providers` (routing config; keys via `SecretVault`), `rag_documents`, `rag_chunks` + `rag_embeddings` (pgvector default), `rag_index_state`, `mcp_capabilities` (allow-lists).

```mermaid
flowchart TB
    subgraph eip-ai
        AO[AgentOrchestrator]
        TB2[AgentToolbox]
        LPR[LlmProviderRegistry]
        LCA[LlmCallAuditor]
        RI[RagIndexer]
        RS[RetrievalService]
        MCG[McpClientGateway]
    end
    subgraph eip-app
        MSE[McpServerEndpoint]
        API[Agent REST API]
    end
    JOBS[(eip.ai.jobs)]
    RES[(eip.ai.results)]
    VS[(VectorStore SPI: pgvector / Qdrant)]
    LLM[Ollama / vLLM / OpenAI-compat / Anthropic-compat]
    EXTMCP[Enterprise MCP servers]
    AUD[AuditTrail eip-tenancy]
    MQ[MetricQueryService eip-analytics]

    API --> JOBS
    JOBS --> AO
    AO --> TB2
    TB2 --> MQ
    TB2 --> RS
    TB2 --> MCG
    MCG --> EXTMCP
    AO --> LPR
    LPR --> LLM
    LPR --> LCA
    LCA --> AUD
    RI --> VS
    RS --> VS
    AO --> RES
    MSE --> TB2
```

## 7. eip-reports — Report Engine, Exports, Artifact Library

**Responsibilities:** report templates and rendering, scheduled and on-demand report jobs, exports (Markdown/HTML/PDF/PPTX/diagrams), and the tenant artifact library.

**Key components:**
- **ReportTemplateEngine** — versioned templates binding metric queries, agent-composed narrative sections, and rendering directives; renders to target formats.
- **ReportJobService** — accepts on-demand and scheduled report requests; publishes `eip.reports.jobs`; tracks job state.
- **ReportScheduler** — cron schedules per tenant/report; Redisson-leased to fire exactly one job per schedule tick.
- **ReportCompositionCoordinator** (workers) — drives the Report Composition agent and the Validation agent (via `AgentOrchestrator`), assembles sections, invokes rendering.
- **ArtifactStore** — persists rendered artifacts to MinIO (tenant-prefixed), records metadata (`GeneratedReport`), serves presigned download URLs, applies retention policy.
- **DeliveryService** — pushes finished reports to notification channels (email/webhook) per subscription.

```java
public interface ReportJobService {
    ReportJobId submit(TenantId t, ReportTemplateId template, ReportParams params, Trigger trigger);
    ReportJobStatus status(ReportJobId id);
}

public interface ArtifactStore {
    ArtifactRef store(TenantId t, GeneratedReport meta, ContentStream content);
    Url presignedDownload(TenantId t, ArtifactRef ref, Duration ttl);
}
```

**Events:** produces and consumes `eip.reports.jobs`; consumes `eip.ai.results` (agent outputs for compositions); DLQ `eip.reports.jobs.reports.dlq`. **Data owned:** `report_templates`, `report_jobs`, `report_schedules`, `generated_reports` (artifact metadata; binaries in MinIO), `report_subscriptions`.

## 8. eip-app — Composition Root / Main API Application

**Responsibilities:** the deployable API application. Owns HTTP concerns only; delegates everything to module `api` interfaces.

**Key components:**
- **ApiGatewayLayer** — REST `/api/v1` controllers per module facade; OpenAPI 3 (springdoc); cursor pagination; RFC 7807 problem+json; idempotency keys on mutating batch endpoints.
- **SecurityConfig / TenantContextFilter** — OIDC (Keycloak default), RBAC enforcement via `PermissionEvaluator`, binds `app.tenant_id` for RLS per request.
- **WebhookController** — inbound connector webhooks → `WebhookVerifier` → `WebhookIntakeService`.
- **McpServerEndpoint** — MCP server surface (allow-listed capabilities; §6).
- **OutboxRelay** — polls `outbox_events` and publishes to Kafka with the event envelope (shared component, active in both runtimes for their own writes).
- **AdminConsoleApi** — tenants, connectors (JSON Schema-driven config forms), model routing, DLQ replay, health.

**Events:** produces `eip.ai.jobs`, `eip.reports.jobs`, `eip.raw.<connector>` (webhook intake), and domain events via outbox; consumes none (workers do). **Data owned:** none beyond composition (each table belongs to a domain module).

## 9. eip-workers — Async Worker Runtime

**Responsibilities:** the second deployable. No business logic of its own; it activates consumer components contributed by `eip-ingestion`, `eip-analytics`, `eip-ai`, and `eip-reports` and provides the operational chassis.

**Key components:**
- **WorkerRuntime** — profile-driven activation (`workers.pipelines=ingestion,normalization,analytics,ai,reports`) so operators can run mixed or dedicated pools.
- **ConsumerGroupManager** — consumer group wiring, concurrency per topic partition count, pause/resume on backpressure signals.
- **RetryAndDlqSupport** — bounded retries with exponential backoff + jitter, then park to `<topic>.<group>.dlq` with error context headers.
- **IdempotencyGuard** — dedup on `eventId` (Redis fast path + `processed_events` durable store) before any consumer handler runs.
- **WorkerHealthEndpoints** — liveness/readiness, per-pipeline lag gauges via Micrometer/OTel.

**Events:** hosts all consumers listed in §§4–7. **Data owned:** `processed_events` (dedup ledger, time-partitioned).

## 10. Module Dependency Matrix

Rows depend on columns. `api` = compile-time dependency on the column module's exported interfaces; `evt` = Kafka events only; `—` = no dependency (forbidden).

| depends on → | eip-core | eip-tenancy | eip-connectors | eip-ingestion | eip-analytics | eip-ai | eip-reports |
|--------------|----------|-------------|----------------|---------------|---------------|--------|-------------|
| **eip-core** | — | — | — | — | — | — | — |
| **eip-tenancy** | api | — | — | — | — | — | — |
| **eip-connectors** | api | api (SecretVault, AuditTrail) | — | — | — | — | — |
| **eip-ingestion** | api | api (AuditTrail) | api (Connector SPI) | — | — | — | — |
| **eip-analytics** | api | api (AuditTrail) | — | evt (`eip.domain.*`) | — | — | — |
| **eip-ai** | api | api (SecretVault, AuditTrail, RBAC) | — | evt (`eip.domain.*`) | api (MetricQueryService) + evt (`eip.analytics.metrics`) | — | — |
| **eip-reports** | api | api (AuditTrail, RBAC) | — | — | api (MetricQueryService) | api (AgentOrchestrator) + evt (`eip.ai.results`) | — |
| **eip-app** | api | api | api | api | api | api | api |
| **eip-workers** | api | api | api | api | api | api | api |

Composition roots (`eip-app`, `eip-workers`) may depend on everything; all other cells not listed are forbidden and verified by Spring Modulith tests.

## 11. Events per Module — Summary

| Module | Produces | Consumes | DLQs |
|--------|----------|----------|------|
| eip-connectors | `eip.raw.<connector>` (via ingestion's RawEmitter) | — | — |
| eip-ingestion | `eip.raw.<connector>`, `eip.domain.workitem`, `eip.domain.scm`, `eip.domain.cicd`, `eip.domain.quality`, `eip.domain.ops` | `eip.raw.<connector>` | `eip.raw.<connector>.normalization.dlq` |
| eip-analytics | `eip.analytics.metrics` | `eip.domain.workitem`, `eip.domain.scm`, `eip.domain.cicd`, `eip.domain.quality`, `eip.domain.ops` | `eip.domain.<name>.analytics.dlq` |
| eip-ai | `eip.ai.jobs`, `eip.ai.results` | `eip.ai.jobs`, `eip.domain.workitem`, `eip.domain.scm`, `eip.domain.quality`, `eip.analytics.metrics` | `eip.ai.jobs.ai.dlq` |
| eip-reports | `eip.reports.jobs` | `eip.reports.jobs`, `eip.ai.results` | `eip.reports.jobs.reports.dlq` |
| eip-app | `eip.ai.jobs`, `eip.reports.jobs`, `eip.raw.<connector>` (webhooks), outbox-relayed domain events | — | — |

All messages use the shared event envelope, are keyed `tenantId+entityId`, and follow at-least-once delivery with idempotent consumers (dedup on `eventId`). Full schemas and versioning rules: [../engineering/EventModel.md](../engineering/EventModel.md).
