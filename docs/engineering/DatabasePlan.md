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
| Soft delete | `deleted_at timestamptz NULL` on mutable domain tables. Rows are never hard-deleted by application code; retention jobs (§10) hard-delete after policy windows. Append-only tables (`audit_event`, `work_item_transition`, `metric_value`) have no `deleted_at`. Unique indexes on soft-deletable tables are partial: `WHERE deleted_at IS NULL`. |
| Enums | Postgres `text` + `CHECK` constraints (not native enums) so value additions are expand-only migrations. |
| JSONB | Source-specific and flexible payloads (`custom_fields`, `payload`, `labels_kv`) are `jsonb`; anything queried by analytics gets promoted to a real column. |
| FKs | Enforced inside a schema; cross-schema/context references are plain `uuid` columns validated at the application layer (module extractability, per DomainModel §13.6). |

## 2. Schema Organization

| Schema | Owner module | Contents |
|---|---|---|
| `core` | eip-tenancy / eip-core | tenant, organization, business_unit, team, member, member_identity, role, external_ref, connector, connector_checkpoint, secret |
| `work` | eip-core (work mgmt) | work_item, work_item_transition, sprint, board, workflow_state, dependency, risk, project, product, roadmap, initiative |
| `scm` | eip-core (source control) | repository, branch, commit, pull_request, code_review |
| `cicd` | eip-core (build & release) | pipeline, build, artifact, environment, deployment, release |
| `quality` | eip-core (quality) | quality_gate, security_finding, technical_debt_item, coverage_snapshot |
| `ops` | eip-core (operations) | service, api_endpoint, incident, alert, metric, metric_value, log_reference, trace_reference, sla_slo |
| `ai` | eip-ai | rag_document, rag_chunk, llm_call_audit, agent_run, mcp_capability_grant |
| `reports` | eip-reports | report_template, generated_report, report_schedule |
| `audit` | eip-tenancy | audit_event (append-only, partitioned) |
| `staging` | eip-ingestion | `raw_<connector>` JSONB landing tables (partitioned), normalizer dead-letter tables |

One database, one application role per concern: `eip_app` (DML via RLS), `eip_migrator` (DDL, used only by Flyway), `eip_readonly` (dashboards/BI, RLS-constrained), `eip_maintenance` (partition/retention jobs).

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
  external_id      text NOT NULL,
  external_key     text,
  url              text,
  last_seen_at     timestamptz NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE core.external_ref ADD CONSTRAINT ux_external_ref_source
  UNIQUE (tenant_id, source_system, source_instance, entity_type, external_id);
CREATE INDEX ix_external_ref_entity ON core.external_ref (tenant_id, entity_type, entity_id);

-- core.connector + checkpoint (ingestion sync engine)
CREATE TABLE core.connector (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  type           text NOT NULL,            -- 'jira','github','sonarqube',...
  name           text NOT NULL,
  config         jsonb NOT NULL,           -- validated against connector JSON Schema; NO secrets
  secret_id      uuid,                     -- -> core.secret
  status         text NOT NULL CHECK (status IN ('ACTIVE','PAUSED','ERROR','DRAFT')),
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
  cursor        jsonb NOT NULL,            -- opaque per-connector cursor (updatedSince, page token, sha)
  last_full_sync_at        timestamptz,
  last_incremental_sync_at timestamptz,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, connector_id, stream)
);

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
  assignee_member_id uuid,
  reporter_member_id uuid,
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
  author_identity_id  uuid,               -- -> core.member_identity
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
  author_member_id uuid,
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

-- ops.metric_value: high-volume time series, monthly range partitions (§6)
CREATE TABLE ops.metric_value (
  tenant_id   uuid NOT NULL,
  metric_id   uuid NOT NULL,              -- -> ops.metric (definition)
  subject_id  uuid NOT NULL,              -- team/project/service/repo/sprint id per grain
  bucket_at   timestamptz NOT NULL,       -- period start (grain-dependent)
  value       double precision NOT NULL,
  sample_size integer,
  dimensions  jsonb NOT NULL DEFAULT '{}',
  computed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, metric_id, subject_id, bucket_at)
) PARTITION BY RANGE (bucket_at);
CREATE TABLE ops.metric_value_2026_07 PARTITION OF ops.metric_value
  FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');

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

CREATE TABLE ai.rag_chunk (
  id              uuid PRIMARY KEY,
  tenant_id       uuid NOT NULL,
  rag_document_id uuid NOT NULL REFERENCES ai.rag_document(id),
  seq             integer NOT NULL,
  text            text NOT NULL,
  token_count     integer NOT NULL,
  metadata        jsonb NOT NULL DEFAULT '{}',  -- source url, headings, labels — filterable
  embedding       vector(1024),            -- dimension is per-deployment config (§11)
  created_at      timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, rag_document_id, seq)
);

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
  completed_at       timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now()
);

