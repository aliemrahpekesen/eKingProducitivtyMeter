-- V11 — core.tenant_role_permission_override (SecurityModel §4; DEBT-012 residual, second part):
-- tenant-editable persona role-permission overrides. Role.permissions() (com.eip.tenancy.rbac,
-- eip-tenancy) remains the fixed default matrix transcribed 1:1 from SecurityModel §4 (RbacMatrixTest
-- fails the build on drift) — this table lets a TENANT_ADMIN layer per-tenant grants/revokes on top
-- of that default, WITHOUT mutating the Java enum's own fixed set: effective permissions for a
-- (tenant, role) pair = Role.permissions() with every granted=false row's permission removed and
-- every granted=true row's permission added (RolePermissionOverrideService, eip-app).
--
-- One row per (tenant, role, permission) delta, not a full permission-list overwrite: an override
-- table shaped this way lets a tenant toggle a single permission without restating the role's entire
-- default set, and an absent row simply means "no override, use the default" — the safest possible
-- failure mode if this table is ever empty (every role behaves exactly as it does today).
--
-- Considered and rejected: reusing the existing (V1, unused) core.role table, which already has a
-- tenant-scoped `permissions text[]` column. Rejected because (a) nothing in this codebase reads or
-- writes core.role today — repurposing dead schema for a new, load-bearing feature is riskier than
-- an additive table with an unambiguous, narrow shape; (b) its shape is a full permission-list
-- overwrite, not a delta — replaying "the default plus/minus a few permissions" through a full-list
-- column requires the caller to already know (and keep in sync with) the CURRENT default set, which
-- silently drifts every time Role.permissions() changes in a future release; a delta-override table
-- never goes stale that way. See ADR-026.
CREATE TABLE core.tenant_role_permission_override (
  tenant_id   uuid NOT NULL REFERENCES core.tenant(id),
  role        text NOT NULL,
  permission  text NOT NULL,
  granted     boolean NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, role, permission)
);
CREATE TRIGGER tg_tenant_role_permission_override_touch
  BEFORE UPDATE ON core.tenant_role_permission_override
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- RLS: standard tenant_isolation policy, added via R__rls_policies.sql's enumerated list (this
-- table is uniformly tenant-scoped, unlike core.service_token's hybrid shape — no exception needed).
