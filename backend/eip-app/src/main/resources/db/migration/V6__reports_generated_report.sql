-- V6 — reports.generated_report (TASK-0022 M4 Wave R1, DatabasePlan.md §3 "reports.generated_report"
-- planned DDL, reproduced verbatim) plus one v0.1 addition: the `document` jsonb column (ADR-023).
-- v0.1 ships one deterministic generator (EXEC_SUMMARY); the rendered-artifact/MinIO and
-- report_job/report_template/report_schedule tables the planned schema anticipates for the
-- AI-composed report phase are deferred (DEBT-021). RLS via R__rls_policies (additive).

CREATE TABLE reports.generated_report (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  type               text NOT NULL CHECK (type IN ('SPRINT_REVIEW','RELEASE_NOTES',
                      'EXEC_SUMMARY','DELIVERY_RISK','INCIDENT_ANALYSIS','CUSTOM')),
  title              text NOT NULL,
  template_id        uuid,
  parameters         jsonb NOT NULL,
  status             text NOT NULL CHECK (status IN ('QUEUED','GENERATING','READY','FAILED')),
  artifact_keys      text[] NOT NULL DEFAULT '{}',  -- MinIO/S3 object keys
  generated_by_agent text NOT NULL,
  llm_call_ids       uuid[] NOT NULL DEFAULT '{}',  -- -> ai.llm_call_audit
  citations          jsonb,
  data_time_range    tstzrange,                     -- provenance: time window of source data (FR-114)
  input_sources      jsonb NOT NULL DEFAULT '[]',   -- provenance: connectors/datasets consulted (FR-114)
  validation_status  text NOT NULL DEFAULT 'PENDING' CHECK (validation_status IN
                      ('PENDING','PASSED','FAILED')), -- Validation Agent verdict (FR-114)
  -- v0.1 addition (ADR-023): the full deterministic ReportDocument, inline. Nullable because the
  -- planned schema's other (future) generators may populate only `artifact_keys` instead.
  document           jsonb,
  completed_at       timestamptz,
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_generated_report_keyset ON reports.generated_report (tenant_id, created_at DESC, id);
CREATE TRIGGER tg_generated_report_touch BEFORE UPDATE ON reports.generated_report
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();
