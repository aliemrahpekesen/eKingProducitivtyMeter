# ADR-004: Enforce tenant isolation with `tenant_id` columns and PostgreSQL Row-Level Security

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [DatabasePlan](../engineering/DatabasePlan.md), [SecurityModel](../architecture/SecurityModel.md); `eip-tenancy` + every tenant-owned table; CC-1.

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). Multi-tenant structural isolation (D4) is a release-blocking property (FR-128): a tenant must never see another tenant's data, and isolation must hold even when application code has a bug. Every tenant-scoped entity already carries a `tenantId`.

## Decision

Isolate tenants with a `tenant_id` column on every tenant-owned table plus PostgreSQL Row-Level Security policies keyed on the `app.tenant_id` GUC, set transaction-scoped via `SET LOCAL` by the tenant-context filter. RLS is the database backstop; the application tenant filter is the primary mechanism.

## Consequences

- **Positive:** structural isolation enforced by the database even against an application fault; cheaper to operate than schema- or database-per-tenant at the target tenant scale; a single, uniform isolation model across all canonical schemas.
- **Negative:** every tenant-scoped table must carry the policy and be exercised by an RLS `TENANT_A/TENANT_B` probe test; constructs that RLS cannot attach to — notably materialized views — are forbidden for tenant-scoped data (ADR-015); a missing `SET LOCAL` would fail closed rather than leak, but must be prevented by the connection customizer.

## Alternatives rejected

- **Schema-per-tenant or database-per-tenant** — rejected: migration and operational cost grows with tenant count (D6), and cross-tenant analytics becomes awkward.
- **Application-only filtering without RLS** — rejected: a single omitted predicate leaks cross-tenant data with no database backstop; RLS exists precisely to survive that bug class (FR-128).
