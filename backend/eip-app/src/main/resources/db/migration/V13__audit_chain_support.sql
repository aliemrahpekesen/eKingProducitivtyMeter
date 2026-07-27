-- V13__audit_chain_support.sql — supporting indexes for the async audit hash-chainer + verifier
-- (DEBT-024 Wave 3A, SecurityModel §11; DatabasePlan §3). Additive-only: no existing column,
-- constraint, or table shape changes — `audit.audit_event`'s V1 baseline DDL is a DatabasePlan
-- contract-anchor and is not reshaped here. Index creation on a RANGE-partitioned parent propagates
-- automatically to the existing default partition and to every future partition (PG11+), so each
-- statement below covers the whole `audit.audit_event` hierarchy going forward, not just today's
-- partitions.

-- Chainer: "for this tenant, the oldest not-yet-chained rows in (occurred_at, id) order" is the
-- chainer's hot query every sweep (AuditEventRepository#findUnchainedOrdered). The partial predicate
-- keeps the index small as the vast majority of history is chained (hash IS NOT NULL) and never
-- revisited by this query again.
CREATE INDEX ix_audit_event_unchained ON audit.audit_event (tenant_id, occurred_at, id)
  WHERE hash IS NULL;

-- General per-tenant time-ordered audit access (DatabasePlan §3's planned `ix_audit_tenant_time`,
-- not yet created by V1): serves the chainer's "last chained row" lookup
-- (AuditEventRepository#findLastChainedHash) and the verifier's full per-tenant walk
-- (AuditEventRepository#findChainedOrdered), both ordered by (occurred_at, id).
CREATE INDEX ix_audit_tenant_time ON audit.audit_event (tenant_id, occurred_at, id);
