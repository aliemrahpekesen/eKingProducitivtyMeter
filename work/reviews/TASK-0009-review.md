# TASK-0009 — R-CR Review (with CC-4 G4 R-DBA approval)

- **Task:** [../tasks/TASK-0009.md](../tasks/TASK-0009.md) · story P0-E2-S1 → P0-E2-S2 · **change class CC-4** (schema/migration)
- **Branch:** `feature/TASK-0009-db-rls-baseline` · **base:** `integration/SPRINT-00` · **commits:** `2f0ed26` (increment 1) + `04e99c8` (increment 2)
- **Reviewers:** R-CR (independent) + **R-DBA** (CC-4 G4 gate owner) · **Date:** 2026-07-08
- **Method:** diff + task spec + cited docs only ([DatabasePlan §1–§7, §12, §14, §15](../../docs/engineering/DatabasePlan.md), [BackendPlan §6](../../docs/engineering/BackendPlan.md), [QualityGatePolicy](../../engineering-operating-system/QualityGatePolicy.md)). Every check independently re-run (Testcontainers, no cache) — author prose not taken as evidence.

## Overall verdict: **APPROVED**

0 BLOCKER · 0 MAJOR · 1 MINOR · 2 NIT. The persistence spine is correct, the RLS tenancy mechanism is proven end-to-end against real PostgreSQL 16 + pgvector under a non-superuser `NOBYPASSRLS` role, and the product-first scope deferrals are documented, defensible, and feature-driven — not dropped requirements.

## Validation (independently re-run)

| Check | Result |
|---|---|
| `./gradlew :eip-tenancy:test :eip-app:test --rerun-tasks` | **BUILD SUCCESSFUL** — **43 tests, 0 failures** (6 RLS/lint integration + 2 tenant-context unit + the eip-core suite) |
| `./gradlew check` (all modules) | green — Spotless/Checkstyle/ErrorProne/NullAway/coverage ratchets pass |

## RLS correctness (the crux — independently verified)

- **Every `tenant_id`-bearing table is under RLS.** Cross-checked the R__rls_policies list against the V1 schema: **18 tenant-scoped tables, all covered; 0 missing.** The 3 platform tables (`core.tenant`, `core.worker_heartbeat`, `analytics.analytics_watermark`) carry no `tenant_id` and are correctly absent — matching the DatabasePlan §2 enumerated exceptions exactly.
- **Policy is correct:** `ENABLE` + **`FORCE`** ROW LEVEL SECURITY; `tenant_isolation` policy with `USING`/`WITH CHECK` on `tenant_id = current_setting('app.tenant_id')::uuid` and **no default** (unset GUC errors, never silent-empty). `R__rls_policies.sql` is idempotent (`DROP POLICY IF EXISTS` + `CREATE`) and re-applies on restore (§13).
- **Proven behaviors (6/6 tests, real PG16+pgvector, `eip_app` `NOBYPASSRLS`):** cross-tenant reads return zero rows on both `core.organization` **and** `core.connector`; `WITH CHECK` blocks cross-tenant writes; RLS enabled+forced; unset GUC errors; and the **§15 scale-out schema-lint** (every non-primary tenant-owned unique constraint includes `tenant_id`).
- **Mechanism:** `RlsTenantBinder.bind` uses `set_config('app.tenant_id', ?, true)` — parameterized (injection-safe), transaction-local (`SET LOCAL`-equivalent, resets on commit/rollback, no pool leakage). Pure JDBC — `eip-tenancy` takes no Spring runtime dependency. Unit-tested (Mockito) + integration-proven.

## Schema correctness (R-DBA)

- **Conventions (§1):** snake_case singular tables; `id uuid` UUIDv7 app-side with `gen_random_uuid()` safety-net default; `tenant_id uuid NOT NULL` on every scoped table; `created_at`/`updated_at` + the shared `core.tg_touch_updated_at()` trigger on mutable tables (append-only tables correctly have none); enums text+CHECK; partial unique indexes `WHERE deleted_at IS NULL`. All correct.
- **All 11 §2 schemas** created (`core`, `audit`, `work`, `scm`, `cicd`, `quality`, `ops`, `analytics`, `ai`, `reports`, `staging`) so RLS/grants are ready for future tables.
- **Control-plane tables verified:** `core.event_outbox` matches BackendPlan §6 exactly (`event_id` UUIDv7 PK, `tenant_id`, `topic`, `partition_key`, `envelope` jsonb, `occurred_at`, `published_at`, `attempts`); `audit.audit_event` has the hash-chain columns (`prev_hash`/`hash` bytea), append-only, RANGE-partitioned on `occurred_at`; `core.external_ref` carries `external_key`+`key_aliases` (AD-14) with `ux_external_ref_source`.
- **Partitioning (§6):** `core.processed_events` (daily), `audit.audit_event` (monthly), `analytics.metric_fact` (monthly) are RANGE-partitioned with the partition column in the PK and a DEFAULT partition so the baseline accepts inserts; RLS applies on the partitioned parent (verified — migration applies cleanly and policies attach).
- **pgvector** (`CREATE EXTENSION IF NOT EXISTS vector`) present (§11).
- **CC-4 gate:** forward-only (V1 + R__, no undo); all-additive baseline (no expand–contract needed); RLS integrity proven; §15 invariants schema-linted. **R-DBA G4: APPROVED.**

