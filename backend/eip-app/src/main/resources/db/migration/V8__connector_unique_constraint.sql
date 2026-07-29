-- V8 — one-simulation-connector-per-tenant-per-type constraint (DEBT-020 item 6).
--
-- ConnectorRegistryRepository.ensureConnector is the sole caller that "get or create" a connector
-- row: SELECT ... WHERE type = :type AND simulation = :simulation ... LIMIT 1, and on an empty
-- result, INSERT. With no unique constraint backing it, two concurrent ensureConnector calls for
-- the same tenant+type (e.g. two simultaneous simulation-ingest runs before either has committed
-- its INSERT) can both miss the SELECT and both INSERT, leaving two "the" simulation connector rows
-- for one tenant+type. ensureConnector exists ONLY for the single simulation-source connector per
-- type per tenant — user-registered real connectors go through ConnectorAdminRepository#insert
-- (ConnectorAdminService#register) and legitimately allow multiple rows of the same type (e.g. two
-- Jira connectors for two different projects), so the constraint is scoped to `simulation = true`
-- only; it must never block real multi-connector-per-type registration.
CREATE UNIQUE INDEX ux_connector_simulation_type
  ON core.connector (tenant_id, type)
  WHERE deleted_at IS NULL AND simulation = true;
