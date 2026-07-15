# G5 Performance Evidence — SPRINT-01 (M5 Wave S3)

Directional performance evidence for the v0.x read surface, event spine, and ingest pipeline,
captured against the live local dev environment per the [PerformanceChecklist](../../engineering-operating-system/PerformanceChecklist.md).
Budgets referenced: [PRD §NFR](../product/PRD.md) — API p95 < 300 ms, dashboard p50 < 500 ms /
p95 < 2 s, 100k events/h sustained + 3× burst.

## 1. Environment disclosure (read this first)

**This is NOT the NFR reference hardware. Every number below is directional, not a budget verdict.**

| Property | Value |
|---|---|
| Host | Apple-silicon dev laptop (Apple M1, 16 GB RAM, macOS 26.5) |
| Backend | `eip-app` running on the host JVM, `:8080`, header security mode |
| Database | PostgreSQL 16.13 (pgvector image), **single Docker container**, default config, port-mapped |
| Kafka / Redis / Keycloak / MinIO / OTel / Prometheus / Grafana | all co-resident single-node Docker containers on the same laptop |
| Dataset | demo tenant `…00de`: 9 work items / 9 flow correlations / 3 teams / 1 report — **orders of magnitude below production scale**; plan shapes at this row count are indicative of index usage, not of production latencies |
| Load driver | `scripts/perf/api_load.py` (stdlib-only, closed-loop, keep-alive), run on the same host — client and server share CPUs |

## 2. API load smoke — 30 s, concurrency 20

Command:

```
python3 scripts/perf/api_load.py --base http://localhost:8080 \
  --tenant 00000000-0000-4000-8000-0000000000de --seconds 30 --concurrency 20
```

Result (2026-07-15): `total_requests=24814`, throughput **826.7 req/s**, **0 errors**.

| endpoint | requests | errors | p50 ms | p95 ms | p99 ms | max ms | budget (p95) | directional verdict |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `/api/v1/friction/summary` | 6203 | 0 | 20.3 | 46.5 | 90.5 | 325.6 | < 300 ms API / < 2 s dashboard | within, ~6× headroom |
| `/api/v1/metrics/trends?weeks=12` | 6205 | 0 | 23.1 | 54.3 | 95.3 | 333.5 | < 300 ms API / < 2 s dashboard | within, ~5× headroom |
| `/api/v1/insights/recommendations` | 6204 | 0 | 20.1 | 46.5 | 91.4 | 330.6 | < 300 ms API | within, ~6× headroom |
| `/api/v1/reports` | 6202 | 0 | 17.7 | 42.1 | 81.7 | 322.5 | < 300 ms API | within, ~7× headroom |

Reading: all four dashboard reads hold p95 between 42–55 ms under 20 concurrent closed-loop
clients at ~827 req/s aggregate — comfortably inside both budgets **at this dataset size on this
hardware**. The p99/max tail (~90/330 ms) coincides with JVM GC + co-resident container noise on a
shared laptop; no request errored or exceeded 340 ms in 24 814 samples.

## 3. EXPLAIN (ANALYZE, BUFFERS) — hot queries

Captured via `psql` in the dev Postgres container as the **NOBYPASSRLS `eip_app` role with the
tenant GUC bound**, so every plan below includes the real RLS predicate. At 9-row scale the
planner's absolute costs are meaningless; what each capture proves is **which index the predicate
can use** (plan shape).

### 3.1 Trends weekly query (`TrendRepository.rows`, 12-week window)

```
Sort (actual time=0.129..0.130 rows=9)
  Sort Key: wi.team_id, week_start
  -> Nested Loop (rows=9)
       -> Index Scan using ix_work_item_keyset on work_item wi
            Index Cond: (tenant_id = current_setting('app.tenant_id')::uuid)
            Filter: resolved_at IS NOT NULL AND deleted_at IS NULL AND team_id IS NOT NULL
                    AND resolved_at >= '2025-10-15 21:00:00+00'
       -> Seq Scan on flow_correlation fc (rows=5, loops=9)
            Filter: (tenant_id = current_setting('app.tenant_id')::uuid)
Execution Time: 0.173 ms
```

