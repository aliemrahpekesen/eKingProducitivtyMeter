# Database Plan

PostgreSQL 16 physical design for the **Engineering Intelligence Platform (EIP)**. This document translates the logical model in `../architecture/DomainModel.md` into schemas, DDL conventions, indexing, partitioning, tenancy enforcement (RLS), Flyway migration practice, read models, capacity planning, retention, pgvector setup, and connection pooling. Backup/restore procedures are operational and live in `../operations/OperationsGuide.md`; this document defines the hooks they rely on.

PostgreSQL 16 is the single primary store. pgvector is the default vector store (Qdrant is a pluggable alternative behind the VectorStore SPI and does not change this plan). Redis holds only cache/locks/rate-limit state — nothing in Redis is durable-of-record.

## 1. Schema Conventions

| Convention | Rule |
|---|---|
| Naming | `snake_case` for schemas, tables, columns, indexes, constraints. Tables singular-noun (`work_item`, not `work_items`). Indexes `ix_<table>_<cols>`, unique `ux_`, FKs `fk_<table>_<ref>`, checks `ck_`. |
| Primary keys | `id uuid` **UUIDv7**, generated in the application (`eip-core` Uuid7Generator); DB default `gen_random_uuid()` exists only as a safety net for manual inserts. Time-ordered UUIDv7 keeps B-tree appends local and makes keyset/cursor pagination natural. |
| Tenancy | Every tenant-scoped table carries `tenant_id uuid NOT NULL`. Global tables (e.g., `core.tenant`, Flyway history) are the enumerated exception. RLS enforces isolation (§5). |
| Timestamps | `created_at timestamptz NOT NULL DEFAULT now()`, `updated_at timestamptz NOT NULL DEFAULT now()` maintained by a shared trigger `core.tg_touch_updated_at()`. All times UTC. |
| Soft delete | `deleted_at timestamptz NULL` on mutable domain tables. Rows are never hard-deleted by application code; retention jobs (§10) hard-delete after policy windows. Append-only tables (`audit_event`, `work_item_transition`, `metric_fact`) have no `deleted_at`. Unique indexes on soft-deletable tables are partial: `WHERE deleted_at IS NULL`. |
| Enums | Postgres `text` + `CHECK` constraints (not native enums) so value additions are expand-only migrations. |
| JSONB | Source-specific and flexible payloads (`custom_fields`, `payload`, `labels_kv`) are `jsonb`; anything queried by analytics gets promoted to a real column. |
| FKs | Enforced inside a schema; cross-schema/context references are plain `uuid` columns validated at the application layer (module extractability, per DomainModel §3 and §14 rule 6). |

## 2. Schema Organization & Table Ownership Catalog

This section is the **single authoritative table → schema → owning-module catalog**. Every table has exactly one owning module. `ComponentModel.md` "Data owned" lists and each module's `MODULE.md` "Owned tables" must be subsets of the rows below (docs-lint rule in `../../engineering-operating-system/ModuleOwnership.md`). Physical names follow the §1 singular convention; where other documents use logical/plural names (`metric_facts`, `llm_calls`, `rag_embeddings`), the mapping is stated in the Notes column.

Schema-level summary: `work`/`scm`/`cicd`/`quality`/`ops` are the canonical schemas — **write side owned by `eip-ingestion`**; `eip-analytics` reads them via a dedicated read-only DB grant (recomputation/rollups only; reads of `staging.raw_*` and any canonical writes are forbidden), and derived canonical columns are written only through `eip-ingestion`'s exported `CanonicalEnrichmentService` (ADR-019). `analytics` is owned by `eip-analytics`, `ai` by `eip-ai`, `reports` by `eip-reports`, `audit` by `eip-tenancy`, `staging` by `eip-ingestion`; `core` ownership is per table below.