## Product-first scope deferrals — assessed as VALID (not dropped)

The author deferred, with explicit documentation in the task file:
- Full canonical business-table DDL (`scm`/`cicd`/`quality`/`ops` detail, and `work.sprint`/`board`/`workflow_state`/…) — **populated by Phase-1 connectors**; consistent with DatabasePlan §7 "V2+ begins with Phase 1 connector additions."
- RAG/agent/report tables (`ai.rag_*`, `ai.agent_*`, `reports.*`) — Phase 3.
- Audit hash-chain **filler**/verifier — the audit **subsystem** is P0-E3-S4 (SPRINT-02); the table + columns exist now.
- Partition-management worker, 1M-row keyset check, ingestion upsert (`ux_external_ref` ON CONFLICT) verification — no volume/ingestion exists yet.

**R-DBA ratification:** these are the right calls. Building empty, unconsumed canonical/RAG tables now would be premature-schema debt (the founder's explicit anti-goal); each lands with the feature that populates it, expand-only. The Phase-0 **control-plane** baseline — tenancy, security-spine tables (`secret`, `audit_event`), connector registry, metric registry, outbox/dedup infra — is complete and is exactly what Phase-0 ("secure, observable, empty platform") requires.

## Findings

- **MINOR-1 — Phase-0 baseline scope vs DatabasePlan §14 AC-1.** §14 AC-1 reads "V1 creates all §2 schemas **and §3 tables**"; this V1 creates all schemas but a subset of tables (control-plane now, canonical/RAG with their features). This is a defensible reading of §7's internal tension ("V1 = full Phase-0 schema" vs "V2+ = Phase-1 connector additions") and is documented — but the two statements should be reconciled. *Fix:* a one-line DatabasePlan §7/§14 clarification that V1 is built incrementally (control-plane first; connector-populated canonical tables land with their Phase-1 tasks). File as DEBT; owner R-DBA + R-DE. Non-blocking (docs-only, no functional gap).
- **NIT-1 — integration-test source set.** The RLS integration test rides `src/test` with `@Tag("integration")` rather than the dedicated `integrationTest` source set (TestingStrategy §2). Already flagged as a follow-up in the task file. Acceptable for now; wire the source set when more integration tests land.
- **NIT-2 — app DB roles.** `eip_app`/`eip_migrator`/`eip_readonly`/`eip_maintenance` (§2) are created in the test setup, not a migration/bootstrap. Correct for the test; production role provisioning + a repeatable grant migration are an ops/SPRINT-02 concern — note it so it is not forgotten.

## Evidence checked

- `git show --stat` both commits; forced no-cache `:eip-tenancy:test` + `:eip-app:test`; per-test XML (43/0).
- Static cross-check: R__rls_policies vs V1 `tenant_id` columns (18 covered / 0 missing / platform exceptions correct); pgvector; no-default `current_setting`; FORCE RLS; touch trigger; all §2 schemas; `event_outbox` vs BackendPlan §6; `audit_event` hash-chain + partitioning; 3 partitioned parents + DEFAULT partitions.

## Write-set / scope

`/backend/gradle/libs.versions.toml`, `/backend/eip-app` (migrations + boot/test deps), `/backend/eip-tenancy` (context mechanism + tests + MODULE.md), `work/sprints/SPRINT-01.md`, `work/tasks/TASK-0009.md`. **No `/docs` product-spec baseline modified** (DatabasePlan followed, not changed). No application source outside the declared write-set.

## Merge decision

**APPROVED FOR MERGE** into `integration/SPRINT-00` (local `--no-ff`) — 0 BLOCKER, 0 MAJOR. CC-4 G4 (R-DBA) + G8 (R-CR) satisfied. MINOR-1 → DEBT-008 (DatabasePlan §7/§14 incremental-V1 clarification). Standing note: `V1__baseline.sql` grew across increments (safe while unpushed/undeployed); it freezes on first deploy — further schema is `V2+`. Not pushed.

## Exact next recommended command

```
Merge TASK-0009 into integration/SPRINT-00 (--no-ff), mark it MERGED in work/sprints/SPRINT-01.md, file
MINOR-1 as DEBT-008, commit the integration-state. Then begin P0-E3-S1 (the first visible increment):
tenant-context filter + tenant-aware /api/v1/session and /api/v1/connectors read surface over the real
RLS persistence, deterministic, simulation-seeded demo data only. No real connectors, no full UI, no AI.
Do not push.
```