One-line read: work-item side is index-served on the tenant prefix of `ix_work_item_keyset`
(resolved-at filter applied post-index); the `flow_correlation` side is a per-loop **seq scan** —
invisible at 9 rows, but at volume this join wants an index on `flow_correlation (tenant_id,
work_item_id)` (the existing `ux_flow_correlation` unique constraint provides exactly that — the
planner simply doesn't bother at this row count, to be re-verified in the reference run).

### 3.2 Friction read-model query (`FrictionReadRepository.summaryRows`, version-pinned)

```
Nested Loop (actual time=0.048..0.055 rows=3)
  -> Index Scan using ux_rm_team_friction on rm_team_friction_current f
       Index Cond: (tenant_id = current_setting('app.tenant_id')::uuid
                    AND metric_version = 'engineering_friction_v0.1')
  -> Bitmap Heap Scan on team t (Bitmap Index Scan on ix_team_bu, tenant cond)
Execution Time: 0.088 ms
```

One-line read: the new `metric_version` pin (DEBT-020 item 1) is picked up directly by the
`ux_rm_team_friction (tenant_id, team_id, metric_version)` unique index — the read stays fully
index-served after the fix, no seq scan.

### 3.3 Outbox relay batch (`OutboxRepository.selectUnpublishedBatch`)

```
Limit -> LockRows
  -> Index Scan using ix_event_outbox_unpublished on event_outbox
       Index Cond: (tenant_id = current_setting('app.tenant_id')::uuid)
       Filter: (published_at IS NULL AND attempts < 10)
Execution Time: 0.020 ms
```

One-line read: the relay's `FOR UPDATE SKIP LOCKED` poll rides `ix_event_outbox_unpublished`
(tenant-scoped) and returned 0 rows here because the dev backlog is fully published — the
steady-state poll is a sub-millisecond index probe when the outbox is drained.

### 3.4 Reports keyset page (`ReportRepository.query`, cursor variant)

```
Limit -> Incremental Sort (Presorted Key: created_at)
  -> Index Scan using ix_generated_report_keyset on generated_report
       Index Cond: (tenant_id = current_setting('app.tenant_id')::uuid
                    AND ROW(created_at, id) < ROW('2026-07-14 12:00:00+00', 'ffff…'))
Execution Time: 0.017 ms
```

One-line read: the keyset predicate `(created_at, id) < (:cursor)` is applied **inside the index
condition** of `ix_generated_report_keyset` — genuine keyset pagination (no offset scan); the
incremental sort only tie-breaks `id DESC` within equal `created_at`.

## 4. Pipeline wall time — ingest + normalize + compute (fresh tenant)

Fresh tenant created via `POST /api/v1/admin/tenants`, then `POST /api/v1/admin/sample-data`
(runs the full simulation pipeline: staged ingest → external-ref anchoring + canonical upserts →
correlation + friction compute + projection) timed with `curl -w`:

| step | result | wall time |
|---|---|---|
| create tenant | `201` | 0.092 s |
| sample-data pipeline (79 raw records → 9 items / 3 teams computed) | `{"ingestion":{"emitted":79,"inserted":79,"updated":0,"unchanged":0},"teamsComputed":3,"itemsCorrelated":9}` | **0.368 s** |
| immediate `GET /friction/summary` read-back on the new tenant | `200`, computed scores present | 0.021 s |

Directional throughput extrapolation: 79 records / 0.368 s ≈ 215 records/s ≈ 770k records/h **in a
single synchronous call on a laptop** — but this is a 79-record batch with warm JVM and zero
contention; it does not demonstrate the 100k events/h sustained budget (see gaps).

## 5. Honest gap list — what this evidence does NOT show

1. **100k events/h sustained + 3× burst is NOT demonstrated.** The NFR requires sustained-hour
   ingest on the reference environment with realistic connector payload shapes. Blockers to a
   real run: `staging.raw_*` is unpartitioned (DEBT-008 / DEBT-017 residual) and normalization
   re-reads full streams per run — both fine at demo scale, both expected to dominate at 100k/h.
   The reference run must measure: sustained ingest rate over ≥ 1 h, staging table bloat/vacuum
   behaviour, normalize wall time growth vs. total history, outbox relay lag
   (`eip_outbox_published_total` rate vs. insert rate), and consumer recompute lag under the new
   coalescer (`eip_events_recompute_runs`/`coalesced` ratio).
2. **9-row plans prove index *availability*, not index *choice* at volume.** The seq scan in §3.1
   (and the planner ignoring most indexes) is expected at this cardinality; every EXPLAIN must be
   re-captured on the reference dataset (≥ 10⁶ work items per DatabasePlan sizing) before G5 can
   pass for CC-3 changes touching these paths.
3. **Client and server shared one laptop** — the load driver competes with the JVM and all nine
   containers for CPU; real p95s under network latency are unmeasured.
4. **Webhook freshness (60 s p95) untested** — no live source emitting webhooks in this
   environment.
5. **Dashboard p50/p95 measured at the API, not the browser.** Frontend render cost is excluded;
   the echarts vendor chunk is now lazy-loaded (1 134 kB deferred off the initial 301 kB bundle),
   but no Lighthouse/Web-Vitals capture was made.
6. **Single-tenant load.** RLS predicate cost under many concurrent tenants (GUC rebinds,
   per-tenant plan cache behaviour) is unmeasured.

## 6. Reproduction

```bash
# load smoke (backend running on :8080, demo tenant seeded)
python3 scripts/perf/api_load.py --base http://localhost:8080 \
  --tenant 00000000-0000-4000-8000-0000000000de --seconds 30 --concurrency 20

# EXPLAIN captures (RLS-bound role)
docker exec -i eip-dev-postgres-1 psql -U eip -d eip
SET ROLE eip_app;
SELECT set_config('app.tenant_id', '00000000-0000-4000-8000-0000000000de', false);
EXPLAIN (ANALYZE, BUFFERS) <query from §3>;

# pipeline timing (fresh tenant)
curl -X POST -H "X-EIP-Tenant: <admin tenant>" -H "Content-Type: application/json" \
  -d '{"name":"G5 Perf","slug":"g5-perf-<ts>"}' http://localhost:8080/api/v1/admin/tenants
curl -w '%{time_total}' -X POST -H "X-EIP-Tenant: <new tenant id>" \
  http://localhost:8080/api/v1/admin/sample-data
```
