-- V3 — canonical flow entities + cross-tool correlation evidence + computed friction read model
-- (TASK-0016 INC-2). Normalizers (eip-ingestion) populate the canonical tables from raw staging;
-- the analytics engine (eip-analytics) correlates them and computes team-level friction. All tables
-- are tenant-scoped (RLS via R__rls_policies). Natural keys carry a (tenant_id, source_key) unique
-- constraint so re-normalizing the same source is idempotent.
--
-- work.work_item already exists (V1 control-plane baseline, DatabasePlan §14 / DEBT-008); this adds
-- its transition history and the scm/cicd/quality detail tables the friction slice needs.
--
-- FK convention (DatabasePlan §2): FKs are enforced only WITHIN a schema; cross-schema/cross-context
-- references are plain `uuid` columns validated at the application layer, so schemas (which align 1:1
-- with modules) stay independently extractable. Cross-schema links below are therefore bare uuids.

CREATE TABLE work.work_item_transition (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL,
  work_item_id uuid NOT NULL REFERENCES work.work_item (id),  -- intra-schema (work) FK
  seq          int  NOT NULL,               -- 1-based order within the item's timeline
  from_state   text,                        -- null for the first transition
  to_state     text NOT NULL,
  occurred_at  timestamptz NOT NULL,
  CONSTRAINT ux_work_item_transition UNIQUE (tenant_id, work_item_id, seq)
);
CREATE INDEX ix_work_item_transition_item ON work.work_item_transition (tenant_id, work_item_id, seq);

CREATE TABLE scm.pull_request (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL,
  team_id           uuid,                                    -- core.team.id (cross-schema, plain uuid)
  work_item_id      uuid,                                    -- work.work_item.id, cross-tool link (cross-schema, plain uuid)
  source_key        text NOT NULL,                          -- 'PR-101'
  title             text,
  source_branch     text,
  status            text,
  created_in_source timestamptz,
  merged_at         timestamptz,
  CONSTRAINT ux_pull_request UNIQUE (tenant_id, source_key)
);
CREATE INDEX ix_pull_request_work_item ON scm.pull_request (tenant_id, work_item_id);

CREATE TABLE scm.code_review (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL,
  pull_request_id uuid NOT NULL REFERENCES scm.pull_request (id),  -- intra-schema (scm) FK
  source_key      text NOT NULL,                            -- 'REV-101'
  outcome         text,                                     -- 'APPROVED' | 'CHANGES_REQUESTED'
  requested_at    timestamptz,
  completed_at    timestamptz,
  CONSTRAINT ux_code_review UNIQUE (tenant_id, source_key)
);
CREATE INDEX ix_code_review_pr ON scm.code_review (tenant_id, pull_request_id);

CREATE TABLE cicd.build (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL,
  pull_request_id uuid,                                     -- scm.pull_request.id (cross-schema, plain uuid)
  source_key      text NOT NULL,                            -- 'BUILD-101'
  status          text,
  started_at      timestamptz,
  finished_at     timestamptz,
  CONSTRAINT ux_build UNIQUE (tenant_id, source_key)
);

CREATE TABLE quality.quality_gate (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL,
  build_id        uuid,                                     -- cicd.build.id (cross-schema, plain uuid)
  pull_request_id uuid,                                     -- scm.pull_request.id (cross-schema, plain uuid)
  source_key      text NOT NULL,                            -- 'QG-101'
  status          text,
  evaluated_at    timestamptz,
  CONSTRAINT ux_quality_gate UNIQUE (tenant_id, source_key)
);

-- Cross-tool correlation evidence: one row per work item stitching the chain (work → PR → build →
-- gate) with the per-item flow-time decomposition the team metric aggregates. This is the drill-to-
-- evidence backing store (team-level analysis; identifies artifacts, never individuals — NFR-071).
-- Cross-schema links are plain uuids (DatabasePlan §2); doubly so here because this is a rebuildable
-- projection (ADR-015) and must not be FK-pinned to canonical row lifecycles.
CREATE TABLE analytics.flow_correlation (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL,
  team_id         uuid NOT NULL,             -- core.team.id (cross-schema, plain uuid)
  work_item_id    uuid NOT NULL,             -- work.work_item.id (cross-schema, plain uuid)
  pull_request_id uuid,                      -- scm.pull_request.id (cross-schema, plain uuid)
  build_id        uuid,                      -- cicd.build.id (cross-schema, plain uuid)
  quality_gate_id uuid,                      -- quality.quality_gate.id (cross-schema, plain uuid)
  cycle_time_sec  bigint NOT NULL,
  active_sec      bigint NOT NULL,
  blocked_sec     bigint NOT NULL,
  review_wait_sec bigint NOT NULL,
  waiting_sec     bigint NOT NULL,
  rework_count    int    NOT NULL,
  computed_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ux_flow_correlation UNIQUE (tenant_id, work_item_id)
);
CREATE INDEX ix_flow_correlation_team ON analytics.flow_correlation (tenant_id, team_id);

-- Computed friction read model (DatabasePlan §8, ADR-015): the per-team surface the friction API
-- reads, replacing the seed-only rm_team_flow_current as its source of truth. Versioned metric.
CREATE TABLE analytics.rm_team_friction_current (
  id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL,
  team_id           uuid NOT NULL,
  metric_version    text NOT NULL,                          -- 'engineering_friction_v0.1'
  work_items        int    NOT NULL,
  total_cycle_sec   bigint NOT NULL,
  active_sec        bigint NOT NULL,
  waiting_sec       bigint NOT NULL,
  blocked_sec       bigint NOT NULL,
  review_wait_sec   bigint NOT NULL,
  rework_count      int    NOT NULL,
  flow_efficiency   numeric(5, 4) NOT NULL,
  blocked_ratio     numeric(5, 4) NOT NULL,
  review_wait_ratio numeric(5, 4) NOT NULL,
  friction_score    int    NOT NULL,                        -- composite 0..100 (EXPERIMENTAL v0.1)
  dominant_cause    text   NOT NULL,                        -- 'BLOCKED' | 'REVIEW_WAIT' | 'NONE'
  computed_at       timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ux_rm_team_friction UNIQUE (tenant_id, team_id, metric_version)
);