| Table | Owning module | RLS | Partitioning | Notes |
|---|---|---|---|---|
| `core.tenant` | eip-tenancy | no (platform) | — | Enumerated RLS exception |
| `core.organization`, `core.business_unit`, `core.team`, `core.role` | eip-tenancy | yes | — | |
| `core.member` | eip-tenancy | yes | — | PII columns `display_name`, `primary_email` — FR-142 erasure targets (§10.1) |
| `core.member_identity` | eip-tenancy | yes | — | PII columns `email`, `display_name` — FR-142 erasure targets (§10.1) |
| `core.secret` | eip-tenancy | yes | — | AES-256-GCM envelope encryption (§3) |
| `core.service_token` | eip-app | **no** (hybrid platform+tenant, app-enforced) | — | SecurityModel §3; SHA-256 hash, `tenant_id NULL` == platform-scoped; enumerated no-RLS exception (ADR-025) despite carrying tenant-scoped rows too — see §2 note below |
| `core.tenant_role_permission_override` | eip-app | yes | — | SecurityModel §4, ADR-026; tenant-editable delta on top of `Role.permissions()`'s fixed default matrix — PK `(tenant_id, role, permission)`, uniformly tenant-scoped (standard RLS, unlike `core.service_token`) |
| `core.external_ref` | eip-ingestion | yes | — | Hottest ingestion path (§4); carries `external_key` + `key_aliases` (DomainModel §2.2) |
| `core.entity_link` | eip-ingestion | yes | — | Correlation edges (DomainModel §2.4, FR-036); §3 DDL, §9 volume row |
| `core.connector` | eip-connectors | yes | — | |
| `core.connector_checkpoint` | eip-connectors | yes | — | |
| `core.event_outbox` | eip-core (infra-owned, accessed via core services only) | yes | — | Transactional outbox (ADR-017); relayed by the OutboxRelay of the runtime that wrote the row (`eip-app` and `eip-workers` each relay their own writes) |
| `core.processed_events` | eip-core (infra-owned, accessed via core services only) | yes | RANGE daily on `processed_at` | Consumer dedup ledger; disposal = partition drop after 35 d (§6, §10) |
| `core.worker_heartbeat` | eip-core (infra-owned, accessed via core services only) | no (platform) | — | Enumerated RLS exception |
| `work.work_item`, `work.sprint`, `work.board`, `work.board_column`, `work.workflow_state`, `work.dependency`, `work.risk` | eip-ingestion (write side) | yes | — | `workflow_state` is project/workflow-scoped; `board_column` holds the board column→state mapping (DomainModel §5) |
| `work.work_item_transition` | eip-ingestion (write side) | yes | Deferred: monthly RANGE when > ~200M rows (§6) | Append-only |
| `work.project`, `work.product`, `work.roadmap`, `work.initiative` | eip-ingestion (write side) | yes | — | Modeled in DomainModel §4 (Organization context); stored here — this catalog is authoritative for placement |
| `scm.repository`, `scm.branch`, `scm.commit`, `scm.pull_request`, `scm.code_review` | eip-ingestion (write side) | yes | — | `commit.message` is an FR-142 redaction target (§10.1) |
| `cicd.pipeline`, `cicd.build`, `cicd.artifact`, `cicd.environment`, `cicd.deployment`, `cicd.release` | eip-ingestion (write side) | yes | — | `deployment.caused_incident`, `release.readiness_score` are enrichment-owned columns (ADR-019) |
| `quality.quality_gate`, `quality.security_finding`, `quality.technical_debt_item`, `quality.coverage_snapshot` | eip-ingestion (write side) | yes | — | |
| `ops.service`, `ops.api_endpoint`, `ops.incident`, `ops.alert`, `ops.log_reference`, `ops.trace_reference`, `ops.sla_slo` | eip-ingestion (write side) | yes | — | |
| `analytics.metric_definition` | eip-analytics | yes | — | DomainModel §9 `Metric` entity (`metric_definitions` in DataFlow §4); carries `active_version` (FR-062) |
| `analytics.metric_fact` | eip-analytics | yes | RANGE monthly on `bucket_at` | The `metric_facts` read model (DataFlow §4); formerly sketched as `ops.metric_value`; PK includes `definition_version` (FR-062, §3) |
| `analytics.rm_sprint_summary`, `analytics.rm_release_readiness`, `analytics.rm_team_flow_current`, `analytics.rm_team_flow_daily`, `analytics.rm_dora_daily`, `analytics.rm_quality_snapshot`, `analytics.rm_ops_health` | eip-analytics | yes | — | Projector-maintained read models (ADR-015, §8); replace the former `reports.mv_*` materialized views |
| `analytics.risk_assessment` | eip-analytics | yes | — | Risk-engine scores (`risk_assessments` in DataFlow); distinct from the `work.risk` register |
| `analytics.analytics_watermark` | eip-analytics | no (platform) | — | Per-(group, topic, partition) processed offsets for read-model rebuild safety (`analytics_watermarks` in DataFlow §4); enumerated RLS exception |
| `ai.rag_document` | eip-ai | yes | — | Document registry; `acl_hash` = document-level permission fingerprint |
| `ai.rag_chunk` | eip-ai | yes | — | Chunk text + typed filter columns incl. `acl_refs text[]` (§11; `RAGArchitecture.md` §5/§7 is source of record for this schema family) |
| `ai.rag_chunk_embedding_<modelKey>` | eip-ai | yes | — | One table per (embeddingModelId, dimension) embedding space (§11); `rag_embeddings` in ComponentModel |
| `ai.rag_index_state` | eip-ai | yes | — | Per-document/per-space index state (DataFlow Flow F) |
| `ai.rag_acl_grant` | eip-ai | yes | — | Normalized source-ACL grants backing `acl_refs` (`RAGArchitecture.md`) |
| `ai.rag_retrieval_audit` | eip-ai | yes | RANGE monthly on `occurred_at` | Immutable retrieval audit (`RAGArchitecture.md` §13) |
| `ai.llm_provider` | eip-ai | yes | — | Provider/routing registry (`llm_providers` in ComponentModel §6); credentials via `core.secret`, never inline |
| `ai.llm_call_audit` | eip-ai | yes | — | `llm_calls` in DataFlow |
| `ai.agent_run` | eip-ai | yes | — | Run state — source of truth for job claim (`AgentArchitecture.md` §3.5) |
| `ai.agent_step` | eip-ai | yes | — | Per-step checkpoints (`agent_steps` in DataFlow Flow G) |
| `ai.mcp_capability_grant` | eip-ai | yes | — | `mcp_capabilities` in ComponentModel |
| `reports.report_template`, `reports.report_schedule`, `reports.report_subscription` | eip-reports | yes | — | |
| `reports.report_job` | eip-reports | yes | — | Process state machine `PENDING → … → DELIVERED\|FAILED` (DataFlow §7; DomainModel §11) |
| `reports.generated_report` | eip-reports | yes | — | Artifact metadata; binaries in MinIO |
| `audit.audit_event` | eip-tenancy | yes | RANGE monthly on `occurred_at` | Append-only, hash-chained (§3); `detail` carries UUIDs/enums only — never PII (§10.1) |
| `staging.raw_<connector>` | eip-ingestion | yes | RANGE weekly on `ingested_at` | JSONB landing tables |
| `staging.webhook_intake_buffer` | eip-ingestion | yes | — | Bounded Kafka-outage buffer for verified webhooks (DataFlow §3; depth gauge `eip_webhook_buffer_depth`) |
| `staging.raw_ingest_errors` | eip-ingestion | yes | — | Single raw/normalization dead-letter table (`stage` column distinguishes raw-intake vs normalization failures; same name used in `ConnectorFramework.md`) |
| `quartz.qrtz_*` | eip-core (infra-owned, accessed via core services only) | no (platform) | — | Quartz clustered JDBC store; tenant scoping lives in job data, not Quartz rows |

