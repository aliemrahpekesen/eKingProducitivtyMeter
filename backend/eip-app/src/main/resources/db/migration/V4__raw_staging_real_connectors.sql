-- V4 — raw staging for the first real connectors (TASK-0019 M2, DatabasePlan §2
-- `staging.raw_<connector>`): same shape as staging.raw_simulation, one table per source so
-- retention/partitioning can differ per connector later. Tenant-scoped (RLS via R__rls_policies);
-- idempotency = (tenant, connector, stream, natural_key) unique + content hash.

CREATE TABLE staging.raw_jira (LIKE staging.raw_simulation INCLUDING ALL);
CREATE TABLE staging.raw_bitbucket (LIKE staging.raw_simulation INCLUDING ALL);
CREATE TABLE staging.raw_sonarqube (LIKE staging.raw_simulation INCLUDING ALL);
