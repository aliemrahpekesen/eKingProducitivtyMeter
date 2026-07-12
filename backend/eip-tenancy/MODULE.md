# Module: eip-tenancy

- **Root package:** `com.eip.tenancy`
- **Owner:** R-BA (R-PA: tenancy/quotas)
- **Allowed dependencies:** eip-core
- **State (TASK-0016):** owns the tenant-aware transaction boundary: `com.eip.tenancy.tx.TenantTransactionRunner` (Spring `TransactionTemplate` + RLS GUC bind on the transaction-bound connection) alongside the pure-JDBC `context` primitives. Spring Modulith OPEN module.
- **State:** Phase-0 — tenant-context RLS mechanism (`com.eip.tenancy.context`: `TenantContext`, `RlsTenantBinder` — TASK-0009; thread-bound `TenantContextHolder` — TASK-0010) landed; tenancy-core tables owned. RBAC/audit/secret services follow (SPRINT-02).

## Purpose

Organizations, tenants, BusinessUnits, Teams, Members, RBAC, audit log, tenant context propagation, Postgres RLS session-variable management (`app.tenant_id`).

## Owned tables / topics / endpoints

**Owned tables** (per [DatabasePlan §2](../../docs/engineering/DatabasePlan.md), created by the eip-app Flyway baseline; subset of the §2 catalog):

- `core.tenant` — tenant registry (platform-scoped, **no RLS** — enumerated exception, DatabasePlan §2).
- `core.organization`, `core.business_unit`, `core.team`, `core.role`, `core.member`, `core.member_identity`, `core.secret` — tenant-scoped, RLS enabled+forced.
- `audit.audit_event` — append-only, hash-chained (partition template landed; chain filler + verifier arrive with the SPRINT-02 audit subsystem, SecurityModel §11).

`core.member`/`core.member_identity` carry the only PII columns (display name, email) — FR-142 erasure targets ([DatabasePlan §10.1](../../docs/engineering/DatabasePlan.md)). No topics/endpoints yet. Remaining tenancy tables and the audit hash chain land in continued TASK-0009 increments / SPRINT-02; this charter is updated in the same PR (RepositoryStructure §6 invariant 4).

## Invariants

- Dependency edges are exactly those listed above; any other edge fails the Spring Modulith `ModularityTests` — a compile-red event, not a review comment ([BackendPlan §1](../../docs/engineering/BackendPlan.md)).
- Package-by-module: all code sits under `com.eip.tenancy.<area>` ([CodingStandards §2.3](../../engineering-operating-system/CodingStandards.md)).
- **Tenant isolation is the backstop, not the mechanism** ([DatabasePlan §5](../../docs/engineering/DatabasePlan.md)): every tenant-scoped table is RLS enabled+forced with the `tenant_isolation` policy; the transaction-scoped `app.tenant_id` GUC is set only via `RlsTenantBinder` (`set_config(..., true)`); an unset GUC errors rather than returning zero rows. Every tenant-owned unique constraint includes `tenant_id` (scale-out seam, §15).

## Content roadmap

Substantive content lands per [BackendPlan §17](../../docs/engineering/BackendPlan.md) phase mapping; see [RepositoryStructure §2.1](../../engineering-operating-system/RepositoryStructure.md).
