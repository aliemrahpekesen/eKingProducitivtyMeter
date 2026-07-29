-- V2 — ingestion raw-staging tables (TASK-0016 INC-1, DatabasePlan §2 `staging.raw_<connector>`).
-- Connectors emit raw source records here through the RawSink; normalizers (eip-ingestion) read
-- them into the canonical model. Tenant-scoped (RLS via R__rls_policies). Idempotency is the
-- (tenant_id, connector_id, stream, natural_key) unique key + the content hash: replaying the same
-- source dataset upserts in place and never creates duplicates.
--
-- v0.1 note: not partitioned (DatabasePlan §6 plans weekly RANGE on ingested_at at volume; deferred
-- while the only producer is the deterministic simulation connector). See DEBT-008/DEBT-017.

CREATE TABLE staging.raw_simulation (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       uuid NOT NULL,
  connector_id    uuid NOT NULL,               -- core.connector.id; cross-schema ref kept a plain uuid (DatabasePlan §2: module extractability)
  stream          text NOT NULL,               -- 'work_item','work_item_transition','pull_request','code_review','build','quality_gate'
  natural_key     text NOT NULL,               -- source natural key, e.g. 'DEMO-101','PR-11','BUILD-31'
  source_system   text NOT NULL,               -- provenance: 'jira','bitbucket','ci','sonarqube'
  source_instance text NOT NULL DEFAULT 'sim',
  external_id     text NOT NULL,               -- immutable native id (AD-14) → core.external_ref on normalize
  op              text NOT NULL DEFAULT 'upsert' CHECK (op IN ('upsert', 'delete')),
  fetch_kind      text NOT NULL DEFAULT 'full'
                    CHECK (fetch_kind IN ('full', 'incremental', 'webhook', 'reconciliation')),
  payload         jsonb NOT NULL,
  content_hash    bytea NOT NULL,              -- sha-256 of payload; unchanged hash ⇒ no downstream write
  ingested_at     timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ux_raw_simulation UNIQUE (tenant_id, connector_id, stream, natural_key)
);
CREATE INDEX ix_raw_simulation_stream ON staging.raw_simulation (tenant_id, stream);

-- Single raw/normalization dead-letter table (DatabasePlan §2; `stage` distinguishes the phase).
CREATE TABLE staging.raw_ingest_errors (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    uuid NOT NULL,
  connector_id uuid,
  stream       text,
  natural_key  text,
  stage        text NOT NULL CHECK (stage IN ('raw_intake', 'normalization')),
  error        text NOT NULL,
  payload      jsonb,
  occurred_at  timestamptz NOT NULL DEFAULT now()
);
