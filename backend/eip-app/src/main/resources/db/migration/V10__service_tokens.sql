-- V10 — core.service_token (SecurityModel §3; DEBT-012 residual, first part): long-lived
-- CI/script credentials, bound to a Role + optional permission subset, stored as a SHA-256 hash
-- (never the raw value), prefix-identifiable (`eipt_...`), expiring, revocable, last-used tracked.
--
-- Tenancy shape is deliberately a HYBRID, unlike every other table in this catalog:
--   - tenant_id NOT NULL  -> a tenant-scoped token, created by that tenant's TENANT_ADMIN.
--   - tenant_id IS NULL   -> a platform-scoped token, created by PLATFORM_ADMIN only
--                            (SecurityModel §3), mirroring core.tenant's own platform scope.
--
-- RLS: deliberately NOT added to R__rls_policies.sql's tenant_isolation loop (see that file's
-- trailing comment for the full rationale). Summary: (1) the authentication lookup
-- (ServiceTokenRepository#findByHash) runs BEFORE any tenant is known — it IS the mechanism that
-- discovers the tenant — so a `tenant_id = current_setting('app.tenant_id')::uuid` predicate would
-- either error (GUC unset) or hide the very row the lookup exists to find; (2) the table mixes
-- tenant-scoped and platform-scoped rows in one relation, unlike the uniform per-tenant shape the
-- standard policy loop assumes. The application layer (ServiceTokenRepository) filters explicitly
-- per query (`tenant_id = :tenantId` for tenant-scoped listing/revocation, `tenant_id IS NULL` for
-- platform-scoped), and ServiceTokenService enforces PLATFORM_ADMIN-only access to platform-scoped
-- rows before the repository is ever called — the same "no RLS, application enforces" precedent
-- `core.tenant` already establishes (V1, DatabasePlan §2), applied here to a table that cannot be
-- purely platform-scoped. Recorded as ADR-025; DatabasePlan.md §2's enumerated no-RLS exception
-- list is updated in lockstep (that list's own governance rule requires R-SA + R-DBA sign-off —
-- flagged as outstanding in this change's PR/report, not silently assumed).
CREATE TABLE core.service_token (
  id                     uuid PRIMARY KEY,
  tenant_id              uuid REFERENCES core.tenant(id),
  name                   text NOT NULL,
  token_prefix           text NOT NULL,
  token_hash             text NOT NULL,
  role                   text NOT NULL CHECK (role IN (
                           'PLATFORM_ADMIN','TENANT_ADMIN','ENGINEERING_MANAGER','TEAM_LEAD',
                           'RELEASE_MANAGER','ANALYST','MEMBER','VIEWER','EXECUTIVE_VIEWER',
                           'SECURITY_AUDITOR')),
  permission_subset      text[],
  created_by_member_id   uuid,
  expires_at             timestamptz NOT NULL,
  revoked_at             timestamptz,
  last_used_at           timestamptz,
  created_at             timestamptz NOT NULL DEFAULT now()
);

-- Lookup path: ServiceTokenAuthenticationFilter hashes the presented bearer value and looks it up
-- by hash on every request carrying an `eipt_...` token.
CREATE UNIQUE INDEX ux_service_token_hash ON core.service_token (token_hash);

-- Listing path: an admin panel listing (tenant-scoped or platform-scoped, per tenant_id) filtered
-- to active/revoked.
CREATE INDEX ix_service_token_tenant_revoked ON core.service_token (tenant_id, revoked_at);
