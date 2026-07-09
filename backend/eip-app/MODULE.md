# Module: eip-app

- **Root package:** `com.eip.app`
- **Owner:** R-BA
- **Allowed dependencies:** all modules (composition root only)
- **State:** Phase-0 — first visible `/api/v1` read surface landed (TASK-0010): tenant-context web filter + `session`/`connectors` endpoints served from the RLS-protected DB. OIDC security filter chain follows (SPRINT-02).

## Purpose

Composition root / main API app: Spring Boot entry point, REST controllers, OpenAPI (springdoc), OIDC + local-account security filter chain, problem+json advice, actuator. No business logic — thin controllers over the modules.

## Packages

- `com.eip.app.api` — the `/api/v1` REST controllers, view records, and the RFC 7807 `@RestControllerAdvice` (BackendPlan §2.4: controllers live only in `eip-app`).
- `com.eip.app.tenant` — web tenant-context wiring: `TenantResolver` (dev `HeaderTenantResolver`, replaced by the OIDC resolver in SPRINT-02), `TenantContextFilter` (binds per request), `TenantScopedJdbc` (RLS-bound read helper).
- `com.eip.app.config` — composition-root config: `OpenApiConfig` (contract metadata), `DemoDataSeeder` (`demo` profile only).

## Owned tables / topics / endpoints

**Owned endpoints** (read-only; tenant-scoped under RLS — [APIDesign](../../docs/engineering/APIDesign.md), RFC 7807 errors):

- `GET /api/v1/session` — the request's tenant identity (id + organization name).
- `GET /api/v1/connectors` — the tenant's connector registry (id, type, name, status, simulation), cursor-paginated in the `PageView` envelope (`items`/`nextCursor`/`hasMore`; params `cursor`, `limit` default 50/max 200) per APIDesign §1.4.

Errors are RFC 7807 problem+json with `/problems/*` `type` URIs (BackendPlan §10). The generated OpenAPI 3 contract is served at `/v3/api-docs` (springdoc) and snapshotted to [`openapi/eip-openapi-v1.json`](openapi/eip-openapi-v1.json) (regenerate with `EIP_OPENAPI_EXPORT=1`; the additive-only diff gate + error-response docs are wired in TASK-0011 — [DEBT-011](../../work/debt-register.md)). Observability on these endpoints (metrics/traces/logs + problem `traceId`) lands with TASK-0012 ([DEBT-009](../../work/debt-register.md)). No owned tables/topics. This charter is updated in the same PR that adds them (RepositoryStructure.md §6 invariant 4).

## Invariants

- Dependency edges are exactly those listed above; any other edge fails the Spring Modulith `ModularityTests` (wired in TASK-0005) — a compile-red event, not a review comment ([BackendPlan §1](../../docs/engineering/BackendPlan.md)).
- Package-by-module: all code sits under `com.eip.app.<area>` ([CodingStandards §2.3](../../engineering-operating-system/CodingStandards.md)).

## Content roadmap

Substantive content lands per [BackendPlan §17](../../docs/engineering/BackendPlan.md) phase mapping; see [RepositoryStructure §2.1](../../engineering-operating-system/RepositoryStructure.md).
