-- V1__baseline.sql — EIP Phase-0 database baseline (DatabasePlan.md §1–§7).
-- Increment 1: the `core` schema + tenancy-core tables + shared conventions.
-- Increment 2 (product-first order): control-plane infra + connector + audit + analytics-metric
-- tables (the tables the first visible slice surfaces), the canonical schemas, and a representative
-- canonical table (work.work_item). The remaining canonical business-table DDL (scm/cicd/quality/ops
-- detail) and the RAG/agent/report tables land with their Phase-1 connectors/features that populate
-- them (DatabasePlan §7 "V2+ begins with Phase 1 connector additions"), each expand-only.
-- Forward-only; RLS policies live in R__rls_policies.sql.
--
-- Conventions (DatabasePlan §1): snake_case singular tables; `id uuid` UUIDv7 supplied by the
-- application (gen_random_uuid() default is a manual-insert safety net only); every tenant-scoped
-- table carries `tenant_id uuid NOT NULL`; timestamptz created_at/updated_at with a shared touch
-- trigger; enums are text + CHECK; unique constraints on tenant-owned tables include tenant_id
-- (scale-out invariant, §15).

CREATE EXTENSION IF NOT EXISTS vector;   -- pgvector (DatabasePlan §11); RAG embedding tables land later.

CREATE SCHEMA IF NOT EXISTS core;

