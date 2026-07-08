-- V1__baseline.sql — EIP Phase-0 database baseline (DatabasePlan.md §1–§7).
-- Increment 1 (TASK-0009): the `core` schema + tenancy-core tables + shared conventions.
-- Remaining canonical schemas (work/scm/cicd/quality/ops/analytics/ai/reports/audit/staging),
-- infra tables, partition templates, and pgvector RAG tables land in later increments of this
-- same baseline before Sprint-01 close. Forward-only; RLS policies live in R__rls_policies.sql.
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