**Enumerated no-RLS exceptions** (platform-scoped; any addition requires R-SA + R-DBA sign-off): `core.tenant`, `core.worker_heartbeat`, `analytics.analytics_watermark`, `quartz.qrtz_*`, Flyway history, **`core.service_token`** (ADR-025 — sign-off outstanding as of that ADR's filing).

`core.service_token` is not purely platform-scoped like the rest of this list — it holds both tenant-scoped rows (`tenant_id` set) and platform-scoped rows (`tenant_id NULL`) in the same table, per SecurityModel §3. It is listed here rather than in the standard `tenant_isolation` policy loop because its authentication lookup (by `token_hash`) runs before any tenant is known — the lookup IS what discovers the tenant — so the standard RLS predicate cannot apply. `ServiceTokenRepository` (`eip-app`) enforces tenant/platform scoping explicitly on every other query path; see ADR-025 for the full rationale and rejected alternatives.

One database, one application role per concern: `eip_app` (DML via RLS), `eip_migrator` (DDL, used only by Flyway), `eip_readonly` (dashboards/BI, RLS-constrained), `eip_maintenance` (partition/retention jobs), plus the dedicated read-only grant for `eip-analytics` canonical reads described above.

Operational-table notes (RLS applicability):

- `core.event_outbox` — transactional outbox / Spring Modulith externalization journal (`BackendPlan.md` §6: `event_id` UUIDv7 PK, `tenant_id`, `topic`, `partition_key`, `envelope` jsonb, `occurred_at`, `published_at`, `attempts`). Tenant-scoped: carries `tenant_id`, RLS applies; published rows are deleted shortly after publication, exhausted rows surface via the Jobs API. Outbox scope per ADR-017: mandatory for domain/analytics/job events; raw intake produces directly with the staged `raw_*` row as durability.
- `core.processed_events` — consumer idempotency/dedup ledger keyed by `(consumer_group, event_id)` (`EventModel.md` §8). Tenant-scoped, RLS applies. **RANGE-partitioned by day on `processed_at`**; disposal = drop partitions older than 35 days (§6, §10) — deliberately longer than the longest domain-topic retention (30 d) plus the replay window; no batched deletes. The ledger insert commits **in the same transaction** as the handler's canonical write (ack-after-write, `EventModel.md` §8), so redelivery after a crash is absorbed exactly once. Ledger vs structural idempotency (`EventModel.md` §2 permits both): the ledger is used by consumer groups whose effects are event-folds rather than natural-key upserts — the `eip-analytics` metric-engine groups (`eip.analytics.flow-metrics`, `eip.analytics.dora-metrics`, and peer metric families), the read-model projector (`eip.analytics.read-models`), and the RAG re-index trigger consumer (`eip.ai.rag-indexer`) — ~6 groups at the committed envelope (§9). Ingestion normalizers (`eip.ingestion.*`) rely on structural natural-key idempotency (`ux_external_ref_source` upsert), and job consumers (`eip.ai.orchestrator`, `eip.reports.job-runner`) dedup structurally on the claimed `agent_run`/`report_job` row.
- `core.worker_heartbeat` — worker liveness rows refreshed every 10 s (`BackendPlan.md` §12). Platform-scoped: no `tenant_id`, **no RLS**.
- `quartz.qrtz_*` — Quartz clustered JDBC store (Flyway-managed DDL). Platform-scoped, **no RLS**; accessed only by worker processes (tenant scoping lives in job data, not in Quartz rows).

## 3. Representative DDL (baseline for Flyway V1)

Illustrative sketches — the authoritative DDL is `backend/eip-app/src/main/resources/db/migration/V1__baseline.sql`. Types and names here are binding; incidental details (fillfactor, storage params) may differ.

```sql
-- core.external_ref: source-tool identity mapping (DomainModel §2.2)
CREATE TABLE core.external_ref (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(), -- app supplies UUIDv7
  tenant_id        uuid NOT NULL,
  entity_type      text NOT NULL,          -- 'WorkItem', 'PullRequest', ...
  entity_id        uuid NOT NULL,
  source_system    text NOT NULL,          -- 'jira', 'github', ...
  source_instance  text NOT NULL,
  external_id      text NOT NULL,          -- IMMUTABLE source id (Jira numeric id, GitHub node id) — never a renameable key
  external_key     text,                   -- human-readable, mutable key (PROJ-1234); display + correlation only
  key_aliases      text[] NOT NULL DEFAULT '{}',  -- prior external_key values, consulted by correlation parsers (DomainModel §2.2)
  url              text,
  last_seen_at     timestamptz NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE core.external_ref ADD CONSTRAINT ux_external_ref_source
  UNIQUE (tenant_id, source_system, source_instance, entity_type, external_id);
CREATE INDEX ix_external_ref_entity ON core.external_ref (tenant_id, entity_type, entity_id);

-- core.entity_link: typed cross-entity correlation edges (DomainModel §2.4, FR-036)
CREATE TABLE core.entity_link (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL,
  from_type   text NOT NULL,               -- 'WorkItem', 'Release', 'Initiative', ...
  from_id     uuid NOT NULL,
  to_type     text NOT NULL,               -- 'PullRequest', 'Commit', 'WorkItem', ...
  to_id       uuid NOT NULL,
  link_type   text NOT NULL CHECK (link_type IN ('RELATES_TO','REFERENCES','SHIPS','REALIZES')),
  provenance  jsonb NOT NULL DEFAULT '{}', -- parser, source field, matched key/alias
  confidence  numeric(3,2) NOT NULL DEFAULT 1.00,
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, from_type, from_id, to_type, to_id, link_type)
);
-- WorkItem↔Commit REFERENCES rows are materialized only for direct commit-trailer references;
-- transitive Commit↔WorkItem via PR membership is derived at query time (DomainModel §2.4).

-- core.connector + checkpoint (ingestion sync engine)
CREATE TABLE core.connector (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  type           text NOT NULL,            -- 'jira','github','sonarqube',...
  name           text NOT NULL,
  config         jsonb NOT NULL,           -- validated against connector JSON Schema; NO secrets
  secret_id      uuid,                     -- -> core.secret
  status         text NOT NULL CHECK (status IN            -- lifecycle per ConnectorFramework.md §3
                   ('REGISTERED','CONFIGURED','VALIDATED','ACTIVE','DEGRADED','DISABLED')),
  simulation     boolean NOT NULL DEFAULT false,
  last_health_at timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  deleted_at     timestamptz
);

CREATE TABLE core.connector_checkpoint (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  connector_id  uuid NOT NULL REFERENCES core.connector(id),
  stream        text NOT NULL,             -- 'issues','pull_requests','builds',...
  cursor        jsonb NOT NULL,            -- opaque per-connector cursor (updatedSince, page token, sha);
                                           -- carries the SPI Checkpoint fields watermark + recordsSeen
  last_full_sync_at        timestamptz,
  last_incremental_sync_at timestamptz,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, connector_id, stream)
);
-- Mapping to the ConnectorFramework.md Checkpoint record: watermark and recordsSeen live inside
-- the cursor jsonb; lastSuccessfulSyncAt = last_incremental_sync_at (falling back to
-- last_full_sync_at when no incremental sync has run yet).

-- core.secret: AES-256-GCM envelope encryption (master key via env/file/Vault KMS SPI)
CREATE TABLE core.secret (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  name           text NOT NULL,
  ciphertext     bytea NOT NULL,           -- AES-256-GCM payload
  dek_wrapped    bytea NOT NULL,           -- data key wrapped by master key
  kek_version    int  NOT NULL,            -- master key version, enables rotation
  algo           text NOT NULL DEFAULT 'AES-256-GCM',
  rotated_at     timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  deleted_at     timestamptz
);
CREATE UNIQUE INDEX ux_secret_name ON core.secret (tenant_id, name) WHERE deleted_at IS NULL;

-- work.work_item: unified supertype (DomainModel §5)
CREATE TABLE work.work_item (
  id                uuid PRIMARY KEY,
  tenant_id         uuid NOT NULL,
  type              text NOT NULL CHECK (type IN
                     ('EPIC','FEATURE','STORY','TASK','BUG','INCIDENT_TICKET')),
  title             text NOT NULL,
  description       text,
  project_id        uuid NOT NULL,
  parent_id         uuid REFERENCES work.work_item(id),
  current_state_id  uuid NOT NULL,         -- -> work.workflow_state
  status            text NOT NULL,         -- normalized: NEW|READY|IN_PROGRESS|BLOCKED|IN_REVIEW|DONE|CANCELLED
  priority          text,
  severity          text,
  story_points      numeric(6,2) CHECK (story_points >= 0),
  estimate_seconds  bigint,
  sprint_id         uuid,
  assignee_member_id uuid,                 -- direct Member FK; re-pointed by the audited ReattributionJob on identity merge/split (DomainModel §2.3)
  reporter_member_id uuid,                 -- direct Member FK; same reattribution semantics
  team_id           uuid,
  labels            text[] NOT NULL DEFAULT '{}',
  due_date          date,
  blocked           boolean NOT NULL DEFAULT false,
  created_in_source timestamptz NOT NULL,
  resolved_at       timestamptz,
  custom_fields     jsonb NOT NULL DEFAULT '{}',
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  deleted_at        timestamptz,
  CONSTRAINT ck_work_item_severity CHECK
    (type NOT IN ('BUG','INCIDENT_TICKET') OR severity IS NOT NULL)
);

CREATE TABLE work.sprint (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  project_id       uuid NOT NULL,
  name             text NOT NULL,
  goal             text,
  start_at         timestamptz NOT NULL,
  end_at           timestamptz NOT NULL CHECK (end_at > start_at),
  state            text NOT NULL CHECK (state IN ('FUTURE','ACTIVE','CLOSED')),
  committed_points numeric(8,2),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz
);

CREATE TABLE scm.repository (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  name             text NOT NULL,
  default_branch   text NOT NULL,
  visibility       text NOT NULL CHECK (visibility IN ('PRIVATE','INTERNAL','PUBLIC')),
  primary_language text,
  team_id          uuid,
  archived         boolean NOT NULL DEFAULT false,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz
);

CREATE TABLE scm.commit (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  repository_id       uuid NOT NULL REFERENCES scm.repository(id),
  sha                 char(40) NOT NULL,
  author_identity_id  uuid,               -- -> core.member_identity (immutable fact: attribution via identity mapping, never rewritten on merge/split — DomainModel §2.3)
  committed_at        timestamptz NOT NULL,
  message             text NOT NULL,
  additions           integer,
  deletions           integer,
  files_changed       integer,
  created_at          timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, repository_id, sha)
);

CREATE TABLE scm.pull_request (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  repository_id    uuid NOT NULL REFERENCES scm.repository(id),
  number           integer NOT NULL,
  title            text NOT NULL,
  state            text NOT NULL CHECK (state IN ('OPEN','MERGED','CLOSED')),
  draft            boolean NOT NULL DEFAULT false,
  author_member_id uuid,                   -- direct Member FK; re-pointed by the audited ReattributionJob (DomainModel §2.3)
  source_branch    text NOT NULL,
  target_branch    text NOT NULL,
  created_at_source timestamptz NOT NULL,
  merged_at        timestamptz,
  closed_at        timestamptz,
  first_review_at  timestamptz,
  additions        integer,
  deletions        integer,
  comment_count    integer NOT NULL DEFAULT 0,
  merge_commit_sha char(40),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz,
  UNIQUE (tenant_id, repository_id, number),
  CONSTRAINT ck_pr_merged CHECK (state <> 'MERGED' OR merged_at IS NOT NULL)
);

CREATE TABLE cicd.deployment (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  environment_id     uuid NOT NULL REFERENCES cicd.environment(id),
  artifact_id        uuid NOT NULL REFERENCES cicd.artifact(id),
  service_id         uuid,
  status             text NOT NULL CHECK (status IN
                      ('PENDING','IN_PROGRESS','SUCCEEDED','FAILED','ROLLED_BACK')),
  started_at         timestamptz NOT NULL,
  finished_at        timestamptz,
  deployer_member_id uuid,
  caused_incident    boolean NOT NULL DEFAULT false,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ops.incident (
  id                     uuid PRIMARY KEY,
  tenant_id              uuid NOT NULL,
  title                  text NOT NULL,
  severity               text NOT NULL CHECK (severity IN ('SEV1','SEV2','SEV3','SEV4')),
  status                 text NOT NULL CHECK (status IN
    ('DETECTED','ACKNOWLEDGED','INVESTIGATING','MITIGATED','RESOLVED','POSTMORTEM','CLOSED')),
  service_id             uuid,
  deployment_id          uuid,
  detected_at            timestamptz NOT NULL,
  acknowledged_at        timestamptz,
  mitigated_at           timestamptz,
  resolved_at            timestamptz,
  postmortem_document_id uuid,
  work_item_id           uuid,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now()
);

-- analytics.metric_fact: high-volume computed series, monthly range partitions (§6).
-- This is the physical `metric_facts` read model of DataFlow §4 (formerly sketched as ops.metric_value).
CREATE TABLE analytics.metric_fact (
  tenant_id          uuid NOT NULL,
  metric_id          uuid NOT NULL,       -- -> analytics.metric_definition
  definition_version smallint NOT NULL DEFAULT 1,  -- FR-062: version of the definition that computed this point
  subject_id         uuid NOT NULL,       -- team/project/service/repo/sprint id per grain
  bucket_at          timestamptz NOT NULL, -- period start (grain-dependent)
  value              double precision NOT NULL,
  sample_size        integer,
  dimensions         jsonb NOT NULL DEFAULT '{}',
  computed_at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, metric_id, definition_version, subject_id, bucket_at)
) PARTITION BY RANGE (bucket_at);
CREATE TABLE analytics.metric_fact_2026_07 PARTITION OF analytics.metric_fact
  FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
-- Version semantics (FR-062): `analytics.metric_definition.active_version` names the version dashboards
-- read by default; a definition change increments it and a recompute writes a full new series under the
-- new version (§9 sizing note). Superseded versions stay queryable for comparisons until GC (§10).

-- ai.rag_document / ai.rag_chunk (pgvector, §12)
CREATE TABLE ai.rag_document (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  source_system  text NOT NULL,           -- 'confluence','jira','file',...
  entity_type    text,                    -- canonical entity when derived from one
  entity_id      uuid,
  title          text NOT NULL,
  content_ref    text NOT NULL,           -- object-storage key (MinIO/S3)
  acl_hash       text NOT NULL,           -- permission fingerprint for permission-aware retrieval
  content_hash   text NOT NULL,           -- change detection for incremental re-indexing
  chunk_count    integer NOT NULL DEFAULT 0,
  indexed_at     timestamptz,
  embedding_model text,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  deleted_at     timestamptz
);

-- Chunk row = text + typed filter columns; embeddings live in per-model tables (§11).
-- RAGArchitecture.md §5/§7 is the source of record for this schema family; this DDL mirrors it.
CREATE TABLE ai.rag_chunk (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  rag_document_id uuid NOT NULL REFERENCES ai.rag_document(id),
  seq             integer NOT NULL,
  text            text NOT NULL,
  token_count     integer NOT NULL,
  acl_refs        text[] NOT NULL DEFAULT '{}', -- permission-aware retrieval filter (GIN, §4); resolved from ai.rag_acl_grant
  metadata        jsonb NOT NULL DEFAULT '{}',  -- source url, headings, labels — filterable
  deleted_at      timestamptz,                  -- typed filter column: excluded from retrieval before ranking
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, rag_document_id, seq)
);
-- Note: ai.rag_document.acl_hash remains the document-level permission fingerprint for change
-- detection; chunk-level enforcement uses the typed acl_refs array, not the hash.

-- One embedding table per (embeddingModelId, dimension) — pattern ai.rag_chunk_embedding_<modelKey> (§11)
CREATE TABLE ai.rag_chunk_embedding_bge_m3_1024 (  -- example modelKey 'bge_m3_1024'
  chunk_id   uuid PRIMARY KEY REFERENCES ai.rag_chunk(id) ON DELETE CASCADE,
  tenant_id  uuid NOT NULL,
  embedding  vector(1024) NOT NULL
);

-- v0.1 (ADR-023): implemented with an additional `document jsonb` column — the full deterministic
-- ReportDocument inline; artifacts/MinIO + report_job/report_template/report_schedule tables land
-- with AI-composed reports (DEBT-021).
CREATE TABLE reports.generated_report (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  type               text NOT NULL CHECK (type IN ('SPRINT_REVIEW','RELEASE_NOTES',
                      'EXEC_SUMMARY','DELIVERY_RISK','INCIDENT_ANALYSIS','CUSTOM')),
  title              text NOT NULL,
  template_id        uuid,
  parameters         jsonb NOT NULL,
  status             text NOT NULL CHECK (status IN ('QUEUED','GENERATING','READY','FAILED')),
  artifact_keys      text[] NOT NULL DEFAULT '{}',  -- MinIO/S3 object keys
  generated_by_agent text NOT NULL,
  llm_call_ids       uuid[] NOT NULL DEFAULT '{}',  -- -> ai.llm_call_audit
  citations          jsonb,
  data_time_range    tstzrange,                     -- provenance: time window of source data (FR-114)
  input_sources      jsonb NOT NULL DEFAULT '[]',   -- provenance: connectors/datasets consulted (FR-114)
  validation_status  text NOT NULL DEFAULT 'PENDING' CHECK (validation_status IN
                      ('PENDING','PASSED','FAILED')), -- Validation Agent verdict (FR-114)
  completed_at       timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now()
);

-- audit.audit_event: append-only, monthly partitions, no updated_at/deleted_at, hash-chained.
-- Field mapping to SecurityModel §11 logical names: category→category, event→action,
-- actor{type,id}→actor_type/actor_id, traceId→traceparent (W3C traceparent, matching the event envelope).
CREATE TABLE audit.audit_event (
  id           uuid NOT NULL,             -- UUIDv7
  tenant_id    uuid NOT NULL,
  category     text NOT NULL,             -- 'security','privacy','connector','ai','platform',... (SecurityModel §11 taxonomy)
  actor_type   text NOT NULL CHECK (actor_type IN ('USER','AGENT','SYSTEM','CONNECTOR')),
  actor_id     uuid,
  action       text NOT NULL,             -- 'secret.read','report.generate','rbac.role.assign',... (= SecurityModel §11 `event`)
  subject_type text,
  subject_id   uuid,
  outcome      text NOT NULL CHECK (outcome IN ('SUCCESS','DENIED','FAILURE')),
  detail       jsonb NOT NULL DEFAULT '{}', -- UUIDs/enums ONLY — never names, emails, or free person-text (FR-142, §10.1)
  traceparent  text,
  occurred_at  timestamptz NOT NULL DEFAULT now(),
  prev_hash    bytea,                     -- hash of the tenant's previous chained event (NULL only for a tenant's first event)
  hash         bytea,                     -- SHA-256 over (prev_hash || canonical serialization); NULL until the chainer visits the row
  PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);
```

**Audit hash-chain write path (SecurityModel §11).** Inserts write audit rows *without* hashes so audit logging never serializes per-tenant writes at NFR-042 volumes. An **asynchronous batch chainer** (single Redisson-locked runner in `eip-workers`) sequences rows per tenant in `(occurred_at, id)` order, computing `hash = SHA-256(prev_hash || canonical_row_serialization)` and filling `prev_hash`/`hash` in batches; a per-tenant cursor row (`tenant_id`, last chained `(occurred_at, id)`, last `hash`) makes the chainer idempotent and restartable. **Lag SLO:** p95 chain lag ≤ 60 s, alert at 5 min — unchained rows are still immutable, merely not yet tamper-evident. **Partition continuation:** the chain is per tenant and continuous across monthly partitions — `prev_hash` of a tenant's first row in a new partition is the `hash` of its last row in the previous partition, and the §10 export job records each partition's closing per-tenant hash in the Parquet archive manifest so archived segments verify independently.

## 4. Indexing Strategy

Indexes follow query patterns, not habit. Every FK used in a join gets a supporting index; everything else must justify itself against write amplification on high-churn tables.

| Pattern | Index |
|---|---|
| Tenant + time scans (dashboards, cursors) | `ix_work_item_tenant_updated (tenant_id, updated_at DESC, id)` and equivalents on `pull_request`, `deployment`, `incident`. UUIDv7 `id` as tiebreaker makes keyset pagination stable. |
| External ref lookup (hottest ingestion path) | `ux_external_ref_source` (unique, §3) — a single index probe per upsert. Reverse lookup via `ix_external_ref_entity`. |
| Correlation link traversal | `ux` per §3 (`tenant_id, from_type, from_id, to_type, to_id, link_type`) serves forward traversal; `ix_entity_link_to (tenant_id, to_type, to_id)` serves reverse traversal (e.g., PR → related WorkItems). |
| FK joins | `ix_commit_repo_time (tenant_id, repository_id, committed_at DESC)`, `ix_pr_repo_state (tenant_id, repository_id, state)`, `ix_deployment_env_time (tenant_id, environment_id, started_at DESC)`, `ix_incident_service_time (tenant_id, service_id, detected_at DESC)`, `ix_transition_item (tenant_id, work_item_id, occurred_at)`. |
| Work board queries | `ix_work_item_sprint (tenant_id, sprint_id) WHERE deleted_at IS NULL`, `ix_work_item_state (tenant_id, project_id, current_state_id)`. Partial indexes keep hot sets small. |
| JSONB search | `GIN` on `work_item.custom_fields (jsonb_path_ops)`, `staging.raw_* .payload`, `alert.labels`. Only `jsonb_path_ops` (containment) — no full `jsonb_ops` unless key-existence queries appear. |
| Vector search | `HNSW` on each `ai.rag_chunk_embedding_<modelKey>.embedding` (§11). Pre-filter via `GIN` on `rag_chunk.acl_refs` and `rag_chunk.metadata` + B-tree on `(tenant_id, rag_document_id)`; `deleted_at IS NULL` filtered before ranking. |
| Metric reads | Partition pruning by `bucket_at` + PK `(tenant_id, metric_id, definition_version, subject_id, bucket_at)` covers all dashboard series queries — dashboards pin `definition_version = metric_definition.active_version`; no secondary indexes on `metric_fact`. |
| Audit queries | `ix_audit_tenant_time (tenant_id, occurred_at DESC)` per partition; `ix_audit_actor (tenant_id, actor_id, occurred_at DESC)`. |

Rules: no index without a named query pattern in this table (extend it via PR); `EXPLAIN (ANALYZE, BUFFERS)` evidence required for new composite indexes; quarterly `pg_stat_user_indexes` review drops unused ones (a one-way `Vxxx__drop_unused_indexes.sql`).

## 5. Row-Level Security (Tenancy)

`tenant_id + Postgres RLS` is the isolation mechanism (brief-fixed decision). The application also filters by tenant in JPA/JdbcClient predicates — RLS is the backstop that makes a missed predicate a non-event instead of a breach.

- The pooled app role `eip_app` has `NOBYPASSRLS`. Each request/consumer sets the tenant on the connection: `SET LOCAL app.tenant_id = '<uuid>'` inside the transaction (HikariCP-safe: `SET LOCAL` resets on commit/rollback, so no cross-request leakage).
- Every tenant-scoped table gets the same policy pair, applied by a repeatable migration (`R__rls_policies.sql`) that iterates over a catalog of tenant-scoped tables:

```sql
ALTER TABLE work.work_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE work.work_item FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON work.work_item
  USING (tenant_id = current_setting('app.tenant_id')::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);
```

- Maintenance jobs (partition management, retention) run as `eip_maintenance` with `BYPASSRLS` — never through the app pool.
- Cross-tenant admin surfaces (platform operator console) use explicit `SECURITY DEFINER` functions with their own audit events; there is no "tenant_id IS NULL means global" convention.
- `current_setting('app.tenant_id')` with no default: an unset GUC errors out rather than silently returning zero rows, which surfaces wiring bugs immediately.

## 6. Partitioning Strategy

| Table | Scheme | Rationale |
|---|---|---|
| `analytics.metric_fact` | RANGE monthly on `bucket_at` | Highest row count; dashboard queries are time-windowed; retention = drop partition. |
| `audit.audit_event` | RANGE monthly on `occurred_at` | Append-only, compliance retention, time-scoped queries. The per-tenant hash chain continues across partition boundaries (§3). |
| `core.processed_events` | RANGE daily on `processed_at` | Insert-only dedup ledger at ~0.5B-row steady state (§9); disposal = whole-partition drop after 35 d (§10) — batched deletes would bloat and vacuum-thrash a table this hot. |
| `ai.rag_retrieval_audit` | RANGE monthly on `occurred_at` | Immutable retrieval audit; time-scoped access reviews; retention = drop partition. |
| `staging.raw_<connector>` | **Landed (V12, DEBT-017 partitioning sub-item).** RANGE weekly on a new `first_ingested_at` column — deliberately **not** the pre-existing `ingested_at` (see finding below). | Raw JSONB is bulky and expires on the 90-day default window (§10); weekly partition drops keep bloat bounded. |
| `work.work_item_transition` | Not partitioned at V1; monthly RANGE planned when > ~200M rows | Read pattern is per-work-item, not per-time; partitioning deferred until volume forces it. |

**`staging.raw_<connector>` unique-constraint finding (V12).** PostgreSQL requires every UNIQUE/PRIMARY KEY constraint on a partitioned table to include the partition key. The original plan above (RANGE on the pre-existing `ingested_at`) would have required widening `ux_raw_<x> UNIQUE (tenant_id, connector_id, stream, natural_key)` to include `ingested_at`. That was investigated and rejected: `ingested_at` is refreshed to `now()` on every content-changing re-ingest (`StagingRawRepository`'s `INSERT ... ON CONFLICT DO UPDATE`, the everyday path, not an edge case) — widening the arbiter with it would (a) make conflict detection silently miss on that path, since the incoming row's value is fresh and essentially never matches the stored one, inserting a duplicate row per content change instead of updating in place, and (b) even where detection happened to match, PostgreSQL flatly refuses an `ON CONFLICT DO UPDATE` that would move a row to a different partition (`ERROR: invalid ON UPDATE specification`, empirically reproduced). Both independently break the documented idempotency invariant ("replaying the same source dataset upserts in place and never creates duplicates," V2). The landed design instead adds a separate, stable `first_ingested_at timestamptz NOT NULL DEFAULT now()` column — set once, never advanced — as the partition key and the widened constraint's fifth column; `ingested_at` keeps its current "last touched" semantics completely unchanged and is no longer part of any constraint. `StagingRawRepository.upsertAll` resupplies each already-seen natural key's existing `first_ingested_at` explicitly (one extra bounded SELECT) so the arbiter still matches on re-ingest; genuinely new keys get the column default. Proven in isolation (Testcontainers, never against the shared dev database) by `StagingRawPartitioningIntegrationTest`: pre-existing rows survive the rename→recreate→copy→verify→drop conversion and land in the correct partition (or the `DEFAULT` safety valve), a content-changing re-ingest of one natural key never duplicates, and a genuine duplicate insert at the identical widened key is rejected by the constraint. Residual, documented rather than solved here: `first_ingested_at` is first-seen, not last-touched, so a naive future retention job keyed on it would expire a partition by original arrival even if the natural key is still being actively re-ingested — consistent with (not a new gap beyond) §10's existing flat 90-day calendar retention for `staging.raw_*`, but worth a reader knowing explicitly.

Partition management: a scheduled worker in `eip-workers` (Redisson-locked, one runner) pre-creates the next 3 partitions and drops expired ones per retention policy (§10). Partition DDL runs as `eip_maintenance`, is idempotent, and emits `audit.audit_event` rows. We deliberately do not depend on `pg_partman` to keep the on-prem footprint minimal, but the worker's behavior is equivalent. **As of V12, this worker does not exist yet for any partitioned table** (`eip-workers` has no source) — `staging.raw_<connector>`'s V12 migration creates a fixed window of weekly partitions (4 weeks past through 12 weeks future from apply time) plus a `DEFAULT` partition as a safety valve; rolling that window forward and dropping expired partitions is the same still-to-be-built worker described here, not a partitioning-specific gap.

## 7. Flyway Migration Conventions

- Location: `backend/eip-app/src/main/resources/db/migration`. Naming: `V<seq>__<snake_case_summary>.sql` (e.g., `V1__baseline.sql`, `V2__add_release_readiness_score.sql`); repeatables `R__<object>.sql` (e.g., `R__rls_policies.sql`, `R__read_model_views.sql`) for views, policies, and functions that are safe to re-apply.
- **One-way only.** No `undo` migrations. Rollback = roll forward with a corrective migration. Every migration must be applied in CI against a snapshot-restored database before merge.
- **Expand–contract for zero downtime.** Additive change (new nullable column, new table, dual-write trigger) → deploy code reading both shapes → backfill in batched maintenance job (never in the migration itself if > ~10⁶ rows) → contract migration (drop old column, add NOT NULL) at least one release later. `NOT NULL` additions on large tables use `ADD COLUMN ... DEFAULT` (PG16 is non-rewriting) or `NOT VALID` check + later `VALIDATE`.
- Transactional DDL everywhere it is possible; `CREATE INDEX CONCURRENTLY` migrations are marked non-transactional and idempotent (`IF NOT EXISTS`).
- Flyway runs as `eip_migrator` at app startup in dev/demo; in production it runs as an explicit pre-deploy job (K8s Job / compose one-shot) so app pods never hold DDL locks.
- Baseline: `V1__baseline.sql` creates all §2 schemas plus the Phase 0 **control-plane** tables (tenancy/security/connector/audit/analytics-metric — the §3 sketches). The connector-populated canonical business tables (`scm`/`cicd`/`quality`/`ops` detail) and later-phase tables (RAG/agent/report) are expand-only additions that land incrementally in `V2+`, with the feature that first populates them, rather than all upfront in `V1`.

## 8. Read Models for Dashboards (no materialized views)

Dashboards (flow, DORA, quality, sprint/kanban) must not run wide joins at request time. There is **one** read-model architecture (ADR-015): projector-maintained plain tables, RLS-enabled, rebuildable by topic replay + canonical recompute (ADR-011).

- **Primary mechanism: precomputed `analytics.metric_fact`.** The metric engine (`eip-analytics`, consuming `eip.domain.*` topics and publishing `eip.analytics.metrics`) writes bucketed series; the API reads them by PK, pinned to `analytics.metric_definition.active_version` (superseded `definition_version` series stay queryable for FR-062 comparisons until GC, §10). This is the read model for anything chartable.
- **Materialized views are FORBIDDEN for tenant-scoped data** (ADR-015; PRD FR-128): PostgreSQL cannot attach RLS policies to materialized views, so MV-backed dashboards would bypass tenant isolation. The formerly sketched `reports.mv_*` views are redefined as **projector-maintained plain tables** in the `analytics` schema, RLS-enabled like every tenant-scoped table:
  - `analytics.rm_sprint_summary` — per sprint: committed vs done points, scope churn, carryover count, blocked-time totals.
  - `analytics.rm_release_readiness` — per open release: open blocker count, failed quality gates, open CRITICAL/HIGH security findings, readiness score inputs.
  - `analytics.rm_team_flow_current` — per team: current WIP, WIP-limit breaches, oldest in-progress item age, review queue depth.
  - Plus the series read models of DataFlow §4: `analytics.rm_team_flow_daily`, `analytics.rm_dora_daily`, `analytics.rm_quality_snapshot`, `analytics.rm_ops_health`.
- **Maintenance:** the `ReadModelProjector` (`eip-analytics` worker) folds `eip.domain.*` / `eip.analytics.metrics` events into `rm_*` rows within seconds of the event (no 5–15 min refresh cycle); `analytics.analytics_watermark` records per-partition processed offsets so every `rm_*` table is rebuildable by replay after a projector bug, without touching canonical data. On-demand full recompute runs after bulk backfills. Staleness is surfaced in the API (`computedAt` on every dashboard payload) per the platform's honesty-about-uncertainty stance.
- **Plain views** (non-materialized, repeatable migrations in `R__read_model_views.sql`) provide stable contracts for `eip_readonly` BI access, so table refactors don't break external consumers. Every plain view readable by `eip_app` or `eip_readonly` MUST be created `WITH (security_invoker = on)` so it executes with caller privileges and RLS applies — owner-privilege views on tenant-scoped data are forbidden.

## 9. Data Volume Estimates & Growth

Reference sizing: mid-size enterprise tenant — 500 engineers, 40 teams, 400 repos, 25 active connectors. On-prem deployments size from this table (multiply by tenant count for shared instances).

| Table | Initial backfill (2y history) | Growth / month | Row size (est.) | 3-year size (est.) |
|---|---|---|---|---|
| work.work_item | 300k | 12k | 2 KB | 1.5 GB |
| work.work_item_transition | 2.4M | 100k | 250 B | 1.5 GB |
| scm.commit | 1.5M | 60k | 600 B | 2.2 GB |
| scm.pull_request (+ code_review) | 250k | 10k | 1.5 KB | 1.0 GB |
| cicd.build / deployment | 1.2M | 80k | 500 B | 1.8 GB |
| ops.alert | 500k | 50k | 800 B | 1.6 GB |
| core.entity_link | ~1.8M (≈1/PR + trailer refs on ~30% of commits + release/initiative links) | 80k | 140 B | ~0.5 GB |
| analytics.metric_fact | 5M | 4M | 120 B | ~20 GB (pre-retention, active version only) |
| core.processed_events | — | ~430M inserts (NFR-003 envelope: 100k events/h × ~6 ledger-using groups) | 120 B | steady-state ≈ 0.5B rows in the rolling 35-day window ≈ 60–100 GB incl. indexes; bounded by daily partition drops (§6) |
| audit.audit_event | — | 1.5M | 700 B | ~35 GB (pre-retention) |
| staging.raw_* | transient | 10M ingested | 3 KB | bounded ≤ 200 GB by weekly drops |
| ai.rag_chunk (text + filters) | 3M | 150k | ~0.5 KB | ~2 GB |
| ai.rag_chunk_embedding_* (1024-dim) | 3M | 150k | ~4 KB | ~35 GB + HNSW index ~12 GB per embedding space (double transiently during a model-change dual-serve window, §11) |

Sizing notes:

- **Version-multiplied `metric_fact` volume (FR-062).** A metric-definition change followed by full-history recompute writes a **complete new series** under the new `definition_version` — at 25 months × 500 teams that is a full copy of every affected series. Plan for transient ~2× volume on affected partitions until the superseded version's GC window (§10) lapses; recomputes are throttled through the dedicated analytics pool (§12).
- **`entity_link` at the scale envelope.** Direct-reference materialization only (DomainModel §2.4) bounds growth to ≈1 row per PR plus ≤1 per commit with trailers; at the 100k-repo extreme this reaches ~10⁸ rows (~15 GB). Time-based partitioning is deferred with the same trigger style as `work_item_transition` (§6); transitive Commit↔WorkItem joins are derived at query time and never stored.

Planning figures: steady-state primary DB 150–350 GB for the reference tenant (plus the `processed_events` window at multi-tenant envelope rates); provision NVMe, `shared_buffers` 25% of RAM, `work_mem` sized for dashboard aggregates (16–64 MB), autovacuum tuned aggressively on `work_item`, `pull_request`, `alert` (high-churn: `autovacuum_vacuum_scale_factor = 0.02`).

## 10. Retention & Archival

Retention windows are per-tenant configuration with these defaults; all jobs run in `eip-workers` as `eip_maintenance`, are Redisson-locked, idempotent, audited, and rate-limited to avoid I/O spikes.

| Data | Default retention | Mechanism |
|---|---|---|
| staging.raw_* partitions | 90 days per tenant (NFR-070 default; per-tenant configurable) | Drop weekly partition |
| core.processed_events (dedup ledger) | 35 days — must exceed the longest domain-topic retention (30 d) plus the replay window | Drop daily partition (§6) |
| analytics.metric_fact raw grain | 13 months fine-grain; rollups (weekly/monthly buckets) kept 5 years | Drop partition after rollup job confirms |
| analytics.metric_fact superseded definition_versions (FR-062) | 90 days after the new version has fully backfilled (per-tenant configurable) | Batched delete by (metric_id, definition_version); dashboards pin active_version so superseded series are never read by default |
| audit.audit_event | 24 months online; archive to MinIO/S3 (Parquet) before drop | Export-then-drop partition; export manifest records the closing per-tenant chain hash (§3) |
| ops.alert (RESOLVED) | 12 months | Batched hard delete |
| Soft-deleted rows (`deleted_at` set) | 90 days grace, then hard delete | Batched delete per table |
| ai.rag_chunk of deleted documents | Immediate on document delete (tenant data hygiene) | Cascade job + vector index maintenance |
| reports.generated_report | Metadata kept indefinitely; artifacts in object storage per tenant policy (default 24 months) | Object lifecycle rules + metadata flag |

Archival format is Parquet in the tenant's object-storage bucket (`eip-archive/<tenant>/<table>/<yyyy-mm>/`), readable by the Generic File connector for re-ingestion if ever needed.

### 10.1 FR-142 Erasure Mechanics (right to erasure)

Erasure works because person PII is contained by construction (DomainModel §2.3, SecurityModel §11):

- **Pseudonymization precondition.** `audit.audit_event.detail` carries UUIDs and enum values only — never names, emails, or free person-text (§3 DDL note; enforced by code review checklist + an audit-payload lint test). Domain facts and metric series reference `member_id`/`member_identity_id` only. The direct-PII columns are exactly: `core.member.display_name`, `core.member.primary_email`, `core.member_identity.email`, `core.member_identity.display_name`.
- **Core action.** Erasure hard-deletes (or crypto-shreds, where the row must survive referentially) the subject's `Member`/`MemberIdentity` mapping values — remaining `member_id` references across facts and the hash-chained audit log become permanently pseudonymous. The audit chain is never mutated and stays verifiable (SecurityModel §11).

Per-store erasure actions (executed by an audited, resumable erasure job as `eip_maintenance`):

| Store | PII exposure | Erasure action |
|---|---|---|
| `core.member` / `core.member_identity` | Direct PII columns | Null/overwrite PII columns (`display_name` → `erased-<short-id>`); identity rows for the subject hard-deleted; correlation heuristics stop matching the erased identifiers |
| `scm.commit.message` | Verbatim PII (names/emails embedded in message text) | Batched redaction pass replacing the subject's known identifiers with the pseudonym token; original text unrecoverable |
| `work.work_item.description` (+ `title` where flagged) | Free text may embed PII | Same batched redaction pass |
| `ai.rag_chunk.text` + `ai.rag_chunk_embedding_*` rows | Member-authored content where applicable | Delete affected chunks and their embedding rows (cascade, §11) — including Qdrant points when configured — then re-chunk/re-index the redacted source document |
| Rendered report artifacts (MinIO `eip-artifacts`) | Reports quoting the subject | **Delete** the affected artifacts (reports are immutable per DomainModel §11 — no in-place edit); `generated_report` metadata row kept with `artifact_keys` cleared and an erasure marker; regeneration on demand produces a new, pseudonymized report |
| Parquet audit archives (MinIO `eip-archive`) | None by construction (pseudonymous rule above) | No routine action. Escape hatch: if a PII leak into `detail` is ever detected, the affected monthly archive is re-exported redacted and the original crypto-shredded (archives are encrypted with per-archive DEKs) |
| Caches (Redis, dashboard payloads) | Transient rendered fragments | Targeted invalidation of subject-/tenant-scoped keys; TTL ≤ 24 h bounds any residual |

**Signed completion record.** Every erasure run ends with a signed record containing: `erasure_request_id`, `tenant_id`, subject `member_id` + erased `member_identity` ids, the per-store action list with row/object counts, post-erasure verification results (scans proving the identifiers no longer appear in the listed stores), initiator, started/completed timestamps, and a platform-key signature. It is stored as `audit.audit_event` (`action = 'privacy.erasure.completed'`, category `privacy`) and retained per the audit policy; the operator runbook is OperationsGuide §3.4.

## 11. pgvector Setup

```sql
CREATE EXTENSION IF NOT EXISTS vector;   -- pgvector, in V1__baseline.sql
```

- **Schema source of record.** The physical RAG schema follows `../ai/RAGArchitecture.md` §5/§7 — chunk rows hold text + typed filter columns (`tenant_id`, `acl_refs text[]`, `metadata`, `deleted_at`); embeddings live in **one table per (embeddingModelId, dimension) embedding space**, pattern `ai.rag_chunk_embedding_<modelKey>` (§3). This document mirrors that model; conflicts resolve in RAGArchitecture's favor.
- **Per-model embedding tables** replace a single fixed-dimension column: each deployment-registered embedding model gets its own table with its own `vector(<dim>)` and HNSW index. A model change is therefore a **new-table + dual-serve cutover** (index into the new space while serving from the old, atomic SPI switch at completeness threshold, rollback window per RAGArchitecture §5) — never an in-place column swap. Default local-model dimension remains 1024 (`eip.ai.embedding.dimensions` seed config).
- **Index: HNSW per embedding table** — `CREATE INDEX CONCURRENTLY ix_rag_emb_<modelKey> ON ai.rag_chunk_embedding_<modelKey> USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 128);` query-time `SET hnsw.ef_search = 80` (tunable per tenant workload). HNSW chosen over IVFFlat: no training step, stable recall under continuous incremental re-indexing.
- Distance: cosine (`vector_cosine_ops`), embeddings stored normalized.
- Retrieval is always filtered *before* similarity ranking by `tenant_id` (RLS enforces it regardless) and by `acl_refs`/metadata filters (`deleted_at IS NULL`) for permission-aware retrieval with source citations; a result-side recheck re-verifies tenant + ACL per hit before results leave the retrieval API (RAGArchitecture §8).
- `maintenance_work_mem ≥ 2GB` during HNSW builds; index build memory and duration are called out in the ops runbook.
- **Qdrant alternative (ADR-016):** when the VectorStore SPI is configured for Qdrant, the embedding tables stay empty and Qdrant holds vectors keyed by `rag_chunk.id` in **one collection per tenant per embedding space**, named `eip_<tenantId>_<embeddingSpace>`, with the SPI-level tenant filter and the result-side recheck retained as defense-in-depth layers (SecurityModel §5, RAGArchitecture §3.2). All relational chunk/document columns are unchanged.

## 12. Connection Pooling (HikariCP)

- One HikariCP pool per deployable (API app, each worker type). Sizing rule: `pool = (2 × cores) + effective_spindle_count`, capped — API app default `maximumPoolSize=20`, workers `10`, never > 50 total per Postgres instance without raising `max_connections` deliberately (default `max_connections=200` leaves headroom for migrator, readonly, maintenance roles).
- `minimumIdle = maximumPoolSize` (fixed-size pool; avoids connection churn), `maxLifetime = 30m` (below any LB/firewall idle cutoff), `connectionTimeout = 5s` fail-fast surfaced as RFC 7807 `503`, `leakDetectionThreshold = 60s` in non-prod.
- Long-running analytics/backfill queries use a dedicated small pool (`analytics` pool, `maximumPoolSize=4`, `statement_timeout=5min`) so dashboard latency never queues behind batch work.
- Because RLS relies on `SET LOCAL app.tenant_id`, the tenant GUC is set inside each transaction by a datasource decorator (`ConnectionCustomizer`) in `eip-tenancy` (`BackendPlan.md` §5; `eip-core` is a leaf module and holds no datasource wiring); no connection-level state survives back into the pool.
- PgBouncer is *not* introduced at V1 (HikariCP + modular monolith keeps connection counts low); revisit if worker replicas × pools approach `max_connections`.

## 13. Backup Hooks

Backup/restore execution, schedules, and drills are defined in `../operations/OperationsGuide.md`. This plan guarantees the database-side prerequisites:

- **Physical**: WAL archiving enabled (`archive_mode=on`) targeting the object-storage abstraction (MinIO/S3) — supports PITR via pgBackRest or wal-g (operator's choice, documented in the ops guide).
- **Logical**: nightly `pg_dump --format=custom` per database, plus per-tenant logical export capability (`COPY` by `tenant_id` through a `SECURITY DEFINER` export function) for tenant offboarding/data portability.
- Consistency: backups capture Postgres only; object storage (report artifacts, raw blobs) and Kafka are recovered independently — the restore runbook re-drives connectors from `core.connector_checkpoint` cursors to close any gap, relying on idempotent upserts (at-least-once safe).
- Every restore must run `R__rls_policies.sql` verification (repeatable migrations re-apply on next Flyway run) and the RLS smoke test (cross-tenant read must return zero rows) before the instance rejoins service.

## 14. Acceptance Checklist (Phase 0 DB baseline)

- [ ] `V1__baseline.sql` creates all §2 schemas plus the Phase 0 control-plane tables (§3 sketches); canonical business-domain tables and later-phase (RAG/agent/report) tables land incrementally via `V2+` as their owning features ship. Flyway applies cleanly on empty PG16.
- [ ] All tenant-scoped tables have RLS enabled + forced; cross-tenant smoke test returns zero rows under `eip_app`.
- [ ] `vector` extension installed; HNSW index builds on a seeded `ai.rag_chunk_embedding_<modelKey>` sample.
- [ ] Partitioned tables (`analytics.metric_fact`, `audit.audit_event`, `core.processed_events`, `ai.rag_retrieval_audit`, `staging.raw_*`) have current + 3 future partitions; partition worker creates/drops idempotently.
- [ ] `ux_external_ref_source` proven unique-upsert path with `INSERT ... ON CONFLICT` in ingestion integration test.
- [ ] Keyset pagination on `(tenant_id, updated_at DESC, id)` verified against 1M-row `work_item` seed.
- [ ] HikariCP pools sized per §12 in `docker-compose` dev stack; `SET LOCAL app.tenant_id` decorator covered by tests.
- [ ] Every plain view readable by `eip_app`/`eip_readonly` is created `WITH (security_invoker = on)` (§8); a lint test rejects views without it.
- [ ] Audit hash chainer fills `prev_hash`/`hash` on a seeded multi-tenant sample within the lag SLO; chain verifies across a partition boundary (§3).
- [ ] §15 scale-out invariants verified by schema lint: every unique constraint on tenant-owned tables includes `tenant_id`; no DB-global sequences on tenant-owned rows.

## 15. Scale-Out Seam

The single-PostgreSQL posture (document intro) is a starting point, not a ceiling. Scale-out follows **sanctioned levers, in order** — each with a numeric trigger — and the binding invariants below keep every lever executable without schema surgery. Matching gate checklist lines live in `ArchitectureOverview.md` §12 and `../../engineering-operating-system/PerformanceChecklist.md` (G5).

**Sanctioned levers (in order):**

1. **Read replicas for `rm_*`/report reads.** Trigger: dashboard/report reads exceed ~60% of primary I/O, or read p95 breaches its budget for two consecutive weeks while ingestion holds the NFR-003 envelope (100k events/h sustained, 3× burst 15 min). `eip_readonly` and report renderers route to a streaming replica; `rm_*`/`metric_fact` reads tolerate replica lag up to the dashboard staleness budget already surfaced via `computedAt` (§8). Canonical writes never move.
2. **Per-tenant DB sharding via tenant→datasource routing.** Trigger: primary steady-state size exceeds ~2 TB, or a single tenant exceeds ~500 GB or ~30% of instance write I/O (whale tenant). `eip-tenancy` already resolves the tenant per request/consumer message; routing maps `tenant_id → datasource` above the pool (§12), moving whole tenants — never splitting one tenant across instances. RLS stays on inside every shard (defense in depth is topology-independent).
3. **Module extraction** per `ArchitectureOverview.md` §2.2 (its triggers govern) — schemas already align 1:1 with owning modules (§2), so a module's schema lifts out with it.

**Binding invariants (hold from V1 so sharding stays migratable):**

- **No cross-tenant SQL joins** outside `SECURITY DEFINER` platform functions (§5) — any query joining rows of two tenants would break under tenant→datasource routing and is rejected in review.
- **No DB-global sequences for tenant-owned rows** — application-side UUIDv7 only (§1); sequence-coupled identity would collide across shards.
- **Every unique constraint on tenant-owned tables includes `tenant_id`** (see §3 — `ux_external_ref_source`, `ux_secret_name`, `entity_link`, `commit`, `pull_request`, `rag_chunk` all comply); tenant-locality of constraints is what makes a tenant's rows portable as a unit.
- **No platform-scoped table FK-references tenant-scoped rows** (`worker_heartbeat`, `qrtz_*`, `analytics_watermark` reference nothing tenant-owned) — platform state must stay valid whichever shard a tenant lives on.
