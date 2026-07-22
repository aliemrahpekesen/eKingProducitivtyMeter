-- V12 — partition staging.raw_<connector> by ingestion week (DEBT-017 partitioning sub-item /
-- DEBT-008; DatabasePlan §6). Converts all 7 raw-staging tables (raw_simulation, raw_jira,
-- raw_bitbucket, raw_sonarqube, raw_github, raw_gitlab, raw_jenkins) from plain heap tables to
-- RANGE-partitioned parents so retention can drop whole weekly partitions instead of running
-- batched deletes on the bulky raw JSONB payload (§9/§10). `staging.raw_ingest_errors` is NOT
-- partitioned — DatabasePlan §6 only plans this for `staging.raw_<connector>`.
--
-- ============================================================================================
-- WHY THE PARTITION KEY IS A NEW `first_ingested_at` COLUMN, NOT THE EXISTING `ingested_at`
-- ============================================================================================
-- PostgreSQL requires every UNIQUE/PRIMARY KEY constraint on a partitioned table to include all
-- partition-key columns (ddl-partitioning.html §5.11.2.2). The existing constraint is
-- `ux_raw_<x> UNIQUE (tenant_id, connector_id, stream, natural_key)` — DatabasePlan §6 originally
-- planned RANGE on the existing `ingested_at` column, which would have required widening that
-- constraint to `(tenant_id, connector_id, stream, natural_key, ingested_at)`.
--
-- That was investigated and rejected: `StagingRawRepository`'s upsert
-- (`INSERT ... ON CONFLICT (tenant_id, connector_id, stream, natural_key) DO UPDATE SET ...,
-- ingested_at = now() WHERE content_hash IS DISTINCT FROM EXCLUDED.content_hash`) refreshes
-- `ingested_at` to `now()` on every content-changing re-ingest — the primary, everyday path this
-- statement exists for (not an edge case). If `ingested_at` were part of the arbiter:
--   1. Conflict DETECTION would silently fail for that path: the incoming row's `ingested_at` is
--      a fresh value (column DEFAULT/bind, not the stored one), so the (tenant_id, connector_id,
--      stream, natural_key, ingested_at) tuple essentially never matches an existing index entry.
--      Postgres would insert a brand-new row instead of updating in place — one extra row per
--      natural key per content change, not a genuine duplicate-prevention constraint anymore.
--   2. Even if detection somehow matched, PostgreSQL flatly refuses a `DO UPDATE` that would move
--      the tuple to a different partition: empirically reproduced against a scratch PG16
--      instance as `ERROR: invalid ON UPDATE specification / DETAIL: The result tuple would
--      appear in a different partition than the original tuple.` (matches the documented
--      restriction discussed on pgsql-hackers, e.g.
--      https://www.postgresql.org/message-id/20180228004602.cwdyralmg5ejdqkq@alvherre.pgsql).
-- Both mechanisms independently break the documented invariant ("Idempotency is the (tenant_id,
-- connector_id, stream, natural_key) unique key + the content hash: replaying the same source
-- dataset upserts in place and never creates duplicates" — V2 header) for the everyday
-- content-changed path, not just a rare edge case.
--
-- Fix: add a SEPARATE, stable anchor column `first_ingested_at timestamptz NOT NULL DEFAULT
-- now()` that is set once (first insert) and never advanced by the DO UPDATE SET clause — so a
-- row's partition never changes after creation, sidestepping restriction (2) entirely — and widen
-- the constraint to include THIS column instead of `ingested_at`. `ingested_at` keeps its current
-- "last touched" semantics completely unchanged (still refreshed on real content changes, still
-- guarded by the existing no-churn-on-unchanged-replay WHERE clause; both `IngestionPipelineIntegrationTest`
-- and `SimulationIngestionIntegrationTest`'s "no ingested_at churn" assertions are unaffected) —
-- it is simply no longer part of any constraint or the partition key.
--
-- For this to work, `StagingRawRepository.upsertAll` must supply the row's EXISTING
-- `first_ingested_at` value explicitly on every re-ingest of an already-seen natural key (an
-- internal pre-load SELECT, mirroring the existing `contentHashes()` pattern) — otherwise the same
-- silent-duplicate failure mode described above recurs under a new column name. Genuinely new
-- natural keys pass NULL and get `COALESCE(?, now())`, i.e. the column DEFAULT. See that class for
-- the corresponding code change, and `StagingRawPartitioningIntegrationTest` for empirical proof of
-- both: (a) a genuine duplicate insert at the identical arbiter key is rejected, and (b) the
-- same-natural-key-different-first_ingested_at failure mode is what motivated this design (the OLD
-- `ingested_at`-keyed approach reproduces it; the new `first_ingested_at`-keyed + resupply approach
-- does not).
--
-- Retention consequence (documented, not solved here — see DatabasePlan §6/§10 update and the
-- runbook note below): `first_ingested_at` is a row's FIRST-seen time, not its last-touched time.
-- A weekly-partition-drop retention job keyed on this column expires rows by original arrival, not
-- by last activity — a natural key re-ingested every day for months still lives in its original
-- (old) partition and would be dropped by a naive "drop partitions older than 90 days" job even
-- though `ingested_at` is recent. This is consistent with DatabasePlan §10's already-documented
-- flat 90-day calendar retention for staging.raw_* (not an activity-based window), so it is not a
-- new gap, but it is a real, sharp edge worth a reader knowing about explicitly.
--
-- ============================================================================================
-- Partition-maintenance runbook note (scope fence: NOT built here)
-- ============================================================================================
-- This migration creates a fixed window of weekly partitions (4 weeks past through 12 weeks
-- future from the moment this migration applies) plus a DEFAULT partition as a safety valve for
-- anything outside that window. It does NOT create a scheduled job to roll the window forward or
-- to drop expired partitions — DatabasePlan §6 already describes the intended mechanism (a
-- Redisson-locked `eip-workers` scheduled worker that pre-creates the next partitions and drops
-- expired ones), but as of this migration `eip-workers` has no source at all (`git grep` over
-- `backend/eip-workers/src/main` is empty) — that worker is aspirational for every partitioned
-- table in this schema (`analytics.metric_fact`, `audit.audit_event`, `core.processed_events`,
-- and now `staging.raw_*`), not a regression introduced here. Operationally, until that worker
-- exists: rows landing in a DEFAULT partition past the pre-created window will NOT be
-- automatically re-homed — an operator must run a corrective migration or manual DDL if the
-- window is not refreshed before ~12 weeks from this migration's apply date.

DO $$
DECLARE
  raw_tables text[] := ARRAY[
    'raw_simulation', 'raw_jira', 'raw_bitbucket', 'raw_sonarqube',
    'raw_github', 'raw_gitlab', 'raw_jenkins'
  ];
  tbl text;
  old_tbl text;
  base_week date := (date_trunc('week', now() AT TIME ZONE 'UTC'))::date; -- UTC Monday 00:00, matches TrendRepository's week bucketing
  w int;
  lo date;
  hi date;
  old_count bigint;
  new_count bigint;
BEGIN
  FOREACH tbl IN ARRAY raw_tables LOOP
    old_tbl := tbl || '_unpartitioned';

    -- 1. Rename the existing heap table out of the way.
    EXECUTE format('ALTER TABLE staging.%I RENAME TO %I', tbl, old_tbl);

    -- 2. Create the partitioned parent. LIKE ... INCLUDING ALL EXCLUDING INDEXES copies defaults,
    --    NOT NULL, CHECK constraints (op/fetch_kind), comments and storage settings, but skips the
    --    PRIMARY KEY/UNIQUE constraint (both backed by indexes, and neither includes the new
    --    partition key) — those are redeclared explicitly, widened.
    EXECUTE format($f$
      CREATE TABLE staging.%I (
        LIKE staging.%I INCLUDING ALL EXCLUDING INDEXES,
        first_ingested_at timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (id, first_ingested_at),
        CONSTRAINT %I UNIQUE (tenant_id, connector_id, stream, natural_key, first_ingested_at)
      ) PARTITION BY RANGE (first_ingested_at)
    $f$, tbl, old_tbl, 'ux_' || tbl || '_natural_key');

    -- 3. Weekly partitions: 4 weeks past through 12 weeks future from apply time (w=0 is the
    --    current UTC week; w=-4..-1 are the 4 past weeks; w=1..12 are the 12 future weeks), UTC
    --    Monday-aligned, plus a DEFAULT partition as the safety valve for anything outside the
    --    window (see runbook note above).
    FOR w IN -4..12 LOOP
      lo := base_week + (w * 7);
      hi := base_week + ((w + 1) * 7);
      EXECUTE format(
        'CREATE TABLE staging.%I PARTITION OF staging.%I FOR VALUES FROM (%L) TO (%L)',
        tbl || '_w' || to_char(lo, 'IYYY_IW'), tbl, lo, hi);
    END LOOP;
    EXECUTE format('CREATE TABLE staging.%I PARTITION OF staging.%I DEFAULT', tbl || '_default', tbl);

    -- 4. Copy existing rows. `first_ingested_at` is backfilled from `ingested_at` — the best
    --    available proxy for "first seen" on pre-migration data (true first-seen history was
    --    never tracked); this only affects rows that existed before this migration ran.
    EXECUTE format($f$
      INSERT INTO staging.%I
        (id, tenant_id, connector_id, stream, natural_key, source_system, source_instance,
         external_id, op, fetch_kind, payload, content_hash, ingested_at, first_ingested_at)
      SELECT id, tenant_id, connector_id, stream, natural_key, source_system, source_instance,
             external_id, op, fetch_kind, payload, content_hash, ingested_at, ingested_at
      FROM staging.%I
    $f$, tbl, old_tbl);

    -- 5. Verify row counts match exactly before dropping the old table; any mismatch raises and
    --    rolls back the whole migration transaction.
    EXECUTE format('SELECT count(*) FROM staging.%I', old_tbl) INTO old_count;
    EXECUTE format('SELECT count(*) FROM staging.%I', tbl) INTO new_count;
    IF old_count <> new_count THEN
      RAISE EXCEPTION 'V12 partition migration row-count mismatch for staging.%: % (old) <> % (new)',
        tbl, old_count, new_count;
    END IF;

    -- 6. Only now drop the old table — and only now re-declare the plain lookup index dropped by
    --    EXCLUDING INDEXES: the old (renamed) table's same-named index is still occupying that
    --    name in the schema's index namespace until this DROP runs.
    EXECUTE format('DROP TABLE staging.%I', old_tbl);
    EXECUTE format('CREATE INDEX %I ON staging.%I (tenant_id, stream)', 'ix_' || tbl || '_stream', tbl);
  END LOOP;
END;
$$;
