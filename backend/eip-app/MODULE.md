# Module: eip-app

- **Root package:** `com.eip.app`
- **Owner:** R-BA
- **Allowed dependencies:** all modules (composition root only)
- **State (TASK-0016):** composition root + REST/infrastructure adapter only. `api` (thin @RestController DTO adapters — no SQL/JdbcClient/pagination mechanics), `application` (ports `RunFrictionPipelineUseCase`, `ListConnectorsQuery`, `GetSessionQuery` + the pipeline orchestrator), `persistence` (control-plane query adapters), `config` (bean wiring, validated `@ConfigurationProperties`, demo seeder, prod startup guard). Boundaries enforced by `ApplicationModularityTests` (`ApplicationModules.of("com.eip").verify()`) + `ArchitectureRulesTest`.
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
- `GET /api/v1/friction/summary` — the Engineering Friction summary: the `engineering_friction` metric definition (purpose/formula/caveats/gaming-risks) + worst-first per-team friction (from `analytics.rm_team_flow_current` + `core.team`). Deterministic (pure `FrictionScore`), team-level only (Law 6). The real friction compute belongs in `eip-analytics` — [DEBT-013](../../work/debt-register.md).

Errors are RFC 7807 problem+json with `/problems/*` `type` URIs (BackendPlan §10). The generated OpenAPI 3 contract is served at `/v3/api-docs` (springdoc) and snapshotted to [`openapi/eip-openapi-v1.json`](openapi/eip-openapi-v1.json) (regenerate with `EIP_OPENAPI_EXPORT=1`; the additive-only diff gate + error-response docs are wired in TASK-0011 — [DEBT-011](../../work/debt-register.md)). Observability on these endpoints (metrics/traces/logs + problem `traceId`) lands with TASK-0012 ([DEBT-009](../../work/debt-register.md)). No owned tables/topics. This charter is updated in the same PR that adds them (RepositoryStructure.md §6 invariant 4).

## Observability (TASK-0012)

The `/api/v1` surface is instrumented per [ObservabilityModel §3–§4](../../docs/architecture/ObservabilityModel.md):

- **Metrics** — `com.eip.app.observability.ApiObservabilityFilter` emits `eip_api_request_duration_seconds` (RED; tags `method, route, status, tenant_present`; `route` is the handler template, never the raw path) + `eip_api_requests_inflight`, common tag `deployable=eip-app`. **The tenant id is never a metric label** (cardinality). Scrape at `GET /actuator/prometheus`; health/info at `/actuator/health` (liveness/readiness) and `/actuator/info`.
- **Traces** — OpenTelemetry auto-instrumentation (HTTP→JDBC), 100 % SDK export, OTLP → the Compose collector (`${EIP_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}`). View in Grafana/Tempo when deployed; without a trace backend the `traceId` still flows through logs and metrics.
- **Logs** — structured JSON on stdout under `demo`/`prod` (`logging.structured.format.console=logstash`), human-readable locally. Every line carries `tenantId`/`traceId`/`spanId` (MDC: tenant from `TenantContextFilter`, trace ids from the OTel scope) plus bounded request fields (`method`, `route`, `status`, `durationMs`, `errorType`). No secrets/PII (ObservabilityModel §4).
- **Errors** — every RFC 7807 problem+json carries `traceId` (`ApiExceptionHandler`), so a client error is walkable to its trace and logs.
- **View locally:** `docker compose up` the observability stack (OTel Collector + Prometheus + Grafana, `infra/docker-compose`), run the app with `--spring.profiles.active=demo` for JSON logs, then scrape `/actuator/prometheus` (or let the collector scrape it) and open Grafana.

## Invariants

- Dependency edges are exactly those listed above; any other edge fails the Spring Modulith `ModularityTests` (wired in TASK-0005) — a compile-red event, not a review comment ([BackendPlan §1](../../docs/engineering/BackendPlan.md)).
- Package-by-module: all code sits under `com.eip.app.<area>` ([CodingStandards §2.3](../../engineering-operating-system/CodingStandards.md)).

## Content roadmap

Substantive content lands per [BackendPlan §17](../../docs/engineering/BackendPlan.md) phase mapping; see [RepositoryStructure §2.1](../../engineering-operating-system/RepositoryStructure.md).