-- Shared updated_at touch trigger (DatabasePlan §1).
CREATE OR REPLACE FUNCTION core.tg_touch_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------
-- core.tenant — tenant registry. Platform-scoped: NO RLS (enumerated exception, DatabasePlan §2).
-- The id IS the tenant_id every other tenant-scoped row references.
-- ---------------------------------------------------------------------------
CREATE TABLE core.tenant (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name        text NOT NULL,
  slug        text NOT NULL,
  status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','ARCHIVED')),
  settings    jsonb NOT NULL DEFAULT '{}',
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_tenant_slug ON core.tenant (slug);
CREATE TRIGGER tg_tenant_touch BEFORE UPDATE ON core.tenant
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- ---------------------------------------------------------------------------
-- core.organization — tenant root aggregate (DomainModel §4). One tenant = one Organization.
-- ---------------------------------------------------------------------------
CREATE TABLE core.organization (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL REFERENCES core.tenant(id),
  name        text NOT NULL,
  slug        text NOT NULL,
  settings    jsonb NOT NULL DEFAULT '{}',
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz
);
CREATE UNIQUE INDEX ux_organization_slug ON core.organization (tenant_id, slug) WHERE deleted_at IS NULL;
CREATE TRIGGER tg_organization_touch BEFORE UPDATE ON core.organization
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- ---------------------------------------------------------------------------
-- core.business_unit — hierarchical org structure (DomainModel §4).
-- ---------------------------------------------------------------------------
CREATE TABLE core.business_unit (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        uuid NOT NULL,
  organization_id  uuid NOT NULL REFERENCES core.organization(id),
  name             text NOT NULL,
  parent_id        uuid REFERENCES core.business_unit(id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz
);
CREATE INDEX ix_business_unit_org ON core.business_unit (tenant_id, organization_id);
CREATE TRIGGER tg_business_unit_touch BEFORE UPDATE ON core.business_unit
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- ---------------------------------------------------------------------------
-- core.team — delivery team; primary aggregation grain for team-health metrics (DomainModel §4).
-- ---------------------------------------------------------------------------
CREATE TABLE core.team (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        uuid NOT NULL,
  business_unit_id uuid NOT NULL REFERENCES core.business_unit(id),
  name             text NOT NULL,
  type             text CHECK (type IN ('STREAM_ALIGNED','PLATFORM','ENABLING','COMPLICATED_SUBSYSTEM')),
  active           boolean NOT NULL DEFAULT true,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  deleted_at       timestamptz
);
CREATE INDEX ix_team_bu ON core.team (tenant_id, business_unit_id);
CREATE TRIGGER tg_team_touch BEFORE UPDATE ON core.team
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- ---------------------------------------------------------------------------
-- core.role — RBAC role; fine-grained permissions, tenant-scoped (DomainModel §4).
-- ---------------------------------------------------------------------------
CREATE TABLE core.role (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL,
  name         text NOT NULL,
  permissions  text[] NOT NULL DEFAULT '{}',
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_role_name ON core.role (tenant_id, name);
CREATE TRIGGER tg_role_touch BEFORE UPDATE ON core.role
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- ---------------------------------------------------------------------------
-- core.member — resolved person (DomainModel §2.3/§4). PII columns display_name, primary_email
-- are FR-142 erasure targets (DatabasePlan §10.1); facts/metrics reference member_id pseudonymously.
-- ---------------------------------------------------------------------------
CREATE TABLE core.member (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL,
  display_name   text NOT NULL,
  primary_email  text,
  oidc_subject   text,
  active         boolean NOT NULL DEFAULT true,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  deleted_at     timestamptz
);
CREATE UNIQUE INDEX ux_member_oidc ON core.member (tenant_id, oidc_subject)
  WHERE oidc_subject IS NOT NULL AND deleted_at IS NULL;
CREATE TRIGGER tg_member_touch BEFORE UPDATE ON core.member
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- ---------------------------------------------------------------------------
-- core.member_identity — per-tool identity of a Member (DomainModel §2.3). PII columns email,
-- display_name are FR-142 erasure targets.
-- ---------------------------------------------------------------------------
CREATE TABLE core.member_identity (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL,
  member_id      uuid NOT NULL REFERENCES core.member(id),
  source_system  text NOT NULL,
  external_id    text NOT NULL,
  email          text,
  display_name   text,
  confidence     numeric(3,2) NOT NULL DEFAULT 1.00,
  merged_by      text NOT NULL DEFAULT 'HEURISTIC' CHECK (merged_by IN ('HEURISTIC','ADMIN')),
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_member_identity_source ON core.member_identity (tenant_id, source_system, external_id);
CREATE INDEX ix_member_identity_member ON core.member_identity (tenant_id, member_id);
CREATE TRIGGER tg_member_identity_touch BEFORE UPDATE ON core.member_identity
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- ---------------------------------------------------------------------------
-- core.secret — AES-256-GCM envelope-encrypted secrets; master key via env/file/Vault KMS SPI
-- (DatabasePlan §3). Ciphertext only; plaintext never stored.
-- ---------------------------------------------------------------------------
CREATE TABLE core.secret (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL,
  name         text NOT NULL,
  ciphertext   bytea NOT NULL,
  dek_wrapped  bytea NOT NULL,
  kek_version  int  NOT NULL,
  algo         text NOT NULL DEFAULT 'AES-256-GCM',
  rotated_at   timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  deleted_at   timestamptz
);
CREATE UNIQUE INDEX ux_secret_name ON core.secret (tenant_id, name) WHERE deleted_at IS NULL;
CREATE TRIGGER tg_secret_touch BEFORE UPDATE ON core.secret
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- ===========================================================================
-- Increment 2 — control-plane infra, connector, audit, and analytics-metric tables.
-- ===========================================================================

-- core.external_ref — source-tool identity mapping (DomainModel §2.2; DatabasePlan §3).
CREATE TABLE core.external_ref (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        uuid NOT NULL,
  entity_type      text NOT NULL,
  entity_id        uuid NOT NULL,
  source_system    text NOT NULL,
  source_instance  text NOT NULL,
  external_id      text NOT NULL,          -- IMMUTABLE native id (AD-14)
  external_key     text,                   -- mutable human key; display/correlation only
  key_aliases      text[] NOT NULL DEFAULT '{}',
  url              text,
  last_seen_at     timestamptz NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE core.external_ref ADD CONSTRAINT ux_external_ref_source
  UNIQUE (tenant_id, source_system, source_instance, entity_type, external_id);
CREATE INDEX ix_external_ref_entity ON core.external_ref (tenant_id, entity_type, entity_id);
CREATE TRIGGER tg_external_ref_touch BEFORE UPDATE ON core.external_ref
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- core.entity_link — typed cross-entity correlation edges (DomainModel §2.4; append-only-ish).
CREATE TABLE core.entity_link (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   uuid NOT NULL,
  from_type   text NOT NULL,
  from_id     uuid NOT NULL,
  to_type     text NOT NULL,
  to_id       uuid NOT NULL,
  link_type   text NOT NULL CHECK (link_type IN ('RELATES_TO','REFERENCES','SHIPS','REALIZES')),
  provenance  jsonb NOT NULL DEFAULT '{}',
  confidence  numeric(3,2) NOT NULL DEFAULT 1.00,
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ux_entity_link UNIQUE (tenant_id, from_type, from_id, to_type, to_id, link_type)
);

-- core.connector — connector registry (surfaced by the connector-admin UI / status API).
CREATE TABLE core.connector (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL,
  type           text NOT NULL,            -- 'jira','bitbucket','sonarqube',... (vendor-neutral SPI)
  name           text NOT NULL,
  config         jsonb NOT NULL DEFAULT '{}',  -- validated against connector JSON Schema; NO secrets
  secret_id      uuid,
  status         text NOT NULL DEFAULT 'REGISTERED' CHECK (status IN
                   ('REGISTERED','CONFIGURED','VALIDATED','ACTIVE','DEGRADED','DISABLED')),
  simulation     boolean NOT NULL DEFAULT false,
  last_health_at timestamptz,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  deleted_at     timestamptz
);
CREATE INDEX ix_connector_status ON core.connector (tenant_id, status) WHERE deleted_at IS NULL;
CREATE TRIGGER tg_connector_touch BEFORE UPDATE ON core.connector
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- core.connector_checkpoint — per-stream sync cursor (DatabasePlan §3).
CREATE TABLE core.connector_checkpoint (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id     uuid NOT NULL,
  connector_id  uuid NOT NULL REFERENCES core.connector(id),
  stream        text NOT NULL,
  cursor        jsonb NOT NULL DEFAULT '{}',
  last_full_sync_at        timestamptz,
  last_incremental_sync_at timestamptz,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ux_connector_checkpoint UNIQUE (tenant_id, connector_id, stream)
);
CREATE TRIGGER tg_connector_checkpoint_touch BEFORE UPDATE ON core.connector_checkpoint
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- core.event_outbox — transactional outbox (ADR-017; BackendPlan §6). Tenant-scoped, RLS applies.
CREATE TABLE core.event_outbox (
  event_id      uuid PRIMARY KEY,          -- UUIDv7
  tenant_id     uuid NOT NULL,
  topic         text NOT NULL,
  partition_key text NOT NULL,
  envelope      jsonb NOT NULL,
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  published_at  timestamptz,
  attempts      int NOT NULL DEFAULT 0
);
CREATE INDEX ix_event_outbox_unpublished ON core.event_outbox (tenant_id, occurred_at)
  WHERE published_at IS NULL;

-- core.processed_events — consumer idempotency/dedup ledger (DatabasePlan §2/§6). RANGE daily on
-- processed_at; the partition column is part of the PK (partitioning requirement).
CREATE TABLE core.processed_events (
  consumer_group text NOT NULL,
  event_id       uuid NOT NULL,
  tenant_id      uuid NOT NULL,
  processed_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer_group, event_id, processed_at)
) PARTITION BY RANGE (processed_at);
CREATE TABLE core.processed_events_default PARTITION OF core.processed_events DEFAULT;

-- core.worker_heartbeat — worker liveness (DatabasePlan §2). Platform-scoped: NO RLS.
CREATE TABLE core.worker_heartbeat (
  worker_id     text PRIMARY KEY,
  runtime       text NOT NULL,
  last_beat_at  timestamptz NOT NULL DEFAULT now()
);

-- --- Canonical + platform schemas (DatabasePlan §2) --------------------------
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS work;
CREATE SCHEMA IF NOT EXISTS scm;
CREATE SCHEMA IF NOT EXISTS cicd;
CREATE SCHEMA IF NOT EXISTS quality;
CREATE SCHEMA IF NOT EXISTS ops;
CREATE SCHEMA IF NOT EXISTS analytics;
CREATE SCHEMA IF NOT EXISTS ai;
CREATE SCHEMA IF NOT EXISTS reports;
CREATE SCHEMA IF NOT EXISTS staging;

-- audit.audit_event — append-only, hash-chained (DatabasePlan §3; SecurityModel §11). RANGE monthly
-- on occurred_at; the partition column is part of the PK. `detail` carries UUIDs/enums only — no PII.
CREATE TABLE audit.audit_event (
  id            uuid NOT NULL DEFAULT gen_random_uuid(),
  tenant_id     uuid NOT NULL,
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  actor_member_id uuid,
  action        text NOT NULL,
  outcome       text NOT NULL CHECK (outcome IN ('SUCCESS','FAILURE')),
  trace_id      text,
  detail        jsonb NOT NULL DEFAULT '{}',
  prev_hash     bytea,
  hash          bytea,
  PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE TABLE audit.audit_event_default PARTITION OF audit.audit_event DEFAULT;

-- work.work_item — unified supertype (DomainModel §5; DatabasePlan §3). Representative canonical
-- table; remaining scm/cicd/quality/ops tables land with their Phase-1 connectors.
CREATE TABLE work.work_item (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL,
  type              text NOT NULL CHECK (type IN
                     ('EPIC','FEATURE','STORY','TASK','BUG','INCIDENT_TICKET')),
  title             text NOT NULL,
  description       text,
  project_id        uuid NOT NULL,
  parent_id         uuid REFERENCES work.work_item(id),
  current_state_id  uuid NOT NULL,
  status            text NOT NULL,
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
  deleted_at        timestamptz
);
CREATE INDEX ix_work_item_keyset ON work.work_item (tenant_id, updated_at DESC, id);
CREATE TRIGGER tg_work_item_touch BEFORE UPDATE ON work.work_item
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- analytics.metric_definition — the metric registry (DomainModel §9; FR-056). Carries the documented
-- formula/caveats/gaming-risks that make the number trustworthy — e.g. the Engineering Friction metric.
CREATE TABLE analytics.metric_definition (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      uuid NOT NULL,
  metric_key     text NOT NULL,            -- 'engineering_friction', 'flow_cycle_time', ...
  name           text NOT NULL,
  purpose        text NOT NULL,
  formula        text NOT NULL,
  inputs         jsonb NOT NULL DEFAULT '{}',
  grain          text NOT NULL,            -- 'team','sprint','day' — team-level for people-adjacent
  caveats        text NOT NULL DEFAULT '',
  gaming_risks   text NOT NULL DEFAULT '',
  active_version int NOT NULL DEFAULT 1,   -- FR-062
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_metric_definition_key ON analytics.metric_definition (tenant_id, metric_key);
CREATE TRIGGER tg_metric_definition_touch BEFORE UPDATE ON analytics.metric_definition
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- analytics.metric_fact — bucketed metric series read model (DataFlow §4; ADR-011/015). RANGE monthly
-- on bucket_at; PK includes bucket_at + definition_version (FR-062).
CREATE TABLE analytics.metric_fact (
  id                 uuid NOT NULL DEFAULT gen_random_uuid(),
  tenant_id          uuid NOT NULL,
  metric_id          uuid NOT NULL,
  definition_version int NOT NULL,
  team_id            uuid,
  grain              text NOT NULL,
  bucket_at          timestamptz NOT NULL,
  value              numeric NOT NULL,
  computed_at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (id, bucket_at),
  CONSTRAINT ux_metric_fact UNIQUE (tenant_id, metric_id, definition_version, team_id, grain, bucket_at)
) PARTITION BY RANGE (bucket_at);
CREATE TABLE analytics.metric_fact_default PARTITION OF analytics.metric_fact DEFAULT;

-- analytics.rm_team_flow_current — per-team flow/friction read model (DatabasePlan §8): the surface
-- the Engineering Friction dashboard reads (WIP, WIP-limit breaches, oldest in-progress age, review
-- queue depth). Projector-maintained plain RLS table (ADR-015).
CREATE TABLE analytics.rm_team_flow_current (
  id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id                   uuid NOT NULL,
  team_id                     uuid NOT NULL,
  wip                         int NOT NULL DEFAULT 0,
  wip_limit_breaches          int NOT NULL DEFAULT 0,
  oldest_in_progress_age_sec  bigint NOT NULL DEFAULT 0,
  review_queue_depth          int NOT NULL DEFAULT 0,
  computed_at                 timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_rm_team_flow_current_team ON analytics.rm_team_flow_current (tenant_id, team_id);

-- analytics.analytics_watermark — projector processed offsets (DataFlow §4). Platform-scoped: NO RLS.
CREATE TABLE analytics.analytics_watermark (
  consumer_group text NOT NULL,
  topic          text NOT NULL,
  partition      int NOT NULL,
  offset_value   bigint NOT NULL,
  updated_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer_group, topic, partition)
);