-- audit.audit_event: append-only, monthly partitions, no updated_at/deleted_at
CREATE TABLE audit.audit_event (
  id           uuid NOT NULL,             -- UUIDv7
  tenant_id    uuid NOT NULL,
  actor_type   text NOT NULL CHECK (actor_type IN ('USER','AGENT','SYSTEM','CONNECTOR')),
  actor_id     uuid,
  action       text NOT NULL,             -- 'secret.read','report.generate','rbac.role.assign',...
  subject_type text,
  subject_id   uuid,
  outcome      text NOT NULL CHECK (outcome IN ('SUCCESS','DENIED','FAILURE')),
  detail       jsonb NOT NULL DEFAULT '{}',
  traceparent  text,
  occurred_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);
```

## 4. Indexing Strategy

Indexes follow query patterns, not habit. Every FK used in a join gets a supporting index; everything else must justify itself against write amplification on high-churn tables.

| Pattern | Index |
|---|---|
| Tenant + time scans (dashboards, cursors) | `ix_work_item_tenant_updated (tenant_id, updated_at DESC, id)` and equivalents on `pull_request`, `deployment`, `incident`. UUIDv7 `id` as tiebreaker makes keyset pagination stable. |
| External ref lookup (hottest ingestion path) | `ux_external_ref_source` (unique, §3) — a single index probe per upsert. Reverse lookup via `ix_external_ref_entity`. |
| FK joins | `ix_commit_repo_time (tenant_id, repository_id, committed_at DESC)`, `ix_pr_repo_state (tenant_id, repository_id, state)`, `ix_deployment_env_time (tenant_id, environment_id, started_at DESC)`, `ix_incident_service_time (tenant_id, service_id, detected_at DESC)`, `ix_transition_item (tenant_id, work_item_id, occurred_at)`. |
| Work board queries | `ix_work_item_sprint (tenant_id, sprint_id) WHERE deleted_at IS NULL`, `ix_work_item_state (tenant_id, project_id, current_state_id)`. Partial indexes keep hot sets small. |
| JSONB search | `GIN` on `work_item.custom_fields (jsonb_path_ops)`, `staging.raw_* .payload`, `alert.labels`. Only `jsonb_path_ops` (containment) — no full `jsonb_ops` unless key-existence queries appear. |
| Vector search | `HNSW` on `ai.rag_chunk.embedding` (§11). Metadata pre-filter via `GIN` on `rag_chunk.metadata` + B-tree on `(tenant_id, rag_document_id)`. |
| Metric reads | Partition pruning by `bucket_at` + PK `(tenant_id, metric_id, subject_id, bucket_at)` covers all dashboard series queries; no secondary indexes on `metric_value`. |
| Audit queries | `ix_audit_tenant_time (tenant_id, occurred_at DESC)` per partition; `ix_audit_actor (tenant_id, actor_id, occurred_at DESC)`. |

Rules: no index without a named query pattern in this table (extend it via PR); `EXPLAIN (ANALYZE, BUFFERS)` evidence required for new composite indexes; quarterly `pg_stat_user_indexes` review drops unused ones (a one-way `Vxxx__drop_unused_indexes.sql`).

## 5. Row-Level Security (Tenancy)

`tenant_id + Postgres RLS` is the isolation mechanism (brief-fixed decision). The application also filters by tenant in JPA/jOOQ predicates — RLS is the backstop that makes a missed predicate a non-event instead of a breach.

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
| `ops.metric_value` | RANGE monthly on `bucket_at` | Highest row count; dashboard queries are time-windowed; retention = drop partition. |
| `audit.audit_event` | RANGE monthly on `occurred_at` | Append-only, compliance retention, time-scoped queries. |
| `staging.raw_<connector>` | RANGE weekly on `ingested_at` | Raw JSONB is bulky and short-lived (normalize-then-expire); weekly drops keep bloat near zero. |
| `work.work_item_transition` | Not partitioned at V1; monthly RANGE planned when > ~200M rows | Read pattern is per-work-item, not per-time; partitioning deferred until volume forces it. |

Partition management: a scheduled worker in `eip-workers` (Redisson-locked, one runner) pre-creates the next 3 partitions and drops expired ones per retention policy (§10). Partition DDL runs as `eip_maintenance`, is idempotent, and emits `audit.audit_event` rows. We deliberately do not depend on `pg_partman` to keep the on-prem footprint minimal, but the worker's behavior is equivalent.

## 7. Flyway Migration Conventions

- Location: `backend/eip-app/src/main/resources/db/migration`. Naming: `V<seq>__<snake_case_summary>.sql` (e.g., `V1__baseline.sql`, `V2__add_release_readiness_score.sql`); repeatables `R__<object>.sql` (e.g., `R__rls_policies.sql`, `R__read_model_views.sql`) for views, policies, and functions that are safe to re-apply.
- **One-way only.** No `undo` migrations. Rollback = roll forward with a corrective migration. Every migration must be applied in CI against a snapshot-restored database before merge.
- **Expand–contract for zero downtime.** Additive change (new nullable column, new table, dual-write trigger) → deploy code reading both shapes → backfill in batched maintenance job (never in the migration itself if > ~10⁶ rows) → contract migration (drop old column, add NOT NULL) at least one release later. `NOT NULL` additions on large tables use `ADD COLUMN ... DEFAULT` (PG16 is non-rewriting) or `NOT VALID` check + later `VALIDATE`.
- Transactional DDL everywhere it is possible; `CREATE INDEX CONCURRENTLY` migrations are marked non-transactional and idempotent (`IF NOT EXISTS`).
- Flyway runs as `eip_migrator` at app startup in dev/demo; in production it runs as an explicit pre-deploy job (K8s Job / compose one-shot) so app pods never hold DDL locks.
- Baseline: `V1__baseline.sql` contains the full Phase 0 schema (all §3 tables plus the remaining catalog); `V2+` begins with Phase 1 connector additions.

## 8. Read Models & Materialized Views for Dashboards

Dashboards (flow, DORA, quality, sprint/kanban) must not run wide joins at request time.

- **Primary mechanism: precomputed `ops.metric_value`.** The metric engine (`eip-analytics`, consuming `eip.domain.*` topics and publishing `eip.analytics.metrics`) writes bucketed series; the API reads them by PK. This is the read model for anything chartable.
- **Materialized views** for cross-entity snapshot grids that are awkward as series, all defined in `R__read_model_views.sql` under a `reports` schema prefix `mv_`:
  - `reports.mv_sprint_summary` — per sprint: committed vs done points, scope churn, carryover count, blocked-time totals.
  - `reports.mv_release_readiness` — per open release: open blocker count, failed quality gates, open CRITICAL/HIGH security findings, readiness score inputs.
  - `reports.mv_team_flow_current` — per team: current WIP, WIP-limit breaches, oldest in-progress item age, review queue depth.
- Refresh: `REFRESH MATERIALIZED VIEW CONCURRENTLY` (all MVs have a unique index) on a schedule (5–15 min per view) triggered by the same worker runtime, plus on-demand refresh after bulk backfills. Staleness is surfaced in the API (`computedAt` on every dashboard payload) per the platform's honesty-about-uncertainty stance.
- Plain views (non-materialized, also repeatable migrations) provide stable contracts for `eip_readonly` BI access, so table refactors don't break external consumers.

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
| ops.metric_value | 5M | 4M | 120 B | ~20 GB (pre-retention) |
| audit.audit_event | — | 1.5M | 700 B | ~35 GB (pre-retention) |
| staging.raw_* | transient | 10M ingested | 3 KB | bounded ≤ 200 GB by weekly drops |
| ai.rag_chunk (1024-dim) | 3M | 150k | ~4.5 KB (vector ≈ 4 KB) | ~35 GB + HNSW index ~12 GB |

Planning figures: steady-state primary DB 150–350 GB for the reference tenant; provision NVMe, `shared_buffers` 25% of RAM, `work_mem` sized for dashboard aggregates (16–64 MB), autovacuum tuned aggressively on `work_item`, `pull_request`, `alert` (high-churn: `autovacuum_vacuum_scale_factor = 0.02`).

## 10. Retention & Archival

Retention windows are per-tenant configuration with these defaults; all jobs run in `eip-workers` as `eip_maintenance`, are Redisson-locked, idempotent, audited, and rate-limited to avoid I/O spikes.

| Data | Default retention | Mechanism |
|---|---|---|
| staging.raw_* partitions | 30 days after successful normalization | Drop weekly partition |
| ops.metric_value raw grain | 13 months fine-grain; rollups (weekly/monthly buckets) kept 5 years | Drop partition after rollup job confirms |
| audit.audit_event | 24 months online; archive to MinIO/S3 (Parquet) before drop | Export-then-drop partition |
| ops.alert (RESOLVED) | 12 months | Batched hard delete |
| Soft-deleted rows (`deleted_at` set) | 90 days grace, then hard delete | Batched delete per table |
| ai.rag_chunk of deleted documents | Immediate on document delete (tenant data hygiene) | Cascade job + vector index maintenance |
| reports.generated_report | Metadata kept indefinitely; artifacts in object storage per tenant policy (default 24 months) | Object lifecycle rules + metadata flag |

Archival format is Parquet in the tenant's object-storage bucket (`eip-archive/<tenant>/<table>/<yyyy-mm>/`), readable by the Generic File connector for re-ingestion if ever needed.

## 11. pgvector Setup

```sql
CREATE EXTENSION IF NOT EXISTS vector;   -- pgvector, in V1__baseline.sql
```

- **Dimensions are deployment-configurable** (embedding model is configurable per the RAG design: local models in air-gapped mode). The column dimension is fixed per database at install time from `eip.ai.embedding.dimensions` (default 1024, matching common local embedding models); changing models with a different dimension triggers a managed re-index migration (new column + full re-embed + swap), executed by the RAG Retrieval pipeline's scheduled re-index machinery.
- **Index: HNSW** — `CREATE INDEX CONCURRENTLY ix_rag_chunk_embedding ON ai.rag_chunk USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 128);` query-time `SET hnsw.ef_search = 80` (tunable per tenant workload). HNSW chosen over IVFFlat: no training step, stable recall under continuous incremental re-indexing.
- Distance: cosine (`vector_cosine_ops`), embeddings stored normalized.
- Retrieval is always filtered *before* similarity ranking by `tenant_id` (RLS enforces it regardless) and by `acl_hash`/metadata filters for permission-aware retrieval with source citations.
- `maintenance_work_mem ≥ 2GB` during HNSW builds; index build memory and duration are called out in the ops runbook.
- Qdrant alternative: when the VectorStore SPI is configured for Qdrant, `ai.rag_chunk.embedding` remains NULL and Qdrant holds vectors keyed by `rag_chunk.id`; all other columns and the relational chunk/document model are unchanged.

## 12. Connection Pooling (HikariCP)

- One HikariCP pool per deployable (API app, each worker type). Sizing rule: `pool = (2 × cores) + effective_spindle_count`, capped — API app default `maximumPoolSize=20`, workers `10`, never > 50 total per Postgres instance without raising `max_connections` deliberately (default `max_connections=200` leaves headroom for migrator, readonly, maintenance roles).
- `minimumIdle = maximumPoolSize` (fixed-size pool; avoids connection churn), `maxLifetime = 30m` (below any LB/firewall idle cutoff), `connectionTimeout = 5s` fail-fast surfaced as RFC 7807 `503`, `leakDetectionThreshold = 60s` in non-prod.
- Long-running analytics/backfill queries use a dedicated small pool (`analytics` pool, `maximumPoolSize=4`, `statement_timeout=5min`) so dashboard latency never queues behind batch work.
- Because RLS relies on `SET LOCAL app.tenant_id`, the tenant GUC is set inside each transaction by a datasource decorator in `eip-core`; no connection-level state survives back into the pool.
- PgBouncer is *not* introduced at V1 (HikariCP + modular monolith keeps connection counts low); revisit if worker replicas × pools approach `max_connections`.

## 13. Backup Hooks

Backup/restore execution, schedules, and drills are defined in `../operations/OperationsGuide.md`. This plan guarantees the database-side prerequisites:

- **Physical**: WAL archiving enabled (`archive_mode=on`) targeting the object-storage abstraction (MinIO/S3) — supports PITR via pgBackRest or wal-g (operator's choice, documented in the ops guide).
- **Logical**: nightly `pg_dump --format=custom` per database, plus per-tenant logical export capability (`COPY` by `tenant_id` through a `SECURITY DEFINER` export function) for tenant offboarding/data portability.
- Consistency: backups capture Postgres only; object storage (report artifacts, raw blobs) and Kafka are recovered independently — the restore runbook re-drives connectors from `core.connector_checkpoint` cursors to close any gap, relying on idempotent upserts (at-least-once safe).
- Every restore must run `R__rls_policies.sql` verification (repeatable migrations re-apply on next Flyway run) and the RLS smoke test (cross-tenant read must return zero rows) before the instance rejoins service.

## 14. Acceptance Checklist (Phase 0 DB baseline)

- [ ] `V1__baseline.sql` creates all §2 schemas and §3 tables; Flyway applies cleanly on empty PG16.
- [ ] All tenant-scoped tables have RLS enabled + forced; cross-tenant smoke test returns zero rows under `eip_app`.
- [ ] `vector` extension installed; HNSW index builds on seeded `ai.rag_chunk` sample.
- [ ] Partitioned tables (`metric_value`, `audit_event`, `staging.raw_*`) have current + 3 future partitions; partition worker creates/drops idempotently.
- [ ] `ux_external_ref_source` proven unique-upsert path with `INSERT ... ON CONFLICT` in ingestion integration test.
- [ ] Keyset pagination on `(tenant_id, updated_at DESC, id)` verified against 1M-row `work_item` seed.
- [ ] HikariCP pools sized per §12 in `docker-compose` dev stack; `SET LOCAL app.tenant_id` decorator covered by tests.
