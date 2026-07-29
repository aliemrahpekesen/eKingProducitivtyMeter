# Performance Checklist

This checklist operationalizes gate **G5** for **CC-3** (hot-path / perf-relevant) changes. It is read by implementation engineers (R-IE) before touching a hot path, and applied by the Performance Engineer (R-PE), who holds the A4 blocking verdict on G5. Budgets come from [../docs/product/PRD.md](../docs/product/PRD.md) §6 and are quoted, never re-derived; query and storage rules come from [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md); load scenarios from [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §11.

## 1. When G5 applies

A PR MUST declare CC-3 ([./QualityGatePolicy.md](./QualityGatePolicy.md) §3) when it touches: the ingestion pipeline, the metric engine, dashboard-serving queries, or RAG retrieval — and additionally whenever it adds or changes a database query or index, a collection endpoint, a Kafka consumer, a cache, or a scheduled job with material I/O. R-CR verifies the declaration; misclassification is a BLOCKER.

## 2. Performance budgets (binding, quoted from the PRD)

| ID | Budget (verbatim from PRD §6) | Verified by |
|---|---|---|
| NFR-010 | "Dashboard API queries: p50 < 500 ms, p95 < 2 s for standard views over NFR-002 volumes." | Gatling *Metric query latency* + *Concurrent dashboard load* |
| NFR-011 | "Non-analytical REST endpoints: p95 < 300 ms." | RED metrics per route; `EipApiNonAnalyticalLatencySloBurn` alert ([../docs/architecture/ObservabilityModel.md](../docs/architecture/ObservabilityModel.md) §7) |
| NFR-012 | "Webhook-driven events visible in the canonical model within 60 s p95; polled connectors within one poll interval + 5 min p95." | `eip_canonical_visibility_lag_seconds` SLO (ObservabilityModel §8) |
| NFR-013 | "Interactive agent runs (e.g., Sprint Review) complete within 5 min p95 with local models on reference hardware; long-running report jobs within 30 min p95." | Out-of-band, non-blocking local-model job only — Gatling uses `FakeLlmProvider` and MUST NOT be cited as NFR-013 evidence (TestingStrategy §11) |
| NFR-003 | "Sustain ≥ 100,000 events/hour ingest per deployment with peak burst 3× for 15 minutes without data loss (backpressure via Kafka permitted)." | Gatling *Ingestion throughput* + *Ingestion burst* |

A CC-3 PR MUST state in its description which budget(s) it can affect and why the change stays inside them.

## 3. New-query rule

- [ ] Every new or reshaped SQL query attaches `EXPLAIN (ANALYZE, BUFFERS)` output in the PR description, captured against a dataset of representative size (≥ the 1M-row `work_item` seed for work-item paths, DatabasePlan §14).
- [ ] Every new index maps to a **named query pattern** in the DatabasePlan §4 table. No index without a named pattern; if the pattern is new, the same PR extends that table (docs-first law, [./EngineeringOperatingSystem.md](./EngineeringOperatingSystem.md) §5) and follows the naming convention `ix_/ux_<table>_<cols>`.
- [ ] New composite indexes carry the required `EXPLAIN (ANALYZE, BUFFERS)` evidence (DatabasePlan §4 rules); high-churn tables justify write amplification explicitly.
- [ ] `CREATE INDEX CONCURRENTLY` migrations are non-transactional and idempotent (`IF NOT EXISTS`) per DatabasePlan §7.

## 4. Collection endpoints: pagination is mandatory

- [ ] Every collection endpoint on `/api/v1` uses cursor pagination (FR-125, FR-073; [../docs/engineering/APIDesign.md](../docs/engineering/APIDesign.md) §1). Offset pagination and unpaginated collection responses are BLOCKERs.
- [ ] Keyset cursors ride the `(tenant_id, updated_at DESC, id)` index family with UUIDv7 `id` as tiebreaker (DatabasePlan §4); page size has a server-side maximum.
- [ ] Property-based pagination tests hold: concatenated pages ≡ unpaginated result, no overlaps or gaps (TestingStrategy §18).
- [ ] Mutating batch endpoints keep idempotency-key semantics — same key + same body → same result, no duplicate side effects (TestingStrategy §5.2); replay hits stay visible on the API & Latency dashboard.

## 5. N+1 and unbounded-fetch checks

- [ ] No N+1 access patterns: per-row lazy loads inside loops are rejected; batch fetch or join instead. Reviewer checks the SQL log of the integration test for the touched path.
- [ ] No unbounded fetches: every query has a LIMIT, a pagination cursor, or a partition-pruned time window; `findAll()`-style access on tenant data is a BLOCKER.
- [ ] Dashboard reads use precomputed `ops.metric_value` series or the projector-maintained `rm_*` read-model tables (RLS-enabled plain tables; materialized views are forbidden for tenant-scoped data per ADR-015 / FR-128) — never wide joins at request time (DatabasePlan §8).
- [ ] Long-running analytics/backfill queries run on the dedicated `analytics` pool (`maximumPoolSize=4`, `statement_timeout=5min`) so dashboards never queue behind batch work (DatabasePlan §12).
- [ ] New or changed read-model projections are projector-maintained (replay-rebuildable, watermarked via `analytics_watermarks`) within the 5–15 min freshness budget and surface `computedAt` staleness in the API payload (DatabasePlan §8; ADR-015 — no materialized views for tenant-scoped data).

## 6. Kafka consumer throughput

- [ ] New consumers use the batching defaults from [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §8: `max.poll.records=200` (raw normalizers 500; AI/report job consumers 1), `fetch.min.bytes=64KB`, `fetch.max.wait.ms=250`. Deviations are justified in the PR with throughput evidence.
- [ ] `max.poll.interval.ms` exceeds worst-case batch processing time with margin (default 300 s; AI/report consumers 30 min). Handlers that could block longer hand off to a job table instead of holding the poll loop.
- [ ] Manual ack after idempotent write (`enable.auto.commit=false`); never ack-then-write (EventModel §8).
- [ ] The consumer's lag SLO family is declared against EventModel §12 (raw normalizers p95 < 2 min; domain→analytics p95 < 5 min; RAG indexer p95 < 15 min; AI/report job start p95 < 60 s) and its backpressure behavior is stated.

## 6.1 Outbox and publication paths

- [ ] Domain/analytics/job event producer paths (`eip.domain.*`, `eip.analytics.metrics`, `eip.ai.*`, `eip.reports.*`) go through the transactional outbox (EventModel §9; ADR-017 — raw intake publishes directly to `eip.raw.<connector>` with staged-row durability); adding direct Kafka publication outside the raw-intake path is a BLOCKER regardless of throughput.
- [ ] Changes to the outbox relay or its polling keep `eip.outbox.lag_seconds` within its alert threshold (> 60 s alerts, EventModel §9); relay batch sizes are justified with evidence when changed.
- [ ] Partitioned-table queries (`ops.metric_value`, `audit.audit_event`, `staging.raw_*`) prune by the partition key (`bucket_at`/`occurred_at`/`ingested_at`); a full-partition scan in the EXPLAIN output is a BLOCKER (DatabasePlan §6).

## 7. Cache usage rules

- [ ] Redis is fail-open: the platform remains correct (degraded performance permitted) on cache loss (FR-140). Any code path that returns wrong results or errors when Redis is down is a BLOCKER.
- [ ] Every cache is a named cache with a declared TTL and eviction rationale in the PR; nothing durable-of-record lives in Redis (DatabasePlan intro).
- [ ] Keys use the `eip:{tenantId}:...` namespace ([../docs/architecture/SecurityModel.md](../docs/architecture/SecurityModel.md) §5).
- [ ] The named cache reports `eip_cache_hit_ratio` (ObservabilityModel §3); warm-cache hit rate target for dashboard caches is > 80% (TestingStrategy §11 *Concurrent dashboard load*).

## 8. Load-test triggers

A CC-3 PR MUST run (or schedule on the nightly RC pipeline, with the run linked before merge for hot-path changes) the Gatling scenarios from TestingStrategy §11 that match its touched area:

| Touched area | Required scenario(s) |
|---|---|
| Ingestion pipeline, connector sync engine, normalizers, outbox/relay | *Ingestion throughput* (100k events/h sustained, lag < 60 s, zero DLQ growth) **and** *Ingestion burst* (300,000 events/hour for 15 minutes — the NFR-003 3× burst; zero acknowledged-event loss, lag returns to baseline) |
| Metric engine, dashboard queries, metric APIs | *Metric query latency* (50 users; p95 < 500 ms, p99 < 1.5 s) and *Concurrent dashboard load* (200 users; p95 initial render API bundle < 2 s) |
| Report generation, agent job runtime | *Report generation under load* (20 concurrent jobs, fake LLM; no starvation of interactive APIs) |
| Connector full-sync paths | *Sync burst* (interactive p95 degradation < 20% during full sync) |
| RAG retrieval | *Metric query latency* baseline unaffected + `eip_rag_retrieval_latency_seconds` p95 evidence against [../docs/ai/RAGArchitecture.md](../docs/ai/RAGArchitecture.md) §15 guidance |

Load evidence MUST be gathered with per-tenant rate limits and quotas enabled as configured (FR-144, FR-130) — disabling limits to hit a target invalidates the run.

## 9. Regression thresholds

- [ ] CI fails when any tracked scenario degrades p95 (or its scenario-specific target) by **> 10% versus the stored baseline** — quoted from TestingStrategy §11: ">10% degradation vs. the stored baseline on any target fails the nightly and pages the owning stream." A release candidate never ships over a red baseline.
- [ ] Baseline updates are deliberate: a PR that legitimately moves a baseline updates the stored baseline in the same PR with R-PE approval recorded.

## 10. Sizing-impact note

- [ ] Any storage-affecting change — new table or column on the DatabasePlan §9 high-volume tables (`work_item`, `work_item_transition`, `commit`, `metric_value`, `audit_event`, `staging.raw_*`, `rag_chunk`), a new metric family, retention change, or embedding-dimension change — includes a **sizing-impact note** in the PR: expected rows/month and bytes/row deltas against the DatabasePlan §9 estimates, and whether the §10 retention mechanisms cover it. Retention changes update DatabasePlan §10 in the same PR (docs-first).

## 11. G5 summary checklist

- [ ] CC-3 declared; affected budgets (§2) stated.
- [ ] New-query rule satisfied (§3); pagination on all collection endpoints (§4).
- [ ] No N+1 / unbounded fetch (§5); consumer batching + lag SLO declared (§6); cache rules met (§7).
- [ ] Matching load scenarios green (§8); baseline within 10% or deliberately re-baselined (§9).
- [ ] Sizing-impact note present for storage-affecting changes (§10).
- [ ] **Scale-out seam invariants hold** (DatabasePlan scale-out seam section; keeps per-tenant sharding migratable): no cross-tenant SQL joins outside SECURITY DEFINER platform functions; no DB-global sequences for tenant-owned rows (app-side UUIDv7 only); every unique constraint on tenant-owned tables includes `tenant_id`; no platform-scoped table FK-references tenant-scoped rows. A diff violating any of these is a BLOCKER.
- [ ] Observability for the hot path is in place per [./ObservabilityRequirements.md](./ObservabilityRequirements.md) (G5 evidence is unreadable without it).

## Related documents

- [../docs/product/PRD.md](../docs/product/PRD.md) §6 — NFR budgets (source of record)
- [../docs/engineering/DatabasePlan.md](../docs/engineering/DatabasePlan.md) §4, §8–§12 — indexing, read models, sizing, pooling
- [../docs/engineering/EventModel.md](../docs/engineering/EventModel.md) §8, §12 — consumer conventions, lag SLOs
- [../docs/testing/TestingStrategy.md](../docs/testing/TestingStrategy.md) §11 — Gatling scenarios and regression rule
- [./QualityGatePolicy.md](./QualityGatePolicy.md) · [./TestingChecklist.md](./TestingChecklist.md) · [./ObservabilityRequirements.md](./ObservabilityRequirements.md) · [./SecurityChecklist.md](./SecurityChecklist.md) · [./CodeReviewChecklist.md](./CodeReviewChecklist.md)
