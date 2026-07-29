# Observability Requirements

This document defines gate **G6**: what every change that adds an endpoint, Kafka consumer, scheduled job, or worker profile must ship in telemetry before it can merge. It is read by implementation engineers (R-IE) when writing the task's observability plan, and enforced by the Observability Engineer (R-OE), who holds the A4 verdict on G6. The source of record for names, pipelines, dashboards, alerts, and SLOs is [../docs/architecture/ObservabilityModel.md](../docs/architecture/ObservabilityModel.md); this document only turns it into merge requirements.

## 1. Principle: unobservable = unfinished

A feature without its metrics, traces, and structured logs does not exist operationally. Task specs MUST contain an observability plan (or "none — justified", per the task-spec template in [./AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) §3.1); the PR template MUST state the observability delta; DoD is not reachable with a missing telemetry surface ([./DefinitionOfDone.md](./DefinitionOfDone.md)). "It works but we can't see it" is a G6 BLOCKER, not a DEBT candidate.

## 2. When G6 applies

G6 applies to every PR that adds or materially changes: an `/api/v1` endpoint, a webhook intake, a Kafka consumer or producer path, a scheduled job (Quartz/partition/retention/re-index), a worker profile in `eip-workers`, an MCP capability, an agent, a cache, or a failure mode in any of the above. CC-6 (docs-only) PRs are exempt.

A PR with genuinely zero telemetry-surface change records `observability delta: none` in its description (PR template, [./AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) §3.2); an empty field is a G6 failure, not an implicit "none". NFR-052 is the floor: every module exposes OTel traces/metrics/logs.

## 3. Metrics

