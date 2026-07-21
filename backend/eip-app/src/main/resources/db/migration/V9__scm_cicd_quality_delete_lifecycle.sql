-- V9 — delete lifecycle for scm.pull_request, scm.code_review, cicd.build, quality.quality_gate
-- (DEBT-018 item 4), mirroring the existing work.work_item soft-delete pattern (DEBT-020 item 3):
-- a bookkeeping `deleted_at` timestamp, cleared on a later re-upsert of the same identity
-- (revival). All four tables are already RLS-enabled (R__rls_policies.sql, TASK-0016 INC-2) — a
-- new nullable column needs no RLS policy change.
ALTER TABLE scm.pull_request ADD COLUMN deleted_at timestamptz;
ALTER TABLE scm.code_review  ADD COLUMN deleted_at timestamptz;
ALTER TABLE cicd.build       ADD COLUMN deleted_at timestamptz;
ALTER TABLE quality.quality_gate ADD COLUMN deleted_at timestamptz;
