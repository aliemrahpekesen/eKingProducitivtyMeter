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
    'core.secret'
  ];
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
