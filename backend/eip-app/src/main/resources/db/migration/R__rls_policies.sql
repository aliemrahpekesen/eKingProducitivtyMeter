-- R__rls_policies.sql — repeatable RLS policy migration (DatabasePlan.md §5).
-- Re-applied on every Flyway run and after every restore (§13), so it is idempotent: ENABLE/FORCE
-- are no-ops when already set, and each policy is dropped-then-created.
--
-- Every tenant-scoped table gets the same pair: RLS ENABLED + FORCED (forced so even the table
-- owner is subject to it), with the `tenant_isolation` policy binding to the transaction-scoped GUC
-- `app.tenant_id` set by eip-tenancy's tenant-context binder (`SET LOCAL` / set_config, DatabasePlan
-- §12). `current_setting('app.tenant_id')` has NO default: an unset GUC errors rather than silently
-- returning zero rows, surfacing wiring bugs immediately (§5). Platform tables (core.tenant) are the
-- enumerated exception and get no policy.

DO $$
DECLARE
  t text;
  tenant_scoped_tables text[] := ARRAY[
    'core.organization',
    'core.business_unit',
    'core.team',
    'core.role',
    'core.member',
    'core.member_identity',
    'core.secret',
    'core.external_ref',
    'core.entity_link',
    'core.connector',
    'core.connector_checkpoint',
    'core.event_outbox',
    'core.processed_events',   -- partitioned parent; policy propagates to partitions (PG11+)
    'audit.audit_event',       -- partitioned parent
    'work.work_item',
    'analytics.metric_definition',
    'analytics.metric_fact',   -- partitioned parent
    'analytics.rm_team_flow_current',
    'staging.raw_simulation',        -- TASK-0016 INC-1 (ingestion raw staging)
    'staging.raw_ingest_errors',     -- TASK-0016 INC-1 (ingestion/normalization DLQ)
    'staging.raw_jira',              -- TASK-0019 M2 (real connectors)
    'staging.raw_bitbucket',         -- TASK-0019 M2
    'staging.raw_sonarqube',         -- TASK-0019 M2
    'staging.raw_github',            -- TASK-0021 M2b Wave 2D (real connectors, second wave)
    'staging.raw_gitlab',            -- TASK-0021 M2b Wave 2D
    'staging.raw_jenkins',           -- TASK-0021 M2b Wave 2D
    'work.work_item_transition',     -- TASK-0016 INC-2 (canonical flow)
    'scm.pull_request',              -- TASK-0016 INC-2
    'scm.code_review',               -- TASK-0016 INC-2
    'cicd.build',                    -- TASK-0016 INC-2
    'quality.quality_gate',          -- TASK-0016 INC-2
    'analytics.flow_correlation',    -- TASK-0016 INC-2 (correlation evidence)
    'analytics.rm_team_friction_current', -- TASK-0016 INC-2 (computed friction read model)
    'reports.generated_report',      -- TASK-0022 M4 Wave R1 (deterministic report engine)
    'core.tenant_ai_policy',         -- M6-A (ADR-024, per-tenant AI explanation layer policy)
    'ai.llm_call_audit'              -- M6-A (ADR-024, hash-only LLM call audit ledger)
  ];
  -- Platform-scoped (enumerated no-RLS exceptions, DatabasePlan §2): core.tenant,
  -- core.worker_heartbeat, analytics.analytics_watermark — deliberately absent from this list.
  -- core.service_token (V10, SecurityModel §3, DEBT-012 residual) is ALSO deliberately absent: it
  -- mixes tenant-scoped and platform-scoped rows in one table (tenant_id NULL == platform-scoped)
  -- and its hash-lookup authentication path runs before any tenant is known, so a uniform
  -- tenant_isolation policy cannot apply — see that migration's header comment and ADR-025 for the
  -- full rationale; the application layer enforces tenant/platform scoping explicitly instead.
BEGIN
  FOREACH t IN ARRAY tenant_scoped_tables LOOP
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %s', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON %s '
      'USING (tenant_id = current_setting(''app.tenant_id'')::uuid) '
      'WITH CHECK (tenant_id = current_setting(''app.tenant_id'')::uuid)', t);
  END LOOP;
END;
$$;
