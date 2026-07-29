# TASK-0012 — R-CR Review (with CC-7 G6 R-OE approval)

- **Task:** [../tasks/TASK-0012.md](../tasks/TASK-0012.md) · story P0-E4-S3 (observability lane) · **change class CC-7** (new observability paths → G6)
- **Branch:** `feature/TASK-0012-observability` · **base:** `integration/SPRINT-00` · **commit reviewed:** `7f8a296`
- **Reviewers:** R-CR (independent) + **R-OE** (G6 gate owner — the gate this task satisfies) · **Date:** 2026-07-09
- **Method:** independent adversarial review — 5 fresh-context dimensions (build/test, metrics+cardinality, logs/MDC/trace/PII, tracing/config/compat, gates/DoD/DEBT), **every finding adversarially re-verified** (refute-first). 15 agents; 3 findings refuted. `./gradlew check` independently re-run (Testcontainers, no cache). Cited docs: [ObservabilityModel §2–§6](../../docs/architecture/ObservabilityModel.md), [BackendPlan §10/§12](../../docs/engineering/BackendPlan.md), [QualityGatePolicy G6](../../engineering-operating-system/QualityGatePolicy.md), [ObservabilityRequirements](../../engineering-operating-system/ObservabilityRequirements.md), [DefinitionOfDone](../../engineering-operating-system/DefinitionOfDone.md).

## Overall verdict: **APPROVED** (post-remediation) — 0 BLOCKER · 0 MAJOR

The instrumentation is correct and, critically, **cardinality-safe and leak-safe** — the two release-blocking risks for this kind of change. Initial verdict was **0 BLOCKER, 1 MAJOR, 4 MINOR, 2 NIT**; the MAJOR and the cheap findings were remediated before merge (the MAJOR blocked a clean approval under the merge gate). One doc-drift MINOR is registered as DEBT-014 (deferred to a governed doc PR, since this task must not alter the product-spec baseline).

## Verification (all independently confirmed)

| Area | Verdict | Evidence |
|---|---|---|
| `./gradlew check` all modules | **PASS** | `BUILD SUCCESSFUL`; coverage ratchets hold; 12 eip-app tests (4→5 observability + all pre-existing) green |
| **Cardinality safety** (the #1 risk) | **PASS** | `route` = matched handler template (`BEST_MATCHING_PATTERN_ATTRIBUTE`) with an `UNMATCHED` bucket; **no raw path/URI/query ever a tag**; tenant is a bounded `tenant_present` boolean — **never the tenant id**; `method` allow-listed → `OTHER` (remediation); `status` bounded. Test asserts the scrape **does not contain** the tenant UUID |
| **MDC no-leak** | **PASS** | `TenantContextFilter` puts `tenantId` only when resolved and removes it in a `finally` that runs on the exception path; `ApiObservabilityFilter` removes all five per-request keys in a `finally`; no early-return skips a removal. Test asserts `MDC.get("tenantId")` is null after a request |
| **traceId on every problem+json** | **PASS** | `ApiExceptionHandler` injects `Tracer`, sets `traceId` from the current span (null-guarded) on both problem builders; test asserts hex `traceId` on 401 + 400 |
| **Structured JSON logs** | **PASS** | `logging.structured.format.console=logstash` gated to `demo\|prod`; local/default human-readable; access log carries `tenantId`/`traceId`/`spanId` + bounded `method/route/status/durationMs/errorType` — no secrets/PII. Test (remediation) asserts the `api.request` event's MDC |
| **Tracing/OTLP** | **PASS** | 100 % SDK sampling (collector samples — ObservabilityModel §4); OTLP endpoint → Compose collector 4318, env-overridable; exporter is async/**fail-open** (no synchronous collector dependency in the request path) |
| **Actuator** | **PASS** | `health` (probes), `info`, `prometheus` exposed; prometheus export explicitly enabled (needed — the defaults fallback resolved false in this context) |
| **Compatibility** | **PASS** | OpenAPI snapshot **byte-identical**; `/api/v1` response bodies/behaviour unchanged (traceId only on error bodies); all pre-existing RLS/determinism/pagination tests green |
| **Deps** | **PASS** | actuator + micrometer-registry-prometheus + micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp, all Boot-BOM-versioned; Apache-2.0, air-gap-friendly |

## Findings (all re-verified; 3 refuted)

### MAJOR (remediated)

- **MAJOR-1 — RED timer had no histogram buckets.** `eip_api_request_duration_seconds` was a plain `Timer` (no `publishPercentileHistogram`/SLO), so Prometheus emitted only `_count`/`_sum` — the documented p95/SLO dashboards (ObservabilityModel §3 calls it a *histogram*) had no `_bucket` series. → **Fixed**: `management.metrics.distribution.percentiles-histogram.eip.api.request.duration=true` + `slo=300ms` (the API p95 budget). Test now asserts `eip_api_request_duration_seconds_bucket`.

### MINOR

- **MINOR-1/2 — `tenant_present` not in the ObservabilityModel §3 catalog** (which lists `method, route, status`). The tag is **required by the task brief** and is a bounded, tenant-safe boolean, so it stays; the catalog is the doc that lags. → **DEBT-014** (CC-6 R-OE doc PR to add it — deferred because TASK-0012 must not edit the product-spec baseline; docs-first).
- **MINOR-3 — AC3 (structured JSON log) had no test.** → **Fixed**: `api_request_log_carries_the_correlation_and_context_fields` asserts the `api.request` event's MDC (method/route/status/durationMs/tenantId/traceId).
- **MINOR-4 — no SLO impact statement** (ObservabilityRequirements §7, required for G6-scope PRs). → **Fixed**: added to the task file.

### NIT (remediated)

- **NIT-1 — `method` tag taken verbatim** (client-controlled). → **Fixed**: allow-list → `OTHER`.
- **NIT-2 — metrics test asserted tag substrings independently.** → **Fixed**: asserts `route`+`tenant_present`+`method`+`deployable` on one `eip_api_request_duration_seconds` line.

### Refuted (no action)

- Access log omits ObservabilityModel §5 `module`/`event` fields — **refuted** (those are the domain-event log schema, not the HTTP access log).
- Grafana dashboard/alert deferral not tracked — **refuted** (dashboards/alerts live in `/infra` per ObservabilityModel §5/§6; called out in the task's out-of-scope + SLO statement).
- DEBT-009 marked resolved while the task is IN_REVIEW/unmerged — **refuted** (the resolution accurately reflects the code delivered on this branch; it lands on merge).

## Gate status (CC-7)

| Gate | Status |
|---|---|
| G1 Build & Static | **PASS** |
| G2 Tests | **PASS** — 5 observability tests + coverage ratchets |
| G3 Security | **PASS** — no secrets/PII in logs; tenant-safe, bounded metric tags |
| **G6 Observability (R-OE)** | **APPROVED** — metrics (`eip_*`, histogram), OTel traces (OTLP→collector), structured JSON logs, problem `traceId`; SLO impact statement present; DEBT-009 legitimately closed |
| G7 Documentation | **PASS** — MODULE.md observability section + task + sprint updated in-PR; DEBT-014 records the catalog drift |
| G8 Review & Done | this review |

## Verdict: **APPROVED** — cleared to merge into `integration/SPRINT-00`. DEBT-009 resolved.
