-- Demo/dev-only role provisioning for the local one-command demo (`make demo-up`). Idempotent;
-- applied by scripts/demo/demo-up.sh as the Compose superuser (POSTGRES_USER, default `eip`).
--
-- The demo runs Flyway as the superuser (a dev shortcut, exactly as the Testcontainers harness does)
-- but the app itself connects as `eip_app`, a **NOBYPASSRLS** role — so Row-Level Security is fully
-- enforced for every /api/v1 read, identical to production. RLS is NOT weakened by the demo.
--
-- NOT for production: production provisions eip_app / eip_migrator per DatabasePlan §2 and resolves
-- tenants via OIDC. This file only exists to make the local simulation demo runnable.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'eip_app') THEN
    CREATE ROLE eip_app LOGIN PASSWORD 'eip_app_dev_pw' NOBYPASSRLS;
  END IF;
END
$$;

-- Future objects created by the migrating superuser are auto-granted to eip_app, so after Flyway
-- runs (during app boot) the app can read/seed as eip_app — always subject to RLS.
ALTER DEFAULT PRIVILEGES GRANT USAGE ON SCHEMAS TO eip_app;
ALTER DEFAULT PRIVILEGES GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO eip_app;
ALTER DEFAULT PRIVILEGES GRANT USAGE, SELECT ON SEQUENCES TO eip_app;

-- Best-effort grants on any schemas that already exist (re-run against an already-migrated volume).
DO $$
DECLARE
  s text;
BEGIN
  FOREACH s IN ARRAY ARRAY['core','audit','work','scm','cicd','quality','ops','analytics','ai','reports','staging']
  LOOP
    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = s) THEN
      EXECUTE format('GRANT USAGE ON SCHEMA %I TO eip_app', s);
      EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO eip_app', s);
      EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO eip_app', s);
    END IF;
  END LOOP;
END
$$;