- MUST be produced via Micrometer with OTel/Prometheus export and the `eip_` prefix, following the ObservabilityModel §3 catalog conventions: `_total` counters, `_seconds` histograms, gauges unsuffixed; common labels `deployable`, `worker_profile` (when applicable), `pod`. Dotted Micrometer ids (e.g., `eip.consumer.lag_seconds` from [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §12) are acceptable at declaration only where they render to the catalog's Prometheus name.
- New metrics MUST be added to the ObservabilityModel §3 catalog table in the same PR (docs-first law, [./EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) §5) — an uncataloged `eip_*` metric fails G7 docs-lint and G6.
- Labels MUST stay cardinality-bounded: `tenantId` only where per-tenant behavior matters (ingestion, LLM cost); **never** `entityId`, user ids, raw paths, or free text (ObservabilityModel §1). Route labels use the route template, never the raw path.
- Cardinality budgets are reviewed per metric at code review (ObservabilityModel §1 principle 3): the PR lists each new label and its bounded value set; Prometheus TSDB head-cardinality guards (§8.1) are the backstop, not the control.
- JVM, Kafka client, HTTP client/server, and Hikari metrics come from Micrometer's standard binders (ObservabilityModel §3) — do not hand-roll duplicates.
- Per surface type, the minimum set is:

| Surface | Minimum metrics |
|---|---|
| `/api/v1` endpoint | Covered by `eip_api_request_duration_seconds{method,route,status}` — verify the route template registers; add a domain counter only if the endpoint has a non-HTTP failure mode |
| Kafka consumer | Lag visibility (`eip_kafka_consumer_lag` + `eip.consumer.lag_seconds` per group/topic), DLQ counter `eip_kafka_dlq_messages_total`, domain outcome counter with bounded `outcome` label |
| Scheduled job | `eip_job_failures_total{job="<name>"}` incremented on failure — this label value is the alerting contract |
| Connector code path | The connector family metrics of §3 (`eip_connector_*`, `eip_ingestion_events_total`, `eip_canonical_visibility_lag_seconds`) |
| Agent / LLM / RAG path | `eip_agent_runs_total`, `eip_llm_calls_total`/`_tokens_total`/`_cost_estimate`, `eip_rag_retrieval_latency_seconds` as applicable |
| Frontend feature | web-vitals + error beacon route through `eip-app` to the Collector (ObservabilityModel §2) — the frontend never talks to telemetry backends directly |
| Cache | `eip_cache_hit_ratio` for the named cache |

## 4. Traces

- Spans MUST follow ObservabilityModel §4 naming — `<module>.<component>.<operation>` in lower-case dot notation (API spans use HTTP semantic conventions with route templates).
- Span attributes MUST always include `eip.tenant_id` and MUST NOT include payload content, secrets, prompt/completion text, or member identities (ObservabilityModel §4; PII rules mirror [./SecurityChecklist.md](./SecurityChecklist.md) §3.5).
- `traceparent` MUST propagate through the canonical event envelope **and** Kafka record headers across ingestion, analytics, AI, and report pipelines (FR-127; ObservabilityModel §4) — a new consumer continues the trace with a link to the producing span. A pipeline hop that drops trace context is a G6 BLOCKER.
- Sampling: SDKs export **100% of spans** — code MUST NOT configure SDK head sampling. All sampling executes in the OTel Collector (ObservabilityModel §4): probabilistic policies (API 10%, background sync 1–10%) plus tail rules retaining **100%** of agent runs, LLM calls, report jobs, DLQ handling, migrations, secret/KMS operations, and any request ending in 5xx — this is what makes the 100%-of-5xx guarantee real. New flows inherit this policy; new always-on classes are added to the Collector tail-sampling config, never as SDK samplers. Auditable actions write audit events inside the active span context so `traceId` is never `-` (ObservabilityModel §10).
- Trace backend optionality holds: code MUST NOT require Tempo — correlation must survive in reduced form via `traceId` in logs, metric exemplars, and audit events (ObservabilityModel §4).

## 5. Structured logs

- JSON on stdout, matching the ObservabilityModel §5 schema (`timestamp`, `level`, `tenantId`, `traceId`/`spanId`, `module`, `event`, `message`, bounded allow-listed context keys). New machine-readable `event` keys are snake_case and listed in the PR description.
- Redaction rules are non-negotiable and CI-tested: no secrets (replaced `[REDACTED]`), no prompt/completion bodies, no Member names/emails, no raw `raw_*` payloads (digests + sizes only).
- No `TRACE` level in production profiles; stack traces at `ERROR` only, with message-part redaction.

## 6. Dashboard and alert delta rule

- Every **new failure mode** MUST ship, in the same PR: (a) a Prometheus alert rule in `/infra/kubernetes` (PrometheusRule) and `/infra/docker-compose` (rules file) with a `runbook` annotation, and (b) the runbook anchor it points at added to [../docs/operations/OperationsGuide.md](../docs/operations/OperationsGuide.md) §4. An alert without a runbook line, or a failure mode without an alert, fails G6 (pattern per ObservabilityModel §7).
- Alert severity follows the §7 policy: `critical` pages on-call, `warning` goes to the ops channel, `info` is dashboard-only; security-tagged alerts additionally route to the security channel.
- New signals that belong on one of the seven shipped dashboards (System Health, Ingestion & Connectors, Kafka & Queues, API & Latency, AI/LLM Usage & Cost, Database & Cache, Report Jobs — ObservabilityModel §6) MUST update the corresponding JSON in `/infra/grafana` in the same PR; dashboards are versioned with the release.
- New latency histograms SHOULD attach `traceId` exemplars where the backend supports them, so Grafana panels deep-link spike → trace → audit (ObservabilityModel §10 rule 3).

## 7. SLO impact statement

- Every PR in G6 scope MUST include an SLO impact statement in its observability-delta field: which ObservabilityModel §8 SLO(s) the change can affect (API availability, API latency non-analytical, dashboard latency, ingestion freshness, report success rate, AI job completion) — or the explicit line "SLO impact: none", with one sentence of justification.
- Changes to an SLI definition, SLO target, or burn-rate window are CC-1-adjacent for operations: they require R-OE approval plus an ObservabilityModel docs PR first; code never redefines an SLO silently.
- When error budget for a deployment is exhausted, the ObservabilityModel §8 policy applies: reliability work preempts feature rollout — R-TPM reflects this in sprint planning.

## 8. Health checks and eip-workers parity

- **Self-observability parity:** every `eip-workers` profile (`ingestion|analytics|ai|reports`) ships the identical telemetry stack as `eip-app` — OTel SDK + Micrometer via the Collector, the §5 log schema, and Actuator liveness/readiness/startup probes (ObservabilityModel §2, §9). A worker that is less observable than the API tier fails G6.
- New workers register heartbeats (`worker_heartbeat` rows, [../docs/engineering/BackendPlan.md](../docs/engineering/BackendPlan.md) §12) and their scheduled jobs appear in `eip_job_failures_total`.
- New hard dependencies MUST NOT be added to readiness: PostgreSQL reachability + Flyway version are the only hard readiness dependencies; Kafka and Redis are degraded-not-unready (ObservabilityModel §9, NFR-020). New subsystems surface in the auth-gated `/actuator/health/deep` component detail instead.
- Meta-monitoring (§8.1) MUST keep covering new sources: a new always-emitting signal SHOULD get an absence alert if its silence would mask an outage.

## 9. G6 checklist

Per surface added or changed — all boxes required before R-OE sign-off:

**Endpoint / webhook**
- [ ] Route-template metrics registered; 4xx/5xx visible in RED panels; rate-limit outcomes labeled.
- [ ] Trace context returned/propagated; span attrs clean of PII (§4).
- [ ] SLO impact statement present (§7).

**Kafka consumer / producer**
- [ ] Lag + DLQ metrics live (§3); `traceparent` continued from envelope and headers (§4).
- [ ] DLQ handling spans always-sampled; `dlq_message_parked` log event emitted.
- [ ] New failure modes have alert + runbook anchor (§6).

**Scheduled job / worker**
- [ ] `eip_job_failures_total{job=...}` wired; job covered by `EipScheduledJobFailed`-style alerting where failure is operator-actionable (§6).
- [ ] Worker profile at telemetry parity with `eip-app`; heartbeat + probes wired (§8).

**Agent / LLM / RAG / MCP path**
- [ ] AI metric family incremented (§3); 100% trace sampling; audit ↔ trace join intact via `traceId` (ObservabilityModel §10).

**Every G6 PR**
- [ ] Logs match §5 schema and redaction; no new PII surface.
- [ ] `traceId` is never `-` on auditable actions (root span created on rare batch paths — ObservabilityModel §10 rule 1).
- [ ] `/infra/grafana` dashboards updated where the signal belongs (§6).
- [ ] ObservabilityModel §3 catalog updated for any new metric (§3).
- [ ] Observability delta recorded in the PR description (template per [./AgentCommunicationProtocol.md](./AgentCommunicationProtocol.md) §3.2).

## Related documents

- [../docs/architecture/ObservabilityModel.md](../docs/architecture/ObservabilityModel.md) — source of record: catalog, tracing, logging, dashboards, alerts, SLOs
- [../docs/operations/OperationsGuide.md](../docs/operations/OperationsGuide.md) §4 — runbooks referenced by alert annotations
- [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §12 — lag signals · [../docs/product/PRD.md](../docs/product/PRD.md) — FR-126, FR-127, NFR-052
- [./QualityGatePolicy.md](./QualityGatePolicy.md) · [./DefinitionOfDone.md](./DefinitionOfDone.md) · [./SecurityChecklist.md](./SecurityChecklist.md) · [./PerformanceChecklist.md](./PerformanceChecklist.md) · [./TestingChecklist.md](./TestingChecklist.md) · [./CodeReviewChecklist.md](./CodeReviewChecklist.md)
