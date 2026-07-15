-- V5 — raw staging for the second wave of real connectors (TASK-0021/M2b Wave 2D,
-- DatabasePlan §2 `staging.raw_<connector>`): same shape as staging.raw_simulation, one table per
-- source so retention/partitioning can differ per connector later. Tenant-scoped (RLS via
-- R__rls_policies); idempotency = (tenant, connector, stream, natural_key) unique + content hash.

CREATE TABLE staging.raw_github (LIKE staging.raw_simulation INCLUDING ALL);
CREATE TABLE staging.raw_gitlab (LIKE staging.raw_simulation INCLUDING ALL);
CREATE TABLE staging.raw_jenkins (LIKE staging.raw_simulation INCLUDING ALL);
